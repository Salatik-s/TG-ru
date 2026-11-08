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
public final class FamilyPrivacyActivity extends FamilyUi {
    private final Map<String, Switch> controls = new LinkedHashMap<>();
    private Switch master;
    private Switch deleted;
    private boolean updating;
    private int pageColor, rowColor, dividerColor, rippleColor, trackOff, trackOn, thumbOff, thumbOn;
    private static final String[] FLAGS = {"sendOnlinePackets", "sendReadMessagePackets", "sendUploadProgress", "sendReadStoriesPackets", "sendOfflinePacketAfterOnline"};

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!FamilyAccess.isAllowed()) { FamilyBinaryGuard.redirect(this); finish(); return; }
        setTitle(R.string.fg_ghost_settings_title);
        createSettingsLayout();
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

    /** This screen owns its edge-to-edge layout; other FamilyUi screens are unchanged. */
    private void createSettingsLayout() {
        boolean dark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        try { dark = (Boolean) Class.forName("org.telegram.ui.ActionBar.Theme").getMethod("isCurrentThemeDark").invoke(null); }
        catch (Exception ignored) { }
        rowColor = themeColor("key_windowBackgroundWhite", dark ? 0xFF1F2933 : Color.WHITE);
        pageColor = themeColor("key_windowBackgroundGray", dark ? 0xFF151E27 : 0xFFF0F2F5);
        ink = themeColor("key_windowBackgroundWhiteBlackText", dark ? 0xFFF1F3F5 : 0xFF222222);
        muted = themeColor("key_windowBackgroundWhiteGrayText", dark ? 0xFFADB8C3 : 0xFF626D78);
        muted = readableColor(muted, ink, 4.5, rowColor, pageColor);
        primary = themeColor("key_windowBackgroundWhiteBlueHeader", dark ? 0xFF80BCF2 : 0xFF2874A6);
        dividerColor = themeColor("key_divider", dark ? 0xFF303C47 : 0xFFE7E9EC);
        rippleColor = themeColor("key_listSelector", dark ? 0x26FFFFFF : 0x12000000);
        trackOff = themeColor("key_switchTrack", dark ? 0xFF667481 : 0xFFADB7C0);
        trackOn = themeColor("key_switchTrackChecked", dark ? 0xFF68A8DF : 0xFF4C9BD5);
        thumbOff = themeColor("key_switchTrackBlueThumb", Color.WHITE);
        thumbOn = themeColor("key_switchTrackBlueThumbChecked", Color.WHITE);
        // Some Telegram themes use a dark thumb intended for a different switch background.
        // Keep their colors when legible; otherwise restore a neutral, clearly enabled OFF state.
        trackOff = over(trackOff, rowColor);
        thumbOff = over(thumbOff, trackOff);
        if (contrast(thumbOff, trackOff) < 3.1
                || Math.max(contrast(thumbOff, rowColor), contrast(trackOff, rowColor)) < 3.1) {
            boolean darkRow = Color.luminance(rowColor) < .5;
            thumbOff = darkRow ? 0xFFBFC6CD : Color.WHITE;
            trackOff = readableColor(trackOff, darkRow ? rowColor : Color.BLACK, 3.1, thumbOff);
            // A white thumb on a light row is outlined by its contrasting track.
            if (Math.max(contrast(thumbOff, rowColor), contrast(trackOff, rowColor)) < 3.1)
                trackOff = readableColor(trackOff, Color.BLACK, 3.1, thumbOff, rowColor);
        }
        int barColor = themeColor("key_actionBarDefault", dark ? 0xFF202B36 : 0xFF527DA3);
        int barTitle = themeColor("key_actionBarDefaultTitle", Color.WHITE);
        int barIcon = themeColor("key_actionBarDefaultIcon", Color.WHITE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(pageColor);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL); header.setBackgroundColor(barColor);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.fg_ic_back); back.setColorFilter(barIcon);
        back.setContentDescription(getString(R.string.fg_back));
        back.setBackground(new RippleDrawable(ColorStateList.valueOf(themeColor("key_actionBarDefaultSelector", 0x22FFFFFF)), null, new ColorDrawable(Color.WHITE)));
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView title = new TextView(this);
        title.setText(R.string.fg_ghost_settings_title); title.setTextColor(barTitle); title.setTextSize(20);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, 0, dp(16), 0);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -1, 1f));
        header.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setClipToPadding(false);
        scroll.setBackgroundColor(pageColor);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(12));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        getWindow().setStatusBarColor(barColor); getWindow().setNavigationBarColor(pageColor);
        int systemUi = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Color.luminance(barColor) > .5f) systemUi |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26 && Color.luminance(pageColor) > .5f) systemUi |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(systemUi);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left, 0, right, 0);
            header.setPadding(0, top, 0, 0); scroll.setPadding(0, 0, 0, bottom);
            return insets;
        });
        setContentView(root); root.requestApplyInsets();
    }

    private Switch toggle(int title, int summary) {
        ToggleRow row = new ToggleRow(title, summary);
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row.control;
    }
    private final class ToggleRow extends LinearLayout {
        final Switch control;
        private final LinearLayout labels;
        ToggleRow(int titleResource, int summaryResource) {
            super(FamilyPrivacyActivity.this);
            setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(72)); setPadding(dp(16), dp(10), dp(12), dp(10));
            setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), new ColorDrawable(rowColor), null));
            labels = new LinearLayout(FamilyPrivacyActivity.this); labels.setOrientation(VERTICAL);
            TextView title = new TextView(FamilyPrivacyActivity.this);
            title.setText(titleResource); title.setTextColor(ink); title.setTextSize(16);
            title.setGravity(Gravity.START); title.setIncludeFontPadding(false);
            String summary = getString(summaryResource);
            if (summaryResource == R.string.fg_ghost_master_summary) {
                int sentence = summary.indexOf('.');
                if (sentence >= 0) summary = summary.substring(0, sentence + 1);
            }
            TextView detail = new TextView(FamilyPrivacyActivity.this);
            detail.setText(summary); detail.setTextColor(muted); detail.setTextSize(13);
            detail.setGravity(Gravity.START); detail.setIncludeFontPadding(false);
            labels.addView(title, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
            detailParams.topMargin = dp(4); labels.addView(detail, detailParams);
            addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
            control = new Switch(FamilyPrivacyActivity.this);
            control.setShowText(false); control.setSwitchMinWidth(dp(36)); control.setMinimumHeight(dp(48));
            control.setGravity(Gravity.CENTER); control.setSaveEnabled(false);
            control.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{thumbOn, thumbOff}));
            control.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{trackOn, trackOff}));
            LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(dp(56), dp(48));
            switchParams.setMarginStart(dp(12)); addView(control, switchParams);
            // One accessible switch row; the full row and the physical switch both toggle the same preference.
            labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            control.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); control.setFocusable(false);
            setContentDescription(getString(titleResource) + ". " + summary);
            setClickable(true); setFocusable(true);
            setOnClickListener(v -> { if (control.isEnabled()) control.performClick(); });
            setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Switch.class.getName()); info.setCheckable(true);
                    info.setChecked(control.isChecked()); info.setEnabled(control.isEnabled());
                }
            });
        }
        void refresh() {
            setEnabled(control.isEnabled()); labels.setAlpha(control.isEnabled() ? 1f : .5f);
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        }
    }
    private void groupSpace() {
        View gap = new View(this); content.addView(gap, new LinearLayout.LayoutParams(-1, dp(12)));
    }
    private void groupHeading(int resource) {
        TextView heading = new TextView(this);
        heading.setText(resource); heading.setTextColor(primary); heading.setTextSize(14);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setPadding(dp(16), dp(16), dp(16), dp(8)); heading.setBackgroundColor(rowColor);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        content.addView(heading, new LinearLayout.LayoutParams(-1, -2));
    }
    private void divider() {
        LinearLayout strip = new LinearLayout(this); strip.setBackgroundColor(rowColor);
        strip.setPaddingRelative(dp(16), 0, 0, 0);
        View line = new View(this); line.setBackgroundColor(dividerColor);
        strip.addView(line, new LinearLayout.LayoutParams(-1, 1));
        content.addView(strip, new LinearLayout.LayoutParams(-1, 1));
    }
    private void note(String value) {
        TextView note = new TextView(this); note.setText(value); note.setTextColor(muted); note.setTextSize(13);
        note.setPadding(dp(16), dp(10), dp(16), dp(16)); note.setLineSpacing(dp(2), 1f);
        content.addView(note, new LinearLayout.LayoutParams(-1, -2));
    }
    private void refreshRow(Switch control) {
        if (control.getParent() instanceof ToggleRow) ((ToggleRow)control.getParent()).refresh();
    }
    private static int themeColor(String key, int fallback) {
        try {
            Class<?> theme = Class.forName("org.telegram.ui.ActionBar.Theme");
            return (Integer)theme.getMethod("getColor", int.class).invoke(null, theme.getField(key).getInt(null));
        } catch (Exception ignored) { return fallback; }
    }
    private static int over(int foreground, int background) {
        float alpha = Color.alpha(foreground) / 255f;
        return Color.rgb(Math.round(Color.red(foreground) * alpha + Color.red(background) * (1f - alpha)),
                Math.round(Color.green(foreground) * alpha + Color.green(background) * (1f - alpha)),
                Math.round(Color.blue(foreground) * alpha + Color.blue(background) * (1f - alpha)));
    }
    private static double contrast(int foreground, int background) {
        double a = Color.luminance(over(foreground, background)), b = Color.luminance(background);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }
    private static double minimumContrast(int color, int... backgrounds) {
        double minimum = Double.MAX_VALUE;
        for (int background : backgrounds) minimum = Math.min(minimum, contrast(color, background));
        return minimum;
    }
    /** Smallest one-percent blend toward the theme's text color that meets both surfaces. */
    private static int readableColor(int color, int toward, double minimum, int... backgrounds) {
        if (minimumContrast(color, backgrounds) >= minimum) return color;
        if (minimumContrast(toward, backgrounds) < minimum)
            toward = minimumContrast(Color.WHITE, backgrounds) > minimumContrast(Color.BLACK, backgrounds) ? Color.WHITE : Color.BLACK;
        color = over(color, backgrounds[0]); toward = over(toward, backgrounds[0]);
        for (int step = 1; step <= 100; step++) {
            float amount = step / 100f;
            int candidate = Color.rgb(Math.round(Color.red(color) + (Color.red(toward) - Color.red(color)) * amount),
                    Math.round(Color.green(color) + (Color.green(toward) - Color.green(color)) * amount),
                    Math.round(Color.blue(color) + (Color.blue(toward) - Color.blue(color)) * amount));
            if (minimumContrast(candidate, backgrounds) >= minimum) return candidate;
        }
        return toward;
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
