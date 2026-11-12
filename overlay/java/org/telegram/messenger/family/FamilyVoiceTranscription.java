package org.telegram.messenger.family;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Selects Telegram Premium or on-device speech for the native transcription bubble. */
public final class FamilyVoiceTranscription {
    private FamilyVoiceTranscription() { }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LinkedHashMap<String, Entry> CACHE = new LinkedHashMap<>(32, .75f, true);
    private static final String[] VOICE_FIELDS = {
        "voiceTranscription", "voiceTranscriptionId", "voiceTranscriptionOpen",
        "voiceTranscriptionFinal", "translatedVoiceTranscription"
    };
    private static final Map<Object, Object[]> PROJECTIONS = new WeakHashMap<>();
    private static final int MAX_ENTRIES = 32, MAX_BYTES = 1024 * 1024, MAX_TEXT_BYTES = 65536;
    private static long nextId = -1;

    private static final class Entry {
        final String key;
        final int account;
        final long id = nextId--;
        WeakReference<Object> message;
        WeakReference<Activity> activity;
        FamilyLocalTranscriber.Job job;
        String text = "";
        boolean running, error, open = true, offeredModel;
        long epoch;
        Entry(String key, int account, Object message, Context context) {
            this.key = key; this.account = account;
            this.message = new WeakReference<>(message);
            this.activity = new WeakReference<>(activity(context));
        }
    }

    /** Called before the original cell binding, before native caption measurement. */
    public static void restore(Object message) {
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        try {
            if (prepareTelegram(message)) return;
            Entry entry = CACHE.get(key(message));
            if (entry != null && eligible(message)) {
                entry.message = new WeakReference<>(message);
                apply(entry, message);
            }
        } catch (Exception ignored) { }
    }

    public static boolean canShow(Object cell, boolean nativeVisible) {
        try {
            Object message = call(cell, "getMessageObject");
            if (usesTelegram(message)) return nativeVisible;
            if (!eligible(message) || bool(message, "isQuickReply") || Boolean.TRUE.equals(field(message, "isRepostPreview"))) return false;
            if (bool(message, "isOutOwner") && !bool(message, "isSent")) return false;
            return bool(message, "isRoundVideo") || bool(message, "isVoice") && Boolean.TRUE.equals(field(cell, "useSeekBarWaveform"));
        } catch (Exception ignored) { return false; }
    }

    private static boolean eligible(Object message) {
        try {
            if (!FamilyAccess.isAllowed() || !FamilyLocalTranscriber.eligible(message)) return false;
            if (!bool(message, "isVoice") && !bool(message, "isRoundVideo")) return false;
            long dialog = ((Number)call(message, "getDialogId")).longValue();
            if ((Boolean)Class.forName("org.telegram.messenger.DialogObject").getMethod("isEncryptedDialog", long.class).invoke(null, dialog)) return false;
            Object owner = field(message, "messageOwner"), media = field(owner, "media");
            if (media != null && (((Number)field(media, "ttl_seconds")).intValue() != 0 || media.getClass().getSimpleName().contains("messageMediaWebPage"))) return false;
            return key(message) != null;
        } catch (Exception ignored) { return false; }
    }

    public static boolean handles(Object message) { return eligible(message); }

    public static boolean usesTelegram(Object message) {
        try {
            // Telegram Premium has its own media limits and supports older Android versions.
            if (message == null || !FamilyAccess.isAllowed()
                    || (!bool(message, "isVoice") && !bool(message, "isRoundVideo"))) return false;
            Class<?> config = Class.forName("xyz.nextalone.nagram.NaConfig");
            Object provider = call(config.getField("INSTANCE").get(null), "getTranscribeProvider");
            int selected = ((Number)call(provider, "Int")).intValue();
            // These are the existing settings values for Auto and Telegram Premium.
            if (selected != 0 && selected != 1) return false;
            int account = ((Number)field(message, "currentAccount")).intValue();
            Object user = Class.forName("org.telegram.messenger.UserConfig")
                    .getMethod("getInstance", int.class).invoke(null, account);
            return bool(user, "isPremium");
        } catch (Exception ignored) { return false; }
    }

    public static boolean prepareTelegram(Object message) {
        if (!usesTelegram(message)) return false;
        try {
            String identity = key(message);
            Entry entry = CACHE.get(identity);
            if (entry != null && entry.running) {
                CACHE.remove(identity);
                entry.epoch++;
                if (entry.job != null) entry.job.cancel();
            }
            removeProjection(message);
            return true;
        } catch (Exception ignored) { return false; }
    }

