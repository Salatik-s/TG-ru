package org.telegram.messenger.family;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashSet;
import java.util.Iterator;

/** Uses Telegram's mention search and mention entities in the forward comment editor. */
public final class FamilyForwardMentions implements TextWatcher, ViewTreeObserver.OnPreDrawListener {
    private final Object sheet;
    private final boolean selector;
    private final EditText editor;
    private final PopupWindow popup;
    private final ArrayList<Object> users = new ArrayList<>();
    private final SuggestionRows rows = new SuggestionRows();
    private final int account;
    private final Object controller;
    private final int foregroundColor, secondaryColor, backgroundColor;
    private Object adapter, notificationCenter, observer;
    private long dialogId, topicId;
    private int cursor = -1, generation;
    private String searched = "";
    private boolean closed, applying;

    /** Keep one row per Telegram peer when local and server results overlap. */
    public static void deduplicate(ArrayList<Object> results) {
        if (results == null || results.size() < 2) return;
        try {
            Class<?> user = Class.forName("org.telegram.tgnet.TLRPC$User");
            Class<?> chat = Class.forName("org.telegram.tgnet.TLRPC$Chat");
            Field userId = user.getField("id");
            Field chatId = chat.getField("id");
            HashSet<Long> seenUsers = new HashSet<>();
            HashSet<Long> seenChats = new HashSet<>();
            Iterator<Object> rows = results.iterator();
            while (rows.hasNext()) {
                Object row = rows.next();
                if (user.isInstance(row) && !seenUsers.add(userId.getLong(row))) rows.remove();
                else if (chat.isInstance(row) && !seenChats.add(chatId.getLong(row))) rows.remove();
            }
        } catch (ReflectiveOperationException ignored) { }
    }

    public static void refresh(Object sheet) {
        refresh(sheet, false);
    }

    public static void refreshSelector(Object fragment) {
        refresh(fragment, true);
    }

    private static void refresh(Object sheet, boolean selector) {
        try {
            Field field = field(sheet, "familyForwardMentions");
            FamilyForwardMentions helper = (FamilyForwardMentions) field.get(sheet);
            Object comment = field(sheet, selector ? "commentView" : "commentTextView").get(sheet);
            if (comment == null) return;
            EditText editor = (EditText) comment.getClass().getMethod(selector ? "getEditField" : "getEditText").invoke(comment);
            if (helper == null || helper.editor != editor) {
                if (helper != null) close(sheet);
                helper = new FamilyForwardMentions(sheet, editor, selector);
                field.set(sheet, helper);
            }
            helper.search(false);
        } catch (ReflectiveOperationException | RuntimeException error) {
            android.util.Log.w("TGruMentions", "Forward mention editor could not initialize", error);
        }
    }

    public static void close(Object sheet) {
        try {
            Field field = field(sheet, "familyForwardMentions");
            FamilyForwardMentions helper = (FamilyForwardMentions) field.get(sheet);
            if (helper == null) return;
            // Keep the closed helper attached: queued native text callbacks can
            // still updateSelectedCount after the sheet has been dismissed.
            helper.closed = true;
            helper.editor.removeTextChangedListener(helper);
            if (helper.editor.getViewTreeObserver().isAlive())
                helper.editor.getViewTreeObserver().removeOnPreDrawListener(helper);
            helper.destroyAdapter();
            helper.popup.dismiss();
        } catch (ReflectiveOperationException | RuntimeException error) {
            android.util.Log.w("TGruMentions", "Forward mention cleanup failed", error);
        }
    }

