package org.telegram.messenger.family;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Resources;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.ModelDownloadListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** File-only caller of the system's on-device recognizer. No cloud or microphone fallback.
 * EXTRA_AUDIO_SOURCE may be ignored by OEM engines: callers MUST verify their exact provider
 * before passing fileSourceVerified=true. The recording monitor detects, but cannot prevent,
 * an incorrectly implemented service's first microphone sample. Default calls fail closed.
 */
public final class FamilySystemSpeech {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_PCM = 300 * 16000 * 2;
    private static Session active;
    private FamilySystemSpeech() { }

    public interface Callback {
        void onResult(String text);
        void onError(String code);
        void onProgress(int sentBytes, int totalBytes);
    }
    public interface SupportCallback {
        void onSupport(SupportInfo info);
        void onError(String code);
    }
    public static final class SupportInfo {
        public final List<String> installedLanguages, supportedLanguages, pendingLanguages;
        public final boolean requestedLanguageInstalled;
        SupportInfo(RecognitionSupport support, String language) {
            installedLanguages = immutable(support.getInstalledOnDeviceLanguages());
            supportedLanguages = immutable(support.getSupportedOnDeviceLanguages());
            pendingLanguages = immutable(support.getPendingOnDeviceLanguages());
            String requested = Locale.forLanguageTag(language).getLanguage();
            boolean present = false;
            for (String entry : installedLanguages) {
                if (entry.equalsIgnoreCase(language) || Locale.forLanguageTag(entry).getLanguage().equals(requested)) present = true;
            }
            requestedLanguageInstalled = present;
        }
        private static List<String> immutable(List<String> list) {
            return Collections.unmodifiableList(new ArrayList<>(list));
        }
    }
    public static boolean isAvailable(Context context) {
        if (Build.VERSION.SDK_INT < 33) return false;
        try { return SpeechRecognizer.isOnDeviceRecognitionAvailable(context); }
        catch (RuntimeException ignored) { return false; }
    }
    public static String providerKey(Context context) {
        if (Build.VERSION.SDK_INT < 33) return "";
        try {
            Resources system = Resources.getSystem();
            int id = system.getIdentifier("config_defaultOnDeviceSpeechRecognitionService", "string", "android");
            ComponentName component = id == 0 ? null : ComponentName.unflattenFromString(system.getString(id));
            if (component == null) return "";
            long version = context.getPackageManager().getPackageInfo(component.getPackageName(), 0).getLongVersionCode();
            return component.flattenToString() + "@" + version;
        } catch (Exception ignored) { return ""; }
    }
    /** QA pin: Android 15, AiAi V.41 (12872254), known-file test with test UID mic AppOp ignored.
     * This verifies file routing, not recognition accuracy or availability of every language.
     * Future OS/provider versions stay unverified until independently tested.
     */
    public static boolean isVerifiedFileProvider(Context context) {
        return Build.VERSION.SDK_INT == 35 && isAvailable(context)
                && "com.google.android.as/com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService@12872254".equals(providerKey(context));
    }
    public static void downloadLanguage(Context context, String language, Callback callback) {
        Session session = new Session(context, null, language, callback, null, false);
        MAIN.post(session::beginDownload);
    }
    public static Session inspect(Context context, String language, SupportCallback callback) {
        Session session = new Session(context, null, language, null, callback, false);
        MAIN.post(session::begin); return session;
    }
    public static Session transcribe(Context context, byte[] pcm16k, String language, Callback callback) {
        return transcribe(context, pcm16k, language, false, callback);
    }
    public static Session transcribe(Context context, byte[] pcm16k, String language, boolean fileSourceVerified, Callback callback) {
        return transcribe(context, pcm16k, language, fileSourceVerified, true, callback);
    }
    /** Production uses 40 ms PCM blocks at 32,000 bytes/second. Unpaced input is QA-only:
     * the verified AiAi provider dropped speech when a complete file was sent in a burst.
     */
    public static Session transcribe(Context context, byte[] pcm16k, String language, boolean fileSourceVerified, boolean paceInput, Callback callback) {
        byte[] copy = pcm16k != null && pcm16k.length <= MAX_PCM ? pcm16k.clone() : null;
        Session session = new Session(context, copy, language, callback, null, fileSourceVerified);
        session.paceInput = paceInput;
        MAIN.post(session::begin); return session;
    }

    public static final class Session implements RecognitionListener {
        private final Context context;
        private final Callback callback;
        private final SupportCallback supportCallback;
        private final String language;
        private final boolean verified;
        private final AtomicBoolean finished = new AtomicBoolean();
        private byte[] pcm;
        private SpeechRecognizer recognizer;
        private ParcelFileDescriptor readPipe, writePipe;
        private volatile boolean inputClosed;
        private boolean paceInput;
        private volatile int sentBytes;
        private int segmentCallbacks, resultCallbacks, endSegmentCallbacks, endSpeechCallbacks;
        private long startedAt;
        private AudioManager audio;
        private AudioManager.AudioRecordingCallback recording;
        private final StringBuilder segments = new StringBuilder();
        private final Runnable deadline = () -> fail("TIMEOUT");

