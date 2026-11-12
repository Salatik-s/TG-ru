package org.telegram.messenger.family;

import android.content.Intent;
import android.os.Bundle;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.telegram.messenger.R;

/** Mandatory device gate. Telegram routing must also enforce FamilyAccess.isAllowed(). */
public final class FamilyGateActivity extends FamilyUi implements FamilyAccess.Listener {
    private TextView title, explanation, feedback, cooldown, fingerprint, fingerprintLabel;
    private EditText name, invitation;
    private Button primaryAction, refresh, copyFingerprint;
    private View invitationLabel;
    private LinearLayout enrollment, connectionPanel;
    private TextView connectionTitle, connectionMessage;
    private ImageView connectionIcon;
    private ProgressBar connectionProgress;
    private Button retryConnection, networkSettings;
    private ConnectivityManager network;
    private boolean watchingNetwork;
    private boolean navigating;
    private boolean autoConnectAttempted;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable reconnectOnNetworkChange = () -> {
        FamilyAccess.Snapshot state = FamilyAccess.state();
        if (!watchingNetwork || connectionPanel.getVisibility() != View.VISIBLE || state.busy) return;
        renderConnection(state);
        if (!hasNetwork() || state.retrySeconds() > 0) return;
        retryConnection();
    };
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network changed) { networkChanged(); }
        @Override public void onLost(Network changed) { networkChanged(); }
        private void networkChanged() {
            handler.removeCallbacks(reconnectOnNetworkChange);
            handler.postDelayed(reconnectOnNetworkChange, 350);
        }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() { updateCooldown(FamilyAccess.state()); handler.postDelayed(this, 1000); }
    };
    private final Runnable checkApproval = new Runnable() {
        @Override public void run() {
            FamilyAccess.Snapshot state = FamilyAccess.state();
            if (!state.busy && state.status.equals("pending")) FamilyAccess.refresh();
            handler.postDelayed(this, 60_000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.fg_join_title);
        network = getSystemService(ConnectivityManager.class);
        LinearLayout page = content;
        enrollment = new LinearLayout(this);
        enrollment.setOrientation(LinearLayout.VERTICAL);
        page.addView(enrollment, new LinearLayout.LayoutParams(-1, -2));
        content = enrollment;
        title = heading(R.string.fg_join_heading);
        explanation = paragraph(R.string.fg_live_join_description);
        name = input(R.string.fg_device_name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, false, 80);
        name.setText(FamilyAccess.deviceName());
        invitation = input(R.string.fg_invitation_code, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, true, 64);
        invitationLabel = content.getChildAt(content.indexOfChild(invitation) - 1);
        invitation.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        invitation.setOnEditorActionListener((v, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { primaryAction.performClick(); return true; }
            return false;
        });
        primaryAction = button(R.string.fg_request_access, true);
        primaryAction.setOnClickListener(v -> act());
        refresh = button(R.string.fg_check_status, false);
        refresh.setOnClickListener(v -> FamilyAccess.refresh());
        feedback = text(R.string.fg_network_error, 14, error);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setVisibility(View.GONE);
        add(feedback, 16);
        cooldown = text(R.string.fg_too_many_attempts, 14, muted);
        add(cooldown, 8);
        fingerprintLabel = text(R.string.fg_device_fingerprint, 14, muted);
        add(fingerprintLabel, 24);
        fingerprint = text(R.string.fg_fingerprint_pending, 14, ink);
        fingerprint.setTypeface(android.graphics.Typeface.MONOSPACE);
        fingerprint.setTextIsSelectable(true);
        add(fingerprint, 8);
        copyFingerprint = button(R.string.fg_copy_fingerprint, false);
        copyFingerprint.setOnClickListener(v -> copyDeviceFingerprint());
        content = page;
        createConnectionPanel();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::leaveGate);
        }
        render(FamilyAccess.state());
        if (!FamilyAccess.state().busy && !FamilyAccess.isAllowed() && hasNetwork()) FamilyAccess.refresh();
    }

    private void act() {
        FamilyAccess.Snapshot state = FamilyAccess.state();
        if (state.busy) return;
        if (!FamilyAccess.saveSetup(FamilyAccess.DEFAULT_SERVER, name.getText().toString())) {
            name.setError(getString(R.string.fg_setup_invalid));
            return;
        }
        FamilyAccess.submitInvite(invitation.getText().toString());
    }
    @Override protected void onStart() {
        super.onStart();
        FamilyAccess.addListener(this);
        handler.post(tick);
        handler.postDelayed(checkApproval, 60_000);
        if (network != null) {
            try { network.registerDefaultNetworkCallback(networkCallback); watchingNetwork = true; }
            catch (RuntimeException ignored) { }
        }
    }
    @Override protected void onStop() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(checkApproval);
        handler.removeCallbacks(reconnectOnNetworkChange);
        if (watchingNetwork) {
            watchingNetwork = false;
            network.unregisterNetworkCallback(networkCallback);
        }
        FamilyAccess.removeListener(this);
        super.onStop();
    }
    @Override public void onFamilyAccessChanged(FamilyAccess.Snapshot state) {
        if (!state.busy && (state.status.equals("pending") || state.status.equals("approved"))) invitation.setText("");
        render(state);
        if (!state.busy && state.status.equals("approved") && state.error.isEmpty()
                && !FamilyAccess.isAllowed() && !autoConnectAttempted) {
            autoConnectAttempted = true;
            FamilyAccess.connect();
            return;
        }
        if (state.connected && FamilyAccess.isAllowed() && !navigating) {
            navigating = true;
            Intent launch = new Intent();
            launch.setClassName(getPackageName(), "org.telegram.ui.LaunchActivity");
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(launch);
            finish();
        }
    }
    private void render(FamilyAccess.Snapshot state) {
        if (title == null) return;
        boolean showConnection = state.status.equals("approved") || (state.status.equals("unknown")
                && (FamilyAccess.previouslyApproved() || !hasNetwork()
                || state.error.equals("network_error") || state.error.equals("server_unavailable")));
        enrollment.setVisibility(showConnection ? View.GONE : View.VISIBLE);
        connectionPanel.setVisibility(showConnection ? View.VISIBLE : View.GONE);
        if (showConnection) {
            renderConnection(state);
            return;
        }
        title.setText(statusTitle(state));
        boolean form = state.status.equals("new") || state.status.equals("unknown");
        boolean restricted = state.status.equals("blocked") || state.status.equals("revoked");
        explanation.setText(form ? R.string.fg_live_join_description
                : restricted ? R.string.fg_contact_access_owner : R.string.fg_waiting_description);
        invitation.setVisibility(form ? View.VISIBLE : View.GONE);
        invitationLabel.setVisibility(form ? View.VISIBLE : View.GONE);
        name.setEnabled(form && !state.busy);
        invitation.setEnabled(!state.busy);
        primaryAction.setVisibility(form ? View.VISIBLE : View.GONE);
        primaryAction.setText(state.busy ? R.string.fg_live_working : R.string.fg_request_access);
        enabled(primaryAction, !state.busy && state.retrySeconds() == 0);
        enabled(refresh, !state.busy);
        feedback.setVisibility(state.error.isEmpty() ? View.GONE : View.VISIBLE);
        if (!state.error.isEmpty()) feedback.setText(errorText(state.error));
        boolean showFingerprint = state.status.equals("pending") || restricted;
        String code = FamilyAccess.deviceFingerprint();
        fingerprintLabel.setVisibility(showFingerprint ? View.VISIBLE : View.GONE);
        fingerprint.setVisibility(showFingerprint ? View.VISIBLE : View.GONE);
        copyFingerprint.setVisibility(showFingerprint && !code.isEmpty() ? View.VISIBLE : View.GONE);
        fingerprint.setText(code.isEmpty() ? getString(R.string.fg_fingerprint_pending) : code);
        updateCooldown(state);
    }
    private void updateCooldown(FamilyAccess.Snapshot state) {
        long seconds = state.retrySeconds();
        if (connectionPanel.getVisibility() == View.VISIBLE) {
            updateRetry(state, seconds);
            return;
        }
        cooldown.setVisibility(seconds > 0 ? View.VISIBLE : View.GONE);
        if (seconds > 0) cooldown.setText(getString(R.string.fg_retry_countdown, seconds / 60, seconds % 60));
        enabled(primaryAction, !state.busy && seconds == 0);
    }
    private void createConnectionPanel() {
        LinearLayout page = content;
        connectionPanel = new LinearLayout(this);
        connectionPanel.setOrientation(LinearLayout.VERTICAL);
        connectionPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams panel = new LinearLayout.LayoutParams(-1, -2);
        panel.topMargin = dp(52);
        page.addView(connectionPanel, panel);
        content = connectionPanel;

        connectionIcon = new ImageView(this);
        int icon = getResources().getIdentifier("baseline_wifi_24", "drawable", getPackageName());
        if (icon == 0) icon = android.R.drawable.ic_dialog_info;
        connectionIcon.setImageResource(icon);
        connectionIcon.setColorFilter(primary);
        connectionIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        connectionIcon.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(container);
        connectionIcon.setBackground(circle);
        connectionIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        connectionPanel.addView(connectionIcon, new LinearLayout.LayoutParams(dp(64), dp(64)));

        connectionTitle = heading(R.string.fg_connection);
        connectionTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        connectionMessage = text(R.string.fg_network_error, 16, muted);
        add(connectionMessage, 8);
        connectionProgress = new ProgressBar(this);
        connectionProgress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(primary));
        connectionProgress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams progress = new LinearLayout.LayoutParams(dp(26), dp(26));
        progress.topMargin = dp(16);
        connectionPanel.addView(connectionProgress, progress);
        retryConnection = button(R.string.fg_reconnect_action, true);
        retryConnection.setOnClickListener(v -> retryConnection());
        networkSettings = button(R.string.fg_connection_details, false);
        networkSettings.setText("Настройки сети");
        networkSettings.setOnClickListener(v -> openNetworkSettings());
        content = page;
    }

    private void retryConnection() {
        if (FamilyAccess.state().status.equals("approved")) FamilyAccess.connect();
        else FamilyAccess.refresh();
    }

    private boolean hasNetwork() {
        if (network == null) return true;
        Network active = network.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities capabilities = network.getNetworkCapabilities(active);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void renderConnection(FamilyAccess.Snapshot state) {
        boolean offline = !hasNetwork();
        if (state.busy) {
            connectionTitle.setText("Проверяем соединение…");
            connectionMessage.setText("Подключаемся к tg.24projects.ru");
        } else if (offline || !state.error.isEmpty()) {
            connectionTitle.setText(offline ? "Нет интернета" : "Не удалось подключиться");
            connectionMessage.setText(offline || state.error.equals("network_error") || state.error.equals("connection_lost")
                    ? R.string.fg_network_error : errorText(state.error));
        } else {
            connectionTitle.setText("Подключение к TG-ru");
            connectionMessage.setText("Можно проверить связь с tg.24projects.ru и вернуться к чатам.");
        }
        connectionIcon.setVisibility(state.busy ? View.INVISIBLE : View.VISIBLE);
        connectionProgress.setVisibility(state.busy ? View.VISIBLE : View.GONE);
        updateRetry(state, state.retrySeconds());
        networkSettings.setVisibility(offline && !state.busy ? View.VISIBLE : View.GONE);
    }

    private void updateRetry(FamilyAccess.Snapshot state, long seconds) {
        retryConnection.setText(state.busy ? "Проверяем…" : seconds > 0
                ? getString(R.string.fg_retry_countdown, seconds / 60, seconds % 60) : "Проверить соединение");
        enabled(retryConnection, !state.busy && seconds == 0);
    }

    private void openNetworkSettings() {
        String action = android.os.Build.VERSION.SDK_INT >= 29
                ? Settings.Panel.ACTION_INTERNET_CONNECTIVITY : Settings.ACTION_WIRELESS_SETTINGS;
        try { startActivity(new Intent(action)); }
        catch (android.content.ActivityNotFoundException ignored) {
            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        }
    }

    private void leaveGate() { moveTaskToBack(true); }
    @SuppressWarnings("deprecation") @Override public void onBackPressed() { leaveGate(); }
}
