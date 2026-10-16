package com.tunpixel;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class BinaryDownloadActivity extends AppCompatActivity {

    private static final String TAG = "BinaryDownloadActivity";
    private static final String BINARY_URL = "https://github.com/cloudflare/cloudflared/releases/download/2026.10.0/cloudflared-linux-arm64";
    private static final String BINARY_NAME = "cloudflared";

    private ProgressBar progressBar;
    private TextView textStatus;
    private Button buttonDownload;
    private Button buttonDone;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_binary_download);

        progressBar = findViewById(R.id.progressBar);
        textStatus = findViewById(R.id.textStatus);
        buttonDownload = findViewById(R.id.buttonDownload);
        buttonDone = findViewById(R.id.buttonDone);

        // Check if binary already exists
        if (TunPixelApp.getTunnelManager().isBinaryReady()) {
            showReadyState();
            return;
        }

        buttonDownload.setOnClickListener(v -> downloadBinary());
    }

    private void showReadyState() {
        progressBar.setVisibility(View.GONE);
        textStatus.setText("Binary ready");
        buttonDownload.setVisibility(View.GONE);
        buttonDone.setVisibility(View.VISIBLE);
        buttonDone.setOnClickListener(v -> {
            Intent resultIntent = new Intent();
            resultIntent.putExtra("binary_ready", true);
            setResult(RESULT_OK, resultIntent);
            finish();
        });
    }

    private void downloadBinary() {
        textStatus.setText("Downloading...");
        progressBar.setVisibility(View.VISIBLE);
        buttonDownload.setEnabled(false);

        new Thread(() -> {
            try {
                File binaryFile = downloadBinaryFile();

                if (binaryFile != null && binaryFile.exists()) {
                    binaryFile.setExecutable(true);
                    TunPixelApp.getTunnelManager().setBinary(binaryFile);
                    runBinaryReady(binaryFile);
                } else {
                    runBinaryDownloadError("Binary file not found after download");
                }
            } catch (Exception e) {
                LogStore.e(TAG, "Download failed", e);
                runBinaryDownloadError("Download failed: " + e.getMessage());
            }
        }).start();
    }

    private File downloadBinaryFile() throws Exception {
        URL url = new URL(BINARY_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(120000);
        connection.setInstanceFollowRedirects(true);

        InputStream inputStream = connection.getInputStream();
        File cacheDir = getCacheDir();
        File binaryFile = new File(cacheDir, BINARY_NAME);

        try (FileOutputStream fos = new FileOutputStream(binaryFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytesRead = 0;
            long contentLength = connection.getContentLength();

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;
                final int progress = (int) ((totalBytesRead * 100) / Math.max(contentLength, 1));
                postProgress(progress);
            }
        }
        return binaryFile;
    }

    private void postProgress(final int progress) {
        mainHandler.post(() -> {
            progressBar.setProgress(progress);
            textStatus.setText("Downloading... " + progress + "%");
        });
    }

    private void runBinaryReady(final File binaryFile) {
        mainHandler.post(() -> {
            progressBar.setVisibility(View.GONE);
            textStatus.setText("Binary ready: " + binaryFile.length() + " bytes");
            buttonDownload.setVisibility(View.GONE);
            buttonDone.setVisibility(View.VISIBLE);
            buttonDone.setOnClickListener(v -> {
                Intent resultIntent = new Intent();
                resultIntent.putExtra("binary_ready", true);
                setResult(RESULT_OK, resultIntent);
                finish();
            });
        });
    }

    private void runBinaryDownloadError(final String message) {
        mainHandler.post(() -> {
            progressBar.setVisibility(View.GONE);
            textStatus.setText("Error: " + message);
            buttonDownload.setEnabled(true);
            buttonDownload.setVisibility(View.VISIBLE);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }
}