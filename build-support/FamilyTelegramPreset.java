package org.telegram.messenger.family;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Source-backed first-install defaults for exteraless v13.1.1-beta15.
 * Call immediately after ApplicationLoader.applicationContext is assigned, before
 * AndroidUtilities/NekoConfig/NaConfig/AppearanceConfig can cache preferences.
 * This disables optional fork styling; it does not establish pixel equality to Telegram.
 */
public final class FamilyTelegramPreset {
    private FamilyTelegramPreset() { }

    public static void apply(Context context) {
        FamilyChatHeader.initialize(context.getApplicationContext());
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences("nkmrcfg", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        if (!prefs.getBoolean("TgRuStoriesHeaderRestored", false)) {
            editor.putBoolean("DisableStories", false);
            editor.putBoolean("HideStoriesFromHeader", false);
            editor.putBoolean("TgRuStoriesHeaderRestored", true);
        }
        String[] off = {
            "OEAppearanceSquareFab", "OEAppearanceSenderMiniAvatars", "OEAppearanceGooeyAvatarAnimation",
            "OEAppearanceGlassMessageMenu", "OEAppearanceNewLoadingStyle", "OEAppearanceNewChatHeaderStyle",
            "OEAppearanceNewNavigationBarStyle", "OEAppearanceM3ListItems", "OEAppearanceMd3Player",
            "OEAppearanceMd3MiniPlayer", "OEAppearanceIosNavigationBarStyle", "OEAppearanceIosChatHeader",
            "OEAppearanceNavigationDrawer", "OEAppearanceImmersiveDrawer", "OEAppearanceShowFeedTab",
            "OEAppearanceBottomNavigationFloating", "OEChatsRemoveMessageTail",
            "HideBottomNavigationBar", "MainTabsHideTitles", "MainTabsHideContacts",
            "MainTabsHideCallsSettings", "MainTabsHideProfile", "CenterActionBarTitle"
        };
        for (String key : off) if (!prefs.contains(key)) editor.putBoolean(key, false);
        for (String key : new String[]{"SwitchStyle", "SliderStyle", "OEChatsStickerShape"}) {
            if (!prefs.contains(key)) editor.putInt(key, 0);
        }
        if (!prefs.contains("OEPillActivePills")) editor.putString("OEPillActivePills", "");
        if (!prefs.contains("OEPillHiddenPills")) editor.putString("OEPillHiddenPills", "5,6");
        // Do not change individual chat themes, account data, ghost/history choices,
        // plugins, wallpaper, profile music or upstream Telegram AI features.
        editor.apply();
    }
}
