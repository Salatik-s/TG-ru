package org.telegram.messenger.family;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.HttpsURLConnection;

/** Installation-scoped access. No gateway credentials or invitations are persisted. */
public final class FamilyAccess {
    public static final String DEFAULT_SERVER = "https://24projects.ru:8443";
    private static final String KEY_ALIAS = "familygram.installation.p256.v1";
    private static final long LEASE_MS = 75_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor();
    private static final ScheduledExecutorService WATCH = Executors.newSingleThreadScheduledExecutor();
    private static final CopyOnWriteArraySet<Listener> LISTENERS = new CopyOnWriteArraySet<>();
    private static final AtomicLong EPOCH = new AtomicLong();
    private static volatile Snapshot snapshot = new Snapshot("unknown", "", false, false, 0);
    private static volatile long allowedUntil;
    private static volatile String activeGatewayHash;
    private static volatile String installationId = "";
    private static volatile boolean phoneOwner;
    private static volatile String server = DEFAULT_SERVER;
    private static volatile String deviceName = "Android";
    private static SharedPreferences preferences;
    private static Context applicationContext;
    private static ScheduledFuture<?> poll;

    private FamilyAccess() { }

    public interface Listener { void onFamilyAccessChanged(Snapshot state); }

    public static final class Snapshot {
        public final String status;
        /** Stable allowlisted code, never a server-provided error message. */
        public final String error;
        public final boolean busy;
        public final boolean connected;
        public final long retryAtElapsed;
        Snapshot(String status, String error, boolean busy, boolean connected, long retryAtElapsed) {
            this.status = status; this.error = error; this.busy = busy;
            this.connected = connected; this.retryAtElapsed = retryAtElapsed;
        }
        public long retrySeconds() {
            return Math.max(0, (retryAtElapsed - SystemClock.elapsedRealtime() + 999) / 1000);
        }
    }

