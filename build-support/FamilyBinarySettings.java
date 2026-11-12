package org.telegram.messenger.family;

import android.app.Activity;
import android.content.Intent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.lang.ref.WeakReference;
import org.telegram.messenger.R;

/** Keep ordinary Telegram settings intact; optional technical settings are last. */
public final class FamilyBinarySettings {
    public static final int ROW_ID = -44001, PRIVACY_ID = -44002, PLUGINS_ID = -44003, DEVICES_ID = -44004;
    private static final int CONTACT_ID = -44005, CONTACT_EDIT_ID = -44006;
    private static final int BACKGROUND_ID = -44007, UPDATE_ID = -44008, HEADER_ID = -44009;
    private static final int CONNECTION_ID = -44010;

    private FamilyBinarySettings() { }

    public static void addRow(Object fragment, ArrayList<Object> items) {
        try {
            Activity activity = (Activity)fragment.getClass().getMethod("getParentActivity").invoke(fragment);
            if (activity == null) throw new IllegalStateException("Settings fragment has no Activity");
            java.lang.reflect.Field search = fragment.getClass().getDeclaredField("searchItem"); search.setAccessible(true);
            Object searchItem = search.get(fragment);
            if (searchItem != null && (Boolean)searchItem.getClass().getMethod("isSearchFieldVisible2").invoke(searchItem)) return;
            int position = 0;
            for (int i = 0; i < items.size(); i++) {
                int id = items.get(i).getClass().getField("id").getInt(items.get(i));
                if (id == ROW_ID) return;
                if (id == -1) position = i;
            }
            for (int i = items.size() - 1; i >= 0; i--) {
                int id = items.get(i).getClass().getField("id").getInt(items.get(i));
                if (id == -1 || id == 100) items.remove(i);
            }
            position = Math.min(position, items.size());
            items.add(position++, row(activity, CONNECTION_ID, "msg_proxy", R.string.fg_connection, R.string.fg_route_settings_summary));
            items.add(position++, row(activity, HEADER_ID, "msg_theme", "Заголовок чатов",
                    "Выбрать текст в шапке"));
            items.add(position++, row(activity, PRIVACY_ID, "ayu_ghost", R.string.fg_ghost_settings_title, R.string.fg_privacy_summary));
            items.add(position++, row(activity, PLUGINS_ID, "msg_plugins", R.string.fg_plugins, R.string.fg_plugins_simple_summary));
            items.add(position++, row(activity, CONTACT_ID, "msg_discussion", "Связаться с разработчиком",
                    "@" + FamilyDeveloperContact.username(activity)));
            if (FamilyAccess.isPhoneOwner()) {
                items.add(position++, row(activity, CONTACT_EDIT_ID, "msg_edit", "Изменить контакт разработчика",
                        "Ссылка в настройках всех пользователей TG-ru"));
                items.add(position, row(activity, DEVICES_ID, "settings_devices", R.string.fg_owner_devices, R.string.fg_owner_summary));
            }
            int end = items.size();
            if (end > 0 && items.get(end - 1).getClass().getField("view").get(items.get(end - 1)) instanceof android.widget.TextView) end--;
            items.add(end++, row(activity, BACKGROUND_ID, "settings_notifications", "Уведомления в фоне",
                    "Разрешения и работа при выключенном экране"));
            items.add(end++, row(activity, UPDATE_ID, "msg_download",
                    FamilyAppUpdate.hasUpdate(activity) ? "Обновить приложение" : "Проверить обновления",
                    "Установка по вашему выбору"));
            items.add(end, row(activity, ROW_ID, "msg_settings", R.string.fg_system_title, R.string.fg_system_summary));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Pinned family settings adapter is incompatible", e);
        }
    }

    private static Object row(Activity activity, int id, String drawable, int title, int subtitle) throws ReflectiveOperationException {
        return row(activity, id, drawable, activity.getString(title), activity.getString(subtitle));
    }

    private static Object row(Activity activity, int id, String drawable, CharSequence title, CharSequence subtitle)
            throws ReflectiveOperationException {
        Class<?> factory = Class.forName("org.telegram.ui.SettingsActivity$SettingCell$Factory");
        Method of = factory.getMethod("of", int.class, int.class, int.class, int.class, CharSequence.class, CharSequence.class);
        int icon = activity.getResources().getIdentifier(drawable, "drawable", activity.getPackageName());
        if (icon == 0) icon = android.R.drawable.ic_menu_manage;
        return of.invoke(null, id, 0xff006397, 0xff006397, icon, title, subtitle);
    }

    public static void onSettingsResumed(Object fragment) {
        try {
            Activity activity = (Activity) fragment.getClass().getMethod("getParentActivity").invoke(fragment);
            if (activity == null) return;
            boolean updateWasAvailable = FamilyAppUpdate.hasUpdate(activity);
            WeakReference<Object> settings = new WeakReference<>(fragment);
            FamilyAppUpdate.check(activity, () -> {
                Object live = settings.get();
                if (live == null || activity.isDestroyed() || updateWasAvailable == FamilyAppUpdate.hasUpdate(activity)) return;
                try {
                    java.lang.reflect.Field listField = live.getClass().getDeclaredField("listView");
                    listField.setAccessible(true);
                    Object list = listField.get(live);
                    if (list == null) return;
                    Object adapter = list.getClass().getField("adapter").get(list);
                    if (adapter != null) adapter.getClass().getMethod("update", boolean.class).invoke(adapter, false);
                } catch (ReflectiveOperationException ignored) { }
            });
        } catch (ReflectiveOperationException ignored) { }
    }

    public static boolean onClick(Object fragment, Object item) {
        try {
            if (item == null) return false;
            int id = item.getClass().getField("id").getInt(item);
            Class<?> destination;
            if (id == ROW_ID || id == CONNECTION_ID) destination = FamilySettingsActivity.class;
            else if (id == PRIVACY_ID) destination = FamilyPrivacyActivity.class;
            else if (id == PLUGINS_ID) destination = FamilyPluginsActivity.class;
            else if (id == DEVICES_ID) destination = FamilyDevicesActivity.class;
            else if (id == CONTACT_ID || id == CONTACT_EDIT_ID || id == BACKGROUND_ID || id == UPDATE_ID || id == HEADER_ID) destination = null;
            else return false;
            Activity activity = (Activity)fragment.getClass().getMethod("getParentActivity").invoke(fragment);
            if (activity == null) return true;
            if (id == CONNECTION_ID) {
                activity.startActivity(new Intent(activity, FamilySettingsActivity.class)
                        .putExtra(FamilySettingsActivity.EXTRA_CONNECTION, true));
                return true;
            }
            if (!FamilyAccess.isAllowed()) { FamilyBinaryGuard.redirect(activity); return true; }
            if (id == CONTACT_ID) FamilyDeveloperContact.open(activity);
            else if (id == CONTACT_EDIT_ID) {
                if (FamilyAccess.isPhoneOwner()) FamilyDeveloperContact.edit(activity);
            } else if (id == BACKGROUND_ID) FamilyNotificationSetup.open(activity);
            else if (id == UPDATE_ID) FamilyAppUpdate.open(activity);
            else if (id == HEADER_ID) FamilyChatHeader.open(fragment, null);
            else if (id != DEVICES_ID || FamilyAccess.isPhoneOwner()) activity.startActivity(new Intent(activity, destination));
            return true;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Pinned family settings action is incompatible", e);
        }
    }
}
