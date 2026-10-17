package com.tunpixel;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
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

    // State & Animations
    private TunnelManager tunnelManager;
    private boolean isTunneled = false;
    private ObjectAnimator pulseAnimator;

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
        buttonToggleTunnel.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            toggleTunnel();
        });
        buttonDetails.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            openTunnelDetails();
        });

        // Dynamic interactive hero card: tap to start or view status
        if (cardIcon != null) {
            cardIcon.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (!isTunneled) {
                    toggleTunnel();
                } else {
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).withEndAction(() ->
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    ).start();
                    String url = tunnelManager != null ? tunnelManager.getTunnelUrl() : null;
                    if (url != null && !url.isEmpty()) {
                        copyToClipboard(url, getString(R.string.text_copied));
                    }
                }
            });
        }

        // Tap active session card to copy live URL
        if (cardStatus != null) {
            cardStatus.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                String url = tunnelManager != null ? tunnelManager.getTunnelUrl() : null;
                if (url != null && !url.isEmpty()) {
                    copyToClipboard(url, getString(R.string.text_copied));
                }
            });
        }

        if (editPort != null) {
            editPort.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (layoutPort != null) {
                        layoutPort.setError(null);
                        String portStr = s != null ? s.toString().trim() : "";
                        if (!portStr.isEmpty()) {
                            try {
                                int p = Integer.parseInt(portStr);
                                if (p >= 1 && p <= 65535) {
                                    layoutPort.setHelperText("Will tunnel: http://localhost:" + p);
                                } else {
                                    layoutPort.setHelperText(getString(R.string.helper_internal_port));
                                }
                            } catch (NumberFormatException ignored) {
                                layoutPort.setHelperText(getString(R.string.helper_internal_port));
                            }
                        } else {
                            layoutPort.setHelperText(getString(R.string.helper_internal_port));
                        }
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }
    }

    private void startPulseAnimation() {
        if (cardIcon == null) return;
        if (pulseAnimator == null) {
            PropertyValuesHolder scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.08f);
            PropertyValuesHolder scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.08f);
            pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(cardIcon, scaleX, scaleY);
            pulseAnimator.setDuration(700);
            pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
            pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
            pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        }
        if (!pulseAnimator.isRunning()) {
            pulseAnimator.start();
        }
    }

    private void stopPulseAnimation() {
        if (pulseAnimator != null && pulseAnimator.isRunning()) {
            pulseAnimator.cancel();
        }
        if (cardIcon != null) {
            cardIcon.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(250)
                .start();
        }
    }

    private void animateConnectedSuccess() {
        stopPulseAnimation();
        if (cardIcon != null) {
            cardIcon.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            cardIcon.setScaleX(0.85f);
            cardIcon.setScaleY(0.85f);
            cardIcon.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(450)
                .setInterpolator(new OvershootInterpolator(2.4f))
                .start();
        }
    }

    private void copyToClipboard(String text, String message) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("tunnel_info", text);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
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
            startPulseAnimation();
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
                animateConnectedSuccess();
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

                if (cardStatus.getVisibility() != View.VISIBLE) {
                    cardStatus.setAlpha(0f);
                    cardStatus.setTranslationY(24f);
                    cardStatus.setVisibility(View.VISIBLE);
                    cardStatus.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(350)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
                }

                if (buttonDetails.getVisibility() != View.VISIBLE) {
                    buttonDetails.setAlpha(0f);
                    buttonDetails.setVisibility(View.VISIBLE);
                    buttonDetails.animate()
                        .alpha(1f)
                        .setDuration(300)
                        .start();
                }
            } else {
                stopPulseAnimation();
                textStatus.setText(R.string.text_tunnel_stopped);
                textSubtitle.setText(R.string.text_subtitle_ready);
                buttonToggleTunnel.setText(R.string.action_start_tunnel);
                buttonToggleTunnel.setIconResource(R.drawable.ic_baseline_play_arrow_24);
                imageTunnel.setImageResource(R.drawable.ic_modern_cloud_24);

                if (cardStatus.getVisibility() == View.VISIBLE) {
                    cardStatus.animate()
                        .alpha(0f)
                        .translationY(24f)
                        .setDuration(250)
                        .withEndAction(() -> cardStatus.setVisibility(View.GONE))
                        .start();
                }

                if (buttonDetails.getVisibility() == View.VISIBLE) {
                    buttonDetails.animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction(() -> buttonDetails.setVisibility(View.GONE))
                        .start();
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        stopPulseAnimation();
        super.onDestroy();
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
