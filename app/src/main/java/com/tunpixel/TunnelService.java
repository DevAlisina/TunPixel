package com.tunpixel;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

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
        LogStore.d(TAG, "TunnelService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            LogStore.e(TAG, "Received null intent");
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        LogStore.d(TAG, "Service action: " + action);

        if (action == null) {
            return START_NOT_STICKY;
        }

        switch (action) {
            case ACTION_START_TUNNEL:
                tunnelUrl = intent.getStringExtra(EXTRA_TUNNEL_URL);
                tunnelHostname = intent.getStringExtra(EXTRA_TUNNEL_HOSTNAME);
                startForegroundNotification();
                break;
            case ACTION_STOP_TUNNEL:
                // Stop the actual tunnel process if it is still alive
                TunnelManager manager = TunPixelApp.getTunnelManager();
                if (manager != null && manager.isRunning()) {
                    manager.stopTunnel();
                }
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

    private Notification buildPersistentNotification() {
        // Clicking notification directly opens TunnelDetailsActivity with the active tunnel info
        Intent detailsIntent = new Intent(this, TunnelDetailsActivity.class);
        detailsIntent.putExtra("tunnel_url", tunnelUrl);
        detailsIntent.putExtra("tunnel_hostname", tunnelHostname);
        detailsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            detailsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Action button to stop the tunnel right from the notification
        Intent stopIntent = new Intent(this, TunnelService.class);
        stopIntent.setAction(ACTION_STOP_TUNNEL);
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String title = getString(R.string.text_tunnel_running);
        String bodyText = (tunnelUrl != null && !tunnelUrl.isEmpty()) ? tunnelUrl : getString(R.string.text_tunnel_active_hint);

        return new NotificationCompat.Builder(this, TunPixelApp.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(bodyText)
            .setSubText(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_shield_check_24)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_baseline_stop_24, getString(R.string.action_stop_tunnel), stopPendingIntent)
            .build();
    }

    private void startForegroundNotification() {
        Notification notification = buildPersistentNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        Notification notification = buildPersistentNotification();
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        LogStore.d(TAG, "TunnelService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}