# Telegram-first appearance

The user's direction is to preserve familiar Telegram screens and put extra functions in settings. This file records verified configuration points, not a claim that screenshots match pixel for pixel.

## Keep the current Telegram navigation

The official Telegram Android app gained a bottom navigation bar on **9 February 2026**. Removing every bottom tab to recreate older Telegram would therefore be an ungrounded change. Keep the chosen base's chat list, chat screen and navigation structure. [Official Telegram announcement](https://telegram.org/blog/crafting-android-design-and-more/ru).

Reference files in `upstream-reference/` were fetched read-only from the **v13.1.1-beta15** tag of [exteraless](https://github.com/exteraless/exteraless/tree/v13.1.1-beta15). They are research snapshots, not replacement source files. They are not compiled into the app by the contingency scripts.

## Proposed defaults

`FamilyTelegramPreset.java` is a small optional integration artifact. It changes only **missing** preferences in `nkmrcfg`, preserving a user's explicit choices. Integrate it immediately after assigning `ApplicationLoader.applicationContext`, before any configuration caches load. Do not call it after views have been built and expect cached configuration to update.

Verified optional fork controls:

| Keys | Proposed default | Source / reason |
|---|---|---|
| `OEAppearanceSquareFab` | false | AppearanceConfig explicitly describes the squircle FAB as a visible extera difference |
| `OEAppearanceSenderMiniAvatars` | false | Additional sender avatars in chat-list previews |
| `OEAppearanceGooeyAvatarAnimation` | false | Fork profile avatar animation |
| `OEAppearanceGlassMessageMenu` | false | Optional glass message menu |
| `OEAppearanceNewLoadingStyle` | false | Optional Material 3 progress indicators |
| `OEAppearanceMd3Player`, `OEAppearanceMd3MiniPlayer` | false | Material 3 player variants |
| `OEAppearanceM3ListItems`, `OEAppearanceNewChatHeaderStyle`, `OEAppearanceNewNavigationBarStyle` | false | Already false upstream; preserve the non-M3 branches |
| `OEAppearanceIosNavigationBarStyle`, `OEAppearanceIosChatHeader` | false | Preserve Android rather than iOS-styled branches |
| `OEAppearanceNavigationDrawer`, `OEAppearanceImmersiveDrawer` | false | Optional custom drawer and animation |
| `OEAppearanceShowFeedTab` | false | No extra feed tab replacing Contacts |
| `OEAppearanceBottomNavigationFloating` | false | Preserve normal navigation placement |
| `HideBottomNavigationBar`, `MainTabsHideTitles`, `MainTabsHideContacts`, `MainTabsHideCallsSettings`, `MainTabsHideProfile` | false | Keep standard destinations and their labels |
| `CenterActionBarTitle` | false | Keep default Android title alignment |
| `SwitchStyle`, `SliderStyle` | 0 | NaConfig explicitly labels 0 as default, 2 as MD3 |
| `OEChatsRemoveMessageTail` | false | Preserve the normal message bubble tail |
| `OEChatsStickerShape` | 0 | ChatsConfig labels 0 as the original shape, 1 as rounded |
| `OEPillActivePills` / `OEPillHiddenPills` | empty / `5,6` | Hide the default cache/proxy pill strip; controls remain in settings |

**Do not disable `OEAppearanceCustomThemes`.** It controls real per-chat themes and wallpapers, including choices the user already made. Do not turn off Telegram profile music, message reactions, AI controls, wallpaper, history or ghost features merely because their names appear near fork styling options. Their absence would also diverge from normal Telegram behavior.

The preset is not a full source revert. Section radii, retained fork components, inherited navigation geometry and theme colors still need a side-by-side Android comparison against the matching official release. No numeric value for those has been invented.

## Actual settings destinations

All three destinations are **BaseFragment** screens, not manifest Activities. Do not use `setClassName` to start them as Android Activities.

| `family_screen` value | Fragment to present | Verified evidence |
|---|---|---|
| `ghost` | `new tw.nekomimi.nekogram.settings.GhostModeActivity()` | MainTabsActivity already presents it from its settings long-press menu |
| `history` | `new tw.nekomimi.nekogram.settings.NekoExperimentalSettingsActivity()` | Contains real `EnableSaveDeletedMessages` and `EnableSaveEditsHistory` controls, category/media/exception settings |
| `plugins` | `new app.exteraless.plugins.ui.PluginsActivity()` | OpenExteraSettingsActivity already presents this engine's plugin screen |

The history destination currently also contains other experimental settings. Label the entry as history settings, but do not claim that it is an isolated history browser. `AyuMessageHistory` and `AyuViewDeleted` are context-dependent chat/message views and are not a substitute for the global settings destination.

The overlay `FamilySettingsActivity` sends an explicit intent to `org.telegram.ui.LaunchActivity` with `family_screen` equal to one of the three values. The parent integration must consume this allowlist **after** access/passcode checks and navigation initialization, remove the extra, and present the actual fragment. It must not construct an arbitrary class from an intent string or let upstream plugin intent handlers intercept these internal settings requests.

Keep the existing `OpenExteraPreferences` entry so all upstream additional settings remain reachable. Family connectivity remains an additional settings row. Do not redesign chat cells, composer, app bar or the main tabs to incorporate family administration.
