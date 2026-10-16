package com.tunpixel;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.io.File;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    // UI components
    private MaterialToolbar toolbar;
    private TextView textStatus;
    private Button buttonToggleTunnel;
    private Button buttonDetails;
    private ImageView imageTunnel;
    private ProgressBar progressBar;
    private ChipGroup chipGroupDns;
    private LinearLayout dnsContainer;

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
        // Must run BEFORE super.onCreate(): swaps the Theme.SplashScreen theme for
        // Theme.TunPixel (postSplashScreenTheme), which AppCompatActivity requires.
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tunnelManager = TunPixelApp.getTunnelManager();

        // Initialize views
        initViews();
        setupToolbar();
        setupListeners();
        checkBinaryState();
        updateUiState(false, false);

        LogStore.i(TAG, "MainActivity shown");
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
        buttonToggleTunnel = findViewById(R.id.buttonToggleTunnel);
        buttonDetails = findViewById(R.id.buttonDetails);
        imageTunnel = findViewById(R.id.imageTunnel);
        progressBar = findViewById(R.id.progressBar);
        chipGroupDns = findViewById(R.id.chipGroupDns);
        dnsContainer = findViewById(R.id.dnsContainer);
    }

    private void setupListeners() {
        buttonToggleTunnel.setOnClickListener(v -> toggleTunnel());
        buttonDetails.setOnClickListener(v -> openTunnelDetails());

        // DNS mode chips
        chipGroupDns.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId != View.NO_ID) {
                String mode;
                if (checkedId == R.id.chipDnsCloudflare) {
                    mode = "cloudflare";
                } else if (checkedId == R.id.chipDnsCustom) {
                    mode = "custom";
                } else if (checkedId == R.id.chipDnsAuto) {
                    mode = "auto";
                } else {
                    mode = "cloudflare";
                }
                tunnelManager.setDnsMode(mode);
                LogStore.i(TAG, "DNS mode selected: " + mode);
            }
        });
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
            Toast.makeText(this, "Binary not ready. Downloading...", Toast.LENGTH_SHORT).show();
            downloadBinary();
            return;
        }

        updateUiState(true, true);
        new Thread(() -> {
            tunnelManager.startTunnel("http://localhost:8080");
            // Wait and check if tunnel started
            try {
                Thread.sleep(3000);
                runOnUiThread(() -> {
                    boolean running = tunnelManager.isRunning();
                    if (running) {
                        updateUiState(true, false);
                    } else {
                        Toast.makeText(MainActivity.this, "Failed to start tunnel", Toast.LENGTH_LONG).show();
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
                Thread.sleep(2000);
                runOnUiThread(() -> updateUiState(false, false));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void updateUiState(boolean tunneled, boolean loading) {
        this.isTunneled = tunneled;

        if (loading) {
            progressBar.setVisibility(View.VISIBLE);
            buttonToggleTunnel.setEnabled(false);
            textStatus.setText("Connecting...");
            imageTunnel.setImageResource(R.drawable.ic_baseline_cloud_24);
        } else {
            progressBar.setVisibility(View.GONE);
            buttonToggleTunnel.setEnabled(true);

            if (tunneled) {
                textStatus.setText(R.string.text_tunnel_running);
                buttonToggleTunnel.setText(R.string.action_stop_tunnel);
                imageTunnel.setImageResource(R.drawable.ic_baseline_vpn_key_24);
                dnsContainer.setVisibility(View.GONE);
                buttonDetails.setVisibility(View.VISIBLE);
            } else {
                textStatus.setText(R.string.text_tunnel_stopped);
                buttonToggleTunnel.setText(R.string.action_start_tunnel);
                imageTunnel.setImageResource(R.drawable.ic_baseline_cloud_24);
                dnsContainer.setVisibility(View.VISIBLE);
                buttonDetails.setVisibility(View.GONE);
            }
        }
    }

    private void checkBinaryState() {
        if (!tunnelManager.isBinaryReady()) {
            textStatus.setText(R.string.text_no_binary);
            buttonToggleTunnel.setEnabled(false);
        } else {
            buttonToggleTunnel.setEnabled(true);
            // If binary was already set, ensure it's executable
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
        // Start the binary download activity
        Intent intent = new Intent(this, BinaryDownloadActivity.class);
        binaryDownloadLauncher.launch(intent);
    }

    private void openTunnelDetails() {
        Intent intent = new Intent(this, TunnelDetailsActivity.class);
        intent.putExtra("tunnel_url", tunnelManager.getTunnelUrl());
        intent.putExtra("tunnel_hostname", tunnelManager.getTunnelHostname());
        intent.putExtra("dns_mode", tunnelManager.getDnsMode());
        startActivity(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Fallback handler for older Android versions
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Handle permissions result
    }
}
