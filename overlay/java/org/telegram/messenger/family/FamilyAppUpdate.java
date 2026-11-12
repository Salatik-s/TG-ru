package org.telegram.messenger.family;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.util.Base64;
import android.widget.EditText;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional updates. Authentication, chat state and normal launch never depend on this service. */
public final class FamilyAppUpdate {
    private static final String PAGE = "https://tg.24projects.ru/index.html";
    private static final String DOWNLOAD = "https://tg.24projects.ru/download/TG-ru.apk";
    private static final String PACKAGE = "com.salatik.familygram";
    private static final Pattern METADATA = Pattern.compile("<script id=\"tg-ru-release\" type=\"application/json\">(\\{[^<]{1,4096}\\})</script>");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean CHECKING = new AtomicBoolean();
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean();
    private static volatile Release available;
    private static volatile long lastChecked;
    private static volatile long lastAttempt;
    private static long offeredVersion;

    private FamilyAppUpdate() { }

    public static boolean hasUpdate(Context context) {
        if (FamilyStoreAccess.isConfigured(context)) return false;
        Release release = available;
        return release != null && release.version > installedVersion(context) && release.minSdk <= Build.VERSION.SDK_INT;
    }

    public static void check(Activity activity) { check(activity, null); }

    public static void check(Activity activity, Runnable changed) {
        long now = SystemClock.elapsedRealtime();
        if ((lastChecked != 0 && now - lastChecked < 6 * 60 * 60 * 1000L)
                || (lastAttempt != 0 && now - lastAttempt < 5 * 60 * 1000L)) {
            if (changed != null) MAIN.post(changed);
            return;
        }
        fetch(activity, changed, false);
    }

