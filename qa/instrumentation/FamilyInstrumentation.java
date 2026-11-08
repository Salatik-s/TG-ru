package com.salatik.familygram.qa;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Separate signed QA runner. Never packaged into the production application. */
public final class FamilyInstrumentation extends Instrumentation {
    private static final String TARGET = "com.salatik.familygram";
    private static final String PREFIX = "org.telegram.messenger.family.";
    private Bundle arguments;
    private volatile Activity resumed;
    private Class<?> access, nativeBridge;
    private String phase = "bootstrap";

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args == null ? new Bundle() : args;
        try {
            installQaTransport();
            Application app = (Application)getTargetContext().getApplicationContext();
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                public void onActivityResumed(Activity activity) { resumed = activity; }
                public void onActivityPaused(Activity activity) { if (resumed == activity) resumed = null; }
                public void onActivityCreated(Activity a, Bundle b) { }
                public void onActivityStarted(Activity a) { }
                public void onActivityStopped(Activity a) { }
                public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                public void onActivityDestroyed(Activity a) { }
            });
            start();
        } catch (Throwable error) { failure(error); }
    }

    private void installQaTransport() throws Exception {
        KeyStore roots = KeyStore.getInstance(KeyStore.getDefaultType());
        roots.load(null);
        try (InputStream input = getContext().getAssets().open("qa-ca.pem")) {
            roots.setCertificateEntry("familygram-local-qa", CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(roots);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, factory.getTrustManagers(), null);
        SSLContext.setDefault(tls);
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
        // Default hostname verification is deliberately left untouched.
        ProxySelector fallback = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            public List<Proxy> select(URI uri) {
                if ("https".equalsIgnoreCase(uri.getScheme()) && "24projects.ru".equalsIgnoreCase(uri.getHost()) && uri.getPort() == 8443)
                    return Collections.singletonList(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("10.0.2.2", 19090)));
                return fallback == null ? Collections.singletonList(Proxy.NO_PROXY) : fallback.select(uri);
            }
            public void connectFailed(URI uri, SocketAddress address, java.io.IOException error) {
                if (fallback != null && !"24projects.ru".equalsIgnoreCase(uri.getHost())) fallback.connectFailed(uri, address, error);
            }
        });
    }

    @Override public void onStart() {
        super.onStart();
        try {
            access = Class.forName(PREFIX + "FamilyAccess", true, getTargetContext().getClassLoader());
            nativeBridge = Class.forName(PREFIX + "FamilyNative", true, getTargetContext().getClassLoader());
            if (arguments.getString("mode", "pending").equals("approved")) { approvedColdStart(); return; }
            phase = "initial_gate";
            launchAlias();
            Activity gate = awaitActivity(PREFIX + "FamilyGateActivity", 30_000);
            awaitIdleState(25_000);
            require(!allowed(), "initial_gate_allowed");
            capture(gate, "01-invitation");
            event("INITIAL_GATE_PASS");
            String mode = arguments.getString("mode", "pending");
            if (mode.equals("inspect")) { complete("INSPECT_PASS"); return; }
            if (mode.equals("cooldown")) {
                phase = "cooldown";
                submitThroughUi(gate, "FG-QA-INVALID-000000000000");
                awaitCondition(() -> !busy() && retrySeconds() > 0, 25_000);
                settleUi();
                require("invalid_invite".equals(stateText("error")), "unexpected_invite_error");
                long retry = retrySeconds();
                require(retry >= 280 && retry <= 300, "cooldown_duration");
                require(!allowed() && !nativeReady(), "cooldown_not_closed");
                require(!((Button)field(gate, "primaryAction")).isEnabled(), "cooldown_button_enabled");
                capture(gate, "02-cooldown");
                Bundle detail = new Bundle(); detail.putLong("retry_seconds", retry);
                send("COOLDOWN_PASS", detail);
                complete("COOLDOWN_COMPLETE"); return;
            }
            require(mode.equals("pending") || mode.equals("enroll"), "unknown_mode");
            phase = "invitation";
            String invitation = readInvitation();
            submitThroughUi(gate, invitation);
            invitation = null;
            awaitCondition(() -> !busy() && "pending".equals(stateText("status")), 25_000);
            settleUi();
            require(!allowed() && !nativeReady(), "pending_not_closed");
            require(((EditText)field(gate, "invitation")).getText().length() == 0, "invitation_not_cleared");
            capture(gate, "03-pending");
            Field idField = access.getDeclaredField("installationId"); idField.setAccessible(true);
            String id = (String)idField.get(null);
            require(id.matches("[a-f0-9]{64}"), "device_id_invalid");
            Bundle detail = new Bundle(); detail.putString("device_id", id);
            send("PENDING_PUBLIC_DEVICE_ID", detail);
            phase = "approval_wait";
            awaitWithRefresh(() -> allowed(), 900_000);
            require(nativeReady(), "approved_native_not_ready");
            if (mode.equals("enroll")) { complete("ENROLL_APPROVED_SETUP_PASS"); return; }
            phase = "reflection_audit";
            auditReflectionTargets();
            event("REFLECTION_TARGETS_PASS");
            phase = "approved_launch";
            Activity launch = awaitActivity("org.telegram.ui.LaunchActivity", 45_000);
            SystemClock.sleep(1500);
            assertNoTelegramAccount();
            capture(launch, "04-telegram-welcome");
            event("APPROVED_NATIVE_AND_LAUNCH_PASS");
            phase = "family_settings";
            startTarget(PREFIX + "FamilySettingsActivity");
            Activity settings = awaitActivity(PREFIX + "FamilySettingsActivity", 20_000);
            awaitIdleState(25_000);
            require(allowed(), "settings_access_lost");
            for (String name : new String[]{"ghost", "history", "plugins"}) {
                View row = (View)field(settings, name);
                require(row.isShown() && row.isEnabled(), "settings_row_" + name);
            }
            capture(settings, "05-family-settings");
            ScrollView scroll = findScroll(settings.getWindow().getDecorView());
            if (scroll != null) {
                runOnMainSync(() -> scroll.fullScroll(View.FOCUS_DOWN));
                settleUi();
                capture(settings, "05b-family-settings-bottom");
            }
            event("SETTINGS_ROWS_PASS");
            runOnMainSync(settings::finish);
            launch = awaitActivity("org.telegram.ui.LaunchActivity", 20_000);
            event("READY_TO_REVOKE");
            phase = "revocation_wait";
            awaitWithRefresh(() -> "revoked".equals(stateText("status")), 900_000);
            require(!allowed() && !nativeReady(), "revocation_not_closed");
            launchAlias();
            gate = awaitActivity(PREFIX + "FamilyGateActivity", 20_000);
            capture(gate, "06-revoked");
            assertNoTelegramAccount();
            complete("REVOKED_NATIVE_STOP_AND_GATE_PASS");
        } catch (Throwable error) { failure(error); }
    }

    private String readInvitation() throws Exception {
        String path = arguments.getString("invite_file", "");
        File folder = getTargetContext().getExternalFilesDir(null);
        File file = new File(path).getCanonicalFile();
        require(folder != null && file.getParentFile().equals(folder.getCanonicalFile()), "invitation_file_scope");
        require(file.length() > 0 && file.length() <= 128, "invitation_file_size");
        byte[] bytes = new byte[(int)file.length()];
        try (FileInputStream stream = new FileInputStream(file)) {
            int at = 0, read;
            while (at < bytes.length && (read = stream.read(bytes, at, bytes.length - at)) != -1) at += read;
            require(at == bytes.length, "invitation_file_read");
        }
        String value = new String(bytes, StandardCharsets.UTF_8).trim();
        require(value.matches("[A-Z0-9-]{1,64}"), "invitation_file_format");
        java.util.Arrays.fill(bytes, (byte)0);
        require(file.delete(), "invitation_file_cleanup");
        return value;
    }

    private void approvedColdStart() throws Exception {
        phase = "approved_cold_start";
        launchAlias();
        awaitWithRefresh(() -> allowed(), 45_000);
        require(nativeReady(), "cold_start_native_not_ready");
        Activity launch = awaitActivity("org.telegram.ui.LaunchActivity", 45_000);
        settleUi();
        assertNoTelegramAccount();
        Class<?> locale = Class.forName("org.telegram.messenger.LocaleController", false, getTargetContext().getClassLoader());
        require("FamilyGram".equals(locale.getMethod("getAppName").invoke(null)), "welcome_brand_name");
        capture(launch, "07-final-welcome");
        event("BRANDING_COLD_START_PASS");
        event("READY_TO_REVOKE");
        phase = "final_revocation";
        awaitWithRefresh(() -> "revoked".equals(stateText("status")), 900_000);
        require(!allowed() && !nativeReady(), "final_revocation_not_closed");
        launchAlias();
        Activity gate = awaitActivity(PREFIX + "FamilyGateActivity", 20_000);
        capture(gate, "08-final-revoked");
        complete("BRANDING_AND_COLD_START_REVOCATION_PASS");
    }

    private void submitThroughUi(Activity gate, String code) throws Exception {
        EditText name = (EditText)field(gate, "name"), invitation = (EditText)field(gate, "invitation");
        Button action = (Button)field(gate, "primaryAction");
        runOnMainSync(() -> { name.setText("QA Android emulator"); invitation.setText(code); action.performClick(); });
    }
    private void launchAlias() { startTarget("org.telegram.messenger.ExteralessIcon"); }
    private void startTarget(String name) {
        Intent intent = new Intent().setComponent(new ComponentName(TARGET, name));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        getTargetContext().startActivity(intent);
    }
    private Activity awaitActivity(String name, long timeout) throws Exception {
        awaitCondition(() -> resumed != null && resumed.getClass().getName().equals(name), timeout);
        return resumed;
    }
    private Object state() throws Exception { return access.getMethod("state").invoke(null); }
    private String stateText(String name) throws Exception { Object s = state(); return (String)s.getClass().getField(name).get(s); }
    private boolean busy() throws Exception { Object s = state(); return s.getClass().getField("busy").getBoolean(s); }
    private long retrySeconds() throws Exception { Object s = state(); return (Long)s.getClass().getMethod("retrySeconds").invoke(s); }
    private boolean allowed() throws Exception { return (Boolean)access.getMethod("isAllowed").invoke(null); }
    private boolean nativeReady() throws Exception { return (Boolean)nativeBridge.getMethod("isReady").invoke(null); }
    private void settleUi() { SystemClock.sleep(300); runOnMainSync(() -> { }); }
    private void awaitIdleState(long timeout) throws Exception { awaitCondition(() -> !busy(), timeout); settleUi(); }
    private interface Check { boolean test() throws Exception; }
    private void awaitCondition(Check check, long timeout) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < until) { if (check.test()) return; SystemClock.sleep(100); }
        throw new CheckFailure("timeout");
    }
    private void awaitWithRefresh(Check check, long timeout) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout, next = 0;
        while (SystemClock.elapsedRealtime() < until) {
            if (check.test()) return;
            if (!busy() && SystemClock.elapsedRealtime() >= next) {
                access.getMethod("refresh").invoke(null); next = SystemClock.elapsedRealtime() + 3000;
            }
            SystemClock.sleep(100);
        }
        throw new CheckFailure("timeout");
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static ScrollView findScroll(View view) {
        if (view instanceof ScrollView) return (ScrollView)view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ScrollView found = findScroll(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
    private void assertNoTelegramAccount() throws Exception {
        Class<?> config = Class.forName("org.telegram.messenger.UserConfig", true, getTargetContext().getClassLoader());
        int count = config.getField("MAX_ACCOUNT_COUNT").getInt(null);
        for (int i = 0; i < count; i++) {
            Object account = config.getMethod("getInstance", int.class).invoke(null, i);
            require(!Boolean.TRUE.equals(config.getMethod("isClientActivated").invoke(account)), "unexpected_telegram_account");
        }
    }
    private void auditReflectionTargets() throws Exception {
        ClassLoader loader = getTargetContext().getClassLoader();
        Class<?> base = Class.forName("org.telegram.ui.ActionBar.BaseFragment", false, loader);
        Class<?> launch = Class.forName("org.telegram.ui.LaunchActivity", false, loader);
        launch.getMethod("presentFragment", base);
        for (String name : new String[]{"tw.nekomimi.nekogram.settings.GhostModeActivity",
                "tw.nekomimi.nekogram.settings.NekoExperimentalSettingsActivity", "app.exteraless.plugins.ui.PluginsActivity"}) {
            Class<?> destination = Class.forName(name, false, loader);
            require(base.isAssignableFrom(destination), "destination_not_fragment");
            destination.getConstructor();
        }
        Class<?> config = Class.forName("org.telegram.messenger.UserConfig", false, loader);
        require(config.getField("MAX_ACCOUNT_COUNT").getType() == int.class, "account_count_field_type");
        config.getMethod("getInstance", int.class); config.getMethod("isClientActivated"); config.getMethod("getClientUserId");
        Class<?> object = Class.forName("org.telegram.tgnet.TLObject", false, loader);
        Class<?> error = Class.forName("org.telegram.tgnet.TLRPC$TL_error", false, loader);
        Class<?> delegate = Class.forName("org.telegram.tgnet.RequestDelegate", false, loader);
        require(delegate.isInterface(), "request_delegate_not_interface");
        delegate.getMethod("run", object, error);
        Object proxy = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{delegate}, (p, method, args) -> null);
        delegate.getMethod("run", object, error).invoke(proxy, new Object[]{null, null});
        Class<?> manager = Class.forName("org.telegram.tgnet.ConnectionsManager", false, loader);
        manager.getMethod("getInstance", int.class); manager.getMethod("sendRequest", object, delegate);
        manager.getMethod("onInternalPushReceived", int.class); manager.getMethod("resumeNetworkMaybe");
        Class<?> request = Class.forName("org.telegram.tgnet.tl.TL_account$registerDevice", false, loader);
        request.getConstructor();
        require(request.getField("token_type").getType() == int.class, "push_token_type_field");
        require(request.getField("token").getType() == String.class, "push_token_field");
        require(request.getField("secret").getType() == byte[].class, "push_secret_field");
        require(request.getField("no_muted").getType() == boolean.class, "push_no_muted_field");
        require(Class.forName("org.telegram.tgnet.TLRPC$TL_boolTrue", false, loader).getSimpleName().equals("TL_boolTrue"), "push_response_class_name");
        Class<?> service = Class.forName("org.telegram.messenger.voip.VoIPService", false, loader);
        service.getMethod("getSharedInstance"); service.getMethod("hangUp");
        Class<?> pushController = Class.forName("org.telegram.messenger.PushListenerController", false, loader);
        Object provider = pushController.getMethod("getProvider").invoke(null);
        require(provider != null && provider.getClass().getName().equals("org.telegram.messenger.PushListenerController$DummyPushProvider"), "upstream_push_provider_not_dummy");
    }
    private void capture(Activity activity, String name) throws Exception {
        SystemClock.sleep(350);
        File directory = new File(getTargetContext().getExternalFilesDir(null), "qa");
        require(directory.isDirectory() || directory.mkdirs(), "screenshot_directory");
        String variant = arguments.getString("variant", "default").replaceAll("[^a-zA-Z0-9_-]", "_");
        File output = new File(directory, variant + "-" + name + ".png");
        Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try {
                View decor = activity.getWindow().getDecorView();
                require(decor.getWidth() > 0 && decor.getHeight() > 0, "screenshot_layout");
                Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
                decor.draw(new Canvas(bitmap));
                try (FileOutputStream stream = new FileOutputStream(output)) { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream), "screenshot_encode"); }
                finally { bitmap.recycle(); }
            } catch (Throwable e) { failure[0] = e; }
        });
        if (failure[0] != null) throw new CheckFailure("screenshot_failed");
        Bundle details = new Bundle(); details.putString("screenshot", output.getAbsolutePath()); send("SCREENSHOT", details);
    }
    private static void require(boolean condition, String code) throws CheckFailure { if (!condition) throw new CheckFailure(code); }
    private static final class CheckFailure extends Exception { CheckFailure(String code) { super(code); } }
    private void event(String event) { send(event, new Bundle()); }
    private void send(String event, Bundle result) { result.putString("event", event); result.putString("phase", phase); sendStatus(0, result); }
    private void complete(String event) { Bundle result = new Bundle(); result.putString("event", event); result.putString("result", "PASS"); finish(Activity.RESULT_OK, result); }
    private void failure(Throwable error) {
        Bundle result = new Bundle(); result.putString("result", "FAIL"); result.putString("phase", phase);
        result.putString("exception", error.getClass().getName());
        if (error instanceof CheckFailure) result.putString("check", error.getMessage());
        try { if (access != null) { result.putString("access_status", stateText("status")); result.putString("access_error", stateText("error")); } } catch (Throwable ignored) { }
        finish(Activity.RESULT_CANCELED, result);
    }
}
