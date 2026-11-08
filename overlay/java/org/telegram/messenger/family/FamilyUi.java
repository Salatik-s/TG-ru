package org.telegram.messenger.family;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.R;

/** Small native, scrollable surface; text and controls follow system font scaling. */
abstract class FamilyUi extends Activity {
    protected LinearLayout content;
    protected int ink, muted, primary, surface, container, error;

    @Override protected void onCreate(Bundle state) {
        setTheme(R.style.FamilyGramTheme);
        super.onCreate(state);
        FamilyAccess.init(this);
        ink = getColor(R.color.fg_on_surface);
        muted = getColor(R.color.fg_on_surface_variant);
        primary = getColor(R.color.fg_primary);
        surface = getColor(R.color.fg_surface);
        container = getColor(R.color.fg_surface_container);
        error = getColor(R.color.fg_error);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        // The invitation can remain visible while entering it; suppress app-switcher screenshots.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(surface);
        root.setFitsSystemWindows(true);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout frame = new LinearLayout(this);
        frame.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        frame.setOrientation(LinearLayout.VERTICAL);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(24), dp(24), dp(32));
        int width = getResources().getConfiguration().screenWidthDp;
        frame.addView(content, new LinearLayout.LayoutParams(width >= 600 ? dp(600) : -1, -2));
        scroll.addView(frame, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
            root.requestApplyInsets();
        }
    }

    protected int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    protected TextView text(int resource, int size, int color) {
        TextView view = new TextView(this);
        view.setText(resource);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(3), 1f);
        view.setIncludeFontPadding(true);
        return view;
    }
    protected TextView heading(int resource) {
        TextView view = text(resource, 24, ink);
        if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
        add(view, 0);
        return view;
    }
    protected void settingsNavigation(int resource) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.fg_ic_back);
        back.setContentDescription(getString(R.string.fg_back));
        back.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22006397), null,
                new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
        back.setOnClickListener(v -> finish());
        row.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text(resource, 22, ink);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.setMarginStart(dp(8));
        row.addView(title, titleParams);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        add(row, 0);
    }
    protected TextView paragraph(int resource) {
        TextView view = text(resource, 16, muted);
        add(view, 12);
        return view;
    }
    protected void add(View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        content.addView(view, params);
    }
    protected Button button(int resource, boolean filled) {
        Button view = new Button(this);
        view.setText(resource);
        view.setAllCaps(false);
        view.setTextSize(16);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setMinHeight(dp(52));
        view.setMinimumHeight(dp(52));
        view.setPadding(dp(24), dp(12), dp(24), dp(12));
        view.setGravity(Gravity.CENTER);
        int enabledInk = filled ? getColor(R.color.fg_on_primary) : primary;
        view.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{muted, enabledInk}));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(filled ? primary : surface);
        shape.setCornerRadius(dp(26));
        if (!filled) shape.setStroke(dp(1), getColor(R.color.fg_outline));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(filled ? 0x33FFFFFF : 0x22006397), shape, null));
        add(view, 12);
        return view;
    }
    protected void enabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .55f);
    }
    protected EditText input(int label, int inputType, boolean secret, int maxLength) {
        TextView caption = text(label, 14, muted);
        add(caption, 20);
        EditText input = new EditText(this);
        input.setId(View.generateViewId());
        caption.setLabelFor(input.getId());
        input.setTextSize(16);
        input.setTextColor(ink);
        input.setHintTextColor(muted);
        input.setInputType(inputType);
        input.setSingleLine(true);
        input.setMinimumHeight(dp(56));
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maxLength)});
        GradientDrawable background = fieldBackground(false);
        GradientDrawable focused = fieldBackground(true);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, background);
        input.setBackground(states);
        input.setSaveEnabled(!secret);
        if (Build.VERSION.SDK_INT >= 26 && secret) input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        add(input, 8);
        return input;
    }
    private GradientDrawable fieldBackground(boolean focused) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(12));
        background.setColor(container);
        background.setStroke(dp(focused ? 2 : 1), focused ? primary : getColor(R.color.fg_outline));
        return background;
    }
    protected TextView section(int title, int description) {
        TextView label = text(title, 16, ink);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
        add(label, 28);
        TextView detail = text(description, 14, muted);
        add(detail, 6);
        return detail;
    }
    protected View navigationRow(int label, int summary, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setMinimumHeight(dp(72));
        row.setPadding(dp(4), dp(14), dp(4), dp(14));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(label, 16, ink);
        TextView subtitle = text(summary, 14, muted);
        labels.addView(title);
        LinearLayout.LayoutParams secondary = new LinearLayout.LayoutParams(-1, -2);
        secondary.topMargin = dp(4);
        labels.addView(subtitle, secondary);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.fg_ic_chevron);
        LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        arrowParams.setMarginStart(dp(16));
        row.addView(arrow, arrowParams);
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.setContentDescription(getString(label) + ". " + getString(summary));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22006397), null,
                new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        row.setOnClickListener(listener);
        add(row, 0);
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.fg_outline_variant));
        content.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        return row;
    }
    protected void copyDeviceFingerprint() {
        String value = FamilyAccess.deviceFingerprint();
        if (value.isEmpty()) return;
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.fg_device_fingerprint), value));
        if (Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(this, R.string.fg_fingerprint_copied, android.widget.Toast.LENGTH_SHORT).show();
    }
    protected int statusTitle(FamilyAccess.Snapshot state) {
        if (state.connected && FamilyAccess.isAllowed()) return R.string.fg_live_connected;
        switch (state.status) {
            case "pending": return R.string.fg_waiting_title;
            case "approved": return R.string.fg_device_approved;
            case "blocked": return R.string.fg_device_blocked;
            case "revoked": return R.string.fg_access_revoked;
            default: return R.string.fg_join_heading;
        }
    }
    protected int errorText(String code) {
        switch (code) {
            case "invalid_invite": return R.string.fg_invite_not_valid;
            case "invalid_invite_format": return R.string.fg_invite_format_help;
            case "cooldown": case "rate_limited": case "too_many_challenges": return R.string.fg_too_many_attempts;
            case "blocked": return R.string.fg_contact_access_owner;
            case "revoked": return R.string.fg_access_revoked;
            case "already_enrolled": return R.string.fg_already_enrolled;
            case "invalid_proof": case "keystore_error": return R.string.fg_device_proof_failed;
            case "native_unavailable": return R.string.fg_native_missing;
            case "tunnel_failed": case "gateway_unavailable": return R.string.fg_tunnel_failed;
            case "invalid_gateway": case "invalid_response": case "server_redirect": return R.string.fg_invalid_service_response;
            case "connection_lost": return R.string.fg_connection_lost;
            case "not_approved": return R.string.fg_not_approved;
            case "expired_or_replayed": return R.string.fg_request_expired;
            default: return R.string.fg_network_error;
        }
    }
}
