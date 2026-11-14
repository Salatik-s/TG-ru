package org.telegram.messenger.family;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;

import org.telegram.messenger.R;

public final class FamilySettingsActivity extends FamilySettingsPage implements FamilyAccess.Listener {
    static final String EXTRA_CONNECTION = "tg_ru_connection_settings";
    private TextView connection, detail, feedback, fingerprint;
    private Button reconnect, refresh, copyFingerprint;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra(EXTRA_CONNECTION, false)) {
            setTitle(R.string.fg_connection);
            createSettingsLayout(R.string.fg_connection);
            Switch automatic = toggle(R.string.fg_route_title, R.string.fg_route_explanation);
            automatic.setChecked(FamilyAccess.routeFailoverEnabled());
            automatic.setOnCheckedChangeListener((button, checked) -> FamilyAccess.setRouteFailoverEnabled(checked));
            return;
        }
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
        diagnostics();
        if (!FamilyAccess.state().busy) FamilyAccess.refresh();
    }
    private void diagnostics() {
        TextView info = text(R.string.fg_connection_details, 14, muted);
        info.setText("Журнал за 24 часа. Сохраняет время, состояние сети, сайта, сервера и счётчики звонков на телефоне.");
        add(info, 20);
        Button record = button(R.string.fg_connection_details, false);
        record.setText(FamilyConnectionMonitor.logging() ? "Остановить запись" : "Записать диагностику");
        record.setOnClickListener(v -> {
            boolean recording = FamilyConnectionMonitor.logging();
            if (recording) FamilyConnectionMonitor.stopLogging();
            else FamilyConnectionMonitor.startLogging();
            record.setText(recording ? "Записать диагностику" : "Остановить запись");
        });
        Button copy = button(R.string.fg_connection_details, false);
        copy.setText("Скопировать журнал");
        copy.setOnClickListener(v -> {
            String report = FamilyConnectionMonitor.report();
            if (report.isEmpty()) {
                android.widget.Toast.makeText(this, "Журнал пока пуст", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TG-ru", report));
            android.widget.Toast.makeText(this, "Журнал скопирован", android.widget.Toast.LENGTH_SHORT).show();
        });
    }
    @Override protected void onStart() { super.onStart(); if (connection != null) FamilyAccess.addListener(this); }
    @Override protected void onStop() { if (connection != null) FamilyAccess.removeListener(this); super.onStop(); }
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
