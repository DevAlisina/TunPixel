package com.tunpixel;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;

public class TunnelDetailsActivity extends AppCompatActivity {

    private TextView textTunnelUrl;
    private TextView textTunnelHostname;
    private TextView textDnsMode;
    private Button buttonCopyUrl;
    private Button buttonCopyHostname;
    private ImageView imageQrCode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tunnel_details);

        textTunnelUrl = findViewById(R.id.textTunnelUrl);
        textTunnelHostname = findViewById(R.id.textTunnelHostname);
        textDnsMode = findViewById(R.id.textDnsMode);
        buttonCopyUrl = findViewById(R.id.buttonCopyUrl);
        buttonCopyHostname = findViewById(R.id.buttonCopyHostname);
        imageQrCode = findViewById(R.id.imageQrCode);

        // Get data from intent
        String tunnelUrl = getIntent().getStringExtra("tunnel_url");
        String tunnelHostname = getIntent().getStringExtra("tunnel_hostname");
        String dnsMode = getIntent().getStringExtra("dns_mode");

        // Set text views
        if (tunnelUrl != null) {
            textTunnelUrl.setText(tunnelUrl);
        }
        if (tunnelHostname != null) {
            textTunnelHostname.setText(tunnelHostname);
        }
        if (dnsMode != null) {
            textDnsMode.setText(dnsMode);
        }

        // Setup copy buttons
        buttonCopyUrl.setOnClickListener(v -> {
            copyToClipboard(textTunnelUrl.getText().toString(), "Tunnel URL copied");
        });

        buttonCopyHostname.setOnClickListener(v -> {
            copyToClipboard(textTunnelHostname.getText().toString(), "Hostname copied");
        });

        // QR code would be generated here in a real implementation
        // For now, just show a placeholder
        imageQrCode.setImageResource(R.drawable.ic_baseline_qr_code_24);
    }

    private void copyToClipboard(String text, String toastMessage) {
        if (text == null || text.isEmpty()) {
            Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show();
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("tunnel_info", text);
        clipboard.setPrimaryClip(clip);

        Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show();
    }
}
