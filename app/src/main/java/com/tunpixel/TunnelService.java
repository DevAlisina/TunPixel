package com.tunpixel;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

public class TunnelService extends Service {
    private static final String TAG = "TunnelService";
    public static final String ACTION_START_TUNNEL = "com.tunpixel.action.START_TUNNEL";
    public static final String ACTION_STOP_TUNNEL = "com.tunpixel.action.STOP_TUNNEL";
    public static final String ACTION_UPDATE_STATUS = "com.tunpixel.action.UPDATE_STATUS";
    public static final String EXTRA_TUNNEL_URL = "tunnel_url";
    public static final String EXTRA_TUNNEL_HOSTNAME = "tunnel_hostname";

    private static final int NOTIFICATION_ID = 101;
    private String tunnelUrl;
    private String tunnelHostname;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "TunnelService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.e(TAG, "Received null intent");
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        Log.d(TAG, "Service action: " + action);

        switch (action) {
            case ACTION_START_TUNNEL:
                tunnelUrl = intent.getStringExtra(EXTRA_TUNNEL_URL);
                tunnelHostname = intent.getStringExtra(EXTRA_TUNNEL_HOSTNAME);
                startForeground();
                break;
            case ACTION_STOP_TUNNEL:
                stopForeground(true);
                stopSelf();
                break;
            case ACTION_UPDATE_STATUS:
                tunnelUrl = intent.getStringExtra(EXTRA_TUNNEL_URL);
                tunnelHostname = intent.getStringExtra(EXTRA_TUNNEL_HOSTNAME);
                updateNotification();
                break;
        }

        return START_NOT_STICKY;
    }

    private void startForeground() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, "tunnel_channel")
            .setContentTitle(getString(R.string.text_tunnel_running))
            .setContentText(tunnelUrl != null ? tunnelUrl : getString(R.string.text_ready))
            .setSmallIcon(R.drawable.ic_baseline_vpn_key_24)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, "tunnel_channel")
            .setContentTitle(getString(R.string.text_tunnel_running))
            .setContentText(tunnelUrl != null ? tunnelUrl : getString(R.string.text_ready))
            .setSmallIcon(R.drawable.ic_baseline_vpn_key_24)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, notification);
        } else {
            // Deprecated but needed for older versions
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "TunnelService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }
}