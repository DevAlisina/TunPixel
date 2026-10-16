package com.tunpixel;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.res.Configuration;
import android.os.Build;

public class TunPixelApp extends Application {
    private static final String TAG = "TunPixelApp";
    private static TunnelManager tunnelManager;

    public static final String CHANNEL_ID = "tunnel_channel";

    @Override
    public void onCreate() {
        super.onCreate();
        LogStore.init(this);
        LogStore.i(TAG, "TunPixelApp created");
        tunnelManager = new TunnelManager(this);
        createNotificationChannels();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Tunnel Notifications",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Notifications for active tunnel sessions");
            channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    public static TunnelManager getTunnelManager() {
        return tunnelManager;
    }
}