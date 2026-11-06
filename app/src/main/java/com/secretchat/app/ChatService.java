package com.secretchat.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

public class ChatService extends Service {
    private volatile boolean running = false;
    private volatile HttpURLConnection conn;
    private Thread worker;
    private SecretKey key;
    private String topic;
    private final List<String> recent = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("svc", "Connection", NotificationManager.IMPORTANCE_MIN));
        nm.createNotificationChannel(new NotificationChannel("msg", "Messages", NotificationManager.IMPORTANCE_HIGH));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = new Notification.Builder(this, "svc")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Secret Chat")
                .setContentText("Listening for messages")
                .setContentIntent(openIntent())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, n);
        if (!running) {
            running = true;
            worker = new Thread(this::loop);
            worker.start();
        }
        return START_STICKY;
    }

    private PendingIntent openIntent() {
        Intent i = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void loop() {
        SharedPreferences p = getSharedPreferences("sc", MODE_PRIVATE);
        String code = p.getString("code", null);
        if (code == null) { stopSelf(); return; }
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("topic:" + code).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder("sc-");
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", h[i]));
            topic = sb.toString();
            byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(code.toCharArray(), "secret-chat-v1".getBytes("UTF-8"), 100000, 256)).getEncoded();
            key = new SecretKeySpec(k, "AES");
        } catch (Exception e) { stopSelf(); return; }
        recent.addAll(p.getStringSet("ids", new HashSet<>()));
        long backoff = 2000;
        while (running) {
            try {
                long since = p.getLong("last", System.currentTimeMillis() / 1000);
                conn = (HttpURLConnection) new URL("https://ntfy.sh/" + topic + "/sse?since=" + since).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(120000);
                BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                backoff = 2000;
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (line.startsWith("data:")) handle(line.substring(5).trim(), p);
                }
            } catch (Exception ignored) {}
            if (!running) break;
            try { Thread.sleep(backoff); } catch (InterruptedException e) { break; }
            backoff = Math.min(backoff * 2, 60000);
        }
    }

    private void handle(String json, SharedPreferences p) {
        try {
            JSONObject o = new JSONObject(json);
            if (!"message".equals(o.optString("event"))) return;
            String id = o.getString("id");
            if (recent.contains(id)) return;
            recent.add(id);
            while (recent.size() > 50) recent.remove(0);
            p.edit().putLong("last", o.getLong("time")).putStringSet("ids", new HashSet<>(recent)).apply();
            JSONObject m = decrypt(o.getString("message"));
            if (m == null) return;
            if (m.optString("u").equals(p.getString("uid", ""))) return;
            if (MainActivity.visible) return;
            String body = "img".equals(m.optString("k")) ? "\uD83D\uDCF7 Photo" : m.optString("t");
            Notification n = new Notification.Builder(this, "msg")
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(m.optString("n", "Secret Chat"))
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setContentIntent(openIntent())
                    .setAutoCancel(true)
                    .build();
            getSystemService(NotificationManager.class).notify(2, n);
        } catch (Exception ignored) {}
    }

    private JSONObject decrypt(String s) {
        try {
            byte[] a = Base64.decode(s, Base64.DEFAULT);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, a, 0, 12));
            return new JSONObject(new String(c.doFinal(a, 12, a.length - 12), "UTF-8"));
        } catch (Exception e) { return null; }
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
