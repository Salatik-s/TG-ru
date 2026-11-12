package org.telegram.messenger.family;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.telegram.messenger.R;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Store onboarding for the explicitly enabled Android QA build. */
public final class FamilyStoreGateActivity extends FamilyUi {
    private static final String SEEN = "store_welcome_seen";
    private static final String LAUNCH_ACTIVITY = "org.telegram.ui.LaunchActivity";
    private static final ExecutorService REQUESTS = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "TG-ru store activation");
        thread.setDaemon(true);
        return thread;
    });

    private EditText login;
    private EditText password;
    private TextView feedback;
    private Button submit;
    private Button accountAction;
    private boolean working;
    private long nextRefreshAt;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!FamilyStoreAccess.isConfigured(this)) {
            finish();
            return;
        }
        FamilyStoreAccess.ensureRefreshing(this);
        renderStart();
    }

    private void renderStart() {
        if (FamilyStoreAccess.isActive(this)) {
            showSuccess(FamilyStoreAccess.accessUntil(this));
        } else if (FamilyStoreAccess.hasDevice(this)) {
            showAccessStatus();
        } else if (getPreferences(MODE_PRIVATE).getBoolean(SEEN, false)) {
            showActivation();
        } else {
            showWelcome();
        }
    }

    private void showWelcome() {
        beginScreen("Прежде чем начать");
        addParagraph("О приложении", "TG-ru — неофициальный клиент Telegram.");
        addParagraph("Как идёт трафик", "Трафик идёт через сервер TG-ru. Он видит ваш IP, время подключений и объём трафика, но не содержимое сообщений.");
        addParagraph("Что остаётся в Telegram", "Номер телефона, код входа и облачный пароль остаются на вашем телефоне и в Telegram.");
        appendText("Локальная проверка. Кабинет работает на подключённом компьютере.", 14, muted, 20);
        action("Понятно", true).setOnClickListener(view -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean(SEEN, true).apply();
            showActivation();
        });
        action("Подробнее", false).setOnClickListener(view -> openBrowser("/privacy"));
    }

    private void showActivation() {
        beginScreen("Активация TG-ru");
        appendText("Введите логин и пароль кабинета TG-ru. Вход в Telegram будет следующим шагом.", 16, muted, 12);
        login = field("Логин", false, 64);
        login.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        password = field("Пароль", true, 128);
        login.setNextFocusForwardId(password.getId());
        login.setNextFocusDownId(password.getId());
        password.setImeOptions(EditorInfo.IME_ACTION_DONE);
        password.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            activate();
            return true;
        });
        appendText("Пароль нужен только сейчас и не сохраняется на телефоне.", 14, muted, 12);
        feedback = feedback();
        submit = action("Активировать", true);
        submit.setOnClickListener(view -> activate());
        accountAction = action("Создать аккаунт", false);
        accountAction.setOnClickListener(view -> openBrowser("/register"));
    }

    private void showAccessStatus() {
        boolean expired = FamilyStoreAccess.accessUntil(this) <= System.currentTimeMillis() / 1000;
        beginScreen(expired ? "Доступ закончился" : "Проверка доступа");
        appendText(expired
                ? "Для продления доступа обратитесь в поддержку. Контакт есть в кабинете."
                : "Проверьте, что подключённый компьютер доступен, и повторите проверку.", 16, muted, 12);
        feedback = feedback();
        action("Открыть кабинет", true).setOnClickListener(view -> openBrowser("/account"));
        submit = action("Проверить снова", false);
        submit.setOnClickListener(view -> checkAccess());
    }

    private void showSuccess(long expiry) {
        beginScreen("Устройство подключено");
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT,
                Locale.forLanguageTag("ru-RU"));
        appendText("Доступ действует до " + format.format(new Date(expiry * 1000)) + ".", 16, muted, 12);
        appendText("В Telegram можно войти по номеру телефона или открыть уже добавленный аккаунт.", 16, muted, 8);
        feedback = feedback();
        action("Открыть Telegram", true).setOnClickListener(view -> {
            if (!FamilyStoreAccess.isActive(this)) {
                showAccessStatus();
                return;
            }
            Intent intent = new Intent().setClassName(getPackageName(), LAUNCH_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
        action("Открыть кабинет", false).setOnClickListener(view -> openBrowser("/account"));
    }

    private void activate() {
        if (working) return;
        String name = login.getText().toString().trim();
        if (name.isEmpty()) {
            login.setError("Введите логин");
            login.requestFocus();
            return;
        }
        if (password.length() == 0) {
            password.setError("Введите пароль");
            password.requestFocus();
            return;
        }

        String secret = password.getText().toString();
        password.getText().clear();
        hideKeyboard();
        perform("Активировать", app -> FamilyStoreAccess.activate(app, name, secret));
    }

    private void checkAccess() {
        if (working) return;
        long now = SystemClock.elapsedRealtime();
        if (now < nextRefreshAt) {
            showFeedback("Подождите несколько секунд и повторите проверку.", false);
            return;
        }
        nextRefreshAt = now + 10_000;
        perform("Проверить снова", FamilyStoreAccess::refresh);
    }

    private void perform(String label, AccessRequest request) {
        working = true;
        enabled(submit, false);
        submit.setText("Проверяем…");
        showFeedback("", false);
        Context app = getApplicationContext();
        REQUESTS.execute(() -> {
            try {
                long expiry = request.run(app);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    working = false;
                    showSuccess(expiry);
                });
            } catch (FamilyStoreAccess.ActivationFailure failure) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    working = false;
                    submit.setText(label);
                    enabled(submit, true);
                    if ("device_revoked".equals(failure.code) || "device_not_found".equals(failure.code)
                            || "device_key_unavailable".equals(failure.code) && login == null) {
                        showActivation();
                    }
                    if ("subscription_required".equals(failure.code) && accountAction != null) {
                        accountAction.setText("Открыть кабинет");
                        accountAction.setOnClickListener(view -> openBrowser("/account"));
                    }
                    showFeedback(errorMessage(failure.code), true);
                });
            }
        });
    }

    private static String errorMessage(String code) {
        switch (code) {
            case "invalid_credentials": return "Неверный логин или пароль.";
            case "subscription_required": return "Нет доступа. Запустите пробный период в кабинете или обратитесь в поддержку.";
            case "subscription_expired": return "Доступ закончился. Для продления обратитесь в поддержку.";
            case "device_limit": return "Достигнут лимит устройств. Отвяжите одно на сайте.";
            case "device_linked_elsewhere": return "Устройство уже привязано к другому аккаунту TG-ru. Войдите в этот аккаунт.";
            case "device_revoked":
            case "device_not_found": return "Устройство отвязано. Активируйте его снова.";
            case "too_many_attempts": return "Слишком много попыток. Подождите немного и повторите.";
            case "invalid_token":
            case "invalid_proof": return "Запрос активации недействителен. Повторите попытку.";
            case "device_key_unavailable": return "Не удалось подтвердить устройство. Повторите активацию.";
            case "local_storage_unavailable": return "Не удалось сохранить доступ на устройстве. Повторите попытку.";
            case "network_unavailable": return "Нет связи с сервером. Проверьте подключение и повторите.";
            default: return "Не удалось проверить доступ. Повторите попытку.";
        }
    }

    private void openBrowser(String path) {
        if (password != null) password.getText().clear();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(FamilyStoreAccess.LOCAL_ORIGIN + path)));
        } catch (ActivityNotFoundException ignored) {
            if (feedback == null) feedback = feedback();
            showFeedback("Откройте кабинет в браузере на подключённом компьютере.", true);
        }
    }

    private void beginScreen(String title) {
        content.removeAllViews();
        login = null;
        password = null;
        feedback = null;
        submit = null;
        accountAction = null;
        setTitle(title);
        setPasswordProtection(false);
        TextView heading = appendText(title, 28, ink, 12);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
    }

    private void addParagraph(String title, String detail) {
        TextView heading = appendText(title, 16, ink, 20);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        appendText(detail, 16, muted, 4);
    }

    private TextView appendText(String value, int size, int color, int top) {
        TextView view = text(android.R.string.ok, size, color);
        view.setText(value);
        add(view, top);
        return view;
    }

    private TextView feedback() {
        TextView view = appendText("", 14, muted, 12);
        view.setVisibility(View.GONE);
        view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return view;
    }

    private void showFeedback(String value, boolean invalid) {
        feedback.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
        feedback.setTextColor(invalid ? error : muted);
        feedback.setText(value);
    }

    private Button action(String label, boolean filled) {
        Button button = button(android.R.string.ok, filled);
        button.setText(label);
        return button;
    }

    private EditText field(String label, boolean secret, int length) {
        TextView caption = appendText(label, 14, muted, 16);
        EditText field = new EditText(this);
        field.setId(View.generateViewId());
        caption.setLabelFor(field.getId());
        field.setTextSize(16);
        field.setTextColor(ink);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        field.setMinimumHeight(dp(56));
        field.setPaddingRelative(dp(16), dp(12), dp(16), dp(12));
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(length)});
        field.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26) field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, fieldBackground(primary, 2));
        states.addState(new int[]{}, fieldBackground(getColor(R.color.fg_outline), 1));
        field.setBackground(states);
        if (secret) {
            field.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                    setPasswordProtection(value.length() > 0);
                }
                @Override public void afterTextChanged(Editable value) { }
            });
        }
        add(field, 4);
        return field;
    }

    private GradientDrawable fieldBackground(int border, int width) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(container);
        drawable.setStroke(dp(width), border);
        drawable.setCornerRadius(dp(12));
        return drawable;
    }

    private void setPasswordProtection(boolean protect) {
        if (protect) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(content.getWindowToken(), 0);
    }

    private interface AccessRequest {
        long run(Context app) throws FamilyStoreAccess.ActivationFailure;
    }
}