    public static synchronized void init(Context context) {
        if (preferences != null) return;
        applicationContext = context.getApplicationContext();
        preferences = context.getApplicationContext().getSharedPreferences("familygram_access_v1", Context.MODE_PRIVATE);
        server = DEFAULT_SERVER;
        preferences.edit().putString("server", DEFAULT_SERVER).apply();
        deviceName = preferences.getString("name", Build.MODEL == null ? "Android" : Build.MODEL);
        WATCH.scheduleWithFixedDelay(() -> {
            if (allowedUntil != 0 && (SystemClock.elapsedRealtime() >= allowedUntil || !nativeReady())) {
                invalidate("connection_lost");
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    public static String server() { return server; }
    public static String deviceName() { return deviceName; }
    public static String deviceFingerprint() {
        String id = installationId;
        if (id.length() < 16) return "";
        String shortId = id.substring(0, 16).toUpperCase(Locale.ROOT);
        return shortId.substring(0, 4) + " " + shortId.substring(4, 8) + " "
                + shortId.substring(8, 12) + " " + shortId.substring(12, 16);
    }
    public static Snapshot state() { return snapshot; }
    public static boolean isPhoneOwner() { return phoneOwner && isAllowed(); }
    public static boolean isAllowed() {
        return allowedUntil > SystemClock.elapsedRealtime() && nativeReady();
    }
    private static boolean nativeReady() {
        try { return FamilyNative.isReady(); } catch (LinkageError | RuntimeException ignored) { return false; }
    }
    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
        MAIN.post(() -> { if (LISTENERS.contains(listener)) listener.onFamilyAccessChanged(snapshot); });
    }
    public static void removeListener(Listener listener) { LISTENERS.remove(listener); }

    public static String validateServer(String value) throws Exception {
        String validated = FamilyProtocol.validateServer(value);
        if (!DEFAULT_SERVER.equals(validated)) throw new IllegalArgumentException("Fixed family service required");
        return validated;
    }

    /** Must be called only after init; modifying endpoint revokes this process's access first. */
    public static synchronized boolean saveSetup(String endpoint, String name) {
        if (preferences == null || snapshot.busy) return false;
        try {
            String validated = validateServer(endpoint);
            String cleanName = FamilyProtocol.trimName(name);
            if (cleanName.isEmpty() || cleanName.length() > 80 || cleanName.matches("(?s).*[\\p{Cntrl}].*")) return false;
            boolean changed = !server.equals(validated);
            if (changed) invalidate("");
            server = validated; deviceName = cleanName;
            preferences.edit().putString("server", server).putString("name", deviceName).apply();
            if (changed) publish(new Snapshot("unknown", "", false, false, 0));
            return true;
        } catch (Exception ignored) { return false; }
    }

    public static void refresh() {
        boolean connected = isAllowed();
        begin(connected ? "config" : "status", null, connected);
    }
    public static void connect() { begin("config", null, true); }
    public static void submitInvite(String invitation) {
        String code = invitation == null ? "" : invitation.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9-]{1,64}")) {
            publish(new Snapshot(snapshot.status, "invalid_invite_format", false, isAllowed(), snapshot.retryAtElapsed));
            return;
        }
        begin("invite", code, false);
    }

    private static synchronized void begin(String action, String code, boolean openTunnel) {
        if (preferences == null || snapshot.busy) return;
        if (action.equals("invite") && snapshot.retrySeconds() > 0) return;
        long epoch = EPOCH.get();
        String endpoint = server;
        String name = deviceName;
        publish(new Snapshot(snapshot.status, "", true, isAllowed(), snapshot.retryAtElapsed));
        IO.execute(() -> perform(action, code, openTunnel, epoch, endpoint, name));
    }

    private static void perform(String action, String code, boolean openTunnel, long epoch, String endpoint, String name) {
        try {
            JSONObject payload = new JSONObject();
            if (action.equals("invite")) { payload.put("code", code); payload.put("name", name); }
            JSONObject response = proofRequest(endpoint, action, payload);
            if (EPOCH.get() != epoch) return;
            String status = response.getString("status");
            if (!status.matches("new|pending|approved|blocked|revoked")) throw new AccessFailure("invalid_response", 0);
            phoneOwner = status.equals("approved") && response.optBoolean("phone_owner",false);
            long wait = Math.max(0, response.getLong("cooldown_until") - response.getLong("server_time"));
            long retry = SystemClock.elapsedRealtime() + Math.min(wait, 86_400L) * 1000;
            if (!status.equals("approved")) {
                clearNative();
                publish(new Snapshot(status, "", false, false, retry));
                return;
            }
            if (openTunnel) {
                JSONObject gateway = selectGateway(response);
                String serialized = gateway.toString();
                String gatewayHash = hex(MessageDigest.getInstance("SHA-256").digest(serialized.getBytes(StandardCharsets.UTF_8)));
                if (!isAllowed() || !gatewayHash.equals(activeGatewayHash)) {
                    allowedUntil = 0;
                    int result;
                    try { result = FamilyNative.start(serialized); }
                    catch (LinkageError | RuntimeException e) { throw new AccessFailure("native_unavailable", 0); }
                    if (result != 0 || !nativeReady()) throw new AccessFailure("tunnel_failed", 0);
                }
                if (FamilyNative.renewLease(75) != 0) throw new AccessFailure("tunnel_failed", 0);
                synchronized (FamilyAccess.class) {
                    if (EPOCH.get() != epoch) { safeStop(); return; }
                    activeGatewayHash = gatewayHash;
                    allowedUntil = SystemClock.elapsedRealtime() + LEASE_MS;
                    schedulePoll();
                }
                FamilyPush.configure(applicationContext, response.getJSONObject("notifications"));
            }
            publish(new Snapshot(status, "", false, isAllowed(), retry));
        } catch (Exception | LinkageError e) {
            if (EPOCH.get() != epoch) return;
            String error = e instanceof AccessFailure ? ((AccessFailure)e).code : "network_error";
            long retry = e instanceof AccessFailure ? ((AccessFailure)e).retrySeconds : 0;
            clearNative();
            String status = error.equals("blocked") || error.equals("revoked") ? error : snapshot.status;
            if (error.equals("not_approved")) status = "unknown";
            publish(new Snapshot(status, error, false, false, SystemClock.elapsedRealtime() + retry * 1000));
        }
    }

    private static synchronized void schedulePoll() {
        if (poll != null) poll.cancel(false);
        poll = IO.schedule(() -> begin("config", null, true), 60, TimeUnit.SECONDS);
    }

    /** Fail closed; native transport is stopped before failure notification. */
    public static void disconnect() { invalidate(""); }
    public interface ManagementCallback {void complete(JSONObject result,String error);}
    /** This flag controls visibility only. The server verifies the installation proof and owner binding for every action. */
    public static void manage(String command,String deviceId,int hours,ManagementCallback callback) {
        if(!isPhoneOwner()){MAIN.post(()->callback.complete(null,"owner_required"));return;}
        IO.execute(()->{
            JSONObject data=null;String error="";
            try {
                JSONObject payload=new JSONObject().put("command",command);
                if("invite".equals(command))payload.put("hours",hours);
                else if("approve".equals(command)||"revoke".equals(command))payload.put("device_id",deviceId);
                else if(!"list".equals(command))throw new AccessFailure("invalid_management",0);
                JSONObject response=proofRequest(server,"manage",payload);
                phoneOwner=response.optBoolean("phone_owner",false);
                if(!phoneOwner)throw new AccessFailure("owner_required",0);
                data=response.getJSONObject("management");
            } catch(Exception failure) {
                error=failure instanceof AccessFailure?((AccessFailure)failure).code:"network_error";
                if("owner_required".equals(error))phoneOwner=false;
                if("revoked".equals(error)||"blocked".equals(error))invalidate(error);
            }
            final JSONObject result=data;final String code=error;
            MAIN.post(()->callback.complete(result,code));
        });
    }
    private static synchronized void invalidate(String error) {
        EPOCH.incrementAndGet();
        clearNative();
        publish(new Snapshot(snapshot.status, error, false, false, snapshot.retryAtElapsed));
    }
    private static synchronized void clearNative() {
        allowedUntil = 0;
        activeGatewayHash = null;
        if (poll != null) { poll.cancel(false); poll = null; }
        safeStop();
        if (applicationContext != null) {
            try { FamilyPush.stop(applicationContext); } catch (RuntimeException ignored) { }
        }
    }
    private static void safeStop() {
        try { FamilyNative.stop(); } catch (LinkageError | RuntimeException ignored) { }
    }
    private static void publish(Snapshot state) {
        snapshot = state;
        MAIN.post(() -> {
            for (Listener listener : LISTENERS) {
                try { listener.onFamilyAccessChanged(snapshot); } catch (RuntimeException ignored) { }
            }
        });
    }

    private static JSONObject proofRequest(String endpoint, String action, JSONObject payload) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
                generator.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256).build());
                generator.generateKeyPair();
            } catch (Exception e) { throw new AccessFailure("keystore_error", 0); }
        }
        byte[] publicKey = store.getCertificate(KEY_ALIAS).getPublicKey().getEncoded();
        String id = hex(MessageDigest.getInstance("SHA-256").digest(publicKey));
        installationId = id;
        JSONObject request = new JSONObject().put("public_key", Base64.encodeToString(publicKey, Base64.NO_WRAP))
                .put("action", action).put("payload", payload);
        JSONObject challenge = post(endpoint, "/v1/challenge", request);
        String challengeId = challenge.getString("challenge_id");
        if (!id.equals(challenge.getString("device_id")) || !challengeId.matches("[0-9a-f-]{36}")) {
            throw new AccessFailure("invalid_response", 0);
        }
        String message = challenge.getString("message");
        boolean challengeBound="manage".equals(action)
                ?FamilyProtocol.validManagementChallenge(message,challengeId,FamilyProtocol.canonicalManagement(payload.getString("command"),payload.optString("device_id",""),payload.optInt("hours",24)))
                :FamilyProtocol.validChallenge(message, challengeId, action);
        if (!challengeBound) {
            throw new AccessFailure("invalid_response", 0);
        }
        Signature signer = Signature.getInstance("SHA256withECDSA");
        try {
            signer.initSign((PrivateKey)store.getKey(KEY_ALIAS, null));
            signer.update(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new AccessFailure("keystore_error", 0); }
        JSONObject result = post(endpoint, "/v1/complete", new JSONObject().put("challenge_id", challengeId)
                .put("signature", Base64.encodeToString(signer.sign(), Base64.NO_WRAP)));
        if (!id.equals(result.getString("device_id"))) throw new AccessFailure("invalid_response", 0);
        return result;
    }

    private static JSONObject post(String endpoint, String path, JSONObject body) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection)new URL(endpoint + path).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) throw new AccessFailure("server_redirect", 0);
            String type = connection.getContentType();
            if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) throw new AccessFailure("invalid_response", 0);
            InputStream raw = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (raw == null) throw new AccessFailure("server_unavailable", 0);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream in = raw) {
                byte[] block = new byte[1024];
                int n;
                while ((n = in.read(block)) != -1) {
                    if (buffer.size() + n > 131_072) throw new AccessFailure("invalid_response", 0);
                    buffer.write(block, 0, n);
                }
            }
            JSONObject result = new JSONObject(buffer.toString("UTF-8"));
            if (status != 200) {
                String error = result.optString("error", "server_unavailable");
                if (!error.matches("blocked|revoked|invalid_invite|cooldown|rate_limited|already_enrolled|expired_or_replayed|invalid_proof|not_approved|gateway_unavailable|too_many_challenges|owner_required|management_unavailable|operation_in_progress|device_not_found|device_not_pending|cannot_modify_owner_phone|invalid_management|owner_must_be_approved")) error = "server_unavailable";
                throw new AccessFailure(error, Math.max(0, Math.min(86_400L, result.optLong("retry_after", 0))));
            }
            return result;
        } finally { connection.disconnect(); }
    }

    private static JSONObject selectGateway(JSONObject response) throws Exception {
        JSONArray alternatives = response.optJSONArray("gateway_fallbacks");
        if (alternatives != null) {
            if (alternatives.length() > 4) throw new AccessFailure("invalid_gateway", 0);
            for (int i = 0; i < alternatives.length(); i++) {
                JSONObject candidate = alternatives.getJSONObject(i);
                if ("tls".equals(candidate.optString("security")) && "ws".equals(candidate.optString("transport"))) {
                    return validGateway(candidate);
                }
            }
        }
        return validGateway(response.getJSONObject("gateway"));
    }

    private static JSONObject validGateway(JSONObject source) throws Exception {
        if (!source.getString("protocol").equals("vless")) {
            throw new AccessFailure("invalid_gateway", 0);
        }
        String host = source.getString("host"), sni = source.getString("server_name");
        if (!host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}") || !sni.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")) throw new AccessFailure("invalid_gateway", 0);
        int port = source.getInt("port");
        String uuid = source.getString("uuid");
        if (port < 1 || port > 65535 || !UUID.fromString(uuid).toString().equalsIgnoreCase(uuid)) throw new AccessFailure("invalid_gateway", 0);
        if ("tls".equals(source.optString("security")) && "ws".equals(source.optString("transport"))) {
            if (!"24projects.ru".equals(host) || !host.equals(sni) || port != 8443
                    || !"/v1/transport".equals(source.optString("path"))
                    || !source.optString("flow").isEmpty() || !source.optString("fingerprint").isEmpty()) {
                throw new AccessFailure("invalid_gateway", 0);
            }
            return new JSONObject().put("protocol", "vless").put("host", host).put("port", port).put("uuid", uuid)
                    .put("security", "tls").put("transport", "ws").put("path", "/v1/transport")
                    .put("server_name", sni).put("flow", "").put("fingerprint", "");
        }
        if (!"reality".equals(source.optString("security")) || !"xtls-rprx-vision".equals(source.optString("flow"))
                || !"chrome".equals(source.optString("fingerprint"))) throw new AccessFailure("invalid_gateway", 0);
        String key = source.getString("public_key"), shortId = source.getString("short_id");
        if (!key.matches("[A-Za-z0-9_-]{43}") || !shortId.matches("(?:[0-9a-fA-F]{2}){1,8}")) throw new AccessFailure("invalid_gateway", 0);
        // Copy known fields only; never forward arbitrary native configuration from the network.
        return new JSONObject().put("protocol", "vless").put("host", host).put("port", port).put("uuid", uuid)
                .put("security", "reality").put("public_key", key).put("short_id", shortId).put("server_name", sni)
                .put("flow", "xtls-rprx-vision").put("fingerprint", "chrome");
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    private static final class AccessFailure extends Exception {
        final String code;
        final long retrySeconds;
        AccessFailure(String code, long retrySeconds) { this.code = code; this.retrySeconds = retrySeconds; }
    }
}
