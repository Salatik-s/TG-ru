package com.salatik.familygram.speechdiag;

import android.app.AppOpsManager;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.telegram.messenger.family.FamilySystemSpeech;

/** Own test-only package. Capabilities mode never calls startListening. */
public final class SystemSpeechDiagnostic extends Instrumentation {
    private Bundle args;
    private final AtomicBoolean done = new AtomicBoolean();
    private FamilySystemSpeech.Session session;
    public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; super.onCreate(arguments); start(); }
    public void onStart() {
        final Context context = getTargetContext();
        final String mode = args.getString("mode", "capabilities");
        final String language = args.getString("language", "en-US");
        final Bundle report = new Bundle();
        report.putString("provider_key", FamilySystemSpeech.providerKey(context));
        report.putBoolean("on_device_available", FamilySystemSpeech.isAvailable(context));
        report.putBoolean("provider_policy_verified", FamilySystemSpeech.isVerifiedFileProvider(context));
        report.putString("mode", mode);
        if ("capabilities".equals(mode)) {
            session = FamilySystemSpeech.inspect(context, language, new FamilySystemSpeech.SupportCallback() {
                public void onSupport(FamilySystemSpeech.SupportInfo info) {
                    report.putString("installed_languages", String.join(",", info.installedLanguages));
                    report.putString("supported_languages", String.join(",", info.supportedLanguages));
                    report.putString("pending_languages", String.join(",", info.pendingLanguages));
                    report.putBoolean("requested_language_installed", info.requestedLanguageInstalled);
                    report.putBoolean("file_input_verified", false); finishReport(report);
                }
                public void onError(String code) { report.putString("error", code); finishReport(report); }
            });
            return;
        }
        if ("download".equals(mode)) {
            FamilySystemSpeech.downloadLanguage(context, language, new FamilySystemSpeech.Callback() {
                public void onResult(String ignored) { report.putBoolean("model_download_complete", true); finishReport(report); }
                public void onError(String code) { report.putString("error", code); finishReport(report); }
                public void onProgress(int percent, int total) { report.putInt("download_progress", percent); }
            });
            return;
        }
        if (!"file".equals(mode) && !"file-paced".equals(mode)) { report.putString("error", "INVALID_MODE"); finishReport(report); return; }
        try {
            AppOpsManager manager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            int op = manager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), context.getPackageName());
            report.putInt("test_app_record_audio_op", op);
            report.putBoolean("test_app_record_audio_permission_granted",
                    context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED);
            if (op != AppOpsManager.MODE_IGNORED && op != AppOpsManager.MODE_ERRORED) {
                report.putString("error", "TEST_MICROPHONE_GUARD_REQUIRED"); finishReport(report); return;
            }
            byte[] pcm;
            String sample=args.getString("sample","jfk");if(!sample.matches("jfk|synthetic-ru|synthetic-en"))throw new IllegalArgumentException();
            report.putString("sample",sample);
            try (InputStream input = context.getAssets().open(sample+".pcm"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] block = new byte[8192]; int count;
                while ((count = input.read(block)) != -1) { out.write(block, 0, count); if (out.size() > 1000000) throw new IllegalStateException(); }
                pcm = out.toByteArray();
            }
            report.putInt("synthetic_pcm_bytes", pcm.length);
            session = FamilySystemSpeech.transcribe(context, pcm, language, true, "file-paced".equals(mode), new FamilySystemSpeech.Callback() {
                public void onResult(String text) {
                    String lower = text.toLowerCase(java.util.Locale.ROOT);
                    boolean matched=sample.equals("synthetic-ru")?lower.contains("провер")&&(lower.contains("телефон")||lower.contains("распозна")):sample.equals("synthetic-en")?lower.contains("speech")&&(lower.contains("test")||lower.contains("recognition")):lower.contains("country")&&(lower.contains("ask")||lower.contains("fellow"));
                    report.putBoolean("known_sample_match",matched);
                    report.putString("public_sample_transcript", text.length() > 2000 ? text.substring(0, 2000) : text);
                    report.putBoolean("file_input_verified",matched);
                    finishReport(report);
                }
                public void onError(String code) { report.putString("error", code); report.putBoolean("file_input_verified", false); finishReport(report); }
                public void onProgress(int sent, int total) { }
            });
            java.util.Arrays.fill(pcm, (byte) 0);
        } catch (Exception ignored) { report.putString("error", "DIAGNOSTIC_SETUP_FAILED"); finishReport(report); }
    }
    private void finishReport(Bundle report) {
        if (done.getAndSet(true)) return;
        if (session != null) report.putAll(session.diagnostics());
        if (session != null) session.destroy();
        finish(-1, report);
    }
}
