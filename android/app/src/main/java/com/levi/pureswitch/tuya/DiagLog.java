package com.levi.pureswitch.tuya;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * A small diagnostics log in the app's private storage, so a problem at home can still be looked
 * at the next day. One file per day; files older than two days are deleted, so at least the last
 * 48 hours are always there. Each day is capped at {@link #MAX_BYTES_PER_DAY}.
 *
 * Writing happens on its own low-priority thread: callers only queue a line and never wait.
 * Never log local keys, login tokens or the Smart Life user code.
 *
 * Read it from a debug build with:
 *   adb exec-out run-as com.levi.pureswitch sh -c "cat files/logs/*.log"
 */
public final class DiagLog {
    private DiagLog() {}

    private static final long MAX_BYTES_PER_DAY = 256 * 1024;
    /** Today plus the two days before: always covers at least the last 48 hours. */
    private static final int KEEP_DAYS = 3;
    private static final int MAX_LINE = 600;

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pureswitch-log");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    // Only touched on the writer thread
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
    private static String lastDay = "";

    private static volatile File dir;

    static void init(Context context) {
        dir = new File(context.getFilesDir(), "logs");
    }

    /** Queues one line. Returns at once; never throws. */
    public static void log(String source, String message) {
        final File d = dir;
        if (d == null) return;
        final long now = System.currentTimeMillis();
        try {
            WRITER.execute(() -> write(d, now, source, message));
        } catch (RuntimeException ignored) {
            // queue shut down: nothing to do
        }
    }

    /** All kept log lines, oldest first. Waits for lines still queued. */
    static String read() throws Exception {
        final File d = dir;
        if (d == null) return "";
        Future<String> f = WRITER.submit(() -> {
            File[] files = d.listFiles((x, name) -> name.endsWith(".log"));
            if (files == null) return "";
            Arrays.sort(files);
            StringBuilder sb = new StringBuilder();
            for (File file : files) {
                sb.append("=== ").append(file.getName()).append('\n');
                byte[] bytes = new byte[(int) Math.min(file.length(), 2 * MAX_BYTES_PER_DAY)];
                try (FileInputStream in = new FileInputStream(file)) {
                    int n = 0;
                    while (n < bytes.length) {
                        int r = in.read(bytes, n, bytes.length - n);
                        if (r < 0) break;
                        n += r;
                    }
                    sb.append(new String(bytes, 0, n, StandardCharsets.UTF_8));
                }
            }
            return sb.toString();
        });
        return f.get(5, TimeUnit.SECONDS);
    }

    private static void write(File d, long now, String source, String message) {
        try {
            Date date = new Date(now);
            String day = DAY.format(date);
            if (!day.equals(lastDay)) {
                lastDay = day;
                prune(d, now);
            }
            if (!d.isDirectory() && !d.mkdirs()) return;
            File file = new File(d, day + ".log");
            if (file.length() > MAX_BYTES_PER_DAY) return;

            String text = message == null ? "" : message.replace('\n', ' ').replace('\r', ' ');
            if (text.length() > MAX_LINE) text = text.substring(0, MAX_LINE) + "…";
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                w.write(TIME.format(date) + " [" + source + "] " + text + "\n");
            }
        } catch (IOException | RuntimeException ignored) {
            // A log must never break the app
        }
    }

    private static void prune(File d, long now) {
        File[] files = d.listFiles((x, name) -> name.endsWith(".log"));
        if (files == null) return;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.add(Calendar.DAY_OF_MONTH, -(KEEP_DAYS - 1));
        String oldestKept = DAY.format(c.getTime());
        for (File f : files) {
            String day = f.getName().substring(0, f.getName().length() - 4);
            if (day.compareTo(oldestKept) < 0) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