    private FamilyForwardMentions(Object sheet, EditText editor, boolean selector) throws ReflectiveOperationException {
        this.sheet = sheet;
        this.editor = editor;
        this.selector = selector;
        account = field(sheet, "currentAccount").getInt(sheet);
        controller = Class.forName("org.telegram.messenger.MessagesController")
                .getMethod("getInstance", int.class).invoke(null, account);
        foregroundColor = color("key_windowBackgroundWhiteBlackText", 0xff202020);
        secondaryColor = color("key_windowBackgroundWhiteGrayText", 0xff707070);
        backgroundColor = color("key_windowBackgroundWhite", 0xffffffff);
        ListView list = new ListView(editor.getContext());
        list.setDividerHeight(0);
        list.setBackgroundColor(backgroundColor);
        list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> insert(position));
        popup = new PopupWindow(list, dp(280), dp(56), false);
        popup.setBackgroundDrawable(new ColorDrawable(backgroundColor));
        popup.setElevation(dp(8));
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
        popup.setOutsideTouchable(true);
        editor.addTextChangedListener(this);
        editor.getViewTreeObserver().addOnPreDrawListener(this);
    }

    private void search(boolean force) throws ReflectiveOperationException {
        if (closed || applying) return;
        Object selected = field(sheet, "selectedDialogs").get(sheet);
        int count = selector ? ((ArrayList<?>) selected).size()
                : (Integer) selected.getClass().getMethod("size").invoke(selected);
        long nextDialog = 0, nextTopic = 0;
        if (count == 1) {
            if (selector) nextDialog = (Long) ((ArrayList<?>) selected).get(0);
            else {
                Object dialog = selected.getClass().getMethod("valueAt", int.class).invoke(selected, 0);
                nextDialog = field(dialog, "id").getLong(dialog);
                Object topic = ((Map<?, ?>) field(sheet, "selectedDialogTopics").get(sheet)).get(dialog);
                if (topic != null) nextTopic = field(topic, "id").getInt(topic);
            }
        }
        boolean destinationChanged = nextDialog != dialogId || nextTopic != topicId;
        if (destinationChanged) {
            destroyAdapter();
            dialogId = nextDialog;
            topicId = nextTopic;
        }
        String text = editor.getText().toString();
        int selection = editor.getSelectionStart();
        if (!force && !destinationChanged && text.equals(searched) && cursor == selection) return;
        searched = text;
        cursor = selection;
        if (dialogId >= 0 || count != 1 || !editor.hasFocus() || !mentionAtCursor(text, selection)) {
            popup.dismiss();
            if (adapter != null) invokeSearch("");
            return;
        }
        if (adapter == null) createAdapter();
        if (adapter != null) invokeSearch(editor.getText());
    }

    private void createAdapter() throws ReflectiveOperationException {
        Object chat = controller.getClass().getMethod("getChat", Long.class).invoke(controller, -dialogId);
        if (chat == null) return;
        Class<?> type = Class.forName("org.telegram.ui.Adapters.MentionsAdapter");
        Class<?> delegate = Class.forName("org.telegram.ui.Adapters.MentionsAdapter$MentionsAdapterDelegate");
        final int createdGeneration = ++generation;
        Object callback = Proxy.newProxyInstance(delegate.getClassLoader(), new Class<?>[]{delegate}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
            if ("needChangePanelVisibility".equals(method.getName())) {
                boolean show = Boolean.TRUE.equals(args[0]);
                editor.post(() -> { if (!closed && generation == createdGeneration) showResults(show); });
            }
            return null;
        });
        Class<?> resources = Class.forName("org.telegram.ui.ActionBar.Theme$ResourcesProvider");
        adapter = type.getConstructor(Context.class, boolean.class, long.class, long.class, delegate, resources, boolean.class)
                .newInstance(editor.getContext(), false, dialogId, topicId, callback, null, false);
        type.getMethod("setNeedBotContext", boolean.class).invoke(adapter, false);
        type.getMethod("setAllowStickers", boolean.class).invoke(adapter, false);
        type.getMethod("setUserOrChat", Class.forName("org.telegram.tgnet.TLRPC$User"),
                Class.forName("org.telegram.tgnet.TLRPC$Chat")).invoke(adapter, null, chat);
        Object info = controller.getClass().getMethod("getChatFull", long.class).invoke(controller, -dialogId);
        boolean loadInfo = info == null;
        if (info == null) {
            info = Class.forName("org.telegram.tgnet.TLRPC$TL_chatFull").getConstructor().newInstance();
            field(info, "id").setLong(info, -dialogId);
        }
        type.getMethod("setChatInfo", Class.forName("org.telegram.tgnet.TLRPC$ChatFull")).invoke(adapter, info);
        field(adapter, "currentAccount").setInt(adapter, account);
        if (loadInfo) {
            Class<?> center = Class.forName("org.telegram.messenger.NotificationCenter");
            Class<?> listener = Class.forName("org.telegram.messenger.NotificationCenter$NotificationCenterDelegate");
            notificationCenter = center.getMethod("getInstance", int.class).invoke(null, account);
            int event = center.getField("chatInfoDidLoad").getInt(null);
            observer = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                if (closed || generation != createdGeneration || adapter == null) return null;
                Object[] eventArguments = (Object[]) args[2];
                if (eventArguments.length == 0 || field(eventArguments[0], "id").getLong(eventArguments[0]) != -dialogId)
                    return null;
                Object currentInfo = controller.getClass().getMethod("getChatFull", long.class).invoke(controller, -dialogId);
                if (currentInfo != null) {
                    type.getMethod("setChatInfo", Class.forName("org.telegram.tgnet.TLRPC$ChatFull")).invoke(adapter, currentInfo);
                    field(adapter, "currentAccount").setInt(adapter, account);
                    search(true);
                    center.getMethod("removeObserver", listener, int.class).invoke(notificationCenter, observer, event);
                    observer = null;
                }
                return null;
            });
            center.getMethod("addObserver", listener, int.class).invoke(notificationCenter, observer, event);
            controller.getClass().getMethod("loadFullChat", long.class, int.class, boolean.class)
                    .invoke(controller, -dialogId, 0, false);
        }
    }

    private void invokeSearch(CharSequence text) throws ReflectiveOperationException {
        adapter.getClass().getMethod("searchUsernameOrHashtag", CharSequence.class, int.class,
                ArrayList.class, boolean.class, boolean.class).invoke(adapter, text,
                text.length() == 0 ? 0 : editor.getSelectionStart(), new ArrayList<>(), false, false);
    }

    private void destroyAdapter() throws ReflectiveOperationException {
        generation++;
        popup.dismiss();
        users.clear();
        if (adapter != null) {
            invokeSearch("");
            adapter.getClass().getMethod("onDestroy").invoke(adapter);
            adapter = null;
        }
        if (notificationCenter != null && observer != null) {
            Class<?> center = notificationCenter.getClass();
            center.getMethod("removeObserver", Class.forName("org.telegram.messenger.NotificationCenter$NotificationCenterDelegate"), int.class)
                    .invoke(notificationCenter, observer, center.getField("chatInfoDidLoad").getInt(null));
        }
        notificationCenter = null;
        observer = null;
    }

    private void showResults(boolean show) {
        try {
            if (!show || adapter == null || !editor.hasFocus() || !editor.isAttachedToWindow()
                    || !mentionAtCursor(editor.getText().toString(), editor.getSelectionStart())) {
                popup.dismiss();
                return;
            }
            users.clear();
            int count = (Integer) adapter.getClass().getMethod("getItemCount").invoke(adapter);
            Class<?> userType = Class.forName("org.telegram.tgnet.TLRPC$User");
            for (int i = 0; i < count; i++) {
                Object user = adapter.getClass().getMethod("getItem", int.class).invoke(adapter, i);
                if (userType.isInstance(user)) users.add(user);
            }
            rows.notifyDataSetChanged();
            if (users.isEmpty()) { popup.dismiss(); return; }
            int height = dp(56) * Math.min(users.size(), 4);
            int width = Math.max(dp(180), editor.getWidth());
            if (popup.isShowing()) popup.update(editor, 0, -editor.getHeight() - height, width, height);
            else {
                popup.setWidth(width);
                popup.setHeight(height);
                popup.showAsDropDown(editor, 0, -editor.getHeight() - height);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            popup.dismiss();
            android.util.Log.w("TGruMentions", "Forward mention results failed", error);
        }
    }

    private void insert(int position) {
        if (position < 0 || position >= users.size() || adapter == null) return;
        try {
            int start = (Integer) adapter.getClass().getMethod("getResultStartPosition").invoke(adapter);
            int length = (Integer) adapter.getClass().getMethod("getResultLength").invoke(adapter);
            if (start < 0 || start + length > editor.length() || editor.getSelectionStart() != start + length) return;
            Object user = users.get(position);
            String username = username(user);
            CharSequence insertion;
            if (username != null && !username.isEmpty()) insertion = "@" + username + " ";
            else {
                SpannableString mention = new SpannableString(name(user) + " ");
                Object span = Class.forName("org.telegram.ui.Components.URLSpanUserMention")
                        .getConstructor(String.class, int.class).newInstance(Long.toString(field(user, "id").getLong(user)), 3);
                mention.setSpan(span, 0, mention.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                insertion = mention;
            }
            applying = true;
            editor.getText().replace(start, start + length, insertion);
            editor.setSelection(start + insertion.length());
            popup.dismiss();
        } catch (ReflectiveOperationException | RuntimeException error) {
            android.util.Log.w("TGruMentions", "Forward mention insertion failed", error);
        } finally { applying = false; }
    }

    private static boolean mentionAtCursor(String text, int cursor) {
        if (cursor < 1 || cursor > text.length()) return false;
        for (int i = cursor - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '@') return i == 0 || Character.isWhitespace(text.charAt(i - 1));
            if (Character.isWhitespace(c)) return false;
        }
        return false;
    }

    private String name(Object user) throws ReflectiveOperationException {
        String first = (String) field(user, "first_name").get(user);
        String last = (String) field(user, "last_name").get(user);
        String value = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return value.isEmpty() ? "Участник" : value;
    }

    private String username(Object user) throws ReflectiveOperationException {
        return (String) Class.forName("org.telegram.messenger.UserObject")
                .getMethod("getPublicUsername", Class.forName("org.telegram.tgnet.TLRPC$User")).invoke(null, user);
    }

    private static Field field(Object target, String name) throws NoSuchFieldException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private int dp(int value) { return Math.round(value * editor.getResources().getDisplayMetrics().density); }

    private static int color(String name, int fallback) {
        try { Class<?> theme = Class.forName("org.telegram.ui.ActionBar.Theme");
            return (Integer) theme.getMethod("getColor", int.class).invoke(null, theme.getField(name).getInt(null));
        } catch (ReflectiveOperationException ignored) { return fallback; }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
        if ("equals".equals(method.getName())) return proxy == args[0];
        return "TGruForwardMentionListener";
    }

    @Override public boolean onPreDraw() {
        if (cursor != editor.getSelectionStart()) refresh(sheet, selector);
        return true;
    }
    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
    @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
    @Override public void afterTextChanged(Editable text) { refresh(sheet, selector); }

    private final class SuggestionRows extends BaseAdapter {
        @Override public int getCount() { return users.size(); }
        @Override public Object getItem(int position) { return users.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row = recycled instanceof LinearLayout ? (LinearLayout) recycled : new LinearLayout(editor.getContext());
            if (row.getChildCount() == 0) {
                row.setOrientation(LinearLayout.VERTICAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(4), dp(16), dp(4));
                row.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, dp(56)));
                TextView title = new TextView(editor.getContext());
                title.setTextSize(16); title.setTextColor(foregroundColor); title.setSingleLine(true);
                TextView subtitle = new TextView(editor.getContext());
                subtitle.setTextSize(13); subtitle.setTextColor(secondaryColor); subtitle.setSingleLine(true);
                row.addView(title); row.addView(subtitle);
            }
            try {
                Object user = users.get(position);
                ((TextView) row.getChildAt(0)).setText(name(user));
                String username = username(user);
                ((TextView) row.getChildAt(1)).setText(username == null || username.isEmpty() ? "Упомянуть участника" : "@" + username);
            } catch (ReflectiveOperationException error) {
                ((TextView) row.getChildAt(0)).setText("Участник");
                ((TextView) row.getChildAt(1)).setText("");
            }
            return row;
        }
    }
}
