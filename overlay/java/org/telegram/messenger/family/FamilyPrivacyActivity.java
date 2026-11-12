package org.telegram.messenger.family;

import android.os.Bundle;
import android.os.Build;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.LinkedHashMap;
import java.util.Map;
import org.telegram.messenger.R;

/** Plain-language controls over the fork's real packet filters; no second settings store. */
public final class FamilyPrivacyActivity extends FamilySettingsPage {
    private final Map<String, Switch> controls = new LinkedHashMap<>();
    private Switch master;
    private Switch deleted;
    private boolean updating;
    private static final String[] FLAGS = {"sendOnlinePackets", "sendReadMessagePackets", "sendUploadProgress", "sendReadStoriesPackets", "sendOfflinePacketAfterOnline"};

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!FamilyAccess.isAllowed()) { FamilyBinaryGuard.redirect(this); finish(); return; }
        setTitle(R.string.fg_ghost_settings_title);
        createSettingsLayout(R.string.fg_ghost_settings_title);
        master = toggle(R.string.fg_ghost_master, R.string.fg_ghost_master_summary);
        master.setOnCheckedChangeListener((v, checked) -> {
            if (updating) return;
            boolean success = true;
            for (String flag : FLAGS) success &= setFlag(flag, flag.equals("sendOfflinePacketAfterOnline") ? checked : !checked);
            if (!success) error();
            update();
        });
        groupSpace();
        item(FLAGS[0], R.string.fg_hide_online, R.string.fg_hide_online_summary);
        divider();
        item(FLAGS[1], R.string.fg_hide_read, R.string.fg_hide_read_summary);
        divider();
        item(FLAGS[2], R.string.fg_hide_typing, R.string.fg_hide_typing_summary);
        divider();
        item(FLAGS[3], R.string.fg_hide_stories, R.string.fg_hide_stories_summary);
        divider();
        item(FLAGS[4], R.string.fg_offline_after, R.string.fg_offline_after_summary);
        note(getString(R.string.fg_privacy_limit));
        groupHeading(R.string.fg_deleted_heading);
        deleted = toggle(R.string.fg_save_deleted_switch, R.string.fg_deleted_description);
        deleted.setChecked(FamilyFeatures.saveDeletedEnabled());
        deleted.setOnCheckedChangeListener((v, checked) -> {
            if (updating) return;
            if (!FamilyFeatures.setSaveDeleted(checked)) { updating = true; v.setChecked(!checked); updating = false; error(); }
            refreshRow(deleted);
        });
        note(getString(R.string.fg_save_deleted_limit) + "\n\n" + getString(R.string.fg_deleted_where));
        update();
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
            refreshRow(control);
        }
        master.setChecked(all); refreshRow(master); updating = false;
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
