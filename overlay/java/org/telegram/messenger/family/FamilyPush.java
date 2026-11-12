package org.telegram.messenger.family;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/** Generic host wake signals; Telegram authentication stays inside Telegram. */
public final class FamilyPush {
    static final String ORIGIN = "https://24projects.ru:8443";
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static volatile String endpoint = "", stream = "", token = "";
    static volatile long validUntil;
    static volatile String state = "stopped";
    private static boolean lifecycleRegistered;
    private static final Map<String, Long> registered = new HashMap<>();
    private static final Map<String, Long> retryAt = new HashMap<>();
    private static final Map<String, Long> pending = new HashMap<>();

    private FamilyPush() { }

    public static void configure(Context context, JSONObject config) {
        try {
            String nextEndpoint = config.getString("endpoint");
            String nextStream = config.getString("stream_url");
            String nextToken = config.getString("token");
            URI e = new URI(nextEndpoint), s = new URI(nextStream);
            if (!trusted(e) || !trusted(s) || !"/v1/events".equals(s.getPath())
                    || !e.getPath().matches("/v1/push/[a-f0-9]{64}/[A-Za-z0-9_-]{43}")
                    || !nextToken.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException();
            Context application = context.getApplicationContext();
            boolean changed = !nextEndpoint.equals(endpoint);
            endpoint = nextEndpoint; stream = nextStream; token = nextToken;
            // Local elapsed-time bound cannot be extended by changing wall clock.
            validUntil = SystemClock.elapsedRealtime() + 70_000;
            MAIN.post(() -> {
                if (changed) { registered.clear(); pending.clear(); retryAt.clear(); }
                registerLifecycle(application);
                registerTelegram();
                FamilyPushService.updateCredentials();
                ensureService(application);
            });
        } catch (Exception ignored) { stop(context); state = "invalid_config"; }
    }

    private static boolean trusted(URI u) {
        return "https".equals(u.getScheme()) && "24projects.ru".equals(u.getHost())
                && u.getPort() == 8443 && u.getUserInfo() == null && u.getQuery() == null && u.getFragment() == null;
    }

    static boolean authorized() {
        return validUntil > SystemClock.elapsedRealtime() && !token.isEmpty() && FamilyAccess.isAllowed();
    }

    public static String status() { return state; }

    public static void stop(Context context) {
        token = ""; stream = ""; endpoint = ""; validUntil = 0; state = "stopped";
        FamilyPushService.updateCredentials();
        context.getApplicationContext().stopService(new Intent(context, FamilyPushService.class));
        MAIN.post(() -> { registered.clear(); pending.clear(); retryAt.clear(); });
    }

    private static void registerLifecycle(Context context) {
        if (lifecycleRegistered || !(context instanceof Application)) return;
        lifecycleRegistered = true;
        ((Application) context).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityStarted(Activity a) { if (authorized()) { registerTelegram(); ensureService(a); } }
            public void onActivityStopped(Activity a) { }
            public void onActivityResumed(Activity a) { if (authorized()) ensureService(a); }
            public void onActivityCreated(Activity a, Bundle b) { }
            public void onActivityPaused(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        });
    }

    private static void ensureService(Context context) {
        if (!authorized() || FamilyPushService.running()) return;
        try {
            Intent i = new Intent(context, FamilyPushService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i); else context.startService(i);
        } catch (RuntimeException ignored) { state = "open_app_to_start"; }
    }

    static void onChatsOpened(Activity activity) {
        if (!authorized()) return;
        registerTelegram();
        ensureService(activity);
    }

    private static void registerTelegram() {
        if (!authorized()) return;
        try {
            Class<?> userConfig = Class.forName("org.telegram.messenger.UserConfig");
            Class<?> cm = Class.forName("org.telegram.tgnet.ConnectionsManager");
            Class<?> object = Class.forName("org.telegram.tgnet.TLObject");
            Class<?> delegate = Class.forName("org.telegram.tgnet.RequestDelegate");
            Class<?> requestType = Class.forName("org.telegram.tgnet.tl.TL_account$registerDevice");
            int count = userConfig.getField("MAX_ACCOUNT_COUNT").getInt(null);
            for (int account = 0; account < count; account++) {
                Object config = userConfig.getMethod("getInstance", int.class).invoke(null, account);
                if (!Boolean.TRUE.equals(userConfig.getMethod("isClientActivated").invoke(config))) continue;
                long uid = ((Number) userConfig.getMethod("getClientUserId").invoke(config)).longValue();
                String key = account + ":" + uid;
                long now = SystemClock.elapsedRealtime();
                if (pending.getOrDefault(key, 0L) > now || registered.getOrDefault(key, 0L) > now || retryAt.getOrDefault(key, 0L) > now) continue;
                Object request = requestType.getDeclaredConstructor().newInstance();
                requestType.getField("token_type").setInt(request, 4);
                requestType.getField("token").set(request, endpoint);
                requestType.getField("secret").set(request, new byte[0]);
                requestType.getField("no_muted").setBoolean(request, false);
                String submittedEndpoint = endpoint;
                Object callback = Proxy.newProxyInstance(delegate.getClassLoader(), new Class<?>[]{delegate}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (method.getName().equals("equals")) return args != null && args.length == 1 && proxy == args[0];
                        return "FamilyPushCallback";
                    }
                    if (method.getName().equals("run")) MAIN.post(() -> {
                        pending.remove(key);
                        if (!submittedEndpoint.equals(endpoint)) return;
                        if (args != null && args.length > 0 && args[0] != null && args[0].getClass().getSimpleName().equals("TL_boolTrue")) {
                            registered.put(key, SystemClock.elapsedRealtime() + 23 * 60 * 60 * 1000L);
                        } else { retryAt.put(key, SystemClock.elapsedRealtime() + 60_000); state = "registration_retry"; }
                    });
                    return null;
                });
                Object manager = cm.getMethod("getInstance", int.class).invoke(null, account);
                pending.put(key, now + 60_000); retryAt.put(key, now + 60_000);
                try { cm.getMethod("sendRequest", object, delegate).invoke(manager, request, callback); }
                catch (Exception e) { pending.remove(key); throw e; }
            }
        } catch (Exception ignored) { state = "registration_unavailable"; }
    }

    static void wake() {
        if (!authorized()) return;
        MAIN.post(() -> {
            if (!authorized()) return;
            try {
                Class<?> uc = Class.forName("org.telegram.messenger.UserConfig"), cm = Class.forName("org.telegram.tgnet.ConnectionsManager");
                int count = uc.getField("MAX_ACCOUNT_COUNT").getInt(null);
                for (int a = 0; a < count; a++) {
                    Object config = uc.getMethod("getInstance", int.class).invoke(null, a);
                    if (!Boolean.TRUE.equals(uc.getMethod("isClientActivated").invoke(config))) continue;
                    cm.getMethod("onInternalPushReceived", int.class).invoke(null, a);
                    Object manager = cm.getMethod("getInstance", int.class).invoke(null, a);
                    cm.getMethod("resumeNetworkMaybe").invoke(manager);
                }
            } catch (Exception ignored) { state = "fetch_unavailable"; }
        });
    }

    static boolean hasActiveAccount() {
        try {
            Class<?> uc = Class.forName("org.telegram.messenger.UserConfig");
            int count = uc.getField("MAX_ACCOUNT_COUNT").getInt(null);
            for (int a = 0; a < count; a++) {
                Object config = uc.getMethod("getInstance", int.class).invoke(null, a);
                if (Boolean.TRUE.equals(uc.getMethod("isClientActivated").invoke(config))) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }
}
