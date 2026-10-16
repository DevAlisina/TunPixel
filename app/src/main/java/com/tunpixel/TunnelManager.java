package com.tunpixel;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.tunpixel.network.TunnelApiClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class TunnelManager {
    private static final String TAG = "TunnelManager";
    private final Context context;
    private File cloudflaredBinary;
    private Process tunnelProcess;
    private boolean isRunning = false;
    private String tunnelUrl;
    private String tunnelToken;
    private String tunnelHostname;
    private String tunnelSecret;
    private String dnsMode = "cloudflare"; // cloudflare | custom | auto

    public TunnelManager(Context context) {
        this.context = context;
        this.cloudflaredBinary = resolveBundledBinary(context);
    }

    /**
     * Returns the cloudflared executable bundled in the APK as
     * jniLibs/&lt;abi&gt;/libcloudflared.so. Android extracts it to the read-only,
     * executable nativeLibraryDir during install. That is the only location an app
     * targeting API 29+ may exec() from, because the writable app data dir is
     * mounted noexec under the W^X policy.
     */
    private static File resolveBundledBinary(Context context) {
        File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
        return binary.exists() ? binary : null;
    }

    public File getBinaryPath() {
        return cloudflaredBinary;
    }

    public void setBinary(File binary) {
        this.cloudflaredBinary = binary;
    }

    public void setDnsMode(String mode) {
        this.dnsMode = mode;
    }

    public String getDnsMode() {
        return dnsMode;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public String getTunnelUrl() {
        return tunnelUrl;
    }

    public String getTunnelHostname() {
        return tunnelHostname;
    }

    public void setTunnelUrl(String tunnelUrl) {
        this.tunnelUrl = tunnelUrl;
    }

    public void setTunnelHostname(String tunnelHostname) {
        this.tunnelHostname = tunnelHostname;
    }

    public void setTunnelToken(String tunnelToken) {
        this.tunnelToken = tunnelToken;
    }

    public void setTunnelSecret(String tunnelSecret) {
        this.tunnelSecret = tunnelSecret;
    }

    public Process getTunnelProcess() {
        return tunnelProcess;
    }

    public void setTunnelProcess(Process process) {
        this.tunnelProcess = process;
        this.isRunning = process != null && process.isAlive();
    }

    public void startTunnel(String localUrl) {
        if (isRunning()) {
            Log.w(TAG, "Tunnel already running");
            return;
        }

        if (cloudflaredBinary == null || !cloudflaredBinary.exists()) {
            Log.e(TAG, "Binary not found");
            return;
        }

        if (!cloudflaredBinary.canExecute()) {
            cloudflaredBinary.setExecutable(true);
        }

        // TryCloudflare API request to get tunnel details
        TunnelApiClient.TunnelResponse response = TunnelApiClient.requestQuickTunnel(localUrl);
        if (response == null || !response.success) {
            Log.e(TAG, "Failed to create tunnel via TryCloudflare API");
            return;
        }

        tunnelUrl = response.tunnelUrl;
        tunnelHostname = response.hostname;
        tunnelToken = response.token;
        tunnelSecret = response.secret;

        // Build process command with DNS override
        ProcessBuilder pb = new ProcessBuilder();
        pb.command(
            cloudflaredBinary.getAbsolutePath(),
            "tunnel",
            "--url", localUrl,
            "--quick-service", TunnelApiClient.QUICK_SERVICE_URL,
            "--edge", "172.65.251.0/24", // Cloudflare edge IPs
            "--edge", "104.16.0.0/12",
            "--edge", "104.24.0.0/14",
            "--edge", "104.32.0.0/13",
            "--edge", "104.40.0.0/14",
            "--edge", "104.48.0.0/13",
            "--edge", "108.162.192.0/21",
            "--edge", "108.162.200.0/21",
            "--edge", "108.162.208.0/21",
            "--edge", "108.162.216.0/21",
            "--edge", "108.162.224.0/21",
            "--edge", "108.162.232.0/21",
            "--edge", "162.159.192.0/22",
            "--edge", "162.159.200.0/22",
            "--edge", "162.159.208.0/22",
            "--edge", "162.159.224.0/22",
            "--edge", "172.64.0.0/13",
            "--edge", "173.245.48.0/20",
            "--edge", "190.93.240.0/20",
            "--edge", "197.234.240.0/22",
            "--edge", "198.41.128.0/17",
            "--edge", "2400:cb00::/32",
            "--edge", "2405:8100::/32",
            "--edge", "2405:b500::/32",
            "--edge", "2606:4700::/32",
            "--edge", "2803:f800::/32",
            "--edge", "2c0f:f248::/32",
            "--edge", "2a06:98c0::/29"
        );

        // DNS environment variables to fix resolv.conf issue on Android
        Map<String, String> env = pb.environment();
        env.put("GODEBUG", "netdns=cgo");
        env.put("ANDROID_DATA", context.getCacheDir().getParent());
        env.put("RES_OPTIONS", "ndots:2 timeout:1 attempts:2");

        if ("custom".equals(dnsMode)) {
            env.put("CLOUDFLARED_DNS", "1.1.1.1,1.0.0.1");
        }

        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            tunnelProcess = process;
            isRunning = true;

            // Start log reader thread
            new Thread(() -> {
                try {
                    java.io.BufferedReader reader =
                        new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.d(TAG, "cloudflared: " + line);
                        if (line.contains("hostname")) {
                            // Parse tunnel URL from output if needed
                            parseTunnelOutput(line);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error reading cloudflared output", e);
                }
            }).start();

            Log.i(TAG, "Tunnel started: " + tunnelUrl);

            // Start foreground service
            Intent serviceIntent = new Intent(context, TunnelService.class);
            serviceIntent.setAction(TunnelService.ACTION_START_TUNNEL);
            serviceIntent.putExtra("tunnel_url", tunnelUrl);
            serviceIntent.putExtra("tunnel_hostname", tunnelHostname);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to start tunnel", e);
        }
    }

    public void stopTunnel() {
        if (tunnelProcess != null && tunnelProcess.isAlive()) {
            tunnelProcess.destroy();
            try {
                tunnelProcess.waitFor(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted waiting for process", e);
            }
            tunnelProcess.destroyForcibly();
        }
        tunnelProcess = null;
        isRunning = false;
        tunnelUrl = null;

        // Stop foreground service
        Intent serviceIntent = new Intent(context, TunnelService.class);
        serviceIntent.setAction(TunnelService.ACTION_STOP_TUNNEL);
        context.stopService(serviceIntent);

        Log.i(TAG, "Tunnel stopped");
    }

    private void parseTunnelOutput(String line) {
        // Try to extract URL from cloudflared output
        // cloudflared outputs: "<hostname> ..."
        String[] parts = line.split("\\s+");
        for (String part : parts) {
            if (part.startsWith("http://") || part.startsWith("https://")) {
                tunnelUrl = part;
                break;
            }
        }
    }

    public String getDnsStatusText() {
        switch (dnsMode) {
            case "cloudflare": return context.getString(R.string.text_cloudflare_dns);
            case "custom": return "Custom DNS (1.1.1.1)";
            case "auto": return "Auto (System resolver)";
            default: return dnsMode;
        }
    }

    public boolean isBinaryReady() {
        return cloudflaredBinary != null && cloudflaredBinary.exists();
    }

    public void downloadBinary() {
        // Trigger binary download via intent - handled by BinaryDownloadActivity
        Intent intent = new Intent(context, BinaryDownloadActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
