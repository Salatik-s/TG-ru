package org.telegram.messenger.family;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.telegram.messenger.R;

public final class FamilySettingsActivity extends FamilyUi implements FamilyAccess.Listener {
    private TextView connection, detail, feedback, fingerprint;
    private Button reconnect, refresh, copyFingerprint;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.fg_system_title);
        settingsNavigation(R.string.fg_system_title);
        paragraph(R.string.fg_system_intro);
        connection = section(R.string.fg_connection, R.string.fg_no_fresh_data);
        connection.setTextColor(ink);
        connection.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        detail = text(R.string.fg_live_no_tunnel, 14, muted);
        add(detail, 8);
        reconnect = button(R.string.fg_connect_action, true);
        reconnect.setOnClickListener(v -> FamilyAccess.connect());
        refresh = button(R.string.fg_check_status, false);
        refresh.setOnClickListener(v -> FamilyAccess.refresh());
        feedback = text(R.string.fg_network_error, 14, error);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(feedback, 12);
        navigationRow(R.string.fg_advanced_title, R.string.fg_advanced_summary, v -> openScreen("advanced"));
        navigationRow(R.string.fg_advanced_neko, R.string.fg_advanced_summary, v -> openScreen("advanced_neko"));
        TextView device = section(R.string.fg_device_name, R.string.fg_device_name);
        device.setText(FamilyAccess.deviceName());
        fingerprint = section(R.string.fg_device_fingerprint, R.string.fg_fingerprint_pending);
        fingerprint.setTypeface(android.graphics.Typeface.MONOSPACE);
        fingerprint.setTextIsSelectable(true);
        copyFingerprint = button(R.string.fg_copy_fingerprint, false);
        copyFingerprint.setOnClickListener(v -> copyDeviceFingerprint());
        Button details = button(R.string.fg_connection_details, false);
        TextView address = text(R.string.fg_service_address, 14, muted);
        address.setText(FamilyAccess.server());
        address.setTextIsSelectable(true);
        address.setVisibility(View.GONE);
        add(address, 8);
        details.setOnClickListener(v -> {
            boolean showing = address.getVisibility() != View.VISIBLE;
            address.setVisibility(showing ? View.VISIBLE : View.GONE);
            details.setText(showing ? R.string.fg_hide_details : R.string.fg_connection_details);
        });
        if (!FamilyAccess.state().busy) FamilyAccess.refresh();
    }
    private void openScreen(String destination) {
        if (!FamilyAccess.isAllowed()) return;
        Intent intent = new Intent();
        intent.setClassName(getPackageName(), "org.telegram.ui.LaunchActivity");
        intent.putExtra("family_screen", destination);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }
    @Override protected void onStart() { super.onStart(); FamilyAccess.addListener(this); }
    @Override protected void onStop() { FamilyAccess.removeListener(this); super.onStop(); }
    @Override public void onFamilyAccessChanged(FamilyAccess.Snapshot state) {
        connection.setText(statusTitle(state));
        detail.setText(FamilyAccess.isAllowed() ? R.string.fg_live_tunnel_active : R.string.fg_live_no_tunnel);
        reconnect.setText(state.busy ? R.string.fg_live_working : R.string.fg_reconnect_action);
        enabled(reconnect, !state.busy && state.status.equals("approved"));
        enabled(refresh, !state.busy);
        feedback.setVisibility(state.error.isEmpty() ? View.GONE : View.VISIBLE);
        if (!state.error.isEmpty()) feedback.setText(errorText(state.error));
        String code = FamilyAccess.deviceFingerprint();
        fingerprint.setText(code.isEmpty() ? getString(R.string.fg_fingerprint_pending) : code);
        copyFingerprint.setVisibility(code.isEmpty() ? View.GONE : View.VISIBLE);
    }
}
