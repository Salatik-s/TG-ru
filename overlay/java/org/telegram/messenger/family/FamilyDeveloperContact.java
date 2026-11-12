package org.telegram.messenger.family;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

/** The owner changes this shared contact through an installation-signed server request. */
public final class FamilyDeveloperContact {
    private static final String DEFAULT_USERNAME = "prince_skazki";
    private static final String PREFERENCES = "familygram_developer_contact";
    private FamilyDeveloperContact() { }

    public static String username(Context context) {
        String value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString("username", DEFAULT_USERNAME);
        return valid(value) ? value : DEFAULT_USERNAME;
    }

    static void update(Context context, String value) {
        if (context != null && valid(value) && !value.equals(username(context))) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                    .putString("username", value).apply();
        }
    }

    private static boolean valid(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_]{3,31}");
    }

    public static void open(Activity activity) {
        if (!FamilyAccess.isAllowed()) { FamilyBinaryGuard.redirect(activity); return; }
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://t.me/" + username(activity) + "?profile"));
        intent.setClassName(activity.getPackageName(), "org.telegram.ui.LaunchActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
    }

    public static void edit(Activity activity) {
        if (!FamilyAccess.isPhoneOwner()) {
            Toast.makeText(activity, "Изменить контакт может только владелец приложения", Toast.LENGTH_LONG).show();
            return;
        }
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        input.setHint("@username или https://t.me/username");
        input.setText("@" + username(activity));
        input.setSelection(input.length());
        LinearLayout layout = new LinearLayout(activity);
        int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding / 2, padding, 0);
        layout.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Контакт разработчика")
                .setMessage("Этот профиль будет открываться у всех пользователей TG-ru.")
                .setView(layout)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = input.getText().toString().trim();
            if (value.startsWith("https://t.me/")) value = value.substring(13);
            else if (value.startsWith("t.me/")) value = value.substring(5);
            else if (value.startsWith("@")) value = value.substring(1);
            if (!valid(value)) {
                input.setError("Введите имя пользователя Telegram, например @prince_skazki");
                return;
            }
            input.setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            final String selected = value;
            FamilyAccess.manage("set_developer", selected, 24, (result, error) -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (error.isEmpty() && result != null && selected.equals(result.optString("developer_username"))) {
                    update(activity, selected);
                    dialog.dismiss();
                    Toast.makeText(activity, "Контакт сохранён для всех пользователей", Toast.LENGTH_LONG).show();
                    return;
                }
                if (!dialog.isShowing()) return;
                input.setEnabled(true);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                if ("owner_required".equals(error)) input.setError("Изменение доступно только телефону владельца");
                else if ("invalid_management".equals(error) || "management_unavailable".equals(error))
                    input.setError("Для изменения контакта требуется обновить сервис на 24projects.ru");
                else input.setError("Не удалось сохранить. Проверьте интернет и повторите попытку");
            });
        }));
        dialog.show();
    }
}