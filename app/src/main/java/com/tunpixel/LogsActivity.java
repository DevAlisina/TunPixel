package com.tunpixel;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.List;

/**
 * Live view of {@link LogStore}. Shows every app event and the raw cloudflared
 * output, with actions to copy the whole log to the clipboard or clear it.
 */
public class LogsActivity extends AppCompatActivity {

    private static final long REFRESH_INTERVAL_MS = 700L;

    private MaterialToolbar toolbar;
    private ScrollView scrollLogs;
    private TextView textLogs;
    private TextView textEmpty;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int lastCount = -1;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);

        toolbar = findViewById(R.id.toolbar);
        scrollLogs = findViewById(R.id.scrollLogs);
        textLogs = findViewById(R.id.textLogs);
        textEmpty = findViewById(R.id.textEmpty);

        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_logs);
        toolbar.setOnMenuItemClickListener(this::onMenuItem);

        refresh();
    }

    private boolean onMenuItem(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_copy_logs) {
            copyLogs();
            return true;
        }
        if (id == R.id.action_clear_logs) {
            clearLogs();
            return true;
        }
        return false;
    }

    private void copyLogs() {
        if (LogStore.size() == 0) {
            Toast.makeText(this, R.string.text_logs_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("TunPixel logs", LogStore.getText()));
        Toast.makeText(this, R.string.text_logs_copied, Toast.LENGTH_SHORT).show();
    }

    private void clearLogs() {
        LogStore.clear();
        lastCount = -1;
        refresh();
        Toast.makeText(this, R.string.text_logs_cleared, Toast.LENGTH_SHORT).show();
    }

    private void refresh() {
        List<String> lines = LogStore.getLines();
        if (lines.size() == lastCount) {
            return;
        }
        lastCount = lines.size();

        boolean empty = lines.isEmpty();
        textEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        scrollLogs.setVisibility(empty ? View.GONE : View.VISIBLE);

        if (empty) {
            textLogs.setText("");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        textLogs.setText(sb.toString());
        scrollLogs.post(() -> scrollLogs.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresher);
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }
}