    public static void offerInChats(Activity activity, Object fragment) {
        java.lang.ref.WeakReference<Object> chats = new java.lang.ref.WeakReference<>(fragment);
        check(activity, () -> {
            Object visible = chats.get();
            Release release = available;
            if (visible == null || release == null || offeredVersion == release.version || !hasUpdate(activity)
                    || !alive(activity) || !FamilyAccess.isAllowed() || !activity.hasWindowFocus()) return;
            try {
                Class<?> base = Class.forName("org.telegram.ui.ActionBar.BaseFragment");
                if (Boolean.TRUE.equals(base.getMethod("isPaused").invoke(visible))) return;
                Class<?> factory = Class.forName("org.telegram.ui.Components.BulletinFactory");
                Object target = factory.getMethod("of", base).invoke(null, visible);
                int icon = activity.getResources().getIdentifier("ic_download", "raw", activity.getPackageName());
                if (icon == 0) return;
                Object bulletin = factory.getMethod("createSimpleBulletin", int.class, CharSequence.class,
                        CharSequence.class, Runnable.class).invoke(target, icon, "Доступна новая версия TG-ru",
                        "Обновить", (Runnable) () -> open(activity));
                bulletin.getClass().getMethod("show").invoke(bulletin);
                offeredVersion = release.version;
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        });
    }

    public static void open(Activity activity) { fetch(activity, null, true); }

    private static void fetch(Activity activity, Runnable changed, boolean interactive) {
        if (FamilyStoreAccess.isConfigured(activity)) return;
        if (!CHECKING.compareAndSet(false, true)) {
            if (interactive) toast(activity, "Проверяем обновления…");
            return;
        }
        lastAttempt = SystemClock.elapsedRealtime();
        if (interactive) toast(activity, "Проверяем обновления…");
        WORKER.execute(() -> {
            String error = null;
            try {
                HttpURLConnection connection = connect(PAGE);
                try {
                    if (connection.getResponseCode() != 200) throw new UpdateFailure("Не удалось проверить обновления. Проверьте интернет и повторите попытку.");
                    byte[] page = readBounded(connection.getInputStream(), 256 * 1024);
                    Matcher match = METADATA.matcher(new String(page, StandardCharsets.UTF_8));
                    if (!match.find()) throw new UpdateFailure("Сведения об обновлении ещё не опубликованы. Повторите проверку позже.");
                    Release release = new Release(new JSONObject(match.group(1)));
                    if (match.find()) throw new UpdateFailure("Не удалось проверить сведения об обновлении.");
                    available = release;
                    lastChecked = SystemClock.elapsedRealtime();
                } finally { connection.disconnect(); }
            } catch (UpdateFailure failure) { error = failure.getMessage(); }
            catch (Exception failure) { error = "Не удалось проверить обновления. Проверьте интернет и повторите попытку."; }
            final String message = error;
            CHECKING.set(false);
            MAIN.post(() -> {
                if (!alive(activity)) return;
                if (changed != null) changed.run();
                if (!interactive) return;
                if (message != null) { toast(activity, message); return; }
                Release release = available;
                if (release.minSdk > Build.VERSION.SDK_INT) {
                    toast(activity, "Новая версия требует более новой версии Android. Установленная версия продолжает работать.");
                } else if (hasUpdate(activity)) {
                    new AlertDialog.Builder(activity).setTitle("Обновить TG-ru")
                            .setMessage("Версия " + release.name + " · " + ((release.bytes + 1048575) / 1048576) + " МБ\n\n" + release.notes)
                            .setPositiveButton("Скачать", (dialog, which) -> requestPassword(activity, release))
                            .setNegativeButton("Позже", null).show();
                } else toast(activity, "У вас актуальная версия TG-ru");
            });
        });
    }

    private static void requestPassword(Activity activity, Release release) {
        File ready = downloaded(activity, release);
        if (ready.isFile()) { verifyAndInstall(activity, release, ready); return; }
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Пароль скачивания");
        int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding / 2, padding, padding / 2);
        if (Build.VERSION.SDK_INT >= 26) input.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        AlertDialog prompt = new AlertDialog.Builder(activity).setTitle("Доступ к обновлению")
                .setMessage("Введите пароль скачивания с сайта, полученный от разработчика.")
                .setView(input).setPositiveButton("Скачать", null).setNegativeButton("Отмена", null).create();
        prompt.setOnShowListener(dialog -> prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String password = input.getText().toString();
            if (password.isEmpty() || password.length() > 256) { input.setError("Введите пароль скачивания"); return; }
            input.setText("");
            prompt.dismiss();
            download(activity, release, password);
        }));
        prompt.show();
    }

    private static void download(Activity activity, Release release, String password) {
        if (!DOWNLOADING.compareAndSet(false, true)) { toast(activity, "Обновление уже скачивается"); return; }
        AtomicBoolean cancelled = new AtomicBoolean();
        ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle("Загрузка обновления");
        progress.setMessage("После проверки файла Android предложит установить обновление.");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setCancelable(true);
        progress.setOnCancelListener(dialog -> cancelled.set(true));
        progress.setButton(ProgressDialog.BUTTON_NEGATIVE, "Отмена", (dialog, which) -> cancelled.set(true));
        progress.show();
        WORKER.execute(() -> {
            File target = downloaded(activity, release);
            File partial = new File(target.getParentFile(), target.getName() + ".part");
            String error = null;
            try {
                File directory = target.getParentFile();
                if (!directory.isDirectory() && !directory.mkdirs()) throw new UpdateFailure("Не удалось создать файл обновления.");
                if (directory.getUsableSpace() < release.bytes + 32L * 1024 * 1024) throw new UpdateFailure("Для обновления не хватает свободного места на телефоне.");
                HttpURLConnection connection = connect(DOWNLOAD);
                byte[] credential = ("tg:" + password).getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(credential, Base64.NO_WRAP));
                Arrays.fill(credential, (byte) 0);
                try {
                    int code = connection.getResponseCode();
                    if (code == 401) throw new UpdateFailure("Пароль скачивания не подошёл. Проверьте его и повторите попытку.");
                    if (code == 429) throw new UpdateFailure("Слишком много попыток. Повторите загрузку через минуту.");
                    if (code != 200) throw new UpdateFailure("Не удалось скачать обновление. Повторите попытку позже.");
                    long declared = connection.getContentLengthLong();
                    if (declared >= 0 && declared != release.bytes) throw new UpdateFailure("На сайте появилась другая сборка. Проверьте обновления снова.");
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    long count = 0, started = SystemClock.elapsedRealtime();
                    int lastPercent = -1;
                    try (InputStream source = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                        byte[] buffer = new byte[64 * 1024];
                        int length;
                        while ((length = source.read(buffer)) != -1) {
                            if (cancelled.get()) throw new UpdateFailure("");
                            if (SystemClock.elapsedRealtime() - started > 30 * 60 * 1000L) throw new UpdateFailure("Загрузка заняла больше 30 минут. Проверьте сеть и повторите попытку.");
                            count += length;
                            if (count > release.bytes) throw new UpdateFailure("Размер обновления не совпадает с опубликованным.");
                            output.write(buffer, 0, length);
                            digest.update(buffer, 0, length);
                            int percent = (int) (count * 100 / release.bytes);
                            if (percent != lastPercent) { lastPercent = percent; MAIN.post(() -> { if (alive(activity)) progress.setProgress(percent); }); }
                        }
                        output.getFD().sync();
                    }
                    if (count != release.bytes || !hex(digest.digest()).equals(release.sha256)) throw new UpdateFailure("Проверка файла не пройдена. Проверьте обновления и скачайте файл ещё раз.");
                    verifyPackage(activity, release, partial);
                    if (cancelled.get()) throw new UpdateFailure("");
                    if (!partial.renameTo(target)) throw new UpdateFailure("Не удалось сохранить обновление.");
                } finally { connection.disconnect(); }
            } catch (UpdateFailure failure) { error = failure.getMessage(); }
            catch (Exception failure) { error = "Загрузка прервалась. Проверьте интернет и повторите попытку."; }
            finally { partial.delete(); DOWNLOADING.set(false); }
            final String result = error;
            MAIN.post(() -> {
                if (!alive(activity)) return;
                progress.dismiss();
                if (cancelled.get()) return;
                if (result != null) { if (!result.isEmpty()) toast(activity, result); }
                else install(activity, release, target);
            });
        });
    }

    private static void verifyAndInstall(Activity activity, Release release, File file) {
        WORKER.execute(() -> {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = new java.io.FileInputStream(file)) {
                    byte[] buffer = new byte[65536];
                    int length;
                    while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
                }
                if (file.length() != release.bytes || !hex(digest.digest()).equals(release.sha256)) throw new UpdateFailure("Проверка сохранённого файла не пройдена. Повторите загрузку.");
                verifyPackage(activity, release, file);
                MAIN.post(() -> { if (alive(activity)) install(activity, release, file); });
            } catch (Exception failure) {
                file.delete();
                MAIN.post(() -> { if (alive(activity)) toast(activity, "Проверка сохранённого обновления не пройдена. Нажмите «Обновить» и скачайте его снова."); });
            }
        });
    }

    private static void install(Activity activity, Release release, File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity).setTitle("Разрешить обновление")
                    .setMessage("Разрешите TG-ru устанавливать обновления. Затем вернитесь в приложение и нажмите «Продолжить».")
                    .setPositiveButton("Открыть настройки", (dialog, which) -> {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
                            new AlertDialog.Builder(activity).setTitle("Обновление скачано")
                                    .setMessage("После выдачи разрешения продолжите установку.")
                                    .setPositiveButton("Продолжить", (next, button) -> verifyAndInstall(activity, release, apk))
                                    .setNegativeButton("Позже", null).show();
                        } catch (RuntimeException failure) { toast(activity, "Откройте настройки Android и разрешите установку приложений для TG-ru."); }
                    }).setNegativeButton("Позже", null).show();
            return;
        }
        try {
            Uri uri = (Uri) Class.forName("androidx.core.content.FileProvider")
                    .getMethod("getUriForFile", Context.class, String.class, File.class)
                    .invoke(null, activity, activity.getPackageName() + ".provider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception failure) { toast(activity, "Не удалось открыть установщик Android. Повторите попытку."); }
    }

    private static void verifyPackage(Context context, Release release, File file) throws Exception {
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
        if (candidate == null || !PACKAGE.equals(candidate.packageName) || version(candidate) != release.version
                || version(candidate) <= version(installed) || candidate.applicationInfo == null
                || candidate.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT
                || !signers(candidate).equals(signers(installed))) {
            throw new UpdateFailure("Файл не является подходящим обновлением TG-ru.");
        }
    }

    private static Set<String> signers(PackageInfo info) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null
                ? info.signingInfo.getApkContentsSigners() : info.signatures;
        if (signatures == null || signatures.length == 0) throw new UpdateFailure("Подпись обновления отсутствует.");
        Set<String> result = new HashSet<>();
        for (Signature signature : signatures) result.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        return result;
    }

    private static long installedVersion(Context context) {
        try { return version(context.getPackageManager().getPackageInfo(context.getPackageName(), 0)); }
        catch (Exception ignored) { return Long.MAX_VALUE; }
    }

    private static long version(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
    private static File downloaded(Context context, Release release) { return new File(new File(context.getCacheDir(), "updates"), "TG-ru-" + release.version + "-" + release.sha256.substring(0, 12) + ".apk"); }
    private static boolean alive(Activity activity) { return !activity.isFinishing() && !activity.isDestroyed(); }
    private static void toast(Activity activity, String message) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }
    private static String hex(byte[] value) { StringBuilder result = new StringBuilder(); for (byte b : value) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return result.toString(); }

    private static HttpURLConnection connect(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("Accept-Encoding", "identity");
        return connection;
    }

    private static byte[] readBounded(InputStream stream, int maximum) throws Exception {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int size;
            while ((size = input.read(buffer)) != -1) {
                if (output.size() + size > maximum) throw new UpdateFailure("Не удалось проверить сведения об обновлении.");
                output.write(buffer, 0, size);
            }
            return output.toByteArray();
        }
    }

    private static final class Release {
        final long version, bytes;
        final int minSdk;
        final String name, sha256, notes;
        Release(JSONObject json) throws Exception {
            version = json.getLong("version_code");
            bytes = json.getLong("bytes");
            minSdk = json.getInt("min_sdk");
            name = json.getString("version_name");
            sha256 = json.getString("sha256");
            notes = json.optString("notes", "Обновление приложения. Установка по вашему выбору.");
            if (json.getInt("schema") != 1 || !PACKAGE.equals(json.getString("package")) || !DOWNLOAD.equals(json.getString("url"))
                    || version < 1 || version > Integer.MAX_VALUE || bytes < 1024 || bytes > 512L * 1024 * 1024
                    || minSdk < 24 || minSdk > 100 || !name.matches("[A-Za-z0-9._+ -]{1,64}")
                    || !sha256.matches("[a-f0-9]{64}") || notes.length() > 1200) throw new UpdateFailure("Не удалось проверить сведения об обновлении.");
        }
    }

    private static final class UpdateFailure extends Exception { UpdateFailure(String message) { super(message); } }
}