    private static boolean removeProjection(Object message) throws Exception {
        Object owner = field(message, "messageOwner");
        Object[] projection = PROJECTIONS.remove(owner);
        if (projection == null || !projection[0].equals(field(owner, "voiceTranscriptionId"))) return false;
        for (int i = 0; i < VOICE_FIELDS.length; i++) set(owner, VOICE_FIELDS[i], projection[i + 1]);
        return true;
    }

    private static String key(Object message) throws Exception {
        int account = ((Number)field(message, "currentAccount")).intValue();
        Class<?> config = Class.forName("org.telegram.messenger.UserConfig");
        Object user = config.getMethod("getInstance", int.class).invoke(null, account);
        long owner = ((Number)config.getMethod("getClientUserId").invoke(user)).longValue();
        if (owner == 0) throw new IllegalStateException("No account");
        Object document = call(message, "getDocument");
        Context application = (Context)Class.forName("org.telegram.messenger.ApplicationLoader").getField("applicationContext").get(null);
        String language = application.getSharedPreferences("saa_system_speech_v1", 0).getString("language", "ru-RU");
        return account + ":" + owner + ":" + call(message, "getDialogId") + ":" + call(message, "getId") + ":"
                + field(document, "id") + ":" + field(document, "dc_id") + ":" + field(document, "size") + ":" + call(message, "getDuration") + ":" + language;
    }

    public static boolean isRunning(Object message) {
        if (Looper.myLooper() != Looper.getMainLooper()) return false;
        try { Entry entry = CACHE.get(key(message)); return entry != null && entry.running && eligible(message); }
        catch (Exception ignored) { return false; }
    }

    /** Returns false when the original Telegram button should handle the tap. */
    public static boolean onButton(Object button) {
        try {
            Object parent = field(button, "parent"), message = call(parent, "getMessageObject");
            if (usesTelegram(message)) {
                Object owner = field(message, "messageOwner");
                boolean wasLocal = PROJECTIONS.containsKey(owner);
                if (!prepareTelegram(message)) return true;
                if (wasLocal) {
                    Class<?> type = button.getClass();
                    type.getMethod("setOpen", boolean.class, boolean.class)
                            .invoke(button, field(owner, "voiceTranscriptionOpen"), false);
                    type.getMethod("setLoading", boolean.class, boolean.class).invoke(button, false, false);
                }
                return false;
            }
            set(button, "pressed", false);
            set(button, "clickedToOpen", !Boolean.TRUE.equals(field(button, "shouldBeOpen")));
            Object selector = field(button, "selectorDrawable");
            if (selector instanceof Drawable) ((Drawable)selector).setState(new int[0]);
            if (parent instanceof View) {
                View cell = (View)parent;
                start(cell.getContext(), message, ((Number)field(message, "currentAccount")).intValue());
                cell.invalidate();
            }
        } catch (Exception ignored) { }
        return true;
    }

    /** Static native entrypoint is also intercepted, including external/context actions. */
    public static void pressed(Object message, boolean open, Object delegate) {
        try {
            Context context = (Context)Class.forName("org.telegram.messenger.ApplicationLoader").getField("applicationContext").get(null);
            Entry entry = CACHE.get(key(message));
            if (entry != null && !entry.running && !entry.error) { entry.open = open; publish(entry, message); }
            else start(context, message, ((Number)field(message, "currentAccount")).intValue());
        } catch (Exception ignored) { }
    }

    public static boolean runFromChat(Object chat, Object message) {
        try {
            Context context = (Context)call(chat, "getParentActivity");
            int account = ((Number)call(chat, "getCurrentAccount")).intValue();
            if (context == null || (!usesTelegram(message) && !eligible(message))) return false;
            if (usesTelegram(message)) {
                Class<?> type = Class.forName("org.telegram.ui.Components.TranscribeButton");
                type.getMethod("transcribePressed", Class.forName("org.telegram.messenger.MessageObject"),
                        boolean.class, Class.forName("org.telegram.ui.Cells.ChatMessageCell$ChatMessageCellDelegate"))
                        .invoke(null, message, true, null);
                return true;
            }
            start(context, message, account); return true;
        } catch (Exception ignored) { return false; }
    }

