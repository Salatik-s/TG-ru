package com.salatik.familygram.phonediag;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.HashMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cold-process inspector only: am instrument cannot attach to the existing hung process. */
public final class PhoneDiagnostic extends Instrumentation {
    private final Map<String, String> previous = new HashMap<>();
    private final AtomicBoolean queueWaiting = new AtomicBoolean();
    private final AtomicBoolean queueResponded = new AtomicBoolean();
    private volatile String foreground = "none";
    private long started;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        ((Application)getTargetContext().getApplicationContext()).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity a) {
                String name = a.getClass().getName();
                foreground = name.equals("org.telegram.ui.LaunchActivity") ? "launch"
                    : name.equals("org.telegram.messenger.family.FamilyGateActivity") ? "family_gate" : "other";
            }
            public void onActivityPaused(Activity a) { foreground = "none"; }
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
        Bundle report = new Bundle();
        report.putString("scope", "instrumentation_new_process_not_live_attachment");
        report.putInt("pid", android.os.Process.myPid());
        try {
            started = SystemClock.elapsedRealtime();
            ClassLoader loader = getTargetContext().getClassLoader();
            Class<?> app = Class.forName("org.telegram.messenger.ApplicationLoader", false, loader);
            Class<?> access = Class.forName("org.telegram.messenger.family.FamilyAccess", false, loader);
            Class<?> bridge = Class.forName("org.telegram.messenger.family.FamilyNative", false, loader);
            Class<?> cm = Class.forName("org.telegram.tgnet.ConnectionsManager", false, loader);
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName("com.salatik.familygram", "org.telegram.messenger.ExteralessIcon"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            getTargetContext().startActivity(intent);
            sendStatus(0, report);
            int samples = 0;
            while (SystemClock.elapsedRealtime() - started < 120_000) {
            Bundle summary = new Bundle();
            summary.putBoolean("application_inited", (Boolean)read(app, null, "applicationInited"));
            summary.putString("foreground", foreground);
            summary.putBoolean("network_online", (Boolean)app.getMethod("isNetworkOnlineRealtime").invoke(null));
            Object state = read(access, null, "snapshot");
            String status = String.valueOf(read(state.getClass(), state, "status"));
            if (!status.matches("unknown|new|pending|approved|blocked|revoked")) status = "other";
            summary.putString("family_status", status);
            String error = String.valueOf(read(state.getClass(), state, "error"));
            if (!error.matches("|connection_lost|invalid_invite_format|invalid_response|native_unavailable|tunnel_failed|network_error|keystore_error|server_redirect|server_unavailable|blocked|revoked|invalid_invite|cooldown|rate_limited|already_enrolled|expired_or_replayed|invalid_proof|not_approved|gateway_unavailable|too_many_challenges|invalid_gateway")) error = "other";
            summary.putString("family_error", error.isEmpty() ? "none" : error);
            summary.putBoolean("family_busy", (Boolean)read(state.getClass(), state, "busy"));
            summary.putBoolean("family_snapshot_connected", (Boolean)read(state.getClass(), state, "connected"));
            long until = ((Number)read(access, null, "allowedUntil")).longValue();
            summary.putBoolean("family_unexpired_lease", until > SystemClock.elapsedRealtime());
            summary.putBoolean("native_ready", (Boolean)bridge.getMethod("isReady").invoke(null));
            summary.putBoolean("stage_queue_responded", queueResponded.get());
            summary.putBoolean("stage_queue_waiting", queueWaiting.get());
            if (queueWaiting.compareAndSet(false, true)) {
                Object queue = Class.forName("org.telegram.messenger.Utilities", false, loader).getField("stageQueue").get(null);
                queueResponded.set(false);
                queue.getClass().getMethod("postRunnable", Runnable.class).invoke(queue, (Runnable)() -> {
                    queueResponded.set(true); queueWaiting.set(false);
                });
            }
            Object[] instances = (Object[])read(cm, null, "Instance");
            int count = 0, pending = 0;
            for (int i = 0; i < instances.length; i++) {
                Object instance = instances[i];
                if (instance == null) continue;
                count++;
                Map<?, ?> requests = (Map<?, ?>)read(cm, instance, "requestCallbacks");
                pending += requests.size();
                Bundle manager = new Bundle();
                manager.putInt("slot", i);
                manager.putInt("pending_callback_count", requests.size());
                manager.putBoolean("app_paused", (Boolean)read(cm, instance, "appPaused"));
                manager.putInt("app_resume_count", ((Number)read(cm, instance, "appResumeCount")).intValue());
                manager.putInt("connection_state", ((Number)read(cm, instance, "connectionState")).intValue());
                // Native getters only for managers whose Java instance already exists.
                manager.putInt("datacenter", ((Number)cm.getMethod("getCurrentDatacenterId").invoke(instance)).intValue());
                manager.putInt("native_connection_state", ((Number)cm.getMethod("native_getConnectionState", int.class).invoke(null, i)).intValue());
                manager.putBoolean("native_has_auth_key", ((Number)cm.getMethod("getCurrentAuthKeyId").invoke(instance)).longValue() != 0);
                changed("MANAGER_" + i, manager);
            }
            summary.putInt("existing_manager_count", count);
            summary.putInt("total_pending_callback_count", pending);
            changed("RUNTIME_STATE", summary);
            samples++;
            SystemClock.sleep(5000);
            }
            report.putInt("samples", samples);
            report.putString("result", "NORMAL_LAUNCH_INSPECTION_COMPLETE");
            finish(Activity.RESULT_OK, report);
        } catch (Throwable failure) {
            report.putString("result", "INSPECTION_FAILED");
            report.putString("exception_type", failure.getClass().getSimpleName()); if(failure instanceof ClassNotFoundException) {String missing=failure.getMessage();if(missing!=null&&missing.matches("[A-Za-z0-9.$/]+"))report.putString("missing_class",missing);}
            finish(Activity.RESULT_CANCELED, report);
        }
    }
    private void changed(String event, Bundle values) {
        StringBuilder fingerprint = new StringBuilder();
        for (String key : new TreeSet<>(values.keySet())) fingerprint.append(key).append('=').append(values.get(key)).append(';');
        String current = fingerprint.toString();
        if (current.equals(previous.put(event, current))) return;
        values.putString("event", event);
        values.putLong("elapsed_seconds", (SystemClock.elapsedRealtime() - started) / 1000);
        sendStatus(0, values);
    }
    private static Object read(Class<?> owner, Object object, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
}
