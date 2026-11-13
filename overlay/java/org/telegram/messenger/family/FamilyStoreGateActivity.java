package org.telegram.messenger.family;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Store onboarding for the explicitly enabled Android QA build. */
public final class FamilyStoreGateActivity extends FamilyUi {
    private static final String SEEN = "store_welcome_seen";
    private static final String PRIVACY_OPEN = "store_privacy_open";
    private static final String LAUNCH_ACTIVITY = "org.telegram.ui.LaunchActivity";
    private static final int INK = 0xFFE8ECF1;
    private static final int MUTED = 0xFF9AA5B1;
    private static final int BACKGROUND = 0xFF0F1318;
    private static final int PANEL = 0xFF171C23;
    private static final int BORDER = 0xFF2A323C;
    private static final int FIELD_BORDER = 0xFF606F80;
    private static final int BLUE = 0xFF6FA3FF;
    private static final int ON_BLUE = 0xFF0B1220;
    private static final int GREEN = 0xFF4CC38A;
    private static final int RED = 0xFFFF8989;
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
    private boolean privacyOpen;
    private long nextRefreshAt;
    private ScrollView scroll;
    private View glow;
    private boolean waitingForAccess;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable watchAccess = new Runnable() {
        @Override public void run() {
            if (!waitingForAccess) return;
            if (!working && FamilyStoreAccess.isActive(FamilyStoreGateActivity.this)) {
                openTelegram();
                return;
            }
            if (!working && !FamilyStoreAccess.hasDevice(FamilyStoreGateActivity.this)) {
                showActivation();
                return;
            }
            main.postDelayed(this, 1_000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!FamilyStoreAccess.isConfigured(this)) {
            finish();
            return;
        }
        buildLayout();
        FamilyStoreAccess.ensureRefreshing(this);
        if (state != null && state.getBoolean(PRIVACY_OPEN)) showPrivacy();
        else renderStart();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(PRIVACY_OPEN, privacyOpen);
    }

    @Override public void onBackPressed() {
        if (privacyOpen) showWelcome();
        else super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        if (waitingForAccess) {
            main.removeCallbacks(watchAccess);
            main.post(watchAccess);
        }
    }

    @Override protected void onPause() {
        main.removeCallbacks(watchAccess);
        super.onPause();
    }

    private void renderStart() {
        if (FamilyStoreAccess.isActive(this)) {
            openTelegram();
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
        mark(false);
        title("Прежде чем начать", "Коротко о том, как работает TG-ru.", 24);
        card(Icon.INFO, "О приложении", "TG-ru — неофициальный клиент Telegram.");
        card(Icon.ARROW, "Как идёт трафик", "При подключении через шлюз TG-ru он видит ваш IP, время подключения и объём трафика. Содержимое сообщений зашифровано.");
        card(Icon.LOCK, "Что остаётся в Telegram", "Номер телефона, код входа и облачный пароль вы вводите отдельно при входе в Telegram.");
        feedback = feedback();
        bottomActions();
        action("Понятно", true).setOnClickListener(view -> acceptWelcome());
        action("Подробнее", false).setOnClickListener(view -> showPrivacy());
    }

    private void acceptWelcome() {
        getPreferences(MODE_PRIVATE).edit().putBoolean(SEEN, true).apply();
        showActivation();
    }

    private void showPrivacy() {
        beginScreen("Что мы видим, а чего нет");
        privacyOpen = true;
        glow.setVisibility(View.GONE);
        Button back = makeAction("Назад", false, false);
        back.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        back.setPaddingRelative(0, dp(12), dp(16), dp(12));
        content.addView(back, new LinearLayout.LayoutParams(-2, -2));
        back.setOnClickListener(view -> showWelcome());
        title("Что мы видим, а чего нет", "Какие данные видят кабинет и шлюз TG-ru.", 16);
        sectionLabel("Видит TG-ru", GREEN);
        card(Icon.EYE, "IP, время, объём трафика", "Эти данные доступны шлюзу при подключении.");
        card(Icon.PHONE, "Устройство и доступ", "Кабинет хранит логин, срок доступа и открытый ключ привязанного устройства.");
        sectionLabel("Не видит", RED);
        card(Icon.LOCK, "Сообщения и вход", "Переписка зашифрована. Кабинет TG-ru не запрашивает номер телефона, код входа и облачный пароль Telegram.");
        feedback = feedback();
        bottomActions();
        action("Понятно", true).setOnClickListener(view -> acceptWelcome());
        Button details = makeAction("Подробная информация на сайте", false, false);
        add(details, 12);
        details.setOnClickListener(view -> openBrowser("/visibility"));
    }

    private void showActivation() {
        beginScreen("Активация TG-ru");
        mark(false);
        title("Активация TG-ru", "Введите логин и пароль кабинета TG-ru. Вход в Telegram будет следующим шагом.", 24);
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
        appendText("Пароль нужен только сейчас и не сохраняется на телефоне.", 13, MUTED, 12);
        feedback = feedback();
        bottomActions();
        submit = action("Активировать", true);
        submit.setOnClickListener(view -> activate());
        accountAction = action("Создать аккаунт", false);
        accountAction.setOnClickListener(view -> openBrowser("/register"));
    }

    private void showAccessStatus() {
        boolean expired = FamilyStoreAccess.accessUntil(this) <= System.currentTimeMillis() / 1000;
        String heading = expired ? "Доступ закончился" : "Проверка доступа";
        beginScreen(heading);
        waitingForAccess = true;
        main.post(watchAccess);
        mark(false);
        title(heading, expired
                ? "Для продления доступа обратитесь в поддержку. Контакт есть в кабинете."
                : "Когда появится интернет, приложение подключится автоматически.", 24);
        feedback = feedback();
        bottomActions();
        action("Открыть кабинет", true).setOnClickListener(view -> openBrowser("/account"));
        submit = action("Проверить снова", false);
        submit.setOnClickListener(view -> checkAccess());
    }

    private void showSuccess(long expiry) {
        beginScreen("Устройство подключено");
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT,
                Locale.forLanguageTag("ru-RU"));
        mark(true);
        title("Устройство подключено", "Доступ действует до " + format.format(new Date(expiry * 1000)) + ".", 24);
        card(Icon.PHONE, "Дальше", "В Telegram можно войти по номеру телефона или открыть уже добавленный аккаунт.");
        feedback = feedback();
        bottomActions();
        action("Открыть Telegram", true).setOnClickListener(view -> openTelegram());
        action("Открыть кабинет", false).setOnClickListener(view -> openBrowser("/account"));
    }

    private void openTelegram() {
        if (!FamilyStoreAccess.isActive(this)) {
            showAccessStatus();
            return;
        }
        Intent intent = new Intent().setClassName(getPackageName(), LAUNCH_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
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
            case "gateway_unavailable": return "Шлюз TG-ru пока недоступен. Повторите попытку через несколько секунд.";
            case "gateway_profile_invalid": return "Не удалось проверить настройки шлюза TG-ru.";
            case "network_unavailable": return "Нет связи с сервером. Проверьте подключение и повторите.";
            default: return "Не удалось проверить доступ. Повторите попытку.";
        }
    }

    private void openBrowser(String path) {
        if (password != null) password.getText().clear();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(FamilyStoreAccess.origin() + path)));
        } catch (ActivityNotFoundException ignored) {
            if (feedback == null) feedback = feedback();
            showFeedback("Откройте кабинет в другом браузере.", true);
        }
    }

    private void beginScreen(String title) {
        waitingForAccess = false;
        main.removeCallbacks(watchAccess);
        content.removeAllViews();
        content.requestFocus();
        scroll.scrollTo(0, 0);
        glow.setVisibility(View.VISIBLE);
        privacyOpen = false;
        login = null;
        password = null;
        feedback = null;
        submit = null;
        accountAction = null;
        setTitle(title);
        setPasswordProtection(false);
    }

    private void buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);
        root.setFitsSystemWindows(true);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility()
                & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets system = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
                view.setPadding(system.left, system.top, system.right, system.bottom);
                return insets;
            });
        }

        glow = new View(this);
        glow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        GradientDrawable light = new GradientDrawable();
        light.setColors(new int[]{0x336FA3FF, 0x006FA3FF});
        light.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        light.setGradientRadius(dp(126));
        glow.setBackground(light);
        FrameLayout.LayoutParams lightParams = new FrameLayout.LayoutParams(dp(360), dp(360));
        lightParams.leftMargin = -dp(80);
        lightParams.topMargin = -dp(160);
        root.addView(glow, lightParams);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setFocusableInTouchMode(true);
        int width = getResources().getConfiguration().screenWidthDp;
        int gutter = Math.max(width < 350 ? 16 : 24, (width - 552) / 2);
        content.setPaddingRelative(dp(gutter), dp(44), dp(gutter), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        root.requestApplyInsets();
    }

    private void mark(boolean success) {
        View view;
        int size;
        if (success) {
            size = 72;
            view = new IconView(Icon.CHECK, GREEN, 36);
            view.setBackground(shape(0x1F4CC38A, 36, Color.TRANSPARENT, 0));
        } else {
            size = 56;
            TextView brand = label("TG", 20, ON_BLUE);
            brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            brand.setGravity(Gravity.CENTER);
            brand.setLetterSpacing(-0.025f);
            brand.setBackground(shape(BLUE, 16, Color.TRANSPARENT, 0));
            view = brand;
        }
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(view, new LinearLayout.LayoutParams(dp(size), dp(size)));
    }

    private void title(String title, String summary, int top) {
        TextView heading = appendText(title, 30, INK, top);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setLetterSpacing(-0.02f);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        appendText(summary, 15, MUTED, 8);
    }

    private void sectionLabel(String title, int color) {
        TextView heading = appendText(title, 13, color, 24);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
    }

    private void card(Icon icon, String title, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPaddingRelative(dp(16), dp(16), dp(16), dp(16));
        row.setBackground(shape(PANEL, 16, BORDER, 1));

        IconView badge = new IconView(icon, BLUE, 20);
        badge.setBackground(shape(0x1F6FA3FF, 12, Color.TRANSPARENT, 0));
        row.addView(badge, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView heading = label(title, 15, INK);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labels.addView(heading);
        TextView body = label(detail, 14, MUTED);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.topMargin = dp(2);
        labels.addView(body, bodyParams);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, -2, 1f);
        labelsParams.setMarginStart(dp(16));
        row.addView(labels, labelsParams);
        add(row, 12);
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(3), 1f);
        return view;
    }

    private TextView appendText(String value, int size, int color, int top) {
        TextView view = label(value, size, color);
        add(view, top);
        return view;
    }

    private TextView feedback() {
        TextView view = appendText("", 14, MUTED, 12);
        view.setVisibility(View.GONE);
        view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return view;
    }

    private void showFeedback(String value, boolean invalid) {
        feedback.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
        feedback.setTextColor(invalid ? RED : MUTED);
        feedback.setText(value);
    }

    private void bottomActions() {
        content.addView(new Space(this), new LinearLayout.LayoutParams(-1, dp(12), 1f));
    }

    private Button action(String label, boolean filled) {
        Button button = makeAction(label, filled, !filled);
        add(button, 12);
        return button;
    }

    private Button makeAction(String title, boolean filled, boolean outline) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTextColor(filled ? ON_BLUE : BLUE);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(52));
        button.setMinimumHeight(dp(52));
        button.setPaddingRelative(dp(16), dp(12), dp(16), dp(12));
        button.setStateListAnimator(null);
        button.setBackgroundTintList(null);
        int fill = filled ? BLUE : Color.TRANSPARENT;
        int border = outline ? FIELD_BORDER : Color.TRANSPARENT;
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, shape(fill, 14, filled ? INK : BLUE, 2));
        states.addState(new int[]{}, shape(fill, 14, border, outline ? 1 : 0));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(filled ? 0x330B1220 : 0x336FA3FF), states,
                shape(Color.WHITE, 14, Color.TRANSPARENT, 0)));
        return button;
    }

    private EditText field(String label, boolean secret, int length) {
        TextView caption = appendText(label, 13, MUTED, 20);
        caption.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        EditText field = new EditText(this);
        field.setId(View.generateViewId());
        caption.setLabelFor(field.getId());
        field.setTextSize(16);
        field.setTextColor(INK);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        field.setTypeface(Typeface.DEFAULT);
        field.setMinimumHeight(dp(52));
        field.setPaddingRelative(dp(16), dp(12), dp(16), dp(12));
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(length)});
        field.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26) field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, shape(PANEL, 14, BLUE, 2));
        states.addState(new int[]{}, shape(PANEL, 14, FIELD_BORDER, 1));
        field.setBackgroundTintList(null);
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
        add(field, 8);
        return field;
    }

    private GradientDrawable shape(int fill, int radius, int border, int width) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        if (width > 0) drawable.setStroke(dp(width), border);
        drawable.setCornerRadius(dp(radius));
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

    private enum Icon { INFO, ARROW, LOCK, CHECK, PHONE, EYE }

    private final class IconView extends View {
        private final Icon icon;
        private final int size;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        IconView(Icon icon, int color, int size) {
            super(FamilyStoreGateActivity.this);
            this.icon = icon;
            this.size = dp(size);
            stroke.setColor(color);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1.8f);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
            canvas.scale(size / 24f, size / 24f);
            switch (icon) {
                case INFO:
                    canvas.drawCircle(12, 12, 9, stroke);
                    canvas.drawLine(12, 8, 12, 12.5f, stroke);
                    canvas.drawCircle(12, 16, 0.25f, stroke);
                    break;
                case ARROW:
                    canvas.drawLine(4, 12, 20, 12, stroke);
                    drawLinePath(canvas, 14, 6, 20, 12, 14, 18);
                    break;
                case LOCK:
                    canvas.drawRoundRect(5, 11, 19, 20, 2, 2, stroke);
                    canvas.drawArc(8, 4, 16, 12, 180, 180, false, stroke);
                    canvas.drawLine(8, 8, 8, 11, stroke);
                    canvas.drawLine(16, 8, 16, 11, stroke);
                    break;
                case CHECK:
                    drawLinePath(canvas, 5, 12.5f, 9.5f, 17, 19, 7.5f);
                    break;
                case PHONE:
                    canvas.drawRoundRect(7, 2.5f, 17, 21.5f, 2.5f, 2.5f, stroke);
                    canvas.drawLine(11, 18.5f, 13, 18.5f, stroke);
                    break;
                case EYE:
                    path.reset();
                    path.moveTo(2, 12);
                    path.cubicTo(2, 12, 6, 5, 12, 5);
                    path.cubicTo(18, 5, 22, 12, 22, 12);
                    path.cubicTo(22, 12, 18, 19, 12, 19);
                    path.cubicTo(6, 19, 2, 12, 2, 12);
                    canvas.drawPath(path, stroke);
                    canvas.drawCircle(12, 12, 3, stroke);
                    break;
            }
            canvas.restore();
        }

        private void drawLinePath(Canvas canvas, float x1, float y1, float x2, float y2, float x3, float y3) {
            path.reset();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            path.lineTo(x3, y3);
            canvas.drawPath(path, stroke);
        }
    }

    private interface AccessRequest {
        long run(Context app) throws FamilyStoreAccess.ActivationFailure;
    }
}