    public static void start(Context context, Object message, int account) {
        if (Looper.myLooper() != Looper.getMainLooper()) { MAIN.post(() -> start(context, message, account)); return; }
        try {
            if (context == null || !eligible(message) || usesTelegram(message) ||
                    ((Number)field(message, "currentAccount")).intValue() != account) return;
            String identity = key(message);
            Entry existing = CACHE.get(identity);
            if (existing != null) {
                existing.message = new WeakReference<>(message);
                existing.activity = new WeakReference<>(activity(context));
                if (existing.running) {
                    existing.epoch++; existing.running = false; existing.error = true; existing.open = true;
                    if (existing.job != null) existing.job.cancel(); existing.job = null;
                    existing.text = label(context, "fg_transcription_cancelled", "Обработка отменена. Нажмите кнопку, чтобы повторить.");
                    publish(existing, message); return;
                }
                if (!existing.error) { existing.open = !existing.open; publish(existing, message); return; }
            }
            Entry entry = existing != null ? existing : new Entry(identity, account, message, context);
            CACHE.put(identity, entry);
            entry.open = true; entry.error = false; entry.running = true;
            entry.text = label(context, "fg_transcription_download", "Загружаем запись…");
            final long epoch = ++entry.epoch;
            final Context application = context.getApplicationContext();
            trim(); publish(entry, message);
            entry.job = FamilyLocalTranscriber.start(application, message, account, new FamilyLocalTranscriber.Callback() {
                public void onState(String state) {
                    Object target = target(entry, epoch); if (target == null) return;
                    entry.text = stateLabel(application, state); publish(entry, target);
                }
                public void onResult(String text) {
                    Object target = target(entry, epoch); if (target == null) return;
                    entry.running = false; entry.job = null; entry.error = false;
                    entry.text = bounded(text == null ? "" : text);
                    trim(); publish(entry, target);
                }
                public void onError(String code) {
                    Object target = target(entry, epoch); if (target == null) return;
                    entry.running = false; entry.job = null; entry.error = true;
                    entry.text = errorLabel(application, code); publish(entry, target);
                    if ("LANGUAGE_MODEL_MISSING".equals(code)) offerModel(entry, application);
                }
            });
        } catch (Exception ignored) { }
    }

    private static Object target(Entry entry, long epoch) {
        if (entry.epoch != epoch || CACHE.get(entry.key) != entry) return null;
        Object message = entry.message.get();
        try {
            if (message != null && entry.key.equals(key(message)) && eligible(message)) {
                if (!usesTelegram(message)) return message;
                removeProjection(message);
            }
        }
        catch (Exception ignored) { }
        entry.epoch++; entry.running = false;
        if (entry.job != null) entry.job.cancel(); entry.job = null;
        CACHE.remove(entry.key); return null;
    }

    private static void apply(Entry entry, Object message) throws Exception {
        Object owner = field(message, "messageOwner");
        Object[] projection = PROJECTIONS.get(owner);
        Object currentId = field(owner, "voiceTranscriptionId");
        if (projection == null || !projection[0].equals(currentId)) {
            projection = new Object[VOICE_FIELDS.length + 1];
            projection[0] = entry.id;
            for (int i = 0; i < VOICE_FIELDS.length; i++) projection[i + 1] = field(owner, VOICE_FIELDS[i]);
            PROJECTIONS.put(owner, projection);
        } else {
            projection[0] = entry.id;
        }
        set(owner, "voiceTranscription", entry.text);
        set(owner, "voiceTranscriptionId", entry.id);
        set(owner, "voiceTranscriptionOpen", entry.open);
        set(owner, "voiceTranscriptionFinal", !entry.running);
        // A previous server translation must not override the new local transcript.
        set(owner, "translatedVoiceTranscription", null);
        if (entry.open) {
            Class<?> type = Class.forName("org.telegram.ui.Components.TranscribeButton");
            type.getMethod("openVideoTranscription", Class.forName("org.telegram.messenger.MessageObject")).invoke(null, message);
        }
    }

    private static void publish(Entry entry, Object message) {
        try {
            if (!entry.key.equals(key(message))) return;
            apply(entry, message);
            // Also refresh voice messages invoked from the menu before a waveform exists.
            set(message, "forceUpdate", true);
            Class<?> type = Class.forName("org.telegram.messenger.NotificationCenter");
            Object center = type.getMethod("getInstance", int.class).invoke(null, entry.account);
            int notification = type.getField("voiceTranscriptionUpdate").getInt(null);
            // Telegram rebinds the actual row, recomputes its own caption and adjusts scrolling.
            type.getMethod("postNotificationName", int.class, Object[].class).invoke(center, notification,
                    new Object[]{message, entry.id, entry.text, entry.open, !entry.running});
        } catch (Exception ignored) { }
    }

