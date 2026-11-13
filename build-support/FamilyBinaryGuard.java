package org.telegram.messenger.family;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Repack-only lifecycle defense; native transport enforcement remains mandatory. */
public final class FamilyBinaryGuard implements Application.ActivityLifecycleCallbacks, FamilyAccess.Listener {
    private static final String LAUNCHER = "org.telegram.ui.LaunchActivity";
    private static FamilyBinaryGuard instance;
    private static final Set<Activity> redirecting = Collections.newSetFromMap(new WeakHashMap<>());
    private final Context application;
    private WeakReference<Activity> foreground = new WeakReference<>(null);

    private FamilyBinaryGuard(Context application) {
        this.application = application;
    }

    public static synchronized void install(Context context) {
        if (instance != null) return;
        Application application = (Application)context.getApplicationContext();
        instance = new FamilyBinaryGuard(application);
        application.registerActivityLifecycleCallbacks(instance);
        FamilyAccess.addListener(instance);
        if (FamilyStoreAccess.isConfigured(application)) {
            FamilyStoreAccess.ensureRefreshing(application);
            instance.watchStoreAccess();
        }
    }
    private static boolean familyScreen(Activity activity) {
        return activity instanceof FamilyGateActivity || activity instanceof FamilySettingsActivity
                || activity instanceof FamilyStoreGateActivity;
    }
    public static boolean canShowChats() {
        FamilyBinaryGuard guard = instance;
        if (guard != null && FamilyStoreAccess.isConfigured(guard.application)) {
            return FamilyStoreAccess.isActive(guard.application) || FamilyStoreAccess.hasOfflineAccess(guard.application);
        }
        return FamilyAccess.isAllowed() || FamilyAccess.hasCachedAccount();
    }

    private static boolean canShow(Activity activity) {
        if (FamilyStoreAccess.isConfigured(activity)) {
            return activity instanceof FamilyStoreGateActivity || FamilyStoreAccess.isActive(activity)
                    || activity.getClass().getName().equals(LAUNCHER) && FamilyStoreAccess.hasOfflineAccess(activity);
        }
        if (FamilyAccess.isAllowed()) return true;
        String type = activity.getClass().getName();
        return FamilyAccess.hasCachedAccount()
                && (type.equals(LAUNCHER) || type.startsWith("org.telegram.messenger.family."));
    }

    static void refreshConnectionHeader() {
        Activity activity = instance == null ? null : instance.foreground.get();
        if (activity == null || !activity.getClass().getName().equals(LAUNCHER) || activity.isFinishing()) return;
        try {
            FamilyFeatures.refreshChatTitle();
            activity.getClass().getMethod("updateCurrentConnectionState", int.class).invoke(activity, 0);
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }

    public static void redirect(Activity activity) {
        boolean store = FamilyStoreAccess.isConfigured(activity);
        if (activity instanceof FamilyStoreGateActivity || !store && familyScreen(activity)
                || activity.isFinishing() || redirecting.contains(activity)) return;
        if (store && canShow(activity)) return;
        redirecting.add(activity);
        Class<? extends Activity> destination = store ? FamilyStoreGateActivity.class : FamilyGateActivity.class;
        Intent intent = new Intent(activity, destination);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finish();
    }

    private void watchStoreAccess() {
        Handler main = new Handler(Looper.getMainLooper());
        Runnable check = new Runnable() {
            private boolean wasActive = FamilyStoreAccess.isActive(application);

            @Override public void run() {
                boolean active = FamilyStoreAccess.isActive(application);
                Activity activity = foreground.get();
                if (!active) {
                    if (activity != null && !canShow(activity)) redirect(activity);
                }
                if (active != wasActive) refreshConnectionHeader();
                wasActive = active;
                main.postDelayed(this, 3_000);
            }
        };
        main.post(check);
    }
    @Override public void onFamilyAccessChanged(FamilyAccess.Snapshot snapshot) {
        Activity activity = foreground.get();
        if (activity != null && !canShow(activity)) redirect(activity);
        else refreshConnectionHeader();
    }
    @Override public void onActivityResumed(Activity activity) {
        foreground = new WeakReference<>(activity);
        if (!canShow(activity)) redirect(activity);
        else if (activity.getClass().getName().equals(LAUNCHER)) {
            FamilyAccess.resumeConnection();
            activity.getWindow().getDecorView().post(() -> {
                if (foreground.get() != activity) return;
                refreshConnectionHeader();
                if (!FamilyStoreAccess.isConfigured(activity)) FamilyRouteConsent.maybeAsk(activity);
            });
        }
    }
    @Override public void onActivityPaused(Activity activity) {
        if (foreground.get() == activity) foreground.clear();
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) {
        if (!canShow(activity)) redirect(activity);
    }
    @Override public void onActivityDestroyed(Activity activity) { redirecting.remove(activity); }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
}
