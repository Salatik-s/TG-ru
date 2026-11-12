package org.telegram.messenger.family;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

/** Process-local connection state and opt-in, installation-private diagnostics. */
public final class FamilyConnectionMonitor {
    private static final int MAX_LOG_CHARS = 32_768;
    private static final long DIAGNOSTIC_MS = 24 * 60 * 60 * 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor();
    private static volatile ConnectivityManager connectivity;
    private static volatile SharedPreferences log;
    private static volatile int telegramState;
    private static boolean networkWasAvailable;
    private static String lastAccess = "";
    private static String lastNetwork = "";

    private FamilyConnectionMonitor() { }

    static void init(Context context) {
        log = context.getSharedPreferences("fg_connection_diagnostics", Context.MODE_PRIVATE);
        connectivity = context.getSystemService(ConnectivityManager.class);
        networkWasAvailable = hasNetwork();
        if (connectivity != null) {
            try {
                connectivity.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network network) { MAIN.post(FamilyConnectionMonitor::networkChanged); }
                    @Override public void onLost(Network network) { MAIN.post(FamilyConnectionMonitor::networkChanged); }
                    @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                        MAIN.post(FamilyConnectionMonitor::networkChanged);
                    }
                });
            } catch (RuntimeException ignored) { }
        }
        IO.scheduleWithFixedDelay(() -> {
            if (!logging()) return;
            boolean network = hasNetwork();
            String site = network ? probe("https://tg.24projects.ru/", "HEAD") : "offline";
            String access = network ? probe(FamilyAccess.DEFAULT_SERVER + "/health", "GET") : "offline";
            append("probe network=" + networkKind() + " site=" + site + " access=" + access
                    + " telegram=" + telegramState + " calls=" + Arrays.toString(FamilyCalls.diagnosticCounters()));
        }, 5, 120, TimeUnit.SECONDS);
    }

    private static NetworkCapabilities capabilities() {
        try {
            ConnectivityManager manager = connectivity;
            return manager == null ? null : manager.getNetworkCapabilities(manager.getActiveNetwork());
        } catch (RuntimeException ignored) { return null; }
    }

    static boolean hasNetwork() {
        NetworkCapabilities caps = capabilities();
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static String networkKind() {
        NetworkCapabilities caps = capabilities();
        if (caps == null) return "offline";
        String type = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "vpn"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "mobile" : "other";
        return type + (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "/validated" : "/unvalidated");
    }

    private static void networkChanged() {
        boolean online = hasNetwork();
        boolean gainedNetwork = online && !networkWasAvailable;
        networkWasAvailable = online;
        String kind = networkKind();
        if (!kind.equals(lastNetwork)) {
            lastNetwork = kind;
            record("network " + kind);
            if (online) FamilyAccess.resumeConnection();
        } else if (gainedNetwork) {
            FamilyAccess.resumeConnection();
        }
        FamilyBinaryGuard.refreshConnectionHeader();
    }

    /** Called by Telegram's own connection-title update, before choosing the displayed title. */
    public static int displayState(int state) {
        if (telegramState != state) record("telegram " + state);
        telegramState = state;
        FamilyAccess.reportTransportState(state);
        if (!FamilyAccess.previouslyApproved() || FamilyAccess.isAllowed()) return state;
        return hasNetwork() ? 1 : 2;
    }

    static void accessChanged(FamilyAccess.Snapshot state) {
        if (state.busy) return;
        String event = state.status + " " + state.connected + " " + state.error;
        IO.execute(() -> {
            if (event.equals(lastAccess)) return;
            lastAccess = event;
            append("access " + event);
        });
    }

    static void routeChanged(String route) { record("route " + route); }

    static int transportState() { return telegramState; }

    static boolean callActive() {
        try {
            return Class.forName("org.telegram.messenger.voip.VoIPService")
                    .getMethod("getSharedInstance").invoke(null) != null;
        } catch (ReflectiveOperationException | RuntimeException ignored) { return false; }
    }

    static boolean logging() {
        SharedPreferences prefs = log;
        return prefs != null && prefs.getLong("until", 0) > System.currentTimeMillis();
    }

    static void startLogging() {
        log.edit().putLong("until", System.currentTimeMillis() + DIAGNOSTIC_MS).remove("events").apply();
        record("start network=" + networkKind() + " telegram=" + telegramState);
    }

    static void stopLogging() { log.edit().remove("until").apply(); }

    static String report() { return log.getString("events", ""); }

    private static void record(String event) {
        if (logging()) IO.execute(() -> append(event));
    }

    private static void append(String event) {
        if (!logging()) return;
        String existing = log.getString("events", "");
        String line = System.currentTimeMillis() + " " + event + "\n";
        String events = existing + line;
        if (events.length() > MAX_LOG_CHARS) {
            events = events.substring(events.indexOf('\n', events.length() - MAX_LOG_CHARS) + 1);
        }
        log.edit().putString("events", events).apply();
    }

    private static String probe(String endpoint, String method) {
        HttpsURLConnection connection = null;
        long start = SystemClock.elapsedRealtime();
        try {
            connection = (HttpsURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(4_000);
            connection.setReadTimeout(4_000);
            connection.setUseCaches(false);
            return connection.getResponseCode() + "/" + (SystemClock.elapsedRealtime() - start) + "ms";
        } catch (java.net.UnknownHostException ignored) {
            return "dns";
        } catch (javax.net.ssl.SSLException ignored) {
            return "tls";
        } catch (java.net.SocketTimeoutException ignored) {
            return "timeout";
        } catch (Exception ignored) {
            return "unreachable";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
