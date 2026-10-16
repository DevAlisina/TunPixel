package com.tunpixel;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import com.tunpixel.network.TunnelApiClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
        LogStore.i(TAG, "TunnelManager created");
        this.cloudflaredBinary = resolveBundledBinary(context);
        if (cloudflaredBinary != null) {
            LogStore.i(TAG, "Bundled binary found: " + cloudflaredBinary.getAbsolutePath() + " [exists=" + cloudflaredBinary.exists() + "]");
        }
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
            LogStore.d(TAG, "Tunnel already running");
            return;
        }

        if (cloudflaredBinary == null || !cloudflaredBinary.exists()) {
            LogStore.e(TAG, "Binary not found");
            return;
        }

        if (!cloudflaredBinary.canExecute()) {
            cloudflaredBinary.setExecutable(true);
        }

        // TryCloudflare API request to get tunnel details
        TunnelApiClient.TunnelResponse response = TunnelApiClient.requestQuickTunnel(localUrl);
        if (response != null && response.success) {
            tunnelUrl = response.tunnelUrl;
            tunnelHostname = response.hostname;
            tunnelToken = response.token;
            tunnelSecret = response.secret;
            LogStore.i(TAG, "API response: success=" + response.success + ", hostname=" + response.hostname);
            LogStore.d(TAG, "Parsed tunnel fields: url=" + tunnelUrl + ", hostname=" + tunnelHostname);
        } else {
            LogStore.w(TAG, "Could not pre-fetch tunnel via TryCloudflare API"
                    + (response != null && response.error != null ? ": " + response.error : "")
                    + "; proceeding to start cloudflared");
        }

        // Build process command.
        // KEY FIX: Go's net package on Android uses cgo/getaddrinfo which hardcodes
        // SRV lookups to [::1]:53 — a non-existent resolver on Android. This causes
        // cloudflared's edge discovery to always fail. The fix: pre-resolve Cloudflare
        // edge hostnames here via Java's InetAddress (uses Android's real resolver),
        // then pass the IPs via --edge flags to bypass Go's DNS path entirely.
        try {
            // Pre-resolve Cloudflare edge IPs via Android/Java resolver
            List<String> edgeArgs = new ArrayList<>();
            String[] edgeHostnames = {"region1.v2.argotunnel.com", "region2.v2.argotunnel.com"};
            for (String host : edgeHostnames) {
                try {
                    InetAddress[] addrs = InetAddress.getAllByName(host);
                    for (InetAddress addr : addrs) {
                        // Only use IPv4 to avoid Android IPv6 loopback issues
                        if (addr instanceof java.net.Inet4Address) {
                            edgeArgs.add("--edge");
                            edgeArgs.add(addr.getHostAddress() + ":7844");
                            LogStore.d(TAG, "Resolved edge: " + host + " -> " + addr.getHostAddress());
                        }
                    }
                } catch (Exception e) {
                    LogStore.w(TAG, "Could not resolve edge host " + host + ": " + e.getMessage());
                }
            }
            if (edgeArgs.isEmpty()) {
                // Fallback: well-known static Cloudflare Argo edge IPs
                LogStore.w(TAG, "Edge DNS resolution failed; using static fallback IPs");
                edgeArgs.add("--edge"); edgeArgs.add("198.41.192.77:7844");
                edgeArgs.add("--edge"); edgeArgs.add("198.41.200.43:7844");
            }

            // DNS resolver address to prevent Go from querying [::1]:53 on Android
            String[] dnsServers;
            if ("custom".equals(dnsMode)) {
                dnsServers = new String[]{"1.1.1.1:53", "1.0.0.1:53"};
            } else if ("auto".equals(dnsMode)) {
                dnsServers = new String[]{"1.1.1.1:53", "8.8.8.8:53"};
            } else {
                dnsServers = new String[]{"1.1.1.1:53", "1.0.0.1:53"};
            }

            ProcessBuilder pb = new ProcessBuilder();
            List<String> command = new ArrayList<>();
            command.add(cloudflaredBinary.getAbsolutePath());
            command.add("tunnel");
            // Force IPv4 and pre-resolved IPs to bypass Go's SRV DNS lookup
            command.add("--edge-ip-version");
            command.add("4");
            command.addAll(edgeArgs);
            // Prevent autoupdate attempts which fail on Android (noexec/read-only dirs)
            command.add("--no-autoupdate");

            if (tunnelToken != null && !tunnelToken.isEmpty()) {
                command.add("run");
                command.add("--token");
                command.add(tunnelToken);
                command.add("--url");
                command.add(localUrl);
                for (String dns : dnsServers) {
                    command.add("--dns-resolver-addrs");
                    command.add(dns);
                }
            } else {
                command.add("--url");
                command.add(localUrl);
                command.add("--quick-service");
                command.add(TunnelApiClient.QUICK_SERVICE_URL);
                for (String dns : dnsServers) {
                    command.add("--dns-resolver-addrs");
                    command.add(dns);
                }
            }

            pb.command(command);
            pb.redirectErrorStream(true);

            // Log the full command line so the logs screen shows exactly what runs.
            StringBuilder cmdLog = new StringBuilder("exec:");
            for (String arg : pb.command()) {
                if (arg != null) cmdLog.append(' ').append(arg);
            }
            LogStore.d(TAG, cmdLog.toString());

            Process process = pb.start();
            tunnelProcess = process;
            isRunning = true;
            LogStore.i(TAG, "cloudflared process started");

            // Start log reader thread
            new Thread(() -> {
                try {
                    java.io.BufferedReader reader =
                        new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        LogStore.d("cloudflared", line);
                        if (line.contains("trycloudflare.com") || line.contains("hostname")) {
                            // Parse tunnel URL from output if needed
                            parseTunnelOutput(line);
                        }
                    }
                } catch (Exception e) {
                    LogStore.e(TAG, "Error reading cloudflared output", e);
                }
            }).start();

            LogStore.i(TAG, "Tunnel started: " + tunnelUrl);

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
            LogStore.e(TAG, "Failed to start tunnel", e);
        }
    }

    public void stopTunnel() {
        if (tunnelProcess != null && tunnelProcess.isAlive()) {
            tunnelProcess.destroy();
            try {
                tunnelProcess.waitFor(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                LogStore.e(TAG, "Interrupted waiting for process", e);
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

        LogStore.i(TAG, "Tunnel stopped");
    }

    private void parseTunnelOutput(String line) {
        // Try to extract URL from cloudflared output
        String[] parts = line.split("\\s+");
        for (String part : parts) {
            if (part.startsWith("http://") || part.startsWith("https://")) {
                tunnelUrl = part;
                try {
                    Uri uri = Uri.parse(part);
                    if (uri.getHost() != null) {
                        tunnelHostname = uri.getHost();
                    }
                } catch (Exception ignored) {
                }
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
