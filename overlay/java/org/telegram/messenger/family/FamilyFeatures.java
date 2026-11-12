package org.telegram.messenger.family;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;
import org.telegram.messenger.R;

/** Internal-only settings navigation, resolved against the pinned Telegram fork. */
public final class FamilyFeatures {
    private static java.lang.ref.WeakReference<Object> chats = new java.lang.ref.WeakReference<>(null);

    private FamilyFeatures() { }
    static boolean isMainChatList(Object fragment) {
        try {
            Class<?> type = fragment.getClass();
            return !field(type, "onlySelect").getBoolean(fragment)
                    && field(type, "folderId").getInt(fragment) == 0
                    && field(type, "communityId").getLong(fragment) == 0
                    && field(type, "initialDialogsType").getInt(fragment) == 0;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static java.lang.reflect.Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try {
                java.lang.reflect.Field result = owner.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    public static void onChatsResumed(Object fragment) {
        if (!isMainChatList(fragment)) return;
        try {
            Activity activity = (Activity) fragment.getClass().getMethod("getParentActivity").invoke(fragment);
            if (activity != null) {
                chats = new java.lang.ref.WeakReference<>(fragment);
                FamilyPush.onChatsOpened(activity);
                FamilyAppUpdate.offerInChats(activity, fragment);
                FamilyNotificationSetup.offerAfterSignIn(activity);
            }
        } catch (ReflectiveOperationException ignored) { }
    }

    public static void refreshChatTitle() {
        Object fragment = chats.get();
        if (fragment == null) return;
        try { fragment.getClass().getMethod("updateTitleText").invoke(fragment); }
        catch (ReflectiveOperationException ignored) { }
    }

    public static boolean open(Activity activity, Intent intent) {
        if (intent == null || !intent.hasExtra("family_screen")) return false;
        String screen = intent.getStringExtra("family_screen");
        if (!FamilyAccess.isAllowed()) return true;
        // Leave the intent untouched so Telegram can resume it after its passcode flow.
        try {
            Class<?> config = Class.forName("org.telegram.messenger.SharedConfig");
            if (config.getField("isWaitingForPasscodeEnter").getBoolean(null)) return false;
            Class<?> utilities = Class.forName("org.telegram.messenger.AndroidUtilities");
            if ((Boolean) utilities.getMethod("needShowPasscode", boolean.class).invoke(null, false)) return false;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
        intent.removeExtra("family_screen");
        if("plugins".equals(screen)){activity.startActivity(new Intent(activity,FamilyPluginsActivity.class));return true;}
        if (!"external_plugins".equals(screen)) return true;
        try {
            Class<?> base = Class.forName("org.telegram.ui.ActionBar.BaseFragment");
            Object fragment = Class.forName("app.exteraless.plugins.ui.PluginsActivity").getConstructor().newInstance();
            if (!base.isInstance(fragment)) throw new IllegalStateException();
            activity.getClass().getMethod("presentFragment", base).invoke(activity, fragment);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Toast.makeText(activity, R.string.fg_feature_unavailable, Toast.LENGTH_LONG).show();
        }
        return true;
    }
}
