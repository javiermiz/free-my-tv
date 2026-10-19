package io.freemytv;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Safety net for the :home process (home screen and screensaver): each crash is recorded, and on the 3rd within 5 min
 * the home screen goes back to the TV's original one (requested from the main process) and the app leaves a notice.
 * File: files/home-crashes.txt.
 */
final class Watchdog {
    private Watchdog() {}

    private static final long WINDOW = 5 * 60_000L;
    private static final int LIMIT = 3;
    private static boolean installed;

    static synchronized void install(Context c) {
        if (installed) return;
        installed = true;
        final Context app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                if (record(app, System.currentTimeMillis()) >= LIMIT) HomeMode.requestRevertHome(app);
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /** Our home screen crashed too often just now: don't retry (avoids a crash loop while the original comes back). */
    static boolean crashedTooOften(Context c) {
        return recent(c, System.currentTimeMillis()).size() >= LIMIT;
    }

    /** After Free My TV mode is turned back on, the count starts over. */
    static void clear(Context c) {
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
    }

    private static int record(Context c, long now) throws IOException {
        List<Long> list = recent(c, now);
        list.add(now);
        StringBuilder sb = new StringBuilder();
        for (long at : list) sb.append(at).append('\n');
        try (FileOutputStream out = new FileOutputStream(file(c))) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return list.size();
    }

    private static List<Long> recent(Context c, long now) {
        List<Long> list = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return list;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) Math.min(f.length(), 4096)];
            int read = in.read(data);
            if (read <= 0) return list;
            for (String line : new String(data, 0, read, StandardCharsets.UTF_8).split("\n")) {
                try {
                    long at = Long.parseLong(line.trim());
                    if (now - at < WINDOW && at <= now) list.add(at);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return list;
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "home-crashes.txt");
    }
}
