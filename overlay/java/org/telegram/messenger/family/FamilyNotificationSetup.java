package org.telegram.messenger.family;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

public final class FamilyNotificationSetup {
    private static final int PERMISSION_REQUEST = 0x4647;
    private static final String PREFS = "familygram_notification_setup";
    private static final String OFFERED = "offered";
    private static final String PERMISSION_REQUESTED = "permission_requested";

    private FamilyNotificationSetup() { }

    public static void offerAfterSignIn(Activity activity) {
        activity.getWindow().getDecorView().postDelayed(() -> {
            SharedPreferences prefs = preferences(activity);
            if (!activity.hasWindowFocus() || activity.isFinishing() || activity.isDestroyed()
                    || !FamilyAccess.isAllowed() || !FamilyPush.hasActiveAccount()
                    || prefs.getBoolean(OFFERED, false)) return;
            prefs.edit().putBoolean(OFFERED, true).apply();
            if (!notificationsEnabled(activity) || !backgroundAllowed(activity)) show(activity, true);
        }, 500);
    }

    public static void open(Activity activity) { show(activity, false); }

    private static void show(Activity activity, boolean firstTime) {
        boolean enabled = notificationsEnabled(activity);
        boolean background = backgroundAllowed(activity);
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity, dialogTheme(activity))
                .setTitle("Уведомления в фоне")
                .setNegativeButton(firstTime ? "Позже" : "Закрыть", null);
        if (!enabled) {
            dialog.setMessage("Разрешите уведомления, чтобы видеть новые сообщения и звонки. Работу в фоне можно настроить в этом же разделе.")
                    .setPositiveButton("Разрешить уведомления", (d, w) -> requestNotifications(activity));
            if (!background) dialog.setNeutralButton("Работа в фоне", (d, w) -> openBackground(activity));
        } else if (!background) {
            dialog.setMessage("Разрешите TG-ru работать в фоне, чтобы получать уведомления, когда экран выключен. На некоторых телефонах нужно также разрешить автозапуск в настройках приложения.")
                    .setPositiveButton("Настроить фон", (d, w) -> openBackground(activity));
        } else {
            dialog.setMessage("Уведомления разрешены. Android не ограничивает фоновую работу TG-ru по расходу батареи.")
                    .setPositiveButton("Настройки уведомлений", (d, w) -> openNotifications(activity));
        }
        dialog.show();
    }

    static int dialogTheme(Activity activity) {
        boolean dark = (activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        try {
            dark = Boolean.TRUE.equals(Class.forName("org.telegram.ui.ActionBar.Theme")
                    .getMethod("isCurrentThemeDark").invoke(null));
        } catch (ReflectiveOperationException ignored) { }
        return dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

    private static boolean notificationsEnabled(Activity activity) {
        NotificationManager manager = activity.getSystemService(NotificationManager.class);
        return manager != null && manager.areNotificationsEnabled();
    }

    private static boolean backgroundAllowed(Activity activity) {
        PowerManager manager = activity.getSystemService(PowerManager.class);
        return manager != null && manager.isIgnoringBatteryOptimizations(activity.getPackageName());
    }

    private static SharedPreferences preferences(Activity activity) {
        return activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    private static void requestNotifications(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && (!preferences(activity).getBoolean(PERMISSION_REQUESTED, false)
                    || activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            preferences(activity).edit().putBoolean(PERMISSION_REQUESTED, true).apply();
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST);
            return;
        }
        openNotifications(activity);
    }

    private static void openNotifications(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
        openSystem(activity, intent);
    }

    private static void openBackground(Activity activity) {
        openSystem(activity, new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(activity)));
    }

    private static Uri packageUri(Activity activity) { return Uri.fromParts("package", activity.getPackageName(), null); }

    private static void openSystem(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(activity)));
            } catch (ActivityNotFoundException | SecurityException ignored) {
                Toast.makeText(activity, "Откройте TG-ru в настройках телефона.", Toast.LENGTH_LONG).show();
            }
        }
    }
}