    private static void trim() {
        int bytes = 0; for (Entry entry : CACHE.values()) bytes += entry.text.getBytes(StandardCharsets.UTF_8).length;
        Iterator<Map.Entry<String, Entry>> iterator = CACHE.entrySet().iterator();
        while ((CACHE.size() > MAX_ENTRIES || bytes > MAX_BYTES) && iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.running) continue;
            bytes -= entry.text.getBytes(StandardCharsets.UTF_8).length; iterator.remove();
        }
    }

    private static String bounded(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_TEXT_BYTES) return value;
        int end = MAX_TEXT_BYTES - 3;
        while (end > 0 && (bytes[end] & 0xc0) == 0x80) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8) + "…";
    }

    private static String stateLabel(Context context, String code) {
        if ("downloading".equals(code)) return label(context, "fg_transcription_download", "Загружаем запись…");
        if ("decoding".equals(code)) return label(context, "fg_transcription_upload", "Подготавливаем звук…");
        return label(context, "fg_transcription_processing", "Распознаём речь на телефоне…");
    }
    private static String errorLabel(Context context, String code) {
        String name = "fg_transcription_failed", fallback = "Не удалось расшифровать запись. Нажмите кнопку, чтобы повторить.";
        if ("CANCELLED".equals(code)) { name = "fg_transcription_cancelled"; fallback = "Обработка отменена"; }
        else if ("BUSY".equals(code)) { name = "fg_transcription_busy"; fallback = "Распознавание занято. Повторите позже."; }
        else if ("LANGUAGE_MODEL_MISSING".equals(code) || "LANGUAGE_UNAVAILABLE".equals(code)) { name = "fg_transcription_model_missing"; fallback = "Для этого языка нет локальной модели."; }
        else if ("ON_DEVICE_UNAVAILABLE".equals(code) || "FILE_SOURCE_UNVERIFIED".equals(code) || "PERMISSION_REQUIRED".equals(code)) { name = "fg_transcription_unavailable"; fallback = "Локальное распознавание недоступно на этом устройстве."; }
        else if ("audio_input_limit".equals(code) || "audio_duration_limit".equals(code) || "pcm_output_limit".equals(code)) { name = "fg_transcription_too_large"; fallback = "Выберите запись до 5 минут и 16 МБ"; }
        return label(context, name, fallback);
    }

    private static void offerModel(Entry entry, Context context) {
        Activity activity = entry.activity.get();
        if (entry.offeredModel || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        entry.offeredModel = true;
        String language = context.getSharedPreferences("saa_system_speech_v1", 0).getString("language", "ru-RU");
        long epoch = entry.epoch;
        new AlertDialog.Builder(activity).setTitle("Загрузить язык распознавания?")
                .setMessage("Android загрузит локальную модель языка «" + language + "». Затем нажмите кнопку расшифровки ещё раз.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Загрузить", (dialog, which) -> {
                    Object message = target(entry, epoch); if (message == null) return;
                    entry.text = label(context, "fg_transcription_model_loading", "Android загружает модель языка…"); publish(entry, message);
                    FamilySystemSpeech.downloadLanguage(context, language, new FamilySystemSpeech.Callback() {
                        public void onResult(String ignored) { complete("Язык загружен. Нажмите кнопку расшифровки."); }
                        public void onError(String code) { complete(errorLabel(context, code)); }
                        public void onProgress(int sent, int total) { }
                        private void complete(String text) { Object current = target(entry, epoch); if (current != null) { entry.text = text; publish(entry, current); } }
                    });
                }).show();
    }

    private static Activity activity(Context context) {
        for (int i = 0; context != null && i < 12; i++) {
            if (context instanceof Activity) return (Activity)context;
            if (!(context instanceof ContextWrapper)) break;
            Context next = ((ContextWrapper)context).getBaseContext(); if (next == context) break; context = next;
        }
        return null;
    }
    private static String label(Context context, String name, String fallback) {
        int id = context.getResources().getIdentifier(name, "string", context.getPackageName());
        return id == 0 ? fallback : context.getString(id);
    }
    private static Object call(Object object, String name) throws Exception { return object.getClass().getMethod(name).invoke(object); }
    private static boolean bool(Object object, String name) throws Exception { return Boolean.TRUE.equals(call(object, name)); }
    private static Field member(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static Object field(Object object, String name) throws Exception { return member(object, name).get(object); }
    private static void set(Object object, String name, Object value) throws Exception { member(object, name).set(object, value); }
}
