package org.telegram.messenger.family;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;

public final class FamilyChatHeader {
    private static final int DEFAULT = 1;
    private static final int PROFILE = 2;
    private static final String TITLE = "Заголовок чатов";
    private static final String[] OPTIONS = {"TG-ru", "Telegram", "Имя в профиле"};
    private static SharedPreferences preferences;

    private FamilyChatHeader() { }

    static void initialize(Context context) {
        preferences = context.getSharedPreferences("tgru_chat_header", Context.MODE_PRIVATE);
    }

    private static int selected() {
        int value = preferences == null ? DEFAULT : preferences.getInt("mode", DEFAULT);
        return value >= 0 && value < OPTIONS.length ? value : DEFAULT;
    }

    public static SpannableStringBuilder title(int account) {
        if (FamilyAccess.previouslyApproved() && !FamilyConnectionMonitor.hasNetwork()) {
            return new SpannableStringBuilder("Ожидание сети…");
        }
        int mode = selected();
        String text = OPTIONS[mode];
        if (mode == PROFILE) text = profileName(account);
        return new SpannableStringBuilder(text);
    }

    private static String profileName(int account) {
        try {
            Class<?> users = Class.forName("org.telegram.messenger.UserConfig");
            Object config = users.getMethod("getInstance", int.class).invoke(null, account);
            Object user = users.getMethod("getCurrentUser").invoke(config);
            Class<?> type = Class.forName("org.telegram.tgnet.TLRPC$User");
            Class<?> contacts = Class.forName("org.telegram.messenger.ContactsController");
            String name = (String) contacts.getMethod("formatName", type).invoke(null, user);
            if (!TextUtils.isEmpty(name)) return name;
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return OPTIONS[0];
    }

    public static Runnable titleAction(Object fragment, Runnable original) {
        return () -> {
            if (!FamilyFeatures.isMainChatList(fragment)) {
                original.run();
                return;
            }
            open(fragment, null);
        };
    }

    public static void open(Object fragment, Runnable onChanged) {
        try {
            Activity activity = (Activity) fragment.getClass().getMethod("getParentActivity").invoke(fragment);
            if (activity == null || activity.isFinishing() || !FamilyAccess.isAllowed()) return;
            if (preferences == null) initialize(activity.getApplicationContext());
            AlertDialog dialog = new AlertDialog.Builder(activity, FamilyNotificationSetup.dialogTheme(activity))
                    .setTitle(TITLE)
                    .setSingleChoiceItems(OPTIONS, selected(), (view, choice) -> {
                        preferences.edit().putInt("mode", choice).apply();
                        FamilyFeatures.refreshChatTitle();
                        if (onChanged != null) onChanged.run();
                        view.dismiss();
                    })
                    .setNegativeButton("Закрыть", null)
                    .create();
            fragment.getClass().getMethod("showDialog", android.app.Dialog.class).invoke(fragment, dialog);
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }
}
