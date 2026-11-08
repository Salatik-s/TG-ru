package org.telegram.messenger.family;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Repack-only lifecycle defense; native transport enforcement remains mandatory. */
public final class FamilyBinaryGuard implements Application.ActivityLifecycleCallbacks, FamilyAccess.Listener {
    private static FamilyBinaryGuard instance;
    private static final Set<Activity> redirecting = Collections.newSetFromMap(new WeakHashMap<>());
    private WeakReference<Activity> foreground = new WeakReference<>(null);

    public static synchronized void install(Context context) {
        if (instance != null) return;
        instance = new FamilyBinaryGuard();
        ((Application)context.getApplicationContext()).registerActivityLifecycleCallbacks(instance);
        FamilyAccess.addListener(instance);
    }
    private static boolean familyScreen(Activity activity) {
        return activity instanceof FamilyGateActivity || activity instanceof FamilySettingsActivity;
    }
    public static void redirect(Activity activity) {
        if (familyScreen(activity) || activity.isFinishing() || redirecting.contains(activity)) return;
        redirecting.add(activity);
        Intent intent = new Intent(activity, FamilyGateActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finish();
    }
    @Override public void onFamilyAccessChanged(FamilyAccess.Snapshot snapshot) {
        Activity activity = foreground.get();
        if (!FamilyAccess.isAllowed() && activity != null) redirect(activity);
    }
    @Override public void onActivityResumed(Activity activity) {
        foreground = new WeakReference<>(activity);
        if (!FamilyAccess.isAllowed()) redirect(activity);
        else if(activity.getClass().getName().equals("org.telegram.ui.LaunchActivity"))FamilyFeatures.ensureDefaults(activity);
    }
    @Override public void onActivityPaused(Activity activity) {
        if (foreground.get() == activity) foreground.clear();
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) {
        if (!FamilyAccess.isAllowed()) redirect(activity);
    }
    @Override public void onActivityDestroyed(Activity activity) { redirecting.remove(activity); }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
}
