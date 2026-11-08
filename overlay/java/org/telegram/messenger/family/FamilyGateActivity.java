package org.telegram.messenger.family;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.telegram.messenger.R;

/** Mandatory device gate. Telegram routing must also enforce FamilyAccess.isAllowed(). */
public final class FamilyGateActivity extends FamilyUi implements FamilyAccess.Listener {
    private TextView title, explanation, feedback, cooldown, fingerprint, fingerprintLabel;
    private EditText name, invitation;
    private Button primaryAction, refresh, copyFingerprint;
    private View invitationLabel;
    private boolean navigating;
    private boolean autoConnectAttempted;
    private final Handler handler = new Handler(Looper.getMainLooper());
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
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::leaveGate);
        }
        render(FamilyAccess.state());
        if (!FamilyAccess.state().busy && !FamilyAccess.isAllowed()) FamilyAccess.refresh();
    }

    private void act() {
        FamilyAccess.Snapshot state = FamilyAccess.state();
        if (state.busy) return;
        if (state.status.equals("approved")) { FamilyAccess.connect(); return; }
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
    }
    @Override protected void onStop() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(checkApproval);
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
        title.setText(statusTitle(state));
        boolean form = state.status.equals("new") || state.status.equals("unknown");
        boolean approved = state.status.equals("approved");
        boolean restricted = state.status.equals("blocked") || state.status.equals("revoked");
        explanation.setText(form ? R.string.fg_live_join_description : approved ? R.string.fg_live_connect_description
                : restricted ? R.string.fg_contact_access_owner : R.string.fg_waiting_description);
        invitation.setVisibility(form ? View.VISIBLE : View.GONE);
        invitationLabel.setVisibility(form ? View.VISIBLE : View.GONE);
        name.setEnabled(form && !state.busy);
        invitation.setEnabled(!state.busy);
        primaryAction.setVisibility(form || approved ? View.VISIBLE : View.GONE);
        primaryAction.setText(state.busy ? R.string.fg_live_working : approved ? R.string.fg_connect_action : R.string.fg_request_access);
        enabled(primaryAction, !state.busy && (approved || state.retrySeconds() == 0));
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
        cooldown.setVisibility(seconds > 0 ? View.VISIBLE : View.GONE);
        if (seconds > 0) cooldown.setText(getString(R.string.fg_retry_countdown, seconds / 60, seconds % 60));
        enabled(primaryAction, !state.busy && (state.status.equals("approved") || seconds == 0));
    }
    private void leaveGate() { moveTaskToBack(true); }
    @SuppressWarnings("deprecation") @Override public void onBackPressed() { leaveGate(); }
}
