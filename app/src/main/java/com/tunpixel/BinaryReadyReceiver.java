package com.tunpixel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BinaryReadyReceiver extends BroadcastReceiver {
    private static final String TAG = "BinaryReadyReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        LogStore.d(TAG, "Binary ready received");
        // When the binary is ready, update the UI in MainActivity if it's visible
        // or do any initialization needed
        TunnelManager manager = TunPixelApp.getTunnelManager();
        if (manager != null && manager.isBinaryReady()) {
            LogStore.d(TAG, "Binary is ready: " + manager.getBinaryPath());
        }
    }
}
