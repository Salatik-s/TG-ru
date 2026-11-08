package com.salatik.familygram.notificationdiag;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Reads only the app's own foreground-service notification. Never reads a chat's
 * notification payload, sends a message, edits preferences, or calls owner APIs. */
public final class NotificationDiagnostic extends Instrumentation {
    private static final String TARGET = "com.salatik.familygram";
    private static final String CHANNEL = "familygram_push_connection";
    private static final int SERVICE_ID = 0x464701;
    private static final long TOTAL_WAIT_MS = 30_000;
    private int expectedVersion = 10012;
    private boolean argumentsValid = true;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        try {
            if (args != null && args.containsKey("expected_version")) {
                String value = args.getString("expected_version", "");
                if (!value.matches("[0-9]{5}")) throw new IllegalArgumentException();
                expectedVersion = Integer.parseInt(value);
                if (expectedVersion < 10012 || expectedVersion > 19999) throw new IllegalArgumentException();
            }
        } catch (Exception ignored) { argumentsValid = false; }
        start();
    }

    private static final class Snapshot {
        final Bundle result = new Bundle();
        boolean pass;
        boolean connected;
        String error;
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        long started = SystemClock.elapsedRealtime();
        long deadline = started + TOTAL_WAIT_MS;
        try {
            if (!argumentsValid) { finishFailure("INVALID_ARGUMENTS", result); return; }
            Context target = getTargetContext();
            if (Build.VERSION.SDK_INT < 26 || !TARGET.equals(target.getPackageName())) {
                finishFailure("TARGET_OR_API_MISMATCH", result); return;
            }
            PackageInfo info = target.getPackageManager().getPackageInfo(TARGET, 0);
            long version = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            result.putBoolean("target_version_expected", version == expectedVersion);
            if (version != expectedVersion) { finishFailure("TARGET_VERSION_MISMATCH", result); return; }
            Method connected = Class.forName("org.telegram.messenger.family.FamilyPushService", false,
                    target.getClassLoader()).getDeclaredMethod("telegramConnected");
            connected.setAccessible(true);
            Handler main = new Handler(Looper.getMainLooper());
            AtomicReference<String> launchFailure = new AtomicReference<>();
            CountDownLatch launched = new CountDownLatch(1);
            Runnable launch = () -> {
                try {
                    Intent intent = target.getPackageManager().getLaunchIntentForPackage(TARGET);
                    if (intent == null) launchFailure.set("LAUNCH_UNAVAILABLE");
                    else target.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception | LinkageError ignored) { launchFailure.set("LAUNCH_FAILED"); }
                finally { launched.countDown(); }
            };
            main.post(launch);
            if (!launched.await(2, TimeUnit.SECONDS)) {
                main.removeCallbacks(launch); finishFailure("MAIN_THREAD_TIMEOUT", result); return;
            }
            if (launchFailure.get() != null) { finishFailure(launchFailure.get(), result); return; }

            boolean previousPass = false, previousConnected = false;
            boolean everFound = false;
            while (SystemClock.elapsedRealtime() < deadline) {
                AtomicReference<Snapshot> captured = new AtomicReference<>();
                CountDownLatch done = new CountDownLatch(1);
                Runnable inspect = () -> {
                    try { captured.set(inspectOwned(target, connected)); }
                    finally { done.countDown(); }
                };
                main.post(inspect);
                long remaining = Math.max(1, deadline - SystemClock.elapsedRealtime());
                if (!done.await(Math.min(2_000, remaining), TimeUnit.MILLISECONDS)) {
                    main.removeCallbacks(inspect); finishFailure("MAIN_THREAD_TIMEOUT", result); return;
                }
                Snapshot snapshot = captured.get();
                if (snapshot == null) { finishFailure("INSPECTION_UNAVAILABLE", result); return; }
                if (snapshot.error != null) { finishFailure(snapshot.error, result); return; }
                result.putAll(snapshot.result);
                everFound |= snapshot.result.getBoolean("owned_notification_found");
                // Two matching samples avoid declaring success during a brief
                // transition before the service's normal 2-second refresh.
                if (snapshot.pass && previousPass && previousConnected == snapshot.connected) {
                    result.putBoolean("own_foreground_notification_verified", true);
                    result.putString("result", "OWN_NOTIFICATION_PASS");
                    result.putLong("elapsed_ms", SystemClock.elapsedRealtime() - started);
                    finish(Activity.RESULT_OK, result); return;
                }
                previousPass = snapshot.pass; previousConnected = snapshot.connected;
                SystemClock.sleep(Math.min(500, Math.max(0, deadline - SystemClock.elapsedRealtime())));
            }
            result.putLong("elapsed_ms", SystemClock.elapsedRealtime() - started);
            finishFailure(everFound ? "OWN_NOTIFICATION_PROPERTIES_MISMATCH" : "OWN_NOTIFICATION_NOT_FOUND", result);
        } catch (Exception | LinkageError ignored) {
            finishFailure("DIAGNOSTIC_FAILED", result);
        }
    }

    private static Snapshot inspectOwned(Context target, Method connectedMethod) {
        Snapshot snapshot = new Snapshot();
        try {
            snapshot.connected = Boolean.TRUE.equals(connectedMethod.invoke(null));
            NotificationManager manager = target.getSystemService(NotificationManager.class);
            if (manager == null) { snapshot.error = "NOTIFICATION_SERVICE_UNAVAILABLE"; return snapshot; }
            StatusBarNotification owned = null;
            for (StatusBarNotification candidate : manager.getActiveNotifications()) {
                // Do not call getNotification/getTag/getKey or inspect extras for
                // any record before BOTH the package and exact service ID match.
                if (candidate.getId() == SERVICE_ID && TARGET.equals(candidate.getPackageName())) {
                    owned = candidate; break;
                }
            }
            snapshot.result.putBoolean("owned_notification_found", owned != null);
            if (owned == null) return snapshot;
            Notification notification = owned.getNotification();
            Bundle extras = notification.extras;
            boolean title = extras != null && "TG-ru".contentEquals(extras.getCharSequence(Notification.EXTRA_TITLE, ""));
            String expectedBody = snapshot.connected ? "Соединено с Telegram" : "Подключение к Telegram…";
            boolean body = extras != null && expectedBody.contentEquals(extras.getCharSequence(Notification.EXTRA_TEXT, ""));
            boolean foreground = (notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
            boolean ongoing = (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
            boolean category = Notification.CATEGORY_SERVICE.equals(notification.category);
            boolean noTime = extras != null && !extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true);
            boolean localOnly = (notification.flags & Notification.FLAG_LOCAL_ONLY) != 0;
            boolean onlyOnce = (notification.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0;
            boolean channelID = CHANNEL.equals(notification.getChannelId());
            NotificationChannel channel = channelID ? manager.getNotificationChannel(CHANNEL) : null;
            boolean channelName = channel != null && "TG-ru".contentEquals(channel.getName());
            snapshot.result.putBoolean("title_tgru", title);
            snapshot.result.putBoolean("body_matches_current_telegram_state", body);
            snapshot.result.putBoolean("telegram_connected", snapshot.connected);
            snapshot.result.putBoolean("foreground_service_flag", foreground);
            snapshot.result.putBoolean("ongoing_flag", ongoing);
            snapshot.result.putBoolean("category_service", category);
            snapshot.result.putBoolean("show_when_disabled", noTime);
            snapshot.result.putBoolean("local_only_flag", localOnly);
            snapshot.result.putBoolean("only_alert_once_flag", onlyOnce);
            snapshot.result.putBoolean("channel_id_preserved", channelID);
            snapshot.result.putBoolean("channel_name_tgru", channelName);
            // Informational: existing user-selected channel importance must be
            // respected. Never reset it or fail merely because the user raised it.
            snapshot.result.putBoolean("channel_importance_quiet", channel != null && channel.getImportance() <= NotificationManager.IMPORTANCE_LOW);
            snapshot.pass = title && body && foreground && ongoing && category && noTime && localOnly && onlyOnce && channelID && channelName;
        } catch (Exception | LinkageError ignored) { snapshot.error = "OWN_NOTIFICATION_INSPECTION_FAILED"; }
        return snapshot;
    }

    private void finishFailure(String token, Bundle result) {
        result.putBoolean("own_foreground_notification_verified", false);
        result.putString("result", token);
        finish(Activity.RESULT_CANCELED, result);
    }
}
