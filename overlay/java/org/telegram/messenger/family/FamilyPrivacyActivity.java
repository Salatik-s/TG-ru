package org.telegram.messenger.family;

import android.os.Bundle;
import android.widget.Switch;
import android.widget.Toast;
import java.util.LinkedHashMap;
import java.util.Map;
import org.telegram.messenger.R;

/** Plain-language controls over the fork's real packet filters; no second settings store. */
public final class FamilyPrivacyActivity extends FamilyUi {
    private final Map<String, Switch> controls = new LinkedHashMap<>();
    private Switch master;
    private boolean updating;
    private static final String[] FLAGS = {"sendOnlinePackets", "sendReadMessagePackets", "sendUploadProgress", "sendReadStoriesPackets", "sendOfflinePacketAfterOnline"};

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!FamilyAccess.isAllowed()) { FamilyBinaryGuard.redirect(this); finish(); return; }
        setTitle(R.string.fg_ghost_settings_title); settingsNavigation(R.string.fg_ghost_settings_title);
        paragraph(R.string.fg_privacy_intro);
        master = toggle(R.string.fg_ghost_master, R.string.fg_ghost_master_summary);
        master.setOnCheckedChangeListener((v, checked) -> {
            if (updating) return;
            boolean success = true;
            for (String flag : FLAGS) success &= setFlag(flag, flag.equals("sendOfflinePacketAfterOnline") ? checked : !checked);
            if (!success) error();
            update();
        });
        item(FLAGS[0], R.string.fg_hide_online, R.string.fg_hide_online_summary);
        item(FLAGS[1], R.string.fg_hide_read, R.string.fg_hide_read_summary);
        item(FLAGS[2], R.string.fg_hide_typing, R.string.fg_hide_typing_summary);
        item(FLAGS[3], R.string.fg_hide_stories, R.string.fg_hide_stories_summary);
        item(FLAGS[4], R.string.fg_offline_after, R.string.fg_offline_after_summary);
        section(R.string.fg_deleted_heading, R.string.fg_deleted_description);
        Switch deleted = new Switch(this);
        deleted.setText(R.string.fg_save_deleted_switch); deleted.setTextSize(16); deleted.setTextColor(ink); deleted.setMinHeight(dp(56));
        deleted.setChecked(FamilyFeatures.saveDeletedEnabled());
        deleted.setOnCheckedChangeListener((v, checked) -> {
            if (updating) return;
            if (!FamilyFeatures.setSaveDeleted(checked)) { updating = true; v.setChecked(!checked); updating = false; error(); }
        });
        add(deleted, 8); paragraph(R.string.fg_save_deleted_limit); paragraph(R.string.fg_deleted_where);
        paragraph(R.string.fg_privacy_limit); update();
    }
    private Switch toggle(int title, int summary) {
        Switch view = new Switch(this); view.setText(title); view.setTextColor(ink); view.setTextSize(16);
        view.setMinHeight(dp(56)); view.setPadding(0, dp(8), 0, dp(8)); add(view, 20);
        android.widget.TextView description = text(summary, 14, muted); add(description, 4);
        return view;
    }
    private void item(String flag, int title, int description) {
        Switch view = toggle(title, description); controls.put(flag, view);
        view.setOnCheckedChangeListener((v, checked) -> {
            if (updating) return;
            if (!setFlag(flag, flag.equals("sendOfflinePacketAfterOnline") ? checked : !checked)) error();
            update();
        });
    }
    private void update() {
        updating = true; boolean all = true;
        for (String flag : FLAGS) {
            Boolean value = flag(flag);
            boolean hidden = value != null && (flag.equals("sendOfflinePacketAfterOnline") ? value : !value);
            Switch control = controls.get(flag); control.setEnabled(value != null); control.setChecked(hidden); all &= hidden;
        }
        master.setChecked(all); updating = false;
    }
    public static Boolean flag(String name) {
        try { Object item = Class.forName("tw.nekomimi.nekogram.NekoConfig").getField(name).get(null); return (Boolean)item.getClass().getMethod("Bool").invoke(item); }
        catch (Exception failure) { return null; }
    }
    public static boolean setFlag(String name, boolean value) {
        try { Object item = Class.forName("tw.nekomimi.nekogram.NekoConfig").getField(name).get(null); item.getClass().getMethod("setConfigBool", boolean.class).invoke(item, value); return true; }
        catch (Exception failure) { return false; }
    }
    private void error() { Toast.makeText(this, R.string.fg_privacy_error, Toast.LENGTH_LONG).show(); }
}
