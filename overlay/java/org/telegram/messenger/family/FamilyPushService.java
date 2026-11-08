package org.telegram.messenger.family;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

/** No Google services, message content, Telegram keys or bearer URL parameters. */
public final class FamilyPushService extends Service {
    private static final String SERVICE_CHANNEL = "familygram_push_connection";
    private static final int SERVICE_ID = 0x464701;
    private static volatile FamilyPushService instance;
    private volatile HttpsURLConnection connection;
    private volatile boolean stopping;
    private Thread readerThread;
    static boolean running() { return instance != null && !instance.stopping; }
    static void updateCredentials() { FamilyPushService s = instance; if (s != null) s.disconnect(); }

    @Override public void onCreate() {
        super.onCreate(); instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(SERVICE_CHANNEL, "Соединение с семейным сервером", NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = notification(SERVICE_CHANNEL, "Семейные уведомления", "Соединение с сервером", true);
        if (Build.VERSION.SDK_INT >= 34) startForeground(SERVICE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
        else startForeground(SERVICE_ID, n);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!FamilyPush.authorized()) { stopSelf(); return START_NOT_STICKY; }
        if (readerThread == null) { readerThread = new Thread(this::runStream, "FamilyPush-stream"); readerThread.start(); }
        return START_NOT_STICKY;
    }

    private Notification notification(String channel, String title, String text, boolean ongoing) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channel) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(title).setContentText(text)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(ongoing).setOnlyAlertOnce(ongoing)
                .setCategory(ongoing ? Notification.CATEGORY_SERVICE : Notification.CATEGORY_MESSAGE);
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) b.setContentIntent(PendingIntent.getActivity(this, ongoing ? 1 : 2, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        if (!ongoing) b.setAutoCancel(true);
        return b.build();
    }

    private void signal(long id, String event) {
        if (!FamilyPush.authorized()) return;
        if (!"cursor".equals(event) && FamilyPush.hasActiveAccount()) {
            FamilyPush.wake();
        }
        getSharedPreferences("familygram_push_cursor", MODE_PRIVATE).edit().putLong("sequence", id).apply();
    }

    private void runStream() {
        int backoff = 1;
        try {
            while (!stopping && FamilyPush.authorized()) {
                try {
                    String token = FamilyPush.token;
                    HttpsURLConnection c = (HttpsURLConnection) new URL(FamilyPush.stream).openConnection();
                    connection = c; c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(10_000); c.setReadTimeout(25_000);
                    c.setRequestProperty("Accept", "text/event-stream");
                    c.setRequestProperty("Authorization", "Bearer " + token);
                    c.setRequestProperty("Last-Event-ID", Long.toString(getSharedPreferences("familygram_push_cursor", MODE_PRIVATE).getLong("sequence", 0)));
                    int response = c.getResponseCode();
                    if (response != 200) throw new java.io.IOException("stream_rejected");
                    FamilyPush.state = "listening"; backoff = 1;
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                        String line, event = "", id = ""; boolean payload = false; int recordSize = 0;
                        while (!stopping && FamilyPush.authorized() && (line = limitedLine(reader)) != null) {
                            if (line.length() > 256 || (recordSize += line.length()) > 1024) throw new java.io.IOException("invalid_event");
                            if (line.isEmpty()) {
                                if (payload && id.matches("[0-9]{1,15}") && ("wake".equals(event) || "reset".equals(event) || "cursor".equals(event))) {
                                    long seq = Long.parseLong(id), previous = getSharedPreferences("familygram_push_cursor", MODE_PRIVATE).getLong("sequence", 0);
                                    if (seq > previous || "reset".equals(event) || "cursor".equals(event)) signal(seq, event);
                                }
                                event = ""; id = ""; payload = false; recordSize = 0;
                            } else if (line.startsWith("id: ")) id = line.substring(4);
                            else if (line.startsWith("event: ")) event = line.substring(7);
                            else if (line.equals("data: {\"kind\":\"wake\"}")) payload = true;
                        }
                    }
                } catch (Exception ignored) { if (!stopping) FamilyPush.state = "reconnecting"; }
                finally { disconnect(); }
                if (!stopping && FamilyPush.authorized()) { try { Thread.sleep(backoff * 1000L); } catch (InterruptedException ignored) { } backoff = Math.min(15, backoff * 2); }
            }
        } finally { if (!stopping) stopSelf(); }
    }

    private static String limitedLine(BufferedReader reader) throws java.io.IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') return line.toString();
            if (c == '\r') continue;
            if (line.length() >= 256) throw new java.io.IOException("event_too_large");
            line.append((char) c);
        }
        return line.length() == 0 ? null : line.toString();
    }

    private void disconnect() { HttpsURLConnection c = connection; connection = null; if (c != null) c.disconnect(); }
    @Override public void onDestroy() { stopping = true; disconnect(); if (readerThread != null) readerThread.interrupt(); if (instance == this) instance = null; super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
