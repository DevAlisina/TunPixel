package com.tunpixel;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

public class TunnelDetailsActivity extends AppCompatActivity {

    private MaterialToolbar toolbar;
    private TextView textTunnelUrl;
    private TextView textTunnelHostname;
    private View buttonCopyUrl;
    private View buttonCopyHostname;
    private ImageView imageQrCode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tunnel_details);

        toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        textTunnelUrl = findViewById(R.id.textTunnelUrl);
        textTunnelHostname = findViewById(R.id.textTunnelHostname);
        buttonCopyUrl = findViewById(R.id.buttonCopyUrl);
        buttonCopyHostname = findViewById(R.id.buttonCopyHostname);
        imageQrCode = findViewById(R.id.imageQrCode);

        // Get data from intent
        String tunnelUrl = getIntent().getStringExtra("tunnel_url");
        String tunnelHostname = getIntent().getStringExtra("tunnel_hostname");

        // Set text views
        if (tunnelUrl != null) {
            textTunnelUrl.setText(tunnelUrl);
        }
        if (tunnelHostname != null) {
            textTunnelHostname.setText(tunnelHostname);
        }

        // Setup copy buttons
        if (buttonCopyUrl != null) {
            buttonCopyUrl.setOnClickListener(v -> {
                copyToClipboard(textTunnelUrl.getText().toString(), getString(R.string.text_copied));
            });
        }

        if (buttonCopyHostname != null) {
            buttonCopyHostname.setOnClickListener(v -> {
                copyToClipboard(textTunnelHostname.getText().toString(), getString(R.string.text_copied));
            });
        }

        if (imageQrCode != null) {
            imageQrCode.setImageResource(R.drawable.ic_baseline_qr_code_24);
        }
    }

    private void copyToClipboard(String text, String toastMessage) {
        if (text == null || text.isEmpty()) {
            Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show();
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("tunnel_info", text);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show();
        }
    }
}
