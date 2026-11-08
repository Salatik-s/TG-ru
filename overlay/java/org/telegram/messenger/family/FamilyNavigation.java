package org.telegram.messenger.family;

import android.app.Activity;
import android.content.Intent;
import org.telegram.messenger.R;

/** Bridge into the pinned fork's native overflow menu, including custom layouts. */
public final class FamilyNavigation {
    private FamilyNavigation() { }
    private static Activity activity(Object fragment) throws Exception {
        return (Activity)fragment.getClass().getMethod("getParentActivity").invoke(fragment);
    }
    public static void addGhostMenu(Object options, Object fragment) {
        try {
            if (number(fragment, "folderId") != 0 || number(fragment, "initialDialogsType") != 0 || number(fragment, "communityId") != 0) return;
            Class<?> layout = Class.forName("app.exteraless.drawer.MainMenuLayout");
            if ((Boolean)layout.getMethod("isCustomized").invoke(null)
                    && ((java.util.List<?>)layout.getMethod("getLayout").invoke(null)).contains(107)) return;
            Activity activity = activity(fragment); if (activity == null) return;
            int icon = activity.getResources().getIdentifier("ayu_ghost", "drawable", activity.getPackageName());
            options.getClass().getMethod("add", int.class, CharSequence.class, Runnable.class).invoke(options,
                    icon, activity.getString(R.string.fg_ghost_settings_title), (Runnable)() -> openPrivacy(activity));
        } catch (Exception failure) { throw new IllegalStateException("Pinned main menu is incompatible", failure); }
    }
    private static long number(Object target, String name) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name); field.setAccessible(true);
        return ((Number)field.get(target)).longValue();
    }
    public static Object ghostMenuInfo(Object context) {
        try {
            Object fragment = context.getClass().getMethod("fragment").invoke(context);
            Activity activity = activity(fragment); if (activity == null) return null;
            Class<?> info = Class.forName("app.exteraless.drawer.MainMenuHelper$MenuItemInfo");
            return info.getConstructor(int.class, CharSequence.class, Runnable.class, Runnable.class).newInstance(
                    activity.getResources().getIdentifier("ayu_ghost", "drawable", activity.getPackageName()),
                    activity.getString(R.string.fg_ghost_settings_title), (Runnable)() -> openPrivacy(activity), null);
        } catch (Exception failure) { throw new IllegalStateException("Pinned ghost menu is incompatible", failure); }
    }
    private static void openPrivacy(Activity activity) {
        if (FamilyAccess.isAllowed()) activity.startActivity(new Intent(activity, FamilyPrivacyActivity.class));
        else FamilyBinaryGuard.redirect(activity);
    }
}
