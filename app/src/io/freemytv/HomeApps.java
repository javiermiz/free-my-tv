package io.freemytv;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What shows up on the home screen and in the "All Apps" sheet, and how to open each thing.
 *
 * On the home shelf, each app is its colored logo on a dark card: the official logo in assets/logos/<key>.png
 * (downloaded from Wikimedia Commons by tools/logos.py) or, without one, the logo lifted from the app's TV art (the
 * Android TV "banner"), saved to disk after the first time. In "All Apps", 16:9 cards with the whole TV art (or the
 * icon on a dark card). Shortcuts (live TV, inputs, All Apps, settings) are icon buttons.
 */
final class HomeApps {
    private HomeApps() {}

    static final int APP = 0, LIVE_TV = 1, INPUT = 2, ALL_APPS = 3, SETTINGS = 4, ANDROID_SETTINGS = 5;
    /** The "HDMI Inputs" button: opens the bottom drawer on the inputs (the home screen handles it). */
    static final int INPUTS = 7;
    /** Image size of each card (16:9; on screen it shows up to ~330 px wide). */
    static final int TILE_W = 384, TILE_H = 216;

    static final class Item {
        int kind;
        String key, label, pkg, inputId, number;
        String symbol;      // icon for the shortcuts and special cards
        String link;        // a title inside the app (a recommendation in Stremio), or null
        Bitmap tile;        // 16:9 card (apps and specials)
        Bitmap logo;        // colored logo with no background (shelf); null = use the card
        Bitmap logoDark;    // version for a light background (YouTube's white icon), if any
        float logoInk;      // fraction of the logo with ink (thin logos are drawn larger, with the same "weight")
        float logoScale = 1; // size tweak by eye (compact logos need more)
        boolean connected = true;
    }

    /**
     * Apps on the home shelf, in this order, when installed (up to MAX_SHELF): key, name, package, brand color (for
     * its logo on glass) and how to lift the logo from the app's TV art: "tint" = a white logo takes the art's
     * background color (Prime Video, Globoplay); "play" = the white triangle inside the red button stays white
     * (YouTube); empty = the art's colors as they are. Last, a visual size tweak for the logo. Edit freely.
     */
    private static final String[][] SHELF = {
        {"netflix", "Netflix", "com.netflix.ninja", "E50914", "", "1"},
        {"youtube", "YouTube", "com.google.android.youtube.tv", "FF0033", "play", "1"},
        {"prime", "Prime Video", "com.amazon.amazonvideo.livingroom", "1A98FF", "tint", "0.96"},
        {"disney", "Disney+", "com.disney.disneyplus", "1838C8", "", "1"},
        {"max", "Max", "com.wbd.stream", "6C3BE0", "", "1.32"},
        {"appletv", "Apple TV", "com.apple.atve.androidtv.appletv", "B8BCC6", "", "1.1"},
        {"hulu", "Hulu", "com.hulu.livingroomplus", "1CE783", "", "1"},
        {"paramount", "Paramount+", "com.cbs.ott", "0064FF", "", "1"},
        {"peacock", "Peacock", "com.peacocktv.peacockandroid", "6E55DC", "", "1"},
        {"globoplay", "Globoplay", "com.globo.globotv", "FF5A1F", "tint", "1"},
        {"spotify", "Spotify", "com.spotify.tv.android", "1DB954", "", "1"},
    };
    static final int MAX_SHELF = 10;
    /** Live TV apps of some brands (the antenna's channels open through the standard Android request first). */
    static final String[] LIVE_TV_APPS = {"com.tcl.tv", "com.google.android.tv", "com.mediatek.wwtv.tvcenter", "com.sony.dtv.tvx"};
    /** Kept out of "All Apps" (only hidden, still installed). Add the ones your TV shows that you never use. */
    private static final Set<String> HIDDEN = new HashSet<>(java.util.Arrays.asList(
            "com.google.android.play.games",        // Google Play Games
            "com.tcl.t_solo",                       // TCL: T-Solo
            "com.tcl.waterfall.overseas",           // TCL Channel
            "com.tcl.dashboard",                    // TCL Home
            "com.tcl.ocean.instructions",           // TCL: User Guide
            "com.tcl.guard",                        // TCL: Safety Guard
            "com.tcl.exhibit"));                    // TCL: T-Exhibition

