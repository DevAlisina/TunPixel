package com.tunpixel;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "tunpixel_prefs";
    private static final String KEY_INTERNAL_PORT = "key_internal_port";
    private static final int DEFAULT_PORT = 8080;

    // UI components
    private MaterialToolbar toolbar;
    private TextView textStatus;
    private TextView textSubtitle;
    private TextView textLiveUrlHint;
    private TextInputLayout layoutPort;
    private TextInputEditText editPort;
    private MaterialButton buttonToggleTunnel;
    private MaterialButton buttonDetails;
    private ImageView imageTunnel;
    private MaterialCardView cardIcon;
    private MaterialCardView cardStatus;
    private LinearProgressIndicator progressBar;

    // State
    private TunnelManager tunnelManager;
    private boolean isTunneled = false;

    // Activity result launcher for binary download
    private final ActivityResultLauncher<Intent> binaryDownloadLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) {
                Intent data = result.getData();
                if (data != null && data.getBooleanExtra("binary_ready", false)) {
                    setupBinary();
                }
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tunnelManager = TunPixelApp.getTunnelManager();

        initViews();
        setupToolbar();
        setupListeners();
        checkBinaryState();

        LogStore.i(TAG, "MainActivity shown");
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncWithCurrentTunnelState();
    }

    private void syncWithCurrentTunnelState() {
        boolean running = tunnelManager != null && tunnelManager.isRunning();
        updateUiState(running, false);
    }

    private void setupToolbar() {
        toolbar.inflateMenu(R.menu.menu_main);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_logs) {
                LogStore.d(TAG, "Opening logs screen");
                startActivity(new Intent(this, LogsActivity.class));
                return true;
            }
            return false;
        });
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        textStatus = findViewById(R.id.textStatus);
        textSubtitle = findViewById(R.id.textSubtitle);
        textLiveUrlHint = findViewById(R.id.textLiveUrlHint);
        layoutPort = findViewById(R.id.layoutPort);
        editPort = findViewById(R.id.editPort);
        buttonToggleTunnel = findViewById(R.id.buttonToggleTunnel);
        buttonDetails = findViewById(R.id.buttonDetails);
        imageTunnel = findViewById(R.id.imageTunnel);
        cardIcon = findViewById(R.id.cardIcon);
        cardStatus = findViewById(R.id.cardStatus);
        progressBar = findViewById(R.id.progressBar);

        loadSavedPort();
    }

    private void setupListeners() {
        buttonToggleTunnel.setOnClickListener(v -> toggleTunnel());
        buttonDetails.setOnClickListener(v -> openTunnelDetails());
        if (editPort != null) {
            editPort.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (layoutPort != null) {
                        layoutPort.setError(null);
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }
    }

    private int getValidPort() {
        if (editPort == null) {
            return DEFAULT_PORT;
        }
        String portStr = editPort.getText() != null ? editPort.getText().toString().trim() : "";
        if (portStr.isEmpty()) {
            if (layoutPort != null) {
                layoutPort.setError(getString(R.string.error_invalid_port));
            }
            return -1;
        }
        try {
            int port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) {
                if (layoutPort != null) {
                    layoutPort.setError(getString(R.string.error_invalid_port));
                }
                return -1;
            }
            if (layoutPort != null) {
                layoutPort.setError(null);
            }
            return port;
        } catch (NumberFormatException e) {
            if (layoutPort != null) {
                layoutPort.setError(getString(R.string.error_invalid_port));
            }
            return -1;
        }
    }

    private void loadSavedPort() {
        int savedPort = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(KEY_INTERNAL_PORT, DEFAULT_PORT);
        if (editPort != null) {
            editPort.setText(String.valueOf(savedPort));
        }
    }

    private void savePort(int port) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(KEY_INTERNAL_PORT, port)
                .apply();
    }

    private void toggleTunnel() {
        if (isTunneled) {
            stopTunnel();
        } else {
            startTunnel();
        }
    }

    private void startTunnel() {
        if (!tunnelManager.isBinaryReady()) {
            Toast.makeText(this, R.string.text_no_binary, Toast.LENGTH_SHORT).show();
            downloadBinary();
            return;
        }

        if (tunnelManager.isRunning()) {
            syncWithCurrentTunnelState();
            return;
        }

        int port = getValidPort();
        if (port == -1) {
            return;
        }

        savePort(port);
        final String localUrl = "http://localhost:" + port;

        updateUiState(true, true);
        new Thread(() -> {
            tunnelManager.startTunnel(localUrl);
            try {
                Thread.sleep(3000);
                runOnUiThread(() -> {
                    boolean running = tunnelManager.isRunning();
                    if (running) {
                        updateUiState(true, false);
                    } else {
                        Toast.makeText(MainActivity.this, R.string.text_subtitle_error, Toast.LENGTH_LONG).show();
                        updateUiState(false, false);
                    }
                });
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void stopTunnel() {
        updateUiState(false, true);
        tunnelManager.stopTunnel();

        new Thread(() -> {
            try {
                Thread.sleep(1500);
                runOnUiThread(() -> updateUiState(false, false));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void updateUiState(boolean tunneled, boolean loading) {
        this.isTunneled = tunneled;

        if (layoutPort != null) {
            layoutPort.setEnabled(!loading && !tunneled);
        }

        if (loading) {
            progressBar.setVisibility(View.VISIBLE);
            buttonToggleTunnel.setEnabled(false);
            if (tunneled) {
                textStatus.setText(R.string.text_tunnel_connecting);
                textSubtitle.setText(R.string.text_subtitle_connecting);
            } else {
                textStatus.setText(R.string.text_closing);
                textSubtitle.setText(R.string.text_closing);
            }
        } else {
            progressBar.setVisibility(View.GONE);
            buttonToggleTunnel.setEnabled(true);

            if (tunneled) {
                textStatus.setText(R.string.text_tunnel_running);
                textSubtitle.setText(R.string.text_subtitle_running);
                buttonToggleTunnel.setText(R.string.action_stop_tunnel);
                buttonToggleTunnel.setIconResource(R.drawable.ic_baseline_stop_24);
                imageTunnel.setImageResource(R.drawable.ic_shield_check_24);

                String currentUrl = tunnelManager.getTunnelUrl();
                if (textLiveUrlHint != null && currentUrl != null && !currentUrl.isEmpty()) {
                    textLiveUrlHint.setText(currentUrl);
                } else if (textLiveUrlHint != null) {
                    textLiveUrlHint.setText(R.string.text_tunnel_active_hint);
                }

                cardStatus.setVisibility(View.VISIBLE);
                buttonDetails.setVisibility(View.VISIBLE);
            } else {
                textStatus.setText(R.string.text_tunnel_stopped);
                textSubtitle.setText(R.string.text_subtitle_ready);
                buttonToggleTunnel.setText(R.string.action_start_tunnel);
                buttonToggleTunnel.setIconResource(R.drawable.ic_baseline_play_arrow_24);
                imageTunnel.setImageResource(R.drawable.ic_modern_cloud_24);
                cardStatus.setVisibility(View.GONE);
                buttonDetails.setVisibility(View.GONE);
            }
        }
    }

    private void checkBinaryState() {
        if (!tunnelManager.isBinaryReady()) {
            textStatus.setText(R.string.text_no_binary);
            textSubtitle.setText(R.string.text_no_binary);
            buttonToggleTunnel.setEnabled(false);
        } else {
            buttonToggleTunnel.setEnabled(true);
            setupBinary();
        }
    }

    private void setupBinary() {
        File binary = tunnelManager.getBinaryPath();
        if (binary != null && binary.exists()) {
            binary.setExecutable(true);
        }
    }

    private void downloadBinary() {
        Intent intent = new Intent(this, BinaryDownloadActivity.class);
        binaryDownloadLauncher.launch(intent);
    }

    private void openTunnelDetails() {
        Intent intent = new Intent(this, TunnelDetailsActivity.class);
        intent.putExtra("tunnel_url", tunnelManager.getTunnelUrl());
        intent.putExtra("tunnel_hostname", tunnelManager.getTunnelHostname());
        startActivity(intent);
    }
}
