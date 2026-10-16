package com.tunpixel;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Central, thread-safe log sink. Every app event and every line produced by the
 * cloudflared child process is timestamped, formatted and kept both in a bounded
 * in-memory ring buffer (for the live Logs screen) and appended to a file, so the
 * history survives process restarts.
 *
 * <p>Formatted line layout:
 * <pre>10-16 12:34:56.789  I/TunnelManager      message</pre>
 */
public final class LogStore {

    private static final String FILE_NAME = "tunpixel.log";
    private static final int MAX_LINES = 3000;
    private static final long MAX_FILE_BYTES = 1024 * 1024; // 1 MB

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();

    private static File logFile;
    private static BufferedWriter writer;
    private static String sessionStart;

    private static final ThreadLocal<SimpleDateFormat> TIME_FORMAT =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
                }
            };

    private LogStore() {
    }

    /** Must be called once, early in {@code Application.onCreate()}. */
    public static void init(Context context) {
        synchronized (LOCK) {
            File dir = new File(context.getFilesDir(), "logs");
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w("LogStore", "Could not create log directory");
            }
            logFile = new File(dir, FILE_NAME);
            sessionStart = TIME_FORMAT.get().format(new Date());
            loadExisting();
            openWriter();
            LINES.addLast(format("I", "LogStore", "Session started " + sessionStart));
        }
    }

    // ---- logging entry points -------------------------------------------------

    public static void d(String tag, String message) { append("D", tag, message, null); }
    public static void i(String tag, String message) { append("I", tag, message, null); }
    public static void w(String tag, String message) { append("W", tag, message, null); }
    public static void e(String tag, String message) { append("E", tag, message, null); }

    public static void d(String tag, String message, Throwable t) { append("D", tag, message, t); }
    public static void i(String tag, String message, Throwable t) { append("I", tag, message, t); }
    public static void w(String tag, String message, Throwable t) { append("W", tag, message, t); }
    public static void e(String tag, String message, Throwable t) { append("E", tag, message, t); }

    // ---- accessors ------------------------------------------------------------

    public static int size() {
        synchronized (LOCK) {
            return LINES.size();
        }
    }

    /** Snapshot of the formatted lines currently held in memory. */
    public static List<String> getLines() {
        synchronized (LOCK) {
            return new ArrayList<>(LINES);
        }
    }

    /** Full log text (with a header) ready to be copied to the clipboard. */
    public static String getText() {
        StringBuilder sb = new StringBuilder();
        sb.append("# TunPixel logs\n");
        sb.append("# session start : ").append(sessionStart).append('\n');
        sb.append("# device        : ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append('\n');
        sb.append("# android       : ").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        if (android.os.Build.SUPPORTED_ABIS.length > 0) {
            sb.append("# abi           : ").append(android.os.Build.SUPPORTED_ABIS[0]).append('\n');
        }
        sb.append('\n');
        synchronized (LOCK) {
            for (String line : LINES) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
            closeWriter();
            if (logFile != null) {
                try {
                    new FileWriter(logFile, false).close(); // truncate
                } catch (IOException ignored) {
                    // best effort
                }
            }
            openWriter();
            LINES.addLast(format("I", "LogStore", "Logs cleared"));
            writeToFile(LINES.peekLast());
        }
    }

    // ---- internals ------------------------------------------------------------

    private static void append(String level, String tag, String message, Throwable throwable) {
        // Mirror to logcat so nothing is lost for developers using adb logcat.
        switch (level) {
            case "D": Log.d(tag, message, throwable); break;
            case "I": Log.i(tag, message, throwable); break;
            case "W": Log.w(tag, message, throwable); break;
            default:  Log.e(tag, message, throwable); break;
        }

        String body = message == null ? "" : message;
        if (throwable != null) {
            body = body + (body.isEmpty() ? "" : "\n") + Log.getStackTraceString(throwable);
        }

        String prefix = TIME_FORMAT.get().format(new Date()) + "  " + level + "/" + padTag(tag) + " ";
        String[] physical = body.split("\n", -1);

        synchronized (LOCK) {
            for (int i = 0; i < physical.length; i++) {
                String text = (i == 0) ? physical[i] : ("          " + physical[i]);
                String line = prefix + text;
                LINES.addLast(line);
                while (LINES.size() > MAX_LINES) {
                    LINES.removeFirst();
                }
                writeToFile(line);
            }
        }
    }

    private static String format(String level, String tag, String message) {
        return TIME_FORMAT.get().format(new Date()) + "  " + level + "/" + padTag(tag) + " " + message;
    }

    private static String padTag(String tag) {
        if (tag == null) {
            tag = "?";
        }
        if (tag.length() >= 18) {
            return tag;
        }
        StringBuilder sb = new StringBuilder(tag);
        while (sb.length() < 18) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static void loadExisting() {
        LINES.clear();
        if (logFile == null || !logFile.exists()) {
            return;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(logFile)));
            String line;
            while ((line = reader.readLine()) != null) {
                LINES.addLast(line);
                while (LINES.size() > MAX_LINES) {
                    LINES.removeFirst();
                }
            }
        } catch (IOException e) {
            Log.w("LogStore", "Could not read existing log file", e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    private static void openWriter() {
        try {
            if (writer == null && logFile != null) {
                writer = new BufferedWriter(new FileWriter(logFile, true));
            }
        } catch (IOException e) {
            Log.w("LogStore", "Could not open log file for writing", e);
        }
    }

    private static void closeWriter() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException ignored) {
                // best effort
            }
            writer = null;
        }
    }

    private static void writeToFile(String line) {
        if (writer == null) {
            return;
        }
        try {
            if (logFile != null && logFile.length() > MAX_FILE_BYTES) {
                rotate();
            }
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            // Logging must never crash the app.
        }
    }

    private static void rotate() {
        try {
            closeWriter();
            List<String> tail = new ArrayList<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(logFile)));
            String line;
            while ((line = reader.readLine()) != null) {
                tail.add(line);
            }
            reader.close();

            int from = Math.max(0, tail.size() - MAX_LINES / 2);
            BufferedWriter out = new BufferedWriter(new FileWriter(logFile, false));
            for (int i = from; i < tail.size(); i++) {
                out.write(tail.get(i));
                out.newLine();
            }
            out.flush();
            writer = out;
        } catch (IOException e) {
            try {
                writer = new BufferedWriter(new FileWriter(logFile, true));
            } catch (IOException ignored) {
                // give up on file logging
            }
        }
    }
}