    private static final Map<String, Bitmap> TILES = new HashMap<>();   // key → ready card
    private static final Map<String, Bitmap> LOGOS = new HashMap<>();   // key → colored logo with no background

    /**
     * Home screen apps. `extract`: also lifts the logos that are not on disk yet (slow: background only); without it,
     * those apps keep their TV art until the next load.
     */
    static List<Item> shelf(Context c, boolean extract) {
        List<Item> items = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (String pkg : shelfPackages(c)) {
            String[] d = null;
            for (String[] row : SHELF) if (row[2].equals(pkg)) d = row;
            Item it = new Item();
            it.kind = APP;
            it.pkg = pkg;
            if (d == null) {
                // An app picked on the TV: its own TV art, as in All Apps.
                it.key = pkg;
                it.label = label(pm, pkg);
                it.tile = appTile(c, null, pkg, 0);
                items.add(it);
                continue;
            }
            it.key = d[0];
            it.label = d[1];
            it.tile = appTile(c, d[0], d[2], 0xFF000000 | Integer.parseInt(d[3], 16));
            it.logo = shelfLogo(c, d[0], d[2], d[4], extract);
            if (it.logo != null) it.logoInk = ink(it.logo);
            it.logoDark = ownLogoCached(c, d[0] + "-dark");
            it.logoScale = Float.parseFloat(d[5]);
            items.add(it);
        }
        return items;
    }

    /**
     * The shelf's packages, in order: the ones arranged on the TV (long press OK), or, until then, the installed apps
     * of SHELF. Uninstalled ones are skipped.
     */
    static List<String> shelfPackages(Context c) {
        PackageManager pm = c.getPackageManager();
        String saved = c.getSharedPreferences("shelf", Context.MODE_PRIVATE).getString("apps", null);
        List<String> out = new ArrayList<>();
        if (saved != null) {
            for (String pkg : saved.split(",")) {
                if (!pkg.isEmpty() && !out.contains(pkg) && launchIntent(pm, pkg) != null) out.add(pkg);
            }
            return out;
        }
        for (String[] d : SHELF) {
            if (out.size() < MAX_SHELF && launchIntent(pm, d[2]) != null) out.add(d[2]);
        }
        return out;
    }

