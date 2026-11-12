package org.telegram.messenger.family;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Loopback-only Store flow for the explicitly marked Android QA build. */
public final class FamilyStoreAccess {
    public static final String QA_MARKER = "org.telegram.messenger.family.STORE_LOCAL_QA";
    static final String LOCAL_ORIGIN = "http://127.0.0.1:48185";
    private static final String KEY_ALIAS = "tg-ru-store-local-device-v1";
    private static final String PREFS = "tg_ru_store_local_qa";
    private static final String DEVICE_ID = "device_id";
    private static final String ACCESS_UNTIL = "access_until";
    private static final String LEASE_UNTIL = "lease_until";
    private static final Pattern TOKEN = Pattern.compile("[a-zA-Z0-9_-]{43}");
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_RESPONSE = 16 * 1024;
    private static volatile Boolean configured;
    private static final AtomicBoolean refreshStarted = new AtomicBoolean();

    private FamilyStoreAccess() { }

    public static boolean isConfigured(Context context) {
        Boolean cached = configured;
        if (cached != null) return cached;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA);
            cached = info.metaData != null && info.metaData.getBoolean(QA_MARKER, false);
        } catch (PackageManager.NameNotFoundException ignored) {
            cached = false;
        }
        configured = cached;
        return cached;
    }

    public static boolean isActive(Context context) {
        if (!isConfigured(context)) return false;
        return preferences(context).getLong(LEASE_UNTIL, 0) > now();
    }

    public static long accessUntil(Context context) {
        if (!isConfigured(context)) return 0;
        return preferences(context).getLong(ACCESS_UNTIL, 0);
    }

    public static void ensureRefreshing(Context context) {
        if (!isConfigured(context) || !refreshStarted.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "TG-ru store lease");
            thread.setDaemon(true);
            return thread;
        });
        loop.scheduleWithFixedDelay(() -> {
            if (!hasDevice(app) || preferences(app).getLong(LEASE_UNTIL, 0) > now() + 30) return;
            try {
                refresh(app);
            } catch (ActivationFailure ignored) {
                // The existing lease covers transient errors until it expires.
            }
        }, 0, 15, TimeUnit.SECONDS);
    }

    static boolean hasDevice(Context context) {
        return FINGERPRINT.matcher(preferences(context).getString(DEVICE_ID, "")).matches();
    }

    static synchronized long activate(Context context, String login, String password) throws ActivationFailure {
        if (!isConfigured(context)) throw new ActivationFailure("disabled");
        try {
            JSONObject credentials = new JSONObject().put("login", login).put("password", password);
            JSONObject issued = post("/api/store/activation-token", credentials);
            String token = issued.getString("token");
            String challenge = issued.getString("challenge");
            if (!TOKEN.matcher(token).matches() || !TOKEN.matcher(challenge).matches()) {
                throw new ActivationFailure("bad_response");
            }

            KeyStore store = getDeviceKey(true);
            byte[] publicKey = publicKey(store);
            JSONObject proof = new JSONObject()
                    .put("token", token)
                    .put("public_key", Base64.encodeToString(publicKey, Base64.NO_WRAP))
                    .put("signature", sign(store, challenge))
                    .put("name", deviceName());
            JSONObject activated = post("/api/store/activate", proof);
            return saveAccess(context, activated, publicKey);
        } catch (GeneralSecurityException error) {
            throw new ActivationFailure("device_key_unavailable");
        } catch (JSONException error) {
            throw new ActivationFailure("bad_response");
        } catch (IOException error) {
            throw new ActivationFailure("network_unavailable");
        }
    }

    static synchronized long refresh(Context context) throws ActivationFailure {
        if (!isConfigured(context) || !hasDevice(context)) throw new ActivationFailure("device_key_unavailable");
        try {
            KeyStore store = getDeviceKey(false);
            String deviceId = preferences(context).getString(DEVICE_ID, "");
            JSONObject issued = post("/api/store/device/challenge", new JSONObject().put(DEVICE_ID, deviceId));
            String token = issued.getString("token");
            String challenge = issued.getString("challenge");
            if (!TOKEN.matcher(token).matches() || !TOKEN.matcher(challenge).matches()) {
                throw new ActivationFailure("bad_response");
            }
            JSONObject renewed = post("/api/store/device/renew", new JSONObject()
                    .put("token", token).put("signature", sign(store, challenge)));
            return saveAccess(context, renewed, publicKey(store));
        } catch (ActivationFailure failure) {
            if ("subscription_expired".equals(failure.code)) {
                preferences(context).edit().remove(LEASE_UNTIL).apply();
            } else if ("device_revoked".equals(failure.code) || "device_not_found".equals(failure.code)) {
                clearDevice(context);
            }
            throw failure;
        } catch (GeneralSecurityException error) {
            clearDevice(context);
            throw new ActivationFailure("device_key_unavailable");
        } catch (JSONException error) {
            throw new ActivationFailure("bad_response");
        } catch (IOException error) {
            throw new ActivationFailure("network_unavailable");
        }
    }

    private static void clearDevice(Context context) {
        preferences(context).edit().remove(DEVICE_ID).remove(ACCESS_UNTIL).remove(LEASE_UNTIL).apply();
    }

    private static KeyStore getDeviceKey(boolean create) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            if (!create) throw new GeneralSecurityException("Missing device key");
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build());
            generator.generateKeyPair();
        }
        return store;
    }

    private static byte[] publicKey(KeyStore store) throws GeneralSecurityException {
        return store.getCertificate(KEY_ALIAS).getPublicKey().getEncoded();
    }

    private static String sign(KeyStore store, String challenge) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign((PrivateKey) store.getKey(KEY_ALIAS, null));
        signer.update(("tg-ru-store-local\n" + challenge).getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(signer.sign(), Base64.NO_WRAP);
    }

    private static long saveAccess(Context context, JSONObject result, byte[] publicKey)
            throws JSONException, GeneralSecurityException, ActivationFailure {
        String deviceId = result.getString(DEVICE_ID);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey);
        StringBuilder expected = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            expected.append(Character.forDigit((value & 0xff) >>> 4, 16));
            expected.append(Character.forDigit(value & 0x0f, 16));
        }
        if (!FINGERPRINT.matcher(deviceId).matches() || !deviceId.contentEquals(expected)) {
            throw new ActivationFailure("bad_response");
        }
        long expiry = result.getLong(ACCESS_UNTIL);
        long current = now();
        if (expiry <= current) {
            throw new ActivationFailure("subscription_required");
        }
        long lease = Math.min(result.getLong(LEASE_UNTIL), current + 75);
        if (lease <= current || lease > expiry) throw new ActivationFailure("bad_response");
        if (!preferences(context).edit().putString(DEVICE_ID, deviceId)
                .putLong(ACCESS_UNTIL, expiry).putLong(LEASE_UNTIL, lease).commit()) {
            throw new ActivationFailure("local_storage_unavailable");
        }
        return expiry;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static String deviceName() {
        String model = Build.MODEL;
        if (model == null || model.trim().isEmpty()) return "Android";
        model = model.replaceAll("\\p{C}", " ").trim();
        if (model.isEmpty()) return "Android";
        int length = model.codePointCount(0, model.length());
        return length <= 48 ? model : model.substring(0, model.offsetByCodePoints(0, 48));
    }

    private static JSONObject post(String path, JSONObject request)
            throws IOException, JSONException, ActivationFailure {
        URL url = validatedEndpoint(LOCAL_ORIGIN, path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(8_000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(body);
            } finally {
                java.util.Arrays.fill(body, (byte) 0);
            }
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) throw new ActivationFailure("bad_response");
            if (status >= 500) throw new ActivationFailure("network_unavailable");
            InputStream response = status < 300 ? connection.getInputStream() : connection.getErrorStream();
            JSONObject json = readObject(response);
            if (status >= 400 || !Boolean.TRUE.equals(json.opt("ok"))) {
                throw new ActivationFailure(json.optString("code", "bad_response"));
            }
            return json;
        } finally {
            connection.disconnect();
        }
    }

    private static URL validatedEndpoint(String origin, String path) throws IOException {
        URL endpoint = new URL(origin + path);
        boolean https = "https".equals(endpoint.getProtocol());
        boolean local = "http".equals(endpoint.getProtocol()) && "127.0.0.1".equals(endpoint.getHost())
                && endpoint.getPort() == 48185;
        if ((!https && !local) || endpoint.getUserInfo() != null || endpoint.getRef() != null) {
            throw new IOException("Untrusted Store endpoint");
        }
        return endpoint;
    }

    private static JSONObject readObject(InputStream input) throws IOException, JSONException, ActivationFailure {
        if (input == null) throw new ActivationFailure("bad_response");
        try (InputStream stream = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[2048];
            for (int n; (n = stream.read(chunk)) != -1; ) {
                if (out.size() + n > MAX_RESPONSE) throw new ActivationFailure("bad_response");
                out.write(chunk, 0, n);
            }
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    static final class ActivationFailure extends Exception {
        final String code;

        ActivationFailure(String code) {
            this.code = code;
        }
    }
}
