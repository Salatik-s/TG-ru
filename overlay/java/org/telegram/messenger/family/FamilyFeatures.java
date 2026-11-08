package org.telegram.messenger.family;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;
import org.telegram.messenger.R;

/** Internal-only settings navigation, resolved against the pinned Telegram fork. */
public final class FamilyFeatures {
    private FamilyFeatures() { }
    public static boolean saveDeletedEnabled() {
        try {Object item=historyItem();return (Boolean)item.getClass().getMethod("Bool").invoke(item);}catch(Exception failure){return false;}
    }
    public static boolean setSaveDeleted(boolean enabled) {
        try {Object item=historyItem();item.getClass().getMethod("setConfigBool",boolean.class).invoke(item,enabled);return true;}catch(Exception failure){return false;}
    }
    private static Object historyItem() throws Exception {
        Class<?> config=Class.forName("xyz.nextalone.nagram.NaConfig");
        return config.getMethod("getEnableSaveDeletedMessages").invoke(config.getField("INSTANCE").get(null));
    }
    public static void ensureDefaults(android.content.Context context) {
        android.content.SharedPreferences preferences=context.getSharedPreferences("familygram_features_v2",0);
        if(!preferences.getBoolean("deleted_history_initialized",false)&&setSaveDeleted(true))
            preferences.edit().putBoolean("deleted_history_initialized",true).apply();
        if(!preferences.getBoolean("saa_chat_heading_initialized",false))try {
            Class<?> appearance=Class.forName("app.exteraless.appearance.AppearanceConfig");Object mode=appearance.getField("titleText").get(null);mode.getClass().getMethod("setConfigInt",int.class).invoke(mode,0);
            Class<?> config=Class.forName("xyz.nextalone.nagram.NaConfig");Object title=config.getMethod("getCustomTitle").invoke(config.getField("INSTANCE").get(null));title.getClass().getMethod("setConfigString",String.class).invoke(title,"S.A.A.");
            preferences.edit().putBoolean("saa_chat_heading_initialized",true).apply();
        } catch(Exception ignored){}
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
        if("ghost".equals(screen)){activity.startActivity(new Intent(activity,FamilyPrivacyActivity.class));return true;}
        String target;
        if ("advanced".equals(screen)) {
            target = "app.exteraless.settings.OpenExteraSettingsActivity";
        } else if ("advanced_neko".equals(screen)) {
            target = "tw.nekomimi.nekogram.settings.NekoSettingsActivity";
        } else if ("history".equals(screen)) {
            target = "tw.nekomimi.nekogram.settings.NekoExperimentalSettingsActivity";
        } else if ("external_plugins".equals(screen)) {
            target = "app.exteraless.plugins.ui.PluginsActivity";
        } else {
            return true;
        }
        try {
            Class<?> base = Class.forName("org.telegram.ui.ActionBar.BaseFragment");
            Object fragment = Class.forName(target).getConstructor().newInstance();
            if (!base.isInstance(fragment)) throw new IllegalStateException();
            activity.getClass().getMethod("presentFragment", base).invoke(activity, fragment);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Toast.makeText(activity, R.string.fg_feature_unavailable, Toast.LENGTH_LONG).show();
        }
        return true;
    }
}