    static void saveShelf(Context c, List<String> pkgs) {
        c.getSharedPreferences("shelf", Context.MODE_PRIVATE).edit().putString("apps", String.join(",", pkgs)).commit();
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** Live TV, the HDMI inputs with a device sending a signal, and All Apps. */
    static List<Item> shortcuts(Context c) {
        List<Item> items = new ArrayList<>();
        if (liveTv(c) != null) {
            items.add(shortcut(LIVE_TV, "live", Ui.tr("TV ao Vivo", "Live TV"), "tv"));
        }
        items.addAll(inputs(c));
        items.add(shortcut(ALL_APPS, "all", Ui.tr("Todos os Apps", "All Apps"), "apps"));
        return items;
    }

    private static Item shortcut(int kind, String key, String label, String symbol) {
        Item it = new Item();
        it.kind = kind;
        it.key = key;
        it.label = label;
        it.symbol = symbol;
        return it;
    }

    /** Icon button (the ones in the corner of the home screen). */
    static Item action(int kind, String key, String label, String symbol) {
        return shortcut(kind, key, label, symbol);
    }

    /** Free My TV settings, in the corner of the home screen. */
    static Item settings() {
        return shortcut(SETTINGS, "settings", Ui.tr("Ajustes", "Settings"), "settings");
    }

    /** Shortcuts in the bottom drawer. */
    static List<Item> drawerShortcuts() {
        List<Item> items = new ArrayList<>();
        items.add(shortcut(ALL_APPS, "all", Ui.tr("Todos os Apps", "All Apps"), "apps"));
        items.add(shortcut(SETTINGS, "settings", Ui.tr("Ajustes do Início", "Home Settings"), "settings"));
        items.add(shortcut(ANDROID_SETTINGS, "tv-settings", Ui.tr("Ajustes da TV", "TV Settings"), "tv_settings"));
        return items;
    }

    /** A streaming service's app (the "Watch" button of the featured pick). */
    static Item app(String pkg, String label) {
        Item it = new Item();
        it.kind = APP;
        it.key = pkg;
        it.pkg = pkg;
        it.label = label;
        return it;
    }

    /** For the bottom drawer: live TV and every HDMI input (the ones with no device are shown dimmed). */
    static List<Item> allInputs(Context c) {
        List<Item> items = new ArrayList<>();
        if (liveTv(c) != null) items.add(shortcut(LIVE_TV, "live", Ui.tr("TV ao Vivo", "Live TV"), "tv"));
        items.addAll(inputs(c, true));
        return items;
    }

    private static List<Item> inputs(Context c) {
        return inputs(c, false);
    }

    /**
     * HDMI inputs: only the ones with a device sending a signal (or all of them, with `all`). A name you gave the input
     * in the TV's settings is used as the label.
     */
    private static List<Item> inputs(Context c, boolean all) {
        List<Item> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        List<TvInputInfo> list = new ArrayList<>();
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() == TvInputInfo.TYPE_HDMI && info.getParentId() == null) list.add(info);
            }
        } catch (RuntimeException e) {
            return out;
        }
        Collections.sort(list, (a, b) -> a.getId().compareTo(b.getId()));
        Pattern digit = Pattern.compile("(\\d)");
        for (int i = 0; i < list.size(); i++) {
            TvInputInfo info = list.get(i);
            int number = i + 1;
            Matcher m = digit.matcher(String.valueOf(info.loadLabel(c)));
            if (m.find()) number = Integer.parseInt(m.group(1));
            // TCL TVs report "connected standby" for every port, with or without a device; only truly "connected"
            // (a device that is on and sending a signal) makes the input show up.
            int state = TvInputManager.INPUT_STATE_DISCONNECTED;
            try {
                state = tim.getInputState(info.getId());
            } catch (RuntimeException ignored) {
            }
            if (!all && state != TvInputManager.INPUT_STATE_CONNECTED) continue;
            CharSequence custom = null;
            try {
                custom = info.loadCustomLabel(c);
            } catch (RuntimeException ignored) {
            }
            String label = custom != null && custom.length() > 0 ? custom.toString() : "HDMI " + number;
            Item it = shortcut(INPUT, "hdmi" + number, label, "hdmi");
            it.inputId = info.getId();
            it.number = String.valueOf(number);
            it.connected = state == TvInputManager.INPUT_STATE_CONNECTED;
            out.add(it);
        }
        Collections.sort(out, (a, b) -> a.number.compareTo(b.number));
        return out;
    }

    /** Installed, enabled TV apps that are not on the home screen (the TV's Settings is among them), plus our own. */
    static List<Item> others(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> skip = new HashSet<>(shelfPackages(c));
        skip.addAll(java.util.Arrays.asList(LIVE_TV_APPS));
        skip.add(c.getPackageName());
        skip.addAll(HIDDEN);
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (skip.contains(pkg) || !seen.add(pkg)) continue;
            Item it = new Item();
            it.kind = APP;
            it.pkg = pkg;
            it.key = pkg;
            it.label = String.valueOf(ri.loadLabel(pm));
            it.tile = appTile(c, null, pkg, 0);
            items.add(it);
        }
        Collections.sort(items, (a, b) -> a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT)));
        items.add(special(c, SETTINGS, "freemytv-settings", "Free My TV", "sparkles"));
        return items;
    }

    private static Item special(Context c, int kind, String key, String label, String symbol) {
        Item it = shortcut(kind, key, label, symbol);
        it.tile = symbolTile(c, key, symbol);
        return it;
    }

    /** Opens the item with the system zoom transition growing out of the card (the app starts opening right away). */
    static boolean open(Activity a, Item it, View from, int x, int y, int w, int h) {
        Intent intent;
        switch (it.kind) {
            case APP:
                if (it.link != null) {
                    intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it.link)).setPackage(it.pkg);
                    break;
                }
                intent = launchIntent(a.getPackageManager(), it.pkg);
                if (intent != null) intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                break;
            case LIVE_TV:
                intent = liveTv(a);
                break;
            case INPUT:
                intent = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(it.inputId));
                break;
            case SETTINGS:
                intent = new Intent().setComponent(new ComponentName(a, SettingsActivity.class));
                break;
            case ANDROID_SETTINGS:
                intent = new Intent(Settings.ACTION_SETTINGS);
                break;
            default:
                return false;
        }
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            a.startActivity(intent, ActivityOptions.makeScaleUpAnimation(from, x, y, Math.max(w, 1), Math.max(h, 1)).toBundle());
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("couldn't open " + it.label + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Live TV: the antenna input's channels through the standard Android request (opening the TV app by itself may go
     * back to the last input used, like an HDMI); otherwise a known live TV app. Null = this TV has none.
     */
    static Intent liveTv(Context c) {
        PackageManager pm = c.getPackageManager();
        String tuner = tunerInput(c);
        if (tuner != null) {
            Intent channels = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(tuner));
            if (channels.resolveActivity(pm) != null) return channels;
        }
        for (String pkg : LIVE_TV_APPS) {
            Intent app = launchIntent(pm, pkg);
            if (app != null) return app;
        }
        return null;
    }

    /** The TV's antenna (tuner) input, or null. The TV maker's own tuner comes first. */
    static String tunerInput(Context c) {
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return null;
        String fallback = null;
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() != TvInputInfo.TYPE_TUNER || info.isPassthroughInput()) continue;
                if (info.getServiceInfo() != null && info.getServiceInfo().applicationInfo != null
                        && (info.getServiceInfo().applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                    return info.getId();
                }
                if (fallback == null) fallback = info.getId();
            }
        } catch (RuntimeException ignored) {
        }
        return fallback;
    }

    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }

    // MARK: - Colored logos (shelf)

    /** Bump when the way logos are lifted changes (the ones saved on disk are redone). */
    private static final int LOGO_VERSION = 2;

    private static Bitmap shelfLogo(Context c, String key, String pkg, String mode, boolean extract) {
        synchronized (LOGOS) {
            if (LOGOS.containsKey(key)) return LOGOS.get(key);
        }
        Bitmap logo = ownLogo(c, key);
        File file = logoFile(c, key, pkg), none = new File(file.getPath() + ".none");
        if (logo == null && file.exists()) logo = BitmapFactory.decodeFile(file.getPath());
        if (logo == null) {
            if (none.exists()) {
                logo = null;
            } else if (!extract) {
                return null;
            } else {
                logo = bannerLogo(c, pkg, mode.equals("tint"), mode.equals("play"));
                saveLogo(logo, file, none);
            }
        }
        if (logo != null) logo.setHasMipMap(true);
        synchronized (LOGOS) {
            LOGOS.put(key, logo);
        }
        return logo;
    }

    /** File for the logo lifted from the TV art: it changes when the app is updated (the art may have changed). */
    private static File logoFile(Context c, String key, String pkg) {
        long updated = 0;
        try {
            updated = c.getPackageManager().getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return new File(new File(c.getCacheDir(), "logos"), key + "-" + updated + "-v" + LOGO_VERSION + ".png");
    }

    private static void saveLogo(Bitmap logo, File file, File none) {
        File dir = file.getParentFile();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(logo != null ? file : none)) {
            if (logo != null) logo.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            AppLog.w("logo not saved: " + e.getMessage());
        }
    }

    /** Fraction (0 to 1) of the logo's pixels that have ink, by sampling. */
    private static float ink(Bitmap logo) {
        int w = logo.getWidth(), h = logo.getHeight(), hits = 0, total = 0;
        for (int y = 0; y < h; y += Math.max(1, h / 48)) {
            for (int x = 0; x < w; x += Math.max(1, w / 96)) {
                if ((logo.getPixel(x, y) >>> 24) > 128) hits++;
                total++;
            }
        }
        return total == 0 ? 0.4f : Math.max(0.08f, (float) hits / total);
    }

    /**
     * Logo lifted from the app's TV art: the background is removed (estimated from the edges, gradients are fine), the
     * brand colors stay and black turns white (the card is dark). With `tint`, a white logo on a colored background
     * takes the background's color; with `holes`, holes inside colored shapes (the play triangle) turn white. The
     * edges are "decontaminated" (no halo from the background).
     */
    private static Bitmap bannerLogo(Context c, String pkg, boolean tint, boolean holes) {
        Drawable d = bannerDrawable(c, pkg);
        if (d == null) return null;
        final int w = 768, h = 432;
        Bitmap src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(src);
        canvas.drawColor(0xFF15161C);
        d.setBounds(0, 0, w, h);
        d.draw(canvas);
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        src.recycle();
        // Edge profiles (a 3% band), smoothed: the background at any point is the "patch" spanning the four sides.
        int band = Math.round(h * 0.03f);
        float[][] left = new float[h][3], right = new float[h][3], top = new float[w][3], bottom = new float[w][3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < band; x++) {
                add(left[y], px[y * w + x], 1f / band);
                add(right[y], px[y * w + w - 1 - x], 1f / band);
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < band; y++) {
                add(top[x], px[y * w + x], 1f / band);
                add(bottom[x], px[(h - 1 - y) * w + x], 1f / band);
            }
        }
        smooth(left, 25);
        smooth(right, 25);
        smooth(top, 41);
        smooth(bottom, 41);
        float[] p00 = top[0], p10 = top[w - 1], p01 = bottom[0], p11 = bottom[w - 1];
        float[] mean = new float[3];
        for (int i = 0; i < 3; i++) mean[i] = (p00[i] + p10[i] + p01[i] + p11[i] + top[w / 2][i] + bottom[w / 2][i] + left[h / 2][i] + right[h / 2][i]) / 8;
        float bgLum = lum(mean), bgSat = sat(mean);
        int[] out = new int[w * h];
        float[] bg = new float[3], col = new float[3];
        int opaque = 0;
        for (int y = 0; y < h; y++) {
            float v = (float) y / (h - 1);
            for (int x = 0; x < w; x++) {
                float u = (float) x / (w - 1);
                for (int i = 0; i < 3; i++) {
                    bg[i] = (1 - v) * top[x][i] + v * bottom[x][i] + (1 - u) * left[y][i] + u * right[y][i]
                            - ((1 - u) * (1 - v) * p00[i] + u * (1 - v) * p10[i] + (1 - u) * v * p01[i] + u * v * p11[i]);
                }
                int p = px[y * w + x];
                col[0] = ((p >> 16) & 255) / 255f;
                col[1] = ((p >> 8) & 255) / 255f;
                col[2] = (p & 255) / 255f;
                float dist = (float) Math.sqrt(((col[0] - bg[0]) * (col[0] - bg[0]) + (col[1] - bg[1]) * (col[1] - bg[1])
                        + (col[2] - bg[2]) * (col[2] - bg[2])) / 3);
                float a = smoothstep(0.07f, 0.22f, dist);
                if (a <= 0.004f) continue;
                for (int i = 0; i < 3; i++) col[i] = clamp((col[i] - (1 - a) * bg[i]) / a);   // no background halo
                float l = lum(col), s = sat(col);
                if (bgLum > 0.6f && l < 0.42f && s < 0.3f) {
                    col[0] = col[1] = col[2] = 1f;                  // black text on a light background → white
                } else if (tint && bgLum <= 0.6f && bgLum > 0.1f && bgSat > 0.35f && l > 0.7f && s < 0.25f) {
                    vivid(bg, col);                                 // white logo on brand color → that color, vivid
                }
                out[y * w + x] = (Math.round(a * 255) << 24) | (Math.round(col[0] * 255) << 16) | (Math.round(col[1] * 255) << 8) | Math.round(col[2] * 255);
                if (a > 0.5f) opaque++;
            }
        }
        float area = (float) opaque / (w * h);
        if (area < 0.01f || area > 0.6f) return null;    // couldn't split the logo from the background: keep the card
        if (holes) fillHoles(out, w, h);
        Bitmap logo = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
        return trimLogo(logo);
    }

    private static Drawable bannerDrawable(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try {
            Intent launch = launchIntent(pm, pkg);
            if (launch != null && launch.getComponent() != null) {
                ActivityInfo ai = pm.getActivityInfo(launch.getComponent(), 0);
                int res = ai.getBannerResource();
                if (res != 0) {
                    android.content.res.Resources r = pm.getResourcesForApplication(ai.applicationInfo);
                    android.util.TypedValue value = new android.util.TypedValue();
                    r.getValue(res, value, true);
                    Drawable d = r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
                    if (d != null) AppLog.w("TV banner for " + pkg + ": " + value.string + " (" + d.getIntrinsicWidth() + "×" + d.getIntrinsicHeight() + ")");
                    return d;
                }
            }
            return pm.getApplicationBanner(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /** Closed holes inside colored shapes (the play triangle) turn white; the ones in letters don't. */
    private static void fillHoles(int[] px, int w, int h) {
        byte[] mark = new byte[w * h];   // 1 = outside (reached from the border), 2 = hole already visited
        int[] queue = new int[w * h];
        int head = 0, tail = 0;
        for (int x = 0; x < w; x++) {
            for (int y : new int[] {0, h - 1}) {
                int i = y * w + x;
                if (mark[i] == 0 && (px[i] >>> 24) < 128) { mark[i] = 1; queue[tail++] = i; }
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x : new int[] {0, w - 1}) {
                int i = y * w + x;
                if (mark[i] == 0 && (px[i] >>> 24) < 128) { mark[i] = 1; queue[tail++] = i; }
            }
        }
        while (head < tail) {
            int i = queue[head++], x = i % w, y = i / w;
            int[] next = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
            for (int n : next) {
                if (n >= 0 && mark[n] == 0 && (px[n] >>> 24) < 128) { mark[n] = 1; queue[tail++] = n; }
            }
        }
        for (int start = 0; start < w * h; start++) {
            if (mark[start] != 0 || (px[start] >>> 24) >= 128) continue;
            head = 0;
            tail = 0;
            queue[tail++] = start;
            mark[start] = 2;
            float satSum = 0;
            int border = 0;
            while (head < tail) {
                int i = queue[head++], x = i % w, y = i / w;
                int[] next = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
                for (int n : next) {
                    if (n < 0) continue;
                    if ((px[n] >>> 24) >= 128) {
                        float[] c = {((px[n] >> 16) & 255) / 255f, ((px[n] >> 8) & 255) / 255f, (px[n] & 255) / 255f};
                        satSum += sat(c);
                        border++;
                    } else if (mark[n] == 0) {
                        mark[n] = 2;
                        queue[tail++] = n;
                    }
                }
            }
            if (border > 0 && satSum / border > 0.45f && tail < w * h / 20) {
                for (int k = 0; k < tail; k++) px[queue[k]] = 0xFFFFFFFF;
            }
        }
    }

    private static Bitmap trimLogo(Bitmap b) {
        Bitmap t = trim(b);
        int pad = Math.max(2, Math.round(Math.max(t.getWidth(), t.getHeight()) * 0.02f));
        Bitmap out = Bitmap.createBitmap(t.getWidth() + 2 * pad, t.getHeight() + 2 * pad, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(t, pad, pad, null);
        return out;
    }

    private static void add(float[] acc, int p, float k) {
        acc[0] += ((p >> 16) & 255) / 255f * k;
        acc[1] += ((p >> 8) & 255) / 255f * k;
        acc[2] += (p & 255) / 255f * k;
    }

    /** Moving average (odd window) on each channel of the profile. */
    private static void smooth(float[][] profile, int window) {
        int n = profile.length, r = window / 2;
        float[][] copy = new float[n][];
        for (int i = 0; i < n; i++) copy[i] = profile[i].clone();
        for (int i = 0; i < n; i++) {
            float[] sum = new float[3];
            int count = 0;
            for (int j = Math.max(0, i - r); j <= Math.min(n - 1, i + r); j++) {
                for (int k = 0; k < 3; k++) sum[k] += copy[j][k];
                count++;
            }
            for (int k = 0; k < 3; k++) profile[i][k] = sum[k] / count;
        }
    }

    private static float lum(float[] c) {
        return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
    }

    private static float sat(float[] c) {
        float max = Math.max(c[0], Math.max(c[1], c[2])), min = Math.min(c[0], Math.min(c[1], c[2]));
        return max <= 0 ? 0 : (max - min) / max;
    }

    /** The local background color (gradients included), vivid and bright: the logo stands out on the dark card. */
    private static void vivid(float[] bg, float[] out) {
        float[] hsv = new float[3];
        android.graphics.Color.RGBToHSV(Math.round(bg[0] * 255), Math.round(bg[1] * 255), Math.round(bg[2] * 255), hsv);
        hsv[1] = Math.max(hsv[1], 0.55f);
        hsv[2] = 1f;
        int c = android.graphics.Color.HSVToColor(hsv);
        out[0] = ((c >> 16) & 255) / 255f;
        out[1] = ((c >> 8) & 255) / 255f;
        out[2] = (c & 255) / 255f;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    // MARK: - Cards

    private static synchronized Bitmap appTile(Context c, String key, String pkg, int brand) {
        String cacheKey = key != null ? key : pkg;
        if (TILES.containsKey(cacheKey)) return TILES.get(cacheKey);
        Bitmap logo = key == null ? null : ownLogo(c, key);
        Bitmap tile = logo != null ? logoTile(logo, brand) : bannerTile(c, pkg);
        if (tile == null) tile = iconTile(c, pkg);
        tile.setHasMipMap(true);
        TILES.put(cacheKey, tile);
        return tile;
    }

    /** The app's official TV art (the Android TV banner), at the highest density the app has. */
    private static Bitmap bannerTile(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Drawable d = null;
        try {
            Intent launch = launchIntent(pm, pkg);
            if (launch != null && launch.getComponent() != null) {
                ActivityInfo ai = pm.getActivityInfo(launch.getComponent(), 0);
                int res = ai.getBannerResource();
                if (res != 0) d = pm.getResourcesForApplication(ai.applicationInfo).getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
            }
            if (d == null) d = pm.getApplicationBanner(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        if (d == null) return null;
        Bitmap b = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        canvas.drawColor(0xFF15161C);   // banners with transparency sit on a dark background
        d.setBounds(0, 0, TILE_W, TILE_H);
        d.draw(canvas);
        return b;
    }

    /** No banner: the app's icon (in its proper shape, with some margin) on a dark card. */
    private static Bitmap iconTile(Context c, String pkg) {
        Bitmap b = darkTile();
        Canvas canvas = new Canvas(b);
        Drawable icon = null;
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (ai.icon != 0) {
                try {
                    icon = pm.getResourcesForApplication(ai).getDrawableForDensity(ai.icon, DisplayMetrics.DENSITY_XXXHIGH, null);
                } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                }
            }
            if (icon == null) icon = pm.getApplicationIcon(ai);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        if (icon == null) return b;
        int size = Math.round(TILE_H * 0.56f);
        float left = (TILE_W - size) / 2f, top = (TILE_H - size) / 2f;
        if (icon instanceof AdaptiveIconDrawable) {
            // Adaptive icon: the layers clipped to a squircle, like an Apple icon.
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) icon;
            Path clip = new Path();
            clip.addRoundRect(new RectF(left, top, left + size, top + size), size * 0.2237f, size * 0.2237f, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            int pad = size / 4;
            for (Drawable layer : new Drawable[] {a.getBackground(), a.getForeground()}) {
                if (layer == null) continue;
                layer.setBounds(Math.round(left) - pad, Math.round(top) - pad, Math.round(left) + size + pad, Math.round(top) + size + pad);
                layer.draw(canvas);
            }
            canvas.restore();
        } else {
            // Legacy icon (a free-form drawing with transparency): smaller, no frame.
            int s = Math.round(TILE_H * 0.46f);
            icon.setBounds((TILE_W - s) / 2, (TILE_H - s) / 2, (TILE_W + s) / 2, (TILE_H + s) / 2);
            icon.draw(canvas);
        }
        return b;
    }

    /** Settings and the like: the icon in white on a dark card. */
    private static synchronized Bitmap symbolTile(Context c, String key, String symbol) {
        if (TILES.containsKey(key)) return TILES.get(key);
        Bitmap b = darkTile();
        Symbols.draw(c, new Canvas(b), symbol, TILE_W / 2f, TILE_H / 2f, TILE_H * 0.36f, 0xF2FFFFFF);
        b.setHasMipMap(true);
        TILES.put(key, b);
        return b;
    }

    /** The official logo (no background) on dark glass with a faint brand tint coming from below. */
    private static Bitmap logoTile(Bitmap logo, int brand) {
        Bitmap b = darkTile();
        Canvas canvas = new Canvas(b);
        Paint glow = new Paint();
        glow.setShader(new RadialGradient(TILE_W / 2f, TILE_H * 1.15f, TILE_W * 0.75f, Ui.alpha(brand, 0.38f), 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, TILE_W, TILE_H, glow);
        boolean wide = logo.getWidth() > logo.getHeight() * 1.35f;
        float maxW = TILE_W * (wide ? 0.62f : 0.4f), maxH = TILE_H * (wide ? 0.42f : 0.56f);
        float scale = Math.min(maxW / logo.getWidth(), maxH / logo.getHeight());
        float w = logo.getWidth() * scale, h = logo.getHeight() * scale;
        canvas.drawBitmap(logo, null, new RectF((TILE_W - w) / 2, (TILE_H - h) / 2, (TILE_W + w) / 2, (TILE_H + h) / 2),
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        return b;
    }

    private static Bitmap darkTile() {
        Bitmap b = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888);
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, 0, TILE_H, 0xFF2B2E3A, 0xFF17191F, Shader.TileMode.CLAMP));
        new Canvas(b).drawRect(0, 0, TILE_W, TILE_H, bg);
        return b;
    }

    /** Official logo (tools/logos.py): trims the transparent margins and scales it down to at most 512 px. */
    /** The bundled logo, read once per process: reading it again on every load made a new image and filled caches. */
    private static Bitmap ownLogoCached(Context c, String key) {
        synchronized (LOGOS) {
            if (LOGOS.containsKey("own:" + key)) return LOGOS.get("own:" + key);
        }
        Bitmap b = ownLogo(c, key);
        synchronized (LOGOS) {
            LOGOS.put("own:" + key, b);
        }
        return b;
    }

    private static Bitmap ownLogo(Context c, String key) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = c.getAssets().open("logos/" + key + ".png")) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / (opts.inSampleSize * 2) >= 640) opts.inSampleSize *= 2;
            Bitmap b;
            try (InputStream in = c.getAssets().open("logos/" + key + ".png")) {
                b = BitmapFactory.decodeStream(in, null, opts);
            }
            if (b == null) return null;
            b = trim(b);
            float scale = Math.min(1f, 512f / Math.max(b.getWidth(), b.getHeight()));
            if (scale < 1f) b = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * scale)), Math.max(1, Math.round(b.getHeight() * scale)), true);
            return b;
        } catch (IOException e) {
            return null;
        }
    }

    private static Bitmap trim(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] row = new int[w];
        int top = h, bottom = -1, left = w, right = -1;
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) > 10) {
                    if (y < top) top = y;
                    bottom = y;
                    if (x < left) left = x;
                    if (x > right) right = x;
                }
            }
        }
        if (bottom < 0) return b;
        return Bitmap.createBitmap(b, left, top, right - left + 1, bottom - top + 1);
    }
}
