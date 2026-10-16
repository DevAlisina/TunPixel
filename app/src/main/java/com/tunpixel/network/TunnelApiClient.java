package com.tunpixel.network;

import com.tunpixel.TunnelManager;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class TunnelApiClient {
    private static final String TAG = "TunnelApiClient";
    public static final String QUICK_SERVICE_URL = "https://api.trycloudflare.com";
    private static final String API_ENDPOINT = QUICK_SERVICE_URL + "/";

    private static volatile TunnelResponse cachedResponse;

    public static TunnelResponse requestQuickTunnel(String localUrl) {
        LogStore.d(TAG, "Requesting quick tunnel from TryCloudflare API");
        LogStore.d(TAG, "Local URL: " + localUrl);

        // The TryCloudflare API is managed by cloudflared itself via --quick-service flag
        // We still make a request to get metadata, but the actual tunnel is established by cloudflared
        // connecting to https://api.trycloudflare.com/

        try {
            URL url = new URL(API_ENDPOINT);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "TunPixel/1.0");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);

            String jsonInputString = String.format("{\"url\":\"%s\"}", localUrl);
            LogStore.d(TAG, "Request body: " + jsonInputString);

            try (DataOutputStream wr = new DataOutputStream(connection.getOutputStream())) {
                wr.write(jsonInputString.getBytes(StandardCharsets.UTF_8));
                wr.flush();
            }

            int responseCode = connection.getResponseCode();
            LogStore.d(TAG, "Response code: " + responseCode);

            BufferedReader br;
            if (responseCode >= 200 && responseCode < 300) {
                br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            } else {
                br = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8));
            }

            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }

            LogStore.d(TAG, "Response body: " + response.toString());

            return parseTunnelResponse(response.toString());

        } catch (Exception e) {
            LogStore.e(TAG, "Error requesting tunnel", e);
            return new TunnelResponse(false, null, null, null, null, "Error: " + e.getMessage());
        }
    }

    private static TunnelResponse parseTunnelResponse(String json) {
        // Simple JSON parsing without external libraries
        try {
            boolean success = json.contains("\"success\":true");
            String hostname = extractJsonValue(json, "hostname");
            String token = extractJsonValue(json, "token");
            String secret = extractJsonValue(json, "secret");
            String tunnelUrl = extractJsonValue(json, "tunnel_url");
            String error = extractJsonValue(json, "error");

            if (error != null && !error.isEmpty()) {
                return new TunnelResponse(false, tunnelUrl, hostname, token, secret, error);
            }

            return new TunnelResponse(success, tunnelUrl, hostname, token, secret, null);
        } catch (Exception e) {
            LogStore.e(TAG, "Error parsing tunnel response", e);
            return new TunnelResponse(false, null, null, null, null, "Parse error: " + e.getMessage());
        }
    }

    private static String extractJsonValue(String json, String key) {
        // Simple extraction: find "key":"value"
        String search = "\"" + key + "\"";
        int keyIndex = json.indexOf(search);
        if (keyIndex < 0) return null;

        int colonIndex = json.indexOf(':', keyIndex);
        if (colonIndex < 0) return null;

        int valueStart = colonIndex + 1;
        // Skip whitespace
        while (valueStart < json.length() && (json.charAt(valueStart) == ' ' || json.charAt(valueStart) == '\\')) {
            valueStart++;
        }

        if (valueStart >= json.length()) return null;

        char firstChar = json.charAt(valueStart);
        if (firstChar == '"') {
            int endIndex = json.indexOf('"', valueStart + 1);
            if (endIndex < 0) return null;
            return json.substring(valueStart + 1, endIndex);
        } else if (firstChar == '{' || firstChar == '[') {
            // Object or array - skip for now
            return null;
        } else {
            // Number or boolean
            int endIndex = valueStart;
            while (endIndex < json.length() && json.charAt(endIndex) != ',' && json.charAt(endIndex) != '}') {
                endIndex++;
            }
            return json.substring(valueStart, endIndex).trim();
        }
    }

    public static class TunnelResponse {
        public final boolean success;
        public final String tunnelUrl;
        public final String hostname;
        public final String token;
        public final String secret;
        public final String error;

        public TunnelResponse(boolean success, String tunnelUrl, String hostname, String token, String secret, String error) {
            this.success = success;
            this.tunnelUrl = tunnelUrl;
            this.hostname = hostname;
            this.token = token;
            this.secret = secret;
            this.error = error;
        }
    }
}