        Session(Context context, byte[] pcm, String language, Callback callback, SupportCallback supportCallback, boolean verified) {
            this.context = context.getApplicationContext(); this.pcm = pcm;
            this.language = normalizeLanguage(language); this.callback = callback;
            this.supportCallback = supportCallback; this.verified = verified;
        }
        private static String normalizeLanguage(String value) {
            if (value == null || value.isEmpty() || "auto".equals(value)) return Locale.getDefault().toLanguageTag();
            if ("ru".equalsIgnoreCase(value)) return "ru-RU";
            return value;
        }
        private void begin() {
            if (finished.get()) return;
            if (!isAvailable(context)) { fail("ON_DEVICE_UNAVAILABLE"); return; }
            if (active != null) { fail("BUSY"); return; }
            if (language.length() > 35 || !language.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")) { fail("INVALID_LANGUAGE"); return; }
            if (supportCallback == null) {
                if (!verified) { fail("FILE_SOURCE_UNVERIFIED"); return; }
                if (pcm == null || pcm.length < 9600 || pcm.length > MAX_PCM || (pcm.length & 1) != 0) { fail("INVALID_PCM"); return; }
                // This is a verified file-descriptor source, not microphone capture. Let the
                // on-device service enforce its FD permission contract; never request microphone
                // permission or retry with microphone/cloud input if the service rejects it.
            }
            active = this;
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe(); readPipe = pipe[0]; writePipe = pipe[1];
                final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
                intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
                intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readPipe);
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000);
                intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
                recognizer.setRecognitionListener(this);
                MAIN.postDelayed(deadline, 15000);
                recognizer.checkRecognitionSupport(intent, command -> MAIN.post(command), new RecognitionSupportCallback() {
                    public void onSupportResult(RecognitionSupport support) {
                        if (finished.get()) return;
                        SupportInfo info = new SupportInfo(support, language);
                        if (supportCallback != null) {
                            cleanup(); supportCallback.onSupport(info); return;
                        }
                        if (!info.requestedLanguageInstalled) { fail("LANGUAGE_MODEL_MISSING"); return; }
                        startFile(intent);
                    }
                    public void onError(int error) { fail("SUPPORT_ERROR_" + error); }
                });
            } catch (Exception ignored) { fail("INITIALIZATION_FAILED"); }
        }
        private void beginDownload() {
            if (finished.get()) return;
            if (Build.VERSION.SDK_INT < 34 || !isAvailable(context)) { fail("MODEL_DOWNLOAD_UNAVAILABLE"); return; }
            if (active != null) { fail("BUSY"); return; }
            active = this;
            try {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
                intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
                MAIN.postDelayed(deadline, 600000);
                recognizer.triggerModelDownload(intent, command -> MAIN.post(command), new ModelDownloadListener() {
                    public void onProgress(int percent) { if (!finished.get()) callback.onProgress(percent, 100); }
                    public void onSuccess() { if (!finished.get()) { cleanup(); callback.onResult(""); } }
                    public void onScheduled() { fail("MODEL_DOWNLOAD_SCHEDULED"); }
                    public void onError(int error) { fail("MODEL_DOWNLOAD_ERROR_" + error); }
                });
            } catch (Exception ignored) { fail("MODEL_DOWNLOAD_FAILED"); }
        }
        private void startFile(Intent intent) {
            if (finished.get()) return;
            try {
                audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (audio == null || !audio.getActiveRecordingConfigurations().isEmpty()) { fail("RECORDING_ACTIVE"); return; }
                recording = new AudioManager.AudioRecordingCallback() {
                    public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configurations) {
                        if (!configurations.isEmpty()) fail("MICROPHONE_OPENED");
                    }
                };
                audio.registerAudioRecordingCallback(recording, MAIN);
                if (!audio.getActiveRecordingConfigurations().isEmpty()) { fail("RECORDING_ACTIVE"); return; }
                MAIN.removeCallbacks(deadline);
                MAIN.postDelayed(deadline, Math.min(630000L, Math.max(60000L, pcm.length / 16L + 30000L)));
                recognizer.startListening(intent); // Exactly the FD-bearing intent checked above.
                startedAt = SystemClock.elapsedRealtime();
                new Thread(this::writePcm, "FamilySystemSpeech-input").start();
            } catch (Exception ignored) { fail("FILE_START_FAILED"); }
        }
        private void writePcm() {
            byte[] input = pcm;
            try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(writePipe)) {
                long start = SystemClock.elapsedRealtime(), lastProgress = 0;
                for (int offset = 0; offset < input.length && !finished.get();) {
                    int count = Math.min(paceInput ? 1280 : 16384, input.length - offset); out.write(input, offset, count); offset += count;
                    sentBytes = offset;
                    if (paceInput) {
                        long due = start + offset * 1000L / 32000L;
                        while (!finished.get()) {
                            long remaining = due - SystemClock.elapsedRealtime();
                            if (remaining <= 0) break;
                            Thread.sleep(Math.min(40L, remaining));
                        }
                    }
                    long now = SystemClock.elapsedRealtime();
                    if (offset == input.length || now - lastProgress >= 200) {
                        lastProgress = now;
                        final int sent = offset; MAIN.post(() -> { if (!finished.get()) callback.onProgress(sent, input.length); });
                    }
                }
                out.flush(); inputClosed = true;
            } catch (Exception ignored) { MAIN.post(() -> { if (!finished.get()) fail("INPUT_PIPE_FAILED"); }); }
            finally { if (input != null) Arrays.fill(input, (byte) 0); }
        }
        public void cancel() { MAIN.post(() -> fail("CANCELLED")); }
        public void destroy() { MAIN.post(this::cleanup); }
        /** Counters only; never audio or transcript content. Intended for the separate QA app. */
        public Bundle diagnostics() {
            Bundle info = new Bundle();
            info.putBoolean("paced_input", paceInput); info.putInt("sent_pcm_bytes", sentBytes);
            info.putBoolean("input_closed", inputClosed); info.putInt("segment_callbacks", segmentCallbacks);
            info.putInt("result_callbacks", resultCallbacks); info.putInt("end_segment_callbacks", endSegmentCallbacks);
            info.putInt("end_speech_callbacks", endSpeechCallbacks);
            info.putLong("recognition_elapsed_ms", startedAt == 0 ? 0 : SystemClock.elapsedRealtime() - startedAt);
            return info;
        }
        private void fail(String code) {
            if (finished.get()) return;
            cleanup();
            if (supportCallback != null) supportCallback.onError(code);
            else if (callback != null) callback.onError(code);
        }
        private void cleanup() {
            if (finished.getAndSet(true)) return;
            MAIN.removeCallbacks(deadline);
            if (active == this) active = null;
            if (audio != null && recording != null) try { audio.unregisterAudioRecordingCallback(recording); } catch (Exception ignored) { }
            if (recognizer != null) {
                try { recognizer.cancel(); } catch (Exception ignored) { }
                try { recognizer.destroy(); } catch (Exception ignored) { }
            }
            try { if (readPipe != null) readPipe.close(); } catch (Exception ignored) { }
            try { if (writePipe != null) writePipe.close(); } catch (Exception ignored) { }
            if (pcm != null) Arrays.fill(pcm, (byte) 0);
            pcm = null; segments.setLength(0);
        }
        private String first(Bundle result) {
            ArrayList<String> list = result == null ? null : result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            return list == null || list.isEmpty() || list.get(0) == null ? "" : list.get(0).trim();
        }
        private void finishText(String text) {
            if (finished.get()) return;
            if (!inputClosed) { fail("INPUT_NOT_CONSUMED"); return; }
            if (text.length() > 65536) { fail("OUTPUT_TOO_LARGE"); return; }
            if (audio != null && !audio.getActiveRecordingConfigurations().isEmpty()) { fail("MICROPHONE_OPENED"); return; }
            cleanup(); callback.onResult(text);
        }
        public void onSegmentResults(Bundle result) {
            if (finished.get()) return;
            segmentCallbacks++;
            String text = first(result);
            if (segments.length() + text.length() + 1 > 65536) { fail("OUTPUT_TOO_LARGE"); return; }
            if (!text.isEmpty()) { if (segments.length() != 0) segments.append('\n'); segments.append(text); }
        }
        public void onEndOfSegmentedSession() { endSegmentCallbacks++; finishText(segments.toString()); }
        public void onResults(Bundle result) {
            if (finished.get()) return;
            resultCallbacks++;
            String text = first(result), prior = segments.toString();
            // Some providers emit a final full-session result; others emit only the last segment.
            // Preserve already delivered segments instead of silently replacing them with a tail.
            String flatPrior = prior.replaceAll("\\s+", " ").trim(), flatText = text.replaceAll("\\s+", " ").trim();
            if (prior.isEmpty() || (!flatText.isEmpty() && flatText.startsWith(flatPrior))) finishText(text);
            else if (text.isEmpty() || flatPrior.endsWith(flatText)) finishText(prior);
            else finishText(prior + "\n" + text);
        }
        public void onError(int error) { fail("RECOGNIZER_ERROR_" + error); }
        public void onReadyForSpeech(Bundle params) { }
        public void onBeginningOfSpeech() { }
        public void onRmsChanged(float rms) { }
        public void onBufferReceived(byte[] buffer) { }
        public void onEndOfSpeech() { endSpeechCallbacks++; }
        public void onPartialResults(Bundle partial) { }
        public void onEvent(int event, Bundle params) { }
    }
}
