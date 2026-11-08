package com.salatik.familygram.productionqa;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.EditText;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Test APK only. Uses the production application's unchanged TLS, keys and UI. */
public final class ProductionEnrollment extends Instrumentation {
    private static final String TARGET = "com.salatik.familygram";
    private static final String FAMILY = "org.telegram.messenger.family.";
    private volatile Activity resumed;
    private ClassLoader loader;
    private Class<?> access;
    private String phase = "bootstrap";
    private Bundle arguments;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args == null ? new Bundle() : args;
        Application app = (Application)getTargetContext().getApplicationContext();
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity a) { resumed = a; }
            public void onActivityPaused(Activity a) { if (resumed == a) resumed = null; }
            public void onActivityCreated(Activity a, Bundle b) { }
            public void onActivityStarted(Activity a) { }
            public void onActivityStopped(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        });
        start();
    }

    @Override public void onStart() {
        super.onStart();
        try {
            loader = getTargetContext().getClassLoader();
            access = type(FAMILY + "FamilyAccess");
            require("https://24projects.ru:8443".equals(access.getMethod("server").invoke(null)), "production_endpoint");
            assertNoAccount();
            phase = "production_gate";
            Intent launch = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(TARGET, "org.telegram.messenger.ExteralessIcon"));
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            getTargetContext().startActivity(launch);
            await(() -> resumed != null, 30_000);
            await(() -> !busy(), 30_000);
            String status = stateText("status");
            if ("new".equals(status) || "unknown".equals(status)) {
                require(resumed.getClass().getName().equals(FAMILY + "FamilyGateActivity"), "gate_activity");
                Activity gate = resumed;
                String invite = readInvitation();
                phase = "invitation";
                Throwable[] failure = new Throwable[1];
                runOnMainSync(() -> {
                    try {
                        ((EditText)field(gate, "name")).setText("FamilyGram QA Android");
                        ((EditText)field(gate, "invitation")).setText(invite);
                        Button submit = (Button)field(gate, "primaryAction");
                        require(submit.isEnabled(), "invitation_button_disabled");
                        require(submit.performClick(), "invitation_click");
                    } catch (Throwable e) { failure[0] = e; }
                });
                if (failure[0] != null) throw new CheckFailure("invitation_ui");
                await(() -> !busy(), 30_000);
                require("pending".equals(stateText("status")), "invitation_pending");
            }
            status = stateText("status");
            require("pending".equals(status) || "approved".equals(status), "enrollment_state");
            String id = (String)staticField(access, "installationId");
            require(id != null && id.matches("[a-f0-9]{64}"), "public_device_id");
            Bundle publicId = new Bundle();
            publicId.putString("device_id", id);
            publicId.putString("fingerprint", String.valueOf(access.getMethod("deviceFingerprint").invoke(null)));
            publicId.putString("status", status);
            emit("PUBLIC_DEVICE_READY_FOR_APPROVAL", publicId);
            phase = "owner_approval";
            long deadline = SystemClock.elapsedRealtime() + 900_000;
            long nextRefresh = 0;
            while (!allowed()) {
                require(SystemClock.elapsedRealtime() < deadline, "approval_timeout");
                if (!busy() && SystemClock.elapsedRealtime() >= nextRefresh) {
                    access.getMethod("refresh").invoke(null);
                    nextRefresh = SystemClock.elapsedRealtime() + 3000;
                }
                SystemClock.sleep(100);
            }
            phase = "approved_launch";
            await(() -> resumed != null && resumed.getClass().getName().equals("org.telegram.ui.LaunchActivity"), 45_000);
            require(Boolean.TRUE.equals(type(FAMILY + "FamilyNative").getMethod("isReady").invoke(null)), "native_ready");
            assertNoAccount();
            networkProbe();
            assertNoAccount();
            finishResult("PRODUCTION_ENROLLMENT_AND_TELEGRAM_CALLBACK_PASS", true);
        } catch (Throwable error) {
            Bundle result = new Bundle(); result.putString("result", "FAIL"); result.putString("phase", phase);
            result.putString("exception_type", error.getClass().getSimpleName());
            if (error instanceof CheckFailure) result.putString("check", error.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void networkProbe() throws Exception {
        phase = "telegram_read_only_probe";
        Class<?> cm = type("org.telegram.tgnet.ConnectionsManager");
        Class<?> uc = type("org.telegram.messenger.UserConfig");
        int account = uc.getField("selectedAccount").getInt(null);
        Object manager = cm.getMethod("getInstance", int.class).invoke(null, account);
        Bundle state = new Bundle();
        state.putBoolean("application_inited", (Boolean)staticField(type("org.telegram.messenger.ApplicationLoader"), "applicationInited"));
        state.putBoolean("network_online", (Boolean)type("org.telegram.messenger.ApplicationLoader").getMethod("isNetworkOnlineRealtime").invoke(null));
        state.putBoolean("app_paused", (Boolean)field(manager, "appPaused"));
        state.putInt("app_resume_count", ((Number)field(manager, "appResumeCount")).intValue());
        state.putInt("connection_state", ((Number)cm.getMethod("getConnectionState").invoke(manager)).intValue());
        state.putInt("datacenter", ((Number)cm.getMethod("getCurrentDatacenterId").invoke(manager)).intValue());
        emit("APPROVED_RUNTIME_STATE", state);
        Class<?> tl = type("org.telegram.tgnet.TLObject");
        Class<?> delegate = type("org.telegram.tgnet.RequestDelegate");
        Class<?> quickAck = type("org.telegram.tgnet.QuickAckDelegate");
        Class<?> writeDelegate = type("org.telegram.tgnet.WriteToSocketDelegate");
        CountDownLatch callback = new CountDownLatch(1);
        AtomicInteger written = new AtomicInteger();
        AtomicInteger errorCode = new AtomicInteger();
        String[] responseType = new String[]{"none"};
        Object completion = Proxy.newProxyInstance(loader, new Class<?>[]{delegate}, (proxy, method, args) -> {
            if (method.getName().equals("run")) {
                if (args[0] != null) responseType[0] = args[0].getClass().getSimpleName();
                if (args[1] != null) errorCode.set(args[1].getClass().getField("code").getInt(args[1]));
                callback.countDown();
            }
            return null;
        });
        Object write = Proxy.newProxyInstance(loader, new Class<?>[]{writeDelegate}, (proxy, method, args) -> {
            if (method.getName().equals("run")) written.incrementAndGet();
            return null;
        });
        Object request = type("org.telegram.tgnet.TLRPC$TL_help_getNearestDc").getConstructor().newInstance();
        int token = ((Number)cm.getMethod("sendRequest", tl, delegate, quickAck, writeDelegate, int.class, int.class, int.class, boolean.class)
                .invoke(manager, request, completion, null, write, 0x1b, Integer.MAX_VALUE, 1, true)).intValue();
        boolean received = callback.await(60, TimeUnit.SECONDS);
        Bundle outcome = new Bundle();
        outcome.putBoolean("callback_received", received);
        outcome.putString("response_type", responseType[0]);
        outcome.putInt("error_code", errorCode.get());
        outcome.putInt("write_callbacks", written.get());
        outcome.putInt("connection_state", ((Number)cm.getMethod("getConnectionState").invoke(manager)).intValue());
        outcome.putBoolean("request_callback_registered", ((Map<?, ?>)field(manager, "requestCallbacks")).containsKey(token));
        emit("TELEGRAM_READ_ONLY_PROBE_RESULT", outcome);
        if (!received) cm.getMethod("cancelRequest", int.class, boolean.class).invoke(manager, token, true);
        require(received, "telegram_callback_timeout");
        require(errorCode.get() == 0 && "TL_nearestDc".equals(responseType[0]), "telegram_nearest_dc_response");
    }

    private String readInvitation() throws Exception {
        File folder = getTargetContext().getExternalFilesDir(null);
        File file = new File(arguments.getString("invite_file", "")).getCanonicalFile();
        require(folder != null && file.getParentFile().equals(folder.getCanonicalFile()), "invite_scope");
        require(file.length() > 0 && file.length() <= 4096, "invite_size");
        byte[] bytes = new byte[(int)file.length()];
        String code;
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) { int n = input.read(bytes, offset, bytes.length - offset); require(n > 0, "invite_read"); offset += n; }
            code = new JSONObject(new String(bytes, StandardCharsets.UTF_8)).getString("code");
            require(code.matches("[A-Z0-9-]{1,64}"), "invite_format");
        } finally { Arrays.fill(bytes, (byte)0); require(file.delete(), "invite_delete"); }
        return code;
    }
    private void assertNoAccount() throws Exception {
        Class<?> uc = type("org.telegram.messenger.UserConfig");
        for (int i = 0; i < uc.getField("MAX_ACCOUNT_COUNT").getInt(null); i++) {
            Object config = uc.getMethod("getInstance", int.class).invoke(null, i);
            require(!Boolean.TRUE.equals(uc.getMethod("isClientActivated").invoke(config)), "telegram_account_present");
        }
    }
    private Class<?> type(String name) throws Exception { return Class.forName(name, true, loader); }
    private Object state() throws Exception { return access.getMethod("state").invoke(null); }
    private String stateText(String name) throws Exception { return String.valueOf(field(state(), name)); }
    private boolean busy() throws Exception { return (Boolean)field(state(), "busy"); }
    private boolean allowed() throws Exception { return (Boolean)access.getMethod("isAllowed").invoke(null); }
    private static Object field(Object value, String name) throws Exception { Field f = value.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(value); }
    private static Object staticField(Class<?> type, String name) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(null); }
    private interface Condition { boolean test() throws Exception; }
    private static void await(Condition check, long duration) throws Exception {
        long end = SystemClock.elapsedRealtime() + duration;
        while (SystemClock.elapsedRealtime() < end) { if (check.test()) return; SystemClock.sleep(100); }
        throw new CheckFailure("phase_timeout");
    }
    private void emit(String name, Bundle bundle) { bundle.putString("event", name); bundle.putString("phase", phase); sendStatus(0, bundle); }
    private void finishResult(String name, boolean pass) { Bundle b = new Bundle(); b.putString("event", name); b.putString("result", pass ? "PASS" : "FAIL"); finish(pass ? Activity.RESULT_OK : Activity.RESULT_CANCELED, b); }
    private static void require(boolean yes, String code) throws CheckFailure { if (!yes) throw new CheckFailure(code); }
    private static final class CheckFailure extends Exception { CheckFailure(String code) { super(code); } }
}
