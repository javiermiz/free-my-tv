package io.freemytv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen, laid out like today's streaming TV apps: a featured title on top, then rows of vertical posters.
 * Moving down brings a row to the top; the focused title is always first in its row, widened to 16:9, and after a
 * moment its trailer (YouTube) plays inside it. Its details sit below the row. At the end: your apps, the
 * rest of the apps, and inputs and settings.
 *
 * Drawn by hand on one Canvas (in a 1920×1080 space, k = width / 1920); the trailer is a WebView underneath, showing
 * through a hole the Canvas leaves where the card is.
 */
final class FlixView extends FrameLayout implements Recs.Listener, HomeData.Listener {
    private static final float MARGIN = 72, NAV_H = 120;
    private static final float LEFT = 168, SIDE_W = 120;   // content starts right of the sidebar's icons
    private static final float LEAD = 150;   // title rows: the card before the focused one peeks in this much
    private static final float POSTER_W = 420, POSTER_H = 630, WIDE_W = 1120, GAP = 22;   // posters 2:3; the focused card 16:9
    private static final float APP_W = 400, APP_H = 225;
    private static final float BILL_TOP = 132, BILL_H = 770;
    private static final long TRAILER_DELAY = 700, ROTATE_EVERY = 20_000;
    private static final int FEATURED_MAX = 6;
    private static final int DEFAULT_ACCENT = 0xFFF5A524;
    /** The sidebar's tabs (after Search): Home, Shows, Movies, Anime. */
    private static final String[] TAB_KIND = {null, "tv", "movie"};   // anime: the Anime platform on top
    /**
     * The platforms on top (the ones installed): Recs' service key, name, its color and a ground tinted with it, its
     * one-color logo on TMDB (a transparent PNG, drawn white, or dark on its white pill; "" = its name), then its
     * packages. Choosing one shows only what's on it, in its colors.
     */
    private static final String[][] PLATFORMS = {
        {"netflix", "Netflix", "E50914", "0A0505", "/wwemzKWzjKYJFfCeiB57q3r4Bcm.png", "com.netflix.ninja"},
        {"disney", "Disney+", "1F80E0", "040A16", "/1edZOYAfoyZyZ3rklNSiUpXX30Q.png", "com.disney.disneyplus"},
        {"prime", "Prime Video", "00A8E1", "03090D", "/w7HfLNm9CWwRmAMU58udl2L7We7.png", "com.amazon.amazonvideo.livingroom"},
        {"max", "Max", "7B5CFF", "07051A", "/nmU0UMDJB3dRRQSTUqawzF2Od1a.png", "com.wbd.stream"},
        {"paramount", "Paramount+", "0064FF", "03061A", "/fi83B1oztoS47xxcemFdPMhIzK.png", "com.cbs.ott", "com.cbs.ca"},
        // Anime: ALL the anime (from any app), with Crunchyroll's logo and colors; shown even without Crunchyroll.
        {"anime", "Anime", "F47521", "0D0702", "/qqyXcZlJQKlRmAD1TCKV7mGLQlt.png", "com.crunchyroll.crunchyroid"},
        {"viki", "Viki", "4DB8FF", "040A10", "", "com.viki.android"},
        {"appletv", "Apple TV", "D4D4D8", "060606", "/bngHRFi794mnMq34gfVcm9nDxN1.png", "com.apple.atve.androidtv.appletv"},
        {"stremio", "Stremio", "9B7BD4", "08060D", "", "com.stremio.one"},
    };

    private static final class Platform {
        String key, name, pkg; // key null = all of them ("For you")
        int accent, ground;
        String logo;           // its one-color logo on TMDB, or null: its name
    }

    private final List<Platform> platforms = new ArrayList<>();
    private int platformIndex;
    private String platform;   // what's shown: null = every platform
    private int accent = DEFAULT_ACCENT, ground = 0xFF000000;
    /** The sidebar, open over the screen (focus there); sideIndex: 0 Search, 1-4 the tabs, 5 Settings. */
    private boolean sideOpen;
    private int sideIndex;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    static final class Card {
        Recs.Pick pick;
        HomeApps.Item app;
    }

    static final class Row {
        String title;
        final List<Card> cards = new ArrayList<>();
        boolean apps, shelf, others;
        Recs.Row source;   // title rows: where more of it comes from
    }

    private final Activity activity;
    private final Recs recs;
    private final HomeData data;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Screen screen;
    private final FrameLayout trailerBox;
    private final WebView web;
    private float k = 1;
    private int w, h;
    private boolean running;

    private List<Row> rows = new ArrayList<>();
    private Recs.Pick featured;
    private final List<Recs.Pick> featuredList = new ArrayList<>();
    private int featuredIndex;
    private String signature = "";
    private List<HomeApps.Item> shelf = new ArrayList<>(), others = new ArrayList<>(), extras = new ArrayList<>();

    /** -2 = the menu on top, -1 = the featured title, 0.. = a row. */
    private int focusRow = -1, navIndex, tab;
    private int[] focusCol = new int[0], firstCol = new int[0];   // focused card per row; apps rows: first one shown
    private float slideY;
    // The focused card growing to wide (0 → 1); the one it came from shrinking.
    private float grow = 1;
    private int growDir, shrinkIdx = -1;
    private android.animation.ValueAnimator slider;
    private boolean okDown, okLong, moving;

    // The trailer playing (or about to) and where.
    private String trailerKey;
    private boolean trailerOn;
    private final RectF trailerRect = new RectF();
    private final Runnable trailerStart = this::fetchTrailer;

    // Continue watching: the movie or episode itself plays in its card (instead of a trailer) from where you left it;
    // OK opens its page (the show page) over it, full screen, and Resume takes the page away, the same video going on.
    private final Playback watch;
    private final Runnable watchStart = this::fetchWatch;
    private final Runnable watchAhead = this::loadAhead;
    private static final long WATCH_DELAY = 150;   // only to skip the cards you go past quickly
    private Recs.Pick keepWatch;      // while rescheduling: the video not to stop
    private Recs.Pick watchPick;      // whose video is in `watch` (null = none)
    private boolean watchOn;          // its first frame is on screen
    private long watchOnAt;           // since when (the picture fades into it)
    private long watchAsk;
    private final RectF watchRect = new RectF();
    /** The featured title changes by itself while it has focus and no trailer is playing. */
    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            if (running && focusRow == -1 && !trailerOn && !watchOn && detail == null) nextFeatured(1);
            handler.postDelayed(this, ROTATE_EVERY);
        }
    };

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Platform logos: their shape in one color (white, or dark on a white pill). */
    private final Paint logoLight = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG),
            logoDark = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    {
        logoLight.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN));
        logoDark.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFF000000, android.graphics.PorterDuff.Mode.SRC_IN));
    }
    private final Path iconPath = new Path();
    private Paint navOn, navOff, navFocus, rowTitle, rowPeek, cardTitle, wideTitle, kindLabel, badge, weatherText, button, peekTitle;
    private Paint menuHead, menuOn, menuOff;
    /** The menu a long press on OK opens (a title's or an app's), over everything; null title = closed. */
    private String menuTitle;
    private final List<String> menuLabels = new ArrayList<>();
    private final List<Runnable> menuActions = new ArrayList<>();
    private int menuFocus;
    private boolean menuOk;
    private TextPaint meta, overview, billTitle, billMeta, billOverview, nameUnder, pill;
    private final RectF r1 = new RectF(), r2 = new RectF(), logoRect = new RectF();
    private final Paint whiteLogo = new Paint(Paint.FILTER_BITMAP_FLAG);
    {
        whiteLogo.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN));
    }
    private final java.util.Map<Bitmap, Boolean> darkLogos = new java.util.WeakHashMap<>();   // black logos don't show on the dark fade
    private final Rect src = new Rect();
    private final Path hole = new Path();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            recs.refreshIfStale();
            rebuild(false);
            screen.invalidate();
            handler.postDelayed(this, 15_000);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    FlixView(Activity a) {
        super(a);
        activity = a;
        recs = Recs.get(a);
        data = HomeData.get(a);
        click = new Click(a);
        setBackgroundColor(0xFF000000);
        trailerBox = new FrameLayout(a);
        trailerBox.setClipChildren(true);
        web = new WebView(a);
        web.setBackgroundColor(0xFF000000);
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setDomStorageEnabled(true);
        // The page's viewport is the card itself (with a "device-width" viewport it measured the whole screen).
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "FreeMyTV");
        trailerBox.addView(web, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        web.loadDataWithBaseURL("https://freemytv.io/", PLAYER, "text/html", "utf-8", null);
        trailerBox.setAlpha(0);
        addView(trailerBox, new LayoutParams(1, 1));
        watch = new Playback(a, new Playback.Listener() {
            @Override public void onStarted() {
                watchOn = true;
                watchOnAt = android.os.SystemClock.uptimeMillis();
                screen.invalidate();
            }

            @Override public void onDone(boolean failed) {
                if (failed && onVideoPage())
                    Toast.makeText(activity, Ui.t("No se pudo reproducir",
                            "Couldn't play it", "Não deu para reproduzir"), Toast.LENGTH_SHORT).show();
                stopWatch();
                if (onVideoPage()) closeDetail();
            }
        });
        addView(watch, new LayoutParams(1, 1));
        screen = new Screen(a);
        addView(screen, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        shelf = HomeApps.shelf(a, false);
        rebuild(true);
    }

    // MARK: - Lifecycle (called by HomeActivity)

    /** Chosen in Search (same process): opened when the home screen is back. */
    static volatile Recs.Pick pendingOpen;

    void resume() {
        final Recs.Pick chosen = pendingOpen;
        pendingOpen = null;
        if (chosen != null) handler.post(() -> openPick(chosen));
        if (running) return;
        running = true;
        recs.addListener(this);
        data.addListener(this);
        recs.refreshIfStale();
        data.refreshIfStale();
        loadPlatforms();
        reloadApps();
        rebuild(false);
        handler.postDelayed(tick, 15_000);
        handler.postDelayed(rotate, ROTATE_EVERY);
        scheduleTrailer();
        screen.invalidate();
    }

    void pause() {
        if (!running) return;
        running = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(rotate);
        recs.removeListener(this);
        data.removeListener(this);
        stopTrailer();
        stopWatch();
        if (onVideoPage()) closeDetail();
    }

    void destroy() {
        pause();
        click.release();
        web.destroy();
    }

    /** Home pressed while on the home screen: back to the featured title, on the first tab. */
    void reset() {
        stopWatch();
        if (onVideoPage()) closeDetail();
        moving = false;
        detail = null;
        focusRow = -1;
        sideOpen = false;
        if (tab != 0 || platformIndex != 0) {
            tab = 0;
            navIndex = 0;
            setPlatform(0);
            rebuild(true);
        }
        for (int i = 0; i < focusCol.length; i++) focusCol[i] = firstCol[i] = 0;
        featuredIndex = 0;
        if (!featuredList.isEmpty()) featured = featuredList.get(0);
        slideY = 0;
        grow = 1;
        shrinkIdx = -1;
        scheduleTrailer();
        screen.invalidate();
    }

    @Override public void onRecs() {
        if (!Config.STREMIO_KEY.trim().isEmpty()) {
            final java.io.File cache = activity.getCacheDir();
            List<Recs.Pick> going = recs.resumes();
            for (int i = 0; i < Math.min(3, going.size()); i++) {
                final String link = going.get(i).link;
                if (link != null && link.startsWith("stremio:///detail/")) LOADER.execute(() -> Streams.prefetch(link, cache));
            }
        }
        rebuild(false);
        if (!handler.hasCallbacks(badgesNow)) scheduleBadges();   // images loading notify often: don't keep pushing it back
        screen.invalidate();
    }

    @Override public void onData() {
        screen.invalidate();
    }

    // MARK: - Rows

    private void reloadApps() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = HomeApps.shelf(c, true);
            final List<HomeApps.Item> o = HomeApps.others(c);
            final List<HomeApps.Item> x = new ArrayList<>(HomeApps.allInputs(c));
            x.add(HomeApps.settings());
            x.add(HomeApps.action(HomeApps.ANDROID_SETTINGS, "tv-settings", Ui.t("Ajustes de la TV",
                    "TV Settings", "Ajustes da TV"), "tv_settings"));
            handler.post(() -> {
                shelf = s;
                others = o;
                extras = x;
                rebuild(true);
                screen.invalidate();
            });
        });
    }

    /** Rows for the current tab; rebuilt only when what they hold changed (`force`: always). */
    private void rebuild(boolean force) {
        String kind = TAB_KIND[tab];
        List<Row> out = new ArrayList<>();
        List<Recs.Pick> going = new ArrayList<>();
        for (Recs.Pick p : recs.resumes()) if (fits(p, kind)) going.add(p);
        if (!going.isEmpty()) {
            Row r = row(out, Ui.t("Continuar viendo", "Continue watching", "Continuar assistindo"));
            for (Recs.Pick p : going) r.cards.add(card(p));
        }
        List<Recs.Pick> listed = new ArrayList<>();
        for (Recs.Pick p : recs.favorites()) if (fits(p, kind)) listed.add(p);
        if (!listed.isEmpty()) {
            Row r = row(out, Ui.t("Mi lista", "My list", "Minha lista"));
            for (Recs.Pick p : listed) r.cards.add(card(p));
        }
        // What's new (new episodes of your shows, premieres) right under it, before everything else.
        for (Recs.Row rr : recs.rows(platform)) if (rr.top) addRow(out, rr, kind);
        List<Recs.Pick> mine = new ArrayList<>();
        for (Recs.Pick p : recs.now("anime".equals(kind) ? null : kind)) if (!p.resuming && p.pkg != null && fits(p, kind)) mine.add(p);
        // The banner is always something new for you: never a title you're in the middle of (that's Continue watching).
        java.util.Set<String> watching = new java.util.HashSet<>();
        for (Recs.Pick p : recs.resumes()) watching.add(Recs.key(p));
        featuredList.clear();
        for (int i = 0; i < mine.size() && featuredList.size() < FEATURED_MAX; i++)
            if (!watching.contains(Recs.key(mine.get(i)))) featuredList.add(mine.get(i));
        featuredIndex = featuredList.isEmpty() ? 0 : Math.min(featuredIndex, featuredList.size() - 1);
        featured = featuredList.isEmpty() ? null : featuredList.get(featuredIndex);
        if (!mine.isEmpty()) {
            Row r = row(out, Ui.t("Para ti", "For you", "Para você"));
            for (Recs.Pick p : mine) r.cards.add(card(p));
        }
        for (Recs.Row rr : recs.rows(platform)) if (!rr.top) addRow(out, rr, kind);   // a platform's own rows, or everyone's
        // Nothing picked for this tab or platform: the featured titles come from its rows.
        for (int i = 0; featuredList.isEmpty() && i < out.size(); i++) {
            for (Card c : out.get(i).cards) {
                if (c.pick != null && !c.pick.resuming && !watching.contains(Recs.key(c.pick)) && c.pick.pkg != null
                        && featuredList.size() < FEATURED_MAX) featuredList.add(c.pick);
            }
            if (!featuredList.isEmpty()) featured = featuredList.get(featuredIndex = 0);
        }
        StringBuilder sig = new StringBuilder(tab + "/" + platform);
        for (Row r : out) {
            sig.append('|').append(r.title);
            for (Card c : r.cards) sig.append(',').append(c.pick != null ? Recs.key(c.pick) + c.pick.backdrop : c.app.key);
        }
        if (!force && sig.toString().equals(signature)) return;
        signature = sig.toString();
        int[] cols = new int[out.size()], firsts = new int[out.size()];
        for (int i = 0; i < cols.length && i < focusCol.length; i++) {
            cols[i] = Math.min(focusCol[i], out.get(i).cards.size() - 1);
            firsts[i] = Math.min(firstCol[i], cols[i]);
        }
        rows = out;
        focusCol = cols;
        firstCol = firsts;
        if (focusRow >= rows.size()) focusRow = rows.size() - 1;
    }

    private void addRow(List<Row> out, Recs.Row rr, String kind) {
        Row r = new Row();
        r.title = rr.title;
        r.source = rr;
        for (Recs.Pick p : rr.picks) if (fits(p, kind)) r.cards.add(card(p));
        if (rr.saga) markNext(r);
        if (!r.cards.isEmpty()) out.add(r);
    }

    /** A saga: after the last one you watched, the next is marked (nothing marked if you haven't started it). */
    private void markNext(Row r) {
        int last = -1;
        for (int i = 0; i < r.cards.size(); i++) {
            r.cards.get(i).pick.sagaNext = false;
            if (recs.watched(r.cards.get(i).pick)) last = i;
        }
        for (int i = last + 1; last >= 0 && i < r.cards.size(); i++) {
            if (!recs.watched(r.cards.get(i).pick)) {
                r.cards.get(i).pick.sagaNext = true;
                if (!r.title.equals(sagaLogged.put(r.title, r.cards.get(i).pick.title)))
                    AppLog.w("saga " + r.title + ": next is " + r.cards.get(i).pick.title);
                return;
            }
        }
        if (sagaLogged.put(r.title, "") == null) AppLog.w("saga " + r.title + ": " + (last < 0 ? "none watched yet" : "all watched"));
    }

    private final java.util.Map<String, String> sagaLogged = new java.util.HashMap<>();   // logged once per saga

    /** In this tab (shows, movies, anime) and on the chosen platform (Stremio: what opens in it). */
    private boolean fits(Recs.Pick p, String kind) {
        if (kind != null && !("anime".equals(kind) ? p.anime : kind.equals(p.kind))) return false;
        if (platform == null) return true;
        if (platform.equals("anime")) return p.anime;   // every anime, whichever app has it
        if (platform.equals("stremio")) return "stremio".equals(p.app);
        return platform.equals(p.app) || ("," + p.services + ",").contains("," + platform + ",");
    }

    private static Row row(List<Row> out, String title) {
        Row r = new Row();
        r.title = title;
        out.add(r);
        return r;
    }

    private static Row appRow(List<Row> out, String title, List<HomeApps.Item> items) {
        Row r = new Row();
        r.title = title;
        r.apps = true;
        for (HomeApps.Item it : items) {
            Card c = new Card();
            c.app = it;
            r.cards.add(c);
        }
        if (!items.isEmpty()) out.add(r);
        return r;
    }

    private static Card card(Recs.Pick p) {
        Card c = new Card();
        c.pick = p;
        return c;
    }

    private Card focused() {
        if (focusRow < 0 || focusRow >= rows.size()) return null;
        Row r = rows.get(focusRow);
        return r.cards.isEmpty() ? null : r.cards.get(Math.min(focusCol[focusRow], r.cards.size() - 1));
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        if (onVideoPage() && detail.full) return fullKey(e);
        if (menuTitle != null) return menuKey(e);
        if (sideOpen) return sideKey(e);
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (down) move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_UP: if (down) move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (down) move(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here; held: its menu (My list, Continue watching; an app's).
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                    else if (okDown && e.getRepeatCount() >= 1) okLong = true;
                } else {
                    if (moving) moving = false;
                    else if (okDown && okLong) { if (!editMenu()) activate(); }
                    else if (okDown && !e.isCanceled()) activate();
                    okDown = okLong = false;
                    screen.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) return true;
                if (detail != null) {
                    closeDetail();
                    return true;
                }
                if (moving) moving = false;
                else if (focusRow >= 0 || focusRow == -2) focusRow = -1;
                else if (tab != 0) selectTab(0);
                else if (platformIndex != 0) selectPlatform(0);
                else {
                    openSide();   // already at the top of Home: the sidebar
                    return true;
                }
                changed(0, 0);
                return true;
            default:
                return false;
        }
    }

    private void move(int dx, int dy) {
        if (detail != null) {
            detailMove(dx, dy);
            return;
        }
        if (moving && focusRow >= 0 && rows.get(focusRow).shelf) {
            moveShelfApp(dx);
            return;
        }
        if (focusRow == -2) {
            if (dx < 0 && navIndex == 0) {
                openSide();
                return;
            }
            if (dx != 0) {
                int next = Math.max(0, Math.min(platforms.size() - 1, navIndex + dx));
                if (next == navIndex) return;
                navIndex = next;
                selectPlatform(next);
            } else if (dy > 0) {
                focusRow = -1;
            } else {
                return;
            }
            changed(0, 0);
            return;
        }
        if (focusRow == -1) {
            if (dx < 0 && featuredList.size() < 2) {
                openSide();   // nothing to go back to: the sidebar
                return;
            }
            if (dx != 0) {
                if (featuredList.size() < 2) return;
                nextFeatured(dx);
                return;
            }
            if (dy < 0) {
                focusRow = -2;
                navIndex = platformIndex;
            } else if (dy > 0 && !rows.isEmpty()) {
                focusRow = 0;
                changed(0, 1);
                return;
            } else {
                return;
            }
            changed(0, 0);
            return;
        }
        Row r = rows.get(focusRow);
        if (dx < 0 && r.apps && focusCol[focusRow] == 0) {
            openSide();   // apps stop at the ends: past the first, the sidebar (title rows still go round)
            return;
        }
        if (dx != 0) {
            int n = r.cards.size();
            // Title rows go round (after the last, the first again); apps stop at the ends.
            int next = r.apps ? Math.max(0, Math.min(n - 1, focusCol[focusRow] + dx)) : n < 2 ? 0 : (focusCol[focusRow] + dx + n) % n;
            if (r.source != null && next >= n - 6) recs.more(r.source);   // near its end (or short): the next page
            if (next == focusCol[focusRow]) return;
            focusCol[focusRow] = next;
            if (r.apps) {
                // Apps don't slide: the ring moves, and the row scrolls only at its edges.
                int visible = Math.max(1, (int) ((1920 - LEFT - MARGIN + GAP) / (APP_W + GAP)));
                if (next < firstCol[focusRow]) firstCol[focusRow] = next;
                if (next > firstCol[focusRow] + visible - 1) firstCol[focusRow] = next - visible + 1;
                changed(0, 0);
            } else {
                changed(dx, 0);
            }
        } else if (dy < 0) {
            focusRow--;
            changed(0, -1);
        } else if (dy > 0 && focusRow < rows.size() - 1) {
            focusRow++;
            changed(0, 1);
        }
        if (dy != 0 && focusRow >= 0 && focusRow < rows.size()) {
            Row now = rows.get(focusRow);
            if (now.source != null && now.cards.size() < 12) recs.more(now.source);   // a short row fills up when you reach it
        }
        if (dy > 0) moreRowsIfNear();
    }

    /** Fewer than 5 title rows left below: more come (Recs stops after a while). */
    private void moreRowsIfNear() {
        int left = 0;
        for (int i = focusRow + 1; i < rows.size(); i++) if (rows.get(i).source != null) left++;
        if (left < 5) recs.moreRows(platform);
    }

    private void selectPlatform(int index) {
        setPlatform(index);
        for (int i = 0; i < focusCol.length; i++) focusCol[i] = firstCol[i] = 0;
        featuredIndex = 0;
        rebuild(true);
    }

    private void setPlatform(int index) {
        platformIndex = Math.max(0, Math.min(platforms.size() - 1, index));
        Platform p = platforms.isEmpty() ? null : platforms.get(platformIndex);
        platform = p == null ? null : p.key;
        accent = p == null ? DEFAULT_ACCENT : p.accent;
        ground = p == null ? 0xFF000000 : p.ground;
    }

    /** The installed platforms, "For you" first; the chosen one kept if still there. */
    private void loadPlatforms() {
        String chosen = platform;
        platforms.clear();
        Platform all = new Platform();
        all.name = Ui.t("Para ti", "For you", "Para você");
        all.accent = DEFAULT_ACCENT;
        all.ground = 0xFF000000;
        platforms.add(all);
        android.content.pm.PackageManager pm = getContext().getPackageManager();
        for (String[] s : PLATFORMS) {
            for (int i = 5; i < s.length; i++) {
                try {
                    pm.getPackageInfo(s[i], 0);
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    if (!s[0].equals("anime")) continue;   // a category, not an app
                }
                Platform p = new Platform();
                p.key = s[0];
                p.name = s[1];
                p.pkg = s[i];
                p.accent = 0xFF000000 | Integer.parseInt(s[2], 16);
                p.ground = 0xFF000000 | Integer.parseInt(s[3], 16);
                p.logo = s[4].isEmpty() ? null : s[4];
                platforms.add(p);
                break;
            }
        }
        int at = 0;
        for (int i = 0; i < platforms.size(); i++) if (chosen != null && chosen.equals(platforms.get(i).key)) at = i;
        setPlatform(at);
    }

    // MARK: - Sidebar: Search, the tabs, Settings

    private static final int SIDE_ITEMS = 6, SIDE_APPS = 4;   // anime is a platform on top now

    private String sideLabel(int i) {
        switch (i) {
            case 0: return Ui.t("Buscar", "Search", "Buscar");
            case 1: return Ui.t("Inicio", "Home", "Início");
            case 2: return Ui.t("Series", "Shows", "Séries");
            case 3: return Ui.t("Películas", "Movies", "Filmes");
            case SIDE_APPS: return "Apps";
            default: return Ui.t("Ajustes", "Settings", "Ajustes");
        }
    }

    private void openSide() {
        sideOpen = true;
        sideIndex = tab + 1;
        click.move();
        screen.invalidate();
    }

    private void closeSide() {
        sideOpen = false;
        screen.invalidate();
    }

    private boolean sideKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                int next = Math.max(0, Math.min(SIDE_ITEMS - 1, sideIndex + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
                if (next != sideIndex) {
                    sideIndex = next;
                    click.move();
                    screen.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                closeSide();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() > 0) return true;
                click.open();
                closeSide();
                if (sideIndex == 0) {
                    // The TV's own search (Google TV's, with the voice too): it searches every app.
                    startOutside(new android.content.Intent(activity, SearchActivity.class));   // by name, in all of TMDB
                } else if (sideIndex == SIDE_APPS) {
                    startOutside(new android.content.Intent(activity, AppsActivity.class));
                } else if (sideIndex == SIDE_ITEMS - 1) {
                    startOutside(new android.content.Intent(activity, SettingsActivity.class));   // the TV's are in there
                } else {
                    if (sideIndex - 1 != tab) selectTab(sideIndex - 1);
                    focusRow = -1;
                    changed(0, 0);
                }
                return true;
            default:
                return true;
        }
    }

    private void startOutside(android.content.Intent in) {
        try {
            activity.startActivity(in.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            AppLog.w("couldn't open " + in.getAction() + ": " + e.getMessage());
            Toast.makeText(activity, Ui.t("No se pudo abrir",
                    "Couldn't open it", "Não deu para abrir"), Toast.LENGTH_SHORT).show();
        }
    }

    private void selectTab(int index) {
        tab = index;
        for (int i = 0; i < focusCol.length; i++) focusCol[i] = firstCol[i] = 0;
        featuredIndex = 0;
        rebuild(true);
    }

    private void nextFeatured(int dir) {
        if (featuredList.isEmpty()) return;
        featuredIndex = (featuredIndex + dir + featuredList.size()) % featuredList.size();
        featured = featuredList.get(featuredIndex);
        changed(0, 0);
    }

    private static final android.view.animation.Interpolator SPRING = new DecelerateInterpolator(2f);   // eases out, no bounce
    private static final android.view.animation.Interpolator EASE = new DecelerateInterpolator(1.6f);

    /**
     * Focus moved: the rows slide into place (dy); in a title row the new card grows to wide while the old one
     * shrinks back (dx, or on entering the row); then a click and the next trailer.
     */
    private void changed(int dx, int dy) {
        if (slider != null) slider.cancel();
        Row r = focusRow >= 0 && focusRow < rows.size() ? rows.get(focusRow) : null;
        boolean titles = r != null && !r.apps;
        int n = r == null ? 0 : r.cards.size();
        growDir = titles ? Integer.signum(dx) : 0;
        shrinkIdx = titles && dx != 0 && n > 1 ? (focusCol[focusRow] - dx + n) % n : -1;
        final boolean growing = titles && (dx != 0 || dy != 0);
        final float fromY = dy * 600f;
        if (growing || fromY != 0) {
            slider = android.animation.ValueAnimator.ofFloat(0, 1);
            slider.setDuration(growing ? 260 : 220);
            slider.addUpdateListener(v -> {
                float t = (float) v.getAnimatedValue();
                slideY = fromY * (1 - EASE.getInterpolation(Math.min(1, t * 260 / 220f)));
                if (growing) grow = SPRING.getInterpolation(t);
                screen.invalidate();
            });
            slider.start();
        } else {
            grow = 1;
        }
        click.move();
        scheduleTrailer();
        scheduleBadges();
        screen.invalidate();
    }

    // MARK: - Badges: in your debrid already, the Spanish it has, Spanish subtitles

    /** One lookup at a time (each asks all your add-ons): only for the titles you stop on. */
    private static final ExecutorService BADGES = Executors.newSingleThreadExecutor();
    private final java.util.Set<String> badgeWanted = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final java.util.Set<String> badgeAsked = new java.util.HashSet<>();
    private final Runnable badgesNow = this::checkBadges;

    /** Half a second after you stop moving (titles you only go past aren't looked up). */
    private void scheduleBadges() {
        handler.removeCallbacks(badgesNow);
        handler.postDelayed(badgesNow, 500);
    }

    /** The featured title, or the focused card and the next two. */
    private void checkBadges() {
        if (Config.STREMIO_KEY.trim().isEmpty()) return;
        List<Recs.Pick> near = new ArrayList<>();
        if (focusRow == -1 && featured != null) near.add(featured);
        else if (focusRow >= 0 && focusRow < rows.size() && !rows.get(focusRow).apps) {
            Row r = rows.get(focusRow);
            for (int i = 0; i < 3 && i < r.cards.size(); i++) {
                Card cd = r.cards.get((focusCol[focusRow] + i) % r.cards.size());
                if (cd.pick != null) near.add(cd.pick);
            }
        }
        badgeWanted.clear();
        for (final Recs.Pick p : near) {
            final String link = badgeLink(p);
            if (link == null) continue;
            badgeWanted.add(link);
            if (Streams.knownBadge(link) != null || !badgeAsked.add(link)) continue;
            badgeTexts.remove(link);   // "checking…" while it's asked
            BADGES.execute(() -> {
                if (badgeWanted.contains(link)) Streams.peek(link);   // else you went past it meanwhile
                handler.post(() -> {
                    badgeAsked.remove(link);
                    badgeTexts.remove(link);
                    screen.invalidate();
                });
            });
        }
        screen.invalidate();
    }

    private final java.util.Map<Recs.Pick, String> badgeLinks = new java.util.WeakHashMap<>();
    private final java.util.Map<String, String> badgeTexts = new java.util.HashMap<>();   // link → its line ("" = none yet)

    /** What's looked up for a card: the movie, the episode you're on, or a show's first episode (made once per title). */
    private String badgeLink(Recs.Pick p) {
        if (p == null) return null;
        String link = badgeLinks.get(p);
        if (link == null) badgeLinks.put(p, link = makeBadgeLink(p));
        return link.isEmpty() ? null : link;
    }

    private static String makeBadgeLink(Recs.Pick p) {
        String prefix = "stremio:///detail/";
        if (p.link == null || !p.link.startsWith(prefix)) return "";
        String[] parts = p.link.substring(prefix.length()).split("/");
        if (parts.length < 2) return "";
        if (parts[0].equals("series") && parts.length == 2) return p.link + "/" + parts[1] + (parts[1].startsWith("kitsu:") ? ":1" : ":1:1");
        return p.link;
    }

    /** "⚡ LAT · SUB" (in your debrid, Latin American Spanish, Spanish subtitles), "…" while it's asked, or null (made once). */
    private String badgeText(Recs.Pick p) {
        String link = badgeLink(p);
        if (link == null) return null;
        String t = badgeTexts.get(link);
        if (t == null) badgeTexts.put(link, t = makeBadgeText(link));
        return t.isEmpty() ? null : t;
    }

    private String makeBadgeText(String link) {
        Streams.Badge b = Streams.knownBadge(link);
        if (b == null) return badgeAsked.contains(link) ? "…" : "";
        if (b.none) return Ui.t("Sin versión", "None", "Sem versão");
        StringBuilder t = new StringBuilder();
        if (b.cached) t.append("⚡ ");
        // Dubbed in your language (LAT: Latin American, CAST: Spain's; DUB, PT-PT) or VO: the original version.
        t.append(b.latino ? Ui.t("LAT", "DUB", "DUB") : b.spain ? Ui.t("CAST",
                "DUB", "PT-PT") : Ui.t("VO", "OV", "VO"));
        if (b.subs) t.append(" · SUB");
        return t.toString();
    }

    private void activate() {
        if (detail != null) {
            detailActivate();
            return;
        }
        HomeApps.Item it;
        RectF r = focusRow >= 0 ? wideCardRect(new RectF()) : billboardRect(new RectF());
        if (focusRow == -2) {
            focusRow = -1;
            changed(0, 0);
            return;
        } else if (focusRow == -1) {
            if (featured == null && platform != null) {
                // Nothing of this platform to show yet: the card opens its app.
                Platform pl = platforms.get(platformIndex);
                launch(HomeApps.app(pl.pkg, pl.name), r);
                return;
            }
            if (featured == null || featured.pkg == null) return;
            if (openResume(featured)) return;
            it = HomeApps.app(featured.pkg, Recs.serviceName(featured));
            it.link = featured.link;
            if (pickEpisode(featured, it, r)) return;
        } else {
            Card c = focused();
            if (c == null) return;
            if (c.pick != null) {
                if (c.pick.pkg == null) return;
                if (openResume(c.pick)) return;
                it = HomeApps.app(c.pick.pkg, Recs.serviceName(c.pick));
                it.link = c.pick.link;
                if (pickEpisode(c.pick, it, r)) return;
            } else {
                it = c.app;
            }
        }
        launch(it, r);
    }

    /** A title from elsewhere (Search) as if its card were pressed: a show's page, a movie plays. */
    private void openPick(Recs.Pick p) {
        if (p == null || p.pkg == null) return;
        RectF r = billboardRect(new RectF());
        if (openResume(p)) return;
        HomeApps.Item it = HomeApps.app(p.pkg, Recs.serviceName(p));
        it.link = p.link;
        if (pickEpisode(p, it, r)) return;
        launch(it, r);
    }

    private void launch(HomeApps.Item it, RectF r) {
        click.open();
        stopTrailer();
        // A title in Stremio: our own player, on the best stream in Spanish, from where you left it.
        if (it.link != null && it.link.startsWith("stremio:///detail/") && !Config.STREMIO_KEY.trim().isEmpty()) {
            final String link = it.link;
            final long asked = ++streamAsk;
            final Recs.Pick p = detail != null ? detail.pick : focusRow == -1 ? featured : focused() != null ? focused().pick : null;
            findingRect.set(r);
            findingSince = android.os.SystemClock.uptimeMillis();
            screen.invalidate();
            final java.io.File cache = activity.getCacheDir();
            new Thread(() -> {
                Streams.Choice choice = Streams.best(link, cache);
                handler.post(() -> {
                    if (asked != streamAsk) return;   // pressed something else meanwhile
                    findingSince = 0;
                    screen.invalidate();
                    if (choice != null) play(choice, p, r);
                    else Toast.makeText(activity, Ui.t("No encontré dónde verlo",
                            "Couldn't find it to watch", "Não achei onde assistir"), Toast.LENGTH_SHORT).show();
                });
            }).start();
            return;
        }
        open(it, r);
    }

    private void play(Streams.Choice choice, Recs.Pick p, RectF r) {
        String title = p == null ? "" : p.title;
        String[] v = choice.video.split(":");
        if (v.length >= 3) title += "  ·  " + Ui.t("T", "S", "T") + v[v.length - 2] + " E" + v[v.length - 1];
        PlayerActivity.choice = choice;
        PlayerActivity.title = title;
        PlayerActivity.name = p == null ? "" : p.title;
        PlayerActivity.posterUrl = p == null || p.poster == null ? null : "https://image.tmdb.org/t/p/w342" + p.poster;
        PlayerActivity.poster = p == null ? null : recs.image(p.backdrop, "w1280");
        activity.startActivity(new android.content.Intent(activity, PlayerActivity.class),
                android.app.ActivityOptions.makeScaleUpAnimation(this, Math.round(r.left), Math.round(r.top),
                        Math.max(1, Math.round(r.width())), Math.max(1, Math.round(r.height()))).toBundle());
    }

    private long streamAsk, findingSince;
    private final RectF findingRect = new RectF(), findingArc = new RectF();

    private void open(HomeApps.Item it, RectF r) {
        if (!HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top), Math.round(r.width()), Math.round(r.height())))
            Toast.makeText(activity, Ui.t("No se pudo abrir ",
                    "Couldn't open ", "Não deu para abrir ") + it.label, Toast.LENGTH_SHORT).show();
    }

    // MARK: - A show's page: its seasons and episodes, Stremio opens right on the one you choose

    private static final float EP_W = 420, EP_H = 236, EP_X = 96, EP_TOP = 652, PILL_Y = 556, PILL_H = 56;

    /** The open show page (null = the home screen). */
    private static final class Detail {
        Recs.Pick pick;
        String imdb;
        HomeApps.Item app;
        List<org.json.JSONObject> seasons, episodes;
        int season, episode;     // index in seasons / episodes
        int row = 1;             // 0 seasons, 1 episodes
        int wantEpisode = -1;    // the episode number to land on once its season loads (yours in progress)
        // Opened from Continue watching: the movie or episode plays behind the page, which leads with Resume and
        // From the start (row 0, before the seasons). full = the page gone, only the video.
        boolean video, movie, full;
        int action = -1;         // 0 Resume, 1 From the start, -1 on the seasons
        float alpha, slide;      // fade in; episodes sliding into place
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());

        boolean upcoming(org.json.JSONObject e) {
            String date = e.optString("air_date", "");
            return date.isEmpty() || "null".equals(date) || date.compareTo(today) > 0;
        }
    }

    private Detail detail;
    private android.animation.ValueAnimator detailAnim, epAnim;

    /** A show in Stremio: its page opens here (fading in over the home screen). */
    private boolean pickEpisode(final Recs.Pick p, final HomeApps.Item it, final RectF r) {
        return pickEpisode(p, it, r, false);
    }

    private boolean pickEpisode(final Recs.Pick p, final HomeApps.Item it, final RectF r, boolean inProgress) {
        final String prefix = "stremio:///detail/series/";
        if ((p.resuming && !inProgress) || p.id == 0 || p.link == null || !p.link.startsWith(prefix)) return false;
        final Detail d = new Detail();
        d.pick = p;
        d.imdb = p.link.substring(prefix.length()).split("/")[0];
        d.app = it;
        if (inProgress) {
            d.video = true;
            d.row = 0;
            d.action = 0;
        }
        // Yours in progress on Stremio: land on that season and episode ("tt…:2:5").
        int wantSeason = -1;
        for (Recs.Pick going : recs.resumes()) {
            String[] v = going.link == null ? new String[0] : going.link.split(":");
            if (going.link != null && going.link.contains("/" + d.imdb + "/") && v.length >= 3) {
                try {
                    wantSeason = Integer.parseInt(v[v.length - 2]);
                    d.wantEpisode = Integer.parseInt(v[v.length - 1]);
                } catch (NumberFormatException ignored) {
                    // a movie's id: no episode
                }
            }
        }
        detail = d;
        stopTrailer();
        click.open();
        fadeDetail(1, null);
        final int want = wantSeason;
        recs.episodes(p, -1, seasons -> handler.post(() -> {
            if (detail != d) return;
            if (seasons == null || seasons.isEmpty()) {
                if (d.video) return;   // the page still has Resume
                closeDetail();
                launch(it, r);
                return;
            }
            d.seasons = seasons;
            for (int i = 0; i < seasons.size(); i++) if (seasons.get(i).optInt("season_number") == want) d.season = i;
            loadSeason(d);
        }));
        return true;
    }

    private void loadSeason(final Detail d) {
        final int number = d.seasons.get(d.season).optInt("season_number");
        d.episodes = null;
        screen.invalidate();
        recs.episodes(d.pick, number, eps -> handler.post(() -> {
            if (detail != d || d.seasons.get(d.season).optInt("season_number") != number) return;
            d.episodes = eps == null ? new ArrayList<>() : eps;
            d.episode = 0;
            for (int i = 0; i < d.episodes.size(); i++) if (d.episodes.get(i).optInt("episode_number") == d.wantEpisode) d.episode = i;
            d.wantEpisode = -1;
            screen.invalidate();
        }));
    }

    private void fadeDetail(float to, Runnable then) {
        final Detail d = detail;
        if (detailAnim != null) detailAnim.cancel();
        detailAnim = android.animation.ValueAnimator.ofFloat(d.alpha, to);
        detailAnim.setDuration(240);
        detailAnim.setInterpolator(EASE);
        detailAnim.addUpdateListener(v -> {
            d.alpha = (float) v.getAnimatedValue();
            screen.invalidate();
        });
        if (then != null) detailAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (detail == d) then.run();
            }
        });
        detailAnim.start();
    }

    private boolean onVideoPage() {
        return detail != null && detail.video;
    }

    private void closeDetail() {
        boolean video = onVideoPage();
        if (detailAnim != null) detailAnim.cancel();
        detail = null;
        screen.invalidate();
        if (!video) {
            scheduleTrailer();
            return;
        }
        activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // The video, if still playing, shrinks back into its card.
        if (watchPick != null && watchPick == trailerPick()) {
            watch.setFull(false);
            place(watch, watchRect);
        } else {
            stopWatch();
            scheduleTrailer();
        }
    }

    private void detailMove(int dx, int dy) {
        final Detail d = detail;
        if (d.video && d.row == 0 && dx != 0 && (d.action >= 0 || (dx < 0 && d.season == 0))) {
            int next = d.action < 0 ? 1 : d.action + dx;
            if (next > 1 && (d.seasons == null || d.movie)) return;
            if (next < 0) return;
            d.action = next > 1 ? -1 : next;
            click.move();
            screen.invalidate();
            return;
        }
        if (dy > 0 && d.row == 0 && d.episodes != null && !d.episodes.isEmpty()) d.row = 1;
        else if (dy < 0 && d.row == 1 && d.seasons != null) d.row = 0;
        else if (dx != 0 && d.row == 0 && d.seasons != null) {
            int next = Math.max(0, Math.min(d.seasons.size() - 1, d.season + dx));
            if (next == d.season) return;
            d.season = next;
            loadSeason(d);
        } else if (dx != 0 && d.row == 1 && d.episodes != null) {
            int next = Math.max(0, Math.min(d.episodes.size() - 1, d.episode + dx));
            if (next == d.episode) return;
            d.episode = next;
            if (epAnim != null) epAnim.cancel();
            final float from = dx * (EP_W + GAP);
            epAnim = android.animation.ValueAnimator.ofFloat(1, 0);
            epAnim.setDuration(220);
            epAnim.setInterpolator(EASE);
            epAnim.addUpdateListener(v -> {
                d.slide = from * (float) v.getAnimatedValue();
                screen.invalidate();
            });
            epAnim.start();
        } else {
            return;
        }
        click.move();
        screen.invalidate();
    }

    private void detailActivate() {
        final Detail d = detail;
        if (d.video && d.row == 0 && d.action >= 0) {
            click.open();
            if (d.action == 1) watch.restart();
            watchFull(d);
            return;
        }
        if (d.row == 0) {
            if (d.episodes != null && !d.episodes.isEmpty()) d.row = 1;
            screen.invalidate();
            return;
        }
        if (d.episodes == null || d.episodes.isEmpty()) return;
        int season = d.seasons.get(d.season).optInt("season_number"), ep = d.episodes.get(d.episode).optInt("episode_number");
        if (d.video) {
            // The episode playing behind: that's Resume; another one plays in its own player, the page staying.
            if (d.pick.link.endsWith(":" + season + ":" + ep)) {
                click.open();
                watchFull(d);
                return;
            }
            stopWatch();
            d.video = false;
            activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        d.app.link = "stremio:///detail/series/" + d.imdb + "/" + d.imdb + ":" + season + ":" + ep;
        RectF r = new RectF(EP_X * k, EP_TOP * k, (EP_X + EP_W) * k, (EP_TOP + EP_H) * k);
        launch(d.app, r);
    }

    private void drawDetail(Canvas c) {
        Detail d = detail;
        Recs.Pick p = d.pick;
        boolean behind = d.video && watchOn;   // the video shows through
        float ca = d.video ? coverAlpha() : 0;
        if (ca > 0) {
            r1.set(0, 0, w, h);
            drawFading(c, recs.image(p.backdrop, "w1280"), r1, 0, 0xFF111114, ca);
            if (d.full && !watchOn) drawSpinner(c, w / 2f, h / 2f);
        }
        int layer = d.alpha < 1 ? c.saveLayerAlpha(0, 0, w, h, Math.round(d.alpha * 255)) : -1;
        if (!d.video) {
            c.drawColor(0xFF000000);
            r1.set(0, 0, w, h);
            drawCover(c, recs.image(p.backdrop, "w1280"), r1, 0, 0xFF111114);
        }
        fill.setColor(0xFF000000);   // opaque: a leftover alpha would fade the shade
        fill.setShader(new LinearGradient(0, 0, w * 0.72f, 0, 0xF2000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, fill);
        fill.setColor(0xFF000000);   // opaque: a leftover alpha would fade the shade
        fill.setShader(new LinearGradient(0, h * 0.42f, 0, h, 0x00000000, 0xF5000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
        // Header: label, logo (or name), name in your language, info, overview.
        float x = EP_X * k, y = 352 * k;
        float logoTop = drawLogo(c, p, x, y, 620 * k, 170 * k, false);
        if (Float.isNaN(logoTop)) logoTop = drawBlockUp(c, p.title, billTitle, x, y, 1000 * k, 2);
        fill.setColor(accent);
        c.drawRect(x, logoTop - 38 * k, x + 6 * k, logoTop - 16 * k, fill);
        c.drawText(d.movie ? Ui.t("PELÍCULA",
                "FILM", "FILME") : Ui.t("SERIE", "SERIES", "SÉRIE"), x + 18 * k, logoTop - 18 * k, kindLabel);
        float ty = y + 16 * k;
        if (namedApart(p)) ty = drawBlockDown(c, p.title, nameUnder, x, ty, 1000 * k, 1) + 6 * k;
        ty = drawBlockDown(c, info(p), billMeta, x, ty, 1000 * k, 1) + 10 * k;
        drawBlockDown(c, p.overview, billOverview, x, ty, 860 * k, 2);
        // Resume and From the start (opened from Continue watching), then the seasons.
        float px = x;
        if (d.video) {
            String[] labels = {Ui.t("Reanudar", "Resume", "Continuar") + episodeOf(p), Ui.t("Desde el inicio",
                    "From the start", "Do início")};
            for (int i = 0; i < 2; i++) {
                boolean focused = d.row == 0 && d.action == i;
                float lead = i == 0 ? 40 * k : 0, pw = pill.measureText(labels[i]) + 48 * k + lead;
                r2.set(px, PILL_Y * k, px + pw, (PILL_Y + PILL_H) * k);
                fill.setColor(focused ? 0xFFFFFFFF : 0x33FFFFFF);
                c.drawRoundRect(r2, r2.height() / 2, r2.height() / 2, fill);
                pill.setColor(focused ? 0xFF000000 : 0xFFFFFFFF);
                if (i == 0) c.drawPath(playTriangle(px + 34 * k, r2.centerY(), 16 * k), pill);
                c.drawText(labels[i], px + 24 * k + lead, r2.centerY() + Ui.halfCap(pill), pill);
                if (i == 0) {
                    // How far in you are, along the bottom of the pill.
                    long dur = watch.duration();
                    float done = dur > 0 ? Math.min(1, (float) watch.position() / dur) : 0;
                    if (done > 0) {
                        float lx = px + 24 * k, lw = pw - 48 * k, ly = r2.bottom - 11 * k;
                        fill.setColor(focused ? 0x26000000 : 0x40FFFFFF);
                        c.drawRoundRect(lx, ly - 2 * k, lx + lw, ly + 2 * k, 2 * k, 2 * k, fill);
                        fill.setColor(accent);
                        c.drawRoundRect(lx, ly - 2 * k, lx + lw * done, ly + 2 * k, 2 * k, 2 * k, fill);
                    }
                }
                px += pw + 12 * k;
            }
            px += 28 * k;
        }
        if (d.movie) {
            // A movie: no seasons or episodes.
        } else if (d.seasons == null) {
            c.drawText(Ui.t("Cargando…", "Loading…", "Carregando…"), px, (PILL_Y + 36) * k, meta);
        } else {
            int first = Math.max(0, Math.min(d.season - 5, d.seasons.size() - 7));
            for (int i = first; i < d.seasons.size() && px < w; i++) {
                String name = d.seasons.get(i).optString("name");
                String label = name.isEmpty() ? Ui.t("Temporada ",
                        "Season ", "Temporada ") + d.seasons.get(i).optInt("season_number") : name;
                boolean focused = d.row == 0 && d.action < 0 && i == d.season, chosen = i == d.season;
                float tw = pill.measureText(label), pw = tw + 48 * k;
                r2.set(px, PILL_Y * k, px + pw, (PILL_Y + PILL_H) * k);
                if (focused || chosen) {
                    fill.setColor(focused ? 0xFFFFFFFF : 0x33FFFFFF);
                    c.drawRoundRect(r2, r2.height() / 2, r2.height() / 2, fill);
                }
                pill.setColor(focused ? 0xFF000000 : chosen ? 0xFFFFFFFF : 0xFFA1A1AA);
                c.drawText(label, px + 24 * k, r2.centerY() + Ui.halfCap(pill), pill);
                px += pw + 12 * k;
            }
        }
        // Episodes: the chosen one first, the one before peeking in at the left.
        if (d.episodes != null) {
            float ex = (EP_X + d.slide) * k, top = EP_TOP * k;
            for (int i = Math.max(0, d.episode - 1); i < d.episodes.size(); i++) {
                float left = ex + (i - d.episode) * (EP_W + GAP) * k;
                if (left > w) break;
                r1.set(left, top, left + EP_W * k, top + EP_H * k);
                drawEpisode(c, d, d.episodes.get(i), r1, d.row == 1 && i == d.episode);
            }
            if (!d.episodes.isEmpty()) {
                org.json.JSONObject e = d.episodes.get(d.episode);
                float by = top + EP_H * k + 34 * k;
                StringBuilder line = new StringBuilder(Ui.t("T",
                        "S",
                        "T") + d.seasons.get(d.season).optInt("season_number") + " · E" + e.optInt("episode_number"));
                if (e.optInt("runtime") > 0) line.append(" · ").append(e.optInt("runtime")).append(" min");
                String date = e.optString("air_date", "");
                if (date.length() >= 4) line.append(" · ").append(date.substring(0, 4));
                by = drawBlockDown(c, e.optString("name") + "   " + line, meta, x, by, 1500 * k, 1) + 8 * k;
                drawBlockDown(c, e.optString("overview"), overview, x, by, 1300 * k, 2);
            } else {
                c.drawText(Ui.t("Sin capítulos todavía",
                        "No episodes yet", "Ainda sem episódios"), x, top + 40 * k, meta);
            }
        }
        if (layer >= 0) c.restoreToCount(layer);
        if (behind) screen.postInvalidateDelayed(1000);   // the progress in Resume
    }

    /** An episode's card: its still (the show's picture when it has none), dimmed with "Coming soon" if not out yet. */
    private void drawEpisode(Canvas c, Detail d, org.json.JSONObject e, RectF r, boolean on) {
        float rad = 12 * k;
        String still = e.optString("still_path", "");
        boolean none = still.isEmpty() || "null".equals(still), soon = d.upcoming(e);
        drawCover(c, none ? recs.image(d.pick.backdrop, "w780") : recs.image(still, "w300"), r, rad, 0xFF26262A);
        fill.setColor(0xFF000000);   // opaque: a leftover alpha would fade the shade
        fill.setShader(new LinearGradient(0, r.bottom - r.height() * 0.55f, 0, r.bottom, 0x00000000, 0xD8000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, fill);
        fill.setShader(null);
        if (soon || none) {
            fill.setColor(soon ? 0x99000000 : 0x55000000);
            c.drawRoundRect(r, rad, rad, fill);
        }
        if (soon) {
            String date = e.optString("air_date", "");
            drawBadge(c, Ui.t("Próximamente",
                    "Coming soon",
                    "Em breve") + (date.length() >= 10 ? " · " + date.substring(8, 10) + "/" + date.substring(5, 7) : ""),
                    r.right - 14 * k, r.top + 40 * k, accent, 0xFF000000);
        }
        String t = TextUtils.ellipsize(e.optInt("episode_number") + ". " + e.optString("name"), new TextPaint(peekTitle),
                r.width() - 32 * k, TextUtils.TruncateAt.END).toString();
        c.drawText(t, r.left + 16 * k, r.bottom - 18 * k, peekTitle);
        if (on) c.drawRoundRect(r, rad, rad, ring);
    }

    // MARK: - The menu of a long press on OK: a title's (My list, Continue watching) or an app's (move, remove, add)

    private boolean editMenu() {
        Card c = focused();
        Recs.Pick held = focusRow == -1 ? featured : c != null ? c.pick : null;
        if (held != null && (focusRow == -1 || c.pick != null)) {
            titleMenu(held);
            return true;
        }
        if (c == null || c.app == null || c.app.kind != HomeApps.APP || c.app.pkg == null) return false;
        final HomeApps.Item it = c.app;
        final Context ctx = getContext();
        final List<String> pkgs = HomeApps.shelfPackages(ctx);
        final int at = pkgs.indexOf(it.pkg);
        List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (at < 0) {
            labels.add(Ui.t("Agregar a Tus apps", "Add to Your apps", "Adicionar a Seus apps"));
            actions.add(() -> {
                if (pkgs.size() >= HomeApps.MAX_SHELF) {
                    Toast.makeText(ctx, Ui.t("Ya tienes " + HomeApps.MAX_SHELF + " apps: quita una primero.",
                            "You already have " + HomeApps.MAX_SHELF + " apps: remove one first.",
                            "Você já tem " + HomeApps.MAX_SHELF + " apps: tire um primeiro."), Toast.LENGTH_LONG).show();
                    return;
                }
                pkgs.add(it.pkg);
                HomeApps.saveShelf(ctx, pkgs);
                reloadApps();
            });
        } else {
            labels.add(Ui.t("Mover", "Move", "Mover"));
            actions.add(() -> {
                moving = true;
                Toast.makeText(ctx, Ui.t("← → para moverla, OK para terminar.",
                        "← → to move it, OK when done.",
                        "← → para mover, OK para terminar."), Toast.LENGTH_LONG).show();
            });
            labels.add(Ui.t("Quitar de Tus apps", "Remove from Your apps", "Tirar de Seus apps"));
            actions.add(() -> {
                pkgs.remove(at);
                HomeApps.saveShelf(ctx, pkgs);
                reloadApps();
            });
        }
        openMenu(it.label, labels, actions);
        return true;
    }

    private void titleMenu(final Recs.Pick p) {
        final Context ctx = getContext();
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(p.resuming ? Ui.t("Reanudar", "Resume", "Continuar") : Ui.t("Reproducir", "Play", "Assistir"));
        actions.add(this::activate);
        boolean listed = recs.isFavorite(p);
        labels.add(listed ? Ui.t("Quitar de Mi lista", "Remove from My list", "Tirar da Minha lista")
                : Ui.t("Agregar a Mi lista", "Add to My list", "Adicionar à Minha lista"));
        actions.add(() -> {
            boolean in = recs.toggleFavorite(p);
            Toast.makeText(ctx, in ? Ui.t("Agregado a Mi lista", "Added to My list", "Adicionado à Minha lista")
                    : Ui.t("Quitado de Mi lista", "Removed from My list", "Removido da Minha lista"), Toast.LENGTH_SHORT).show();
        });
        if (p.resuming) {
            labels.add(Ui.t("Quitar de Continuar viendo", "Remove from Continue watching", "Tirar de Continuar assistindo"));
            actions.add(() -> {
                recs.forgetResume(p);
                rebuild(true);
                changed(0, 0);
                Toast.makeText(ctx, Ui.t("Quitado de Continuar viendo", "Removed from Continue watching",
                        "Removido de Continuar assistindo"), Toast.LENGTH_SHORT).show();
            });
        }
        openMenu(p.title, labels, actions);
    }

    private void openMenu(String title, List<String> labels, List<Runnable> actions) {
        labels.add(Ui.t("Cancelar", "Cancel", "Cancelar"));
        actions.add(() -> {});
        menuTitle = title == null ? "" : title;
        menuLabels.clear();
        menuLabels.addAll(labels);
        menuActions.clear();
        menuActions.addAll(actions);
        menuFocus = 0;
        menuOk = false;
        click.open();
        screen.invalidate();
    }

    /** ↑ ↓ through the menu, OK (on release) does it, Back closes it. */
    private boolean menuKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && menuFocus > 0) menuFocus--;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && menuFocus < menuLabels.size() - 1) menuFocus++;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) menuOk = true;
                } else if (menuOk) {
                    Runnable action = menuActions.get(menuFocus);
                    menuTitle = null;
                    action.run();
                }
                break;
            case KeyEvent.KEYCODE_BACK:
                if (!down) menuTitle = null;
                break;
            default:
                break;
        }
        screen.invalidate();
        return true;
    }

    /** The menu: a dark card in the middle over a shade, its choices in pills (the focused one white). */
    private void drawMenu(Canvas c) {
        if (menuTitle == null) return;
        fill.setShader(null);
        fill.setColor(0xB3000000);
        c.drawRect(0, 0, w, h, fill);
        float rowH = 72 * k, gap = 8 * k, pad = 28 * k, cw = 680 * k, head = 76 * k;
        float ch = pad + head + menuLabels.size() * (rowH + gap) - gap + pad;
        float left = (w - cw) / 2, top = (h - ch) / 2;
        r1.set(left, top, left + cw, top + ch);
        fill.setColor(0xFF161618);
        c.drawRoundRect(r1, 32 * k, 32 * k, fill);
        String t = TextUtils.ellipsize(menuTitle, new TextPaint(menuHead), cw - 2 * pad - 24 * k, TextUtils.TruncateAt.END).toString();
        c.drawText(t, left + pad + 24 * k, top + pad + 46 * k, menuHead);
        for (int i = 0; i < menuLabels.size(); i++) {
            float y = top + pad + head + i * (rowH + gap);
            r1.set(left + pad, y, left + cw - pad, y + rowH);
            boolean on = i == menuFocus;
            if (on) {
                fill.setColor(0xFFFFFFFF);
                c.drawRoundRect(r1, rowH / 2, rowH / 2, fill);
            }
            Paint tp = on ? menuOn : menuOff;
            c.drawText(menuLabels.get(i), r1.left + 24 * k, r1.centerY() + Ui.halfCap(tp), tp);
        }
    }

    private void moveShelfApp(int dx) {
        int col = focusCol[focusRow], to = col + dx;
        if (dx == 0 || to < 0 || to >= shelf.size()) return;
        List<HomeApps.Item> list = new ArrayList<>(shelf);
        java.util.Collections.swap(list, col, to);
        List<String> pkgs = new ArrayList<>();
        for (HomeApps.Item i : list) pkgs.add(i.pkg);
        HomeApps.saveShelf(getContext(), pkgs);
        shelf = list;
        rebuild(true);
        focusCol[focusRow] = to;
        changed(dx, 0);
    }

    // MARK: - Trailer: a YouTube player under the focused card, shown once it really plays

    private Recs.Pick trailerPick() {
        if (focusRow == -1) return featured;
        Card c = focused();
        return c == null ? null : c.pick;
    }

    private void scheduleTrailer() {
        // Kept: the video already in this card, or the one loaded ahead for the row below.
        Recs.Pick p = trailerPick();
        keepWatch = watchPick != null && (watchPick == p && p != null && canWatch(p)
                || watch.waiting() && watchPick == nextInProgress()) ? watchPick : null;
        stopTrailer();
        keepWatch = null;
        if (!running || detail != null || p == null) return;
        if (canWatch(p)) {
            handler.postDelayed(watchStart, WATCH_DELAY);
            return;
        }
        handler.postDelayed(trailerStart, TRAILER_DELAY);
        // The row below is Continue watching: its title loads ahead, so it plays at once when you get there.
        Recs.Pick ahead = nextInProgress();
        if (ahead != null && ahead != watchPick) handler.postDelayed(watchAhead, 1000);
    }

    private void fetchTrailer() {
        final Recs.Pick p = trailerPick();
        if (p == null) return;
        recs.trailer(p, found -> {
            if (found == null || !running || p != trailerPick()) return;
            final String key = found.substring(0, found.indexOf('|'));
            // "|es": in your language; "|music": an opening song; "|mute": the original trailer (with sound now, and
            // YouTube's Spanish captions when it has them).
            final String tag = found.substring(found.indexOf('|') + 1);
            final boolean mute = false;
            final boolean subs = !tag.equals(Ui.LANG) && !tag.equals("music");
            trailerKey = key;
            RectF r = focusRow == -1 ? billboardRect(new RectF()) : wideCardRect(new RectF());
            trailerRect.set(r);
            LayoutParams lp = new LayoutParams(Math.round(r.width()), Math.round(r.height()));
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            lp.leftMargin = Math.round(r.left);
            lp.topMargin = Math.round(r.top);
            trailerBox.setLayoutParams(lp);
            // After the layout pass: the player must crop for this card's size, not the last one's.
            // Started after the layout pass, so the page measures this card, not the last one.
            float d = getResources().getDisplayMetrics().density;
            final String call = String.format(Locale.US, "play('%s',%.1f,%.1f,%b,'%s',%b)", key, r.width() / d, r.height() / d,
                    subs, Ui.LANG, mute);
            trailerBox.post(() -> {
                if (key.equals(trailerKey)) web.evaluateJavascript(call, null);
            });
        });
        // The next card's trailer, looked up ahead so it starts sooner.
        Card next = focusRow >= 0 && focusCol[focusRow] + 1 < rows.get(focusRow).cards.size()
                ? rows.get(focusRow).cards.get(focusCol[focusRow] + 1) : null;
        if (next != null && next.pick != null) recs.trailer(next.pick, key -> {});
    }

    private void stopTrailer() {
        handler.removeCallbacks(trailerStart);
        handler.removeCallbacks(watchStart);
        handler.removeCallbacks(watchAhead);
        if (!onVideoPage() && (watchPick == null || watchPick != keepWatch)) stopWatch();
        if (trailerKey != null) web.evaluateJavascript("stop()", null);
        if (trailerOn) AppLog.w("trailer off");
        trailerKey = null;
        trailerOn = false;
        trailerBox.setAlpha(0);
        screen.invalidate();
    }

    /**
     * One page with YouTube's player, loaded once and kept: each trailer only swaps the video. The video covers the
     * card (cropped like a photo, a little zoomed so YouTube's title strip and logo fall outside), with sound.
     */
    private static final String PLAYER = "<!doctype html><html><head>"
            + "<style>html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}#p{position:absolute;border:0}</style>"
            + "</head><body><div id=p></div><script src='https://www.youtube.com/iframe_api'></script><script>"
            + "var player=null,ready=false,pending=null;addEventListener('resize',fit);"
            // Fit the card (its visible size: the layout viewport here measured the whole screen) at the video's own
            // 16:9, no zoom: on a 16:9 card it fits exactly; on the wider featured card it fills the width.
            + "function fit(){var vv=window.visualViewport,W=vv?vv.width:innerWidth,H=vv?vv.height:innerHeight,"
            + "vw=Math.max(W,H*16/9),vh=vw*9/16,f=document.getElementById('p');if(!f)return;"
            + "f.style.width=vw+'px';f.style.height=vh+'px';f.style.left=((W-vw)/2)+'px';f.style.top=((H-vh)/2)+'px';}"
            + "var M=false;function sound(p){if(M){p.mute();}else{p.unMute();p.setVolume(70);}}"
            + "function onYouTubeIframeAPIReady(){ready=true;if(pending){var a=pending;pending=null;play.apply(null,a);}}"
            // Subtitles in your language: its own track if the trailer has one, else YouTube's translation of the first.
            + "function captions(subs,lang){try{if(!subs){player.unloadModule('captions');return;}player.loadModule('captions');"
            + "setTimeout(function(){try{var t=player.getOption('captions','tracklist')||[],m=null;"
            + "for(var i=0;i<t.length;i++)if(t[i].languageCode.indexOf(lang)==0)m=t[i];"
            + "if(m)player.setOption('captions','track',{languageCode:m.languageCode});"
            + "else if(t.length)player.setOption('captions','track',{languageCode:t[0].languageCode,translationLanguage:{languageCode:lang}});"
            + "}catch(e){}},1500);}catch(e){}}"
            + "function play(k,w,h,subs,lang,mute){M=mute;if(!ready){pending=[k,w,h,subs,lang,mute];return;}fit();setTimeout(fit,300);setTimeout(fit,1200);"
            + "if(!player){player=new YT.Player('p',{videoId:k,playerVars:{autoplay:1,controls:0,disablekb:1,fs:0,rel:0,"
            + "playsinline:1,iv_load_policy:3,hl:lang,cc_lang_pref:lang,cc_load_policy:subs?1:0},events:{"
            + "onReady:function(e){fit();sound(e.target);captions(subs,lang);e.target.playVideo();},"
            + "onStateChange:function(e){var id=(player&&player.getVideoData)?player.getVideoData().video_id:'';"
            + "if(e.data==1){fit();FreeMyTV.playing(id);}if(e.data==0)FreeMyTV.ended(id);},"
            + "onError:function(e){FreeMyTV.failed();}}});}"
            + "else{player.loadVideoById(k);sound(player);captions(subs,lang);}}"
            + "function stop(){pending=null;if(player&&player.stopVideo)player.stopVideo();}</script></body></html>";

    private final class Bridge {
        /** Shown only if it's this card's video (a late event from the last one must not show it here). */
        @JavascriptInterface public void playing(String id) {
            handler.post(() -> {
                if (trailerKey == null) return;
                if (!trailerKey.equals(id)) {
                    AppLog.w("player: playing " + id + " but this card's trailer is " + trailerKey);
                    return;
                }
                // A moment later: YouTube flashes its pause icon as it starts.
                final String key = trailerKey;
                handler.postDelayed(() -> {
                    if (!key.equals(trailerKey)) return;
                    if (!trailerOn) AppLog.w("trailer on " + key);   // next to the player's errors: two videos at once?
                    trailerOn = true;
                    trailerBox.animate().alpha(1).setDuration(400).start();
                    screen.invalidate();
                }, 700);
            });
        }

        @JavascriptInterface public void failed() {
            handler.post(FlixView.this::stopTrailer);
        }

        /** The trailer ended: the photo comes back; on the featured title, the next one. */
        @JavascriptInterface public void ended(String id) {
            handler.post(() -> {
                if (trailerKey == null || !trailerKey.equals(id)) return;
                stopTrailer();
                if (focusRow == -1) nextFeatured(1);
            });
        }
    }

    // MARK: - Continue watching: the video itself in the card, then its page over it

    private static boolean canWatch(Recs.Pick p) {
        return p.resuming && p.link != null && p.link.startsWith("stremio:///detail/") && !Config.STREMIO_KEY.trim().isEmpty();
    }

    /** The title in progress in the row right below the focused one, if that row is Continue watching. */
    private Recs.Pick nextInProgress() {
        int r = focusRow + 1;
        if (focusRow < -1 || r >= rows.size()) return null;
        Row row = rows.get(r);
        if (row.cards.isEmpty()) return null;
        Card c = row.cards.get(Math.max(0, Math.min(focusCol[r], row.cards.size() - 1)));
        return c.pick != null && canWatch(c.pick) ? c.pick : null;
    }

    private void loadAhead() {
        final Recs.Pick p = nextInProgress();
        if (p == null || p == watchPick || !running) return;
        final long asked = ++watchAsk;
        final String link = p.link;
        final java.io.File cache = activity.getCacheDir();
        new Thread(() -> {
            Streams.Choice choice = Streams.best(link, cache);
            handler.post(() -> {
                if (asked != watchAsk || !running || choice == null || nextInProgress() != p || detail != null) return;
                watch.stop();
                watchPick = p;
                watchOn = false;
                place(watch, new RectF());
                watch.setFull(false);
                watch.play(choice, null, episodeTitle(p, choice), p.title,
                        p.poster == null ? null : "https://image.tmdb.org/t/p/w342" + p.poster, true);
            });
        }).start();
    }

    private void fetchWatch() {
        final Recs.Pick p = onVideoPage() ? detail.pick : trailerPick();
        if (p != null && p == watchPick && !watch.waiting() && !onVideoPage()) {
            // Already playing (the same title on the featured card and in a row): it moves to where the focus is.
            watchRect.set(focusRow == -1 ? billboardRect(new RectF()) : wideCardRect(new RectF()));
            place(watch, watchRect);
            screen.invalidate();
            return;
        }
        if (p != null && p == watchPick && watch.waiting()) {
            // Loaded ahead: only to show it.
            watchRect.set(focusRow == -1 ? billboardRect(new RectF()) : wideCardRect(new RectF()));
            place(watch, onVideoPage() ? null : watchRect);
            watch.reveal();
            return;
        }
        if (p == null || !canWatch(p) || p == watchPick) return;
        final long asked = ++watchAsk;
        final String link = p.link;
        final java.io.File cache = activity.getCacheDir();
        new Thread(() -> {
            Streams.Choice choice = Streams.best(link, cache);
            handler.post(() -> {
                boolean page = onVideoPage();
                Recs.Pick now = page ? detail.pick : trailerPick();
                if (asked != watchAsk || !running || now != p) return;
                if (choice == null) {
                    if (page) {
                        Toast.makeText(activity, Ui.t("No encontré dónde verlo",
                                "Couldn't find it to watch", "Não achei onde assistir"), Toast.LENGTH_SHORT).show();
                        closeDetail();
                    }
                    return;
                }
                watchPick = p;
                watchRect.set(focusRow == -1 ? billboardRect(new RectF()) : wideCardRect(new RectF()));
                place(watch, page ? null : watchRect);
                watch.setFull(false);
                watch.play(choice, null, episodeTitle(p, choice), p.title,
                        p.poster == null ? null : "https://image.tmdb.org/t/p/w342" + p.poster);
            });
        }).start();
    }

    private void stopWatch() {
        watchAsk++;
        watch.stop();
        watchPick = null;
        watchOn = false;
        place(watch, new RectF());
        screen.invalidate();
    }

    /** A view under the Canvas, over this rectangle (null = the whole screen). */
    private void place(View v, RectF r) {
        LayoutParams lp = r == null ? new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                : new LayoutParams(Math.max(1, Math.round(r.width())), Math.max(1, Math.round(r.height())));
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        if (r != null) {
            lp.leftMargin = Math.round(r.left);
            lp.topMargin = Math.round(r.top);
        }
        v.setLayoutParams(lp);
    }

    private String episodeTitle(Recs.Pick p, Streams.Choice choice) {
        String[] v = choice.video.split(":");
        return v.length >= 3 ? p.title + "  ·  " + Ui.t("T",
                "S", "T") + v[v.length - 2] + " E" + v[v.length - 1] : p.title;
    }

    /** " T1:E2" of the episode in progress ("" for a movie). */
    private static String episodeOf(Recs.Pick p) {
        String[] v = p.link.split(":");
        return v.length >= 4 ? " " + Ui.t("T", "S", "T") + v[v.length - 2] + ":E" + v[v.length - 1] : "";
    }

    /** OK on a title in progress: its page over its video (already playing in the card, or starting now). */
    private boolean openResume(Recs.Pick p) {
        if (!canWatch(p)) return false;
        handler.removeCallbacks(watchStart);
        handler.removeCallbacks(trailerStart);
        if (trailerKey != null) {
            web.evaluateJavascript("stop()", null);
            trailerKey = null;
            trailerOn = false;
            trailerBox.setAlpha(0);
        }
        activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        boolean same = watchPick == p;
        if (p.link.startsWith("stremio:///detail/series/")) {
            HomeApps.Item it = HomeApps.app(p.pkg, Recs.serviceName(p));
            it.link = p.link;
            pickEpisode(p, it, new RectF(0, 0, w, h), true);
        } else {
            Detail d = new Detail();
            d.pick = p;
            d.video = d.movie = true;
            d.row = 0;
            d.action = 0;
            detail = d;
            click.open();
            fadeDetail(1, null);
        }
        if (same && watch.waiting()) fetchWatch();
        else if (same) place(watch, null);
        else {
            stopWatch();
            fetchWatch();
        }
        return true;
    }

    /** Resume: the page fades away and the same video goes on, full screen. */
    private void watchFull(final Detail d) {
        fadeDetail(0, () -> {
            d.full = true;
            watch.setFull(true);
            screen.invalidate();
        });
    }

    /** The remote while only the video shows; BACK brings its page back (your place is saved), the video going on. */
    private boolean fullKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && !watch.onBack()) {
                detail.full = false;
                watch.setFull(false);
                fadeDetail(1, null);
            }
            return true;
        }
        watch.onKey(e);
        return true;
    }

    private void drawSpinner(Canvas c, float cx, float cy) {
        float t = android.os.SystemClock.uptimeMillis() / 1000f, s = 26 * k;
        findingArc.set(cx - s, cy - s, cx + s, cy + s);
        spinner.setAlpha(255);
        c.drawArc(findingArc, t * 360 % 360, 100, false, spinner);
        screen.postInvalidateOnAnimation();
    }

    // MARK: - Layout

    private RectF billboardRect(RectF out) {
        out.set(LEFT * k, BILL_TOP * k, w - MARGIN * k, (BILL_TOP + BILL_H) * k);
        return out;
    }

    private RectF wideCardRect(RectF out) {
        Row r = rows.get(focusRow);
        float top = 190 * k;
        if (r.apps) {
            float x = (LEFT + (focusCol[focusRow] - firstCol[focusRow]) * (APP_W + GAP)) * k;
            out.set(x, top, x + APP_W * k, top + APP_H * k);
        } else {
            out.set((LEFT + LEAD) * k, top, (LEFT + LEAD + WIDE_W) * k, top + POSTER_H * k);
        }
        return out;
    }

    private void makePaints() {
        navOn = Ui.text(Ui.semibold, 24 * k, 0xFFFFFFFF);
        navOff = Ui.text(Ui.semibold, 24 * k, 0xFFB4B4BC);
        navFocus = Ui.text(Ui.semibold, 24 * k, 0xFF000000);
        menuHead = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
        menuOn = Ui.text(Ui.semibold, 28 * k, 0xFF000000);
        menuOff = Ui.text(Ui.semibold, 28 * k, 0xFFE4E4E7);
        rowTitle = Ui.text(Ui.semibold, 30 * k, 0xFFFFFFFF);
        rowPeek = Ui.text(Ui.semibold, 28 * k, 0xFFA1A1AA);
        cardTitle = Ui.text(Ui.semibold, 21 * k, 0xFFFFFFFF);
        peekTitle = Ui.text(Ui.semibold, 19 * k, 0xFFFFFFFF);
        wideTitle = Ui.text(Ui.displaySemibold, 42 * k, 0xFFFFFFFF);
        kindLabel = Ui.text(Ui.semibold, 15 * k, 0xFFE4E4E7);
        kindLabel.setLetterSpacing(0.22f);
        badge = Ui.text(Ui.semibold, 16 * k, 0xFFFFFFFF);
        weatherText = Ui.text(Ui.medium, 22 * k, 0xFFD4D4D8);
        button = Ui.text(Ui.semibold, 23 * k, 0xFF000000);
        meta = new TextPaint(Ui.text(Ui.medium, 23 * k, 0xFFE4E4E7));
        nameUnder = new TextPaint(Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF));
        pill = new TextPaint(Ui.text(Ui.semibold, 23 * k, 0xFFFFFFFF));
        overview = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xFFB4B4BC));
        billTitle = new TextPaint(Ui.text(Ui.displaySemibold, 92 * k, 0xFFFFFFFF));
        billMeta = new TextPaint(Ui.text(Ui.medium, 23 * k, 0xFFE4E4E7));
        billOverview = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xFFD4D4D8));
        // A soft shadow under the banner's text: it reads on a white picture too.
        for (Paint t : new Paint[] {billTitle, billMeta, billOverview, nameUnder, kindLabel}) t.setShadowLayer(10 * k, 0, 2 * k, 0xE6000000);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(4 * k);
        ring.setColor(0xFFFFFFFF);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);
        spinner.setStyle(Paint.Style.STROKE);
        spinner.setStrokeWidth(4 * k);
        spinner.setStrokeCap(Paint.Cap.ROUND);
        spinner.setColor(0xFFFFFFFF);
    }

    // MARK: - Drawing

    private final class Screen extends View {
        Screen(Context c) {
            super(c);
        }

        @Override protected void onSizeChanged(int width, int height, int ow, int oh) {
            w = width;
            h = height;
            k = width / 1920f;
            makePaints();
        }

        @Override protected void onDraw(Canvas c) {
            if (navOn == null) return;
            if (onVideoPage()) {
                // Over the video (under this Canvas): nothing drawn here once it's only the video.
                if (!detail.full || !watchOn) drawDetail(c);
                return;
            }
            // Black everywhere but the playing trailer (or video in progress), which shows through from underneath.
            if (trailerOn || watchOn) {
                float rr = (focusRow == -1 ? 22 : 12) * k;
                hole.reset();
                hole.setFillType(Path.FillType.EVEN_ODD);
                hole.addRect(0, 0, w, h, Path.Direction.CW);
                hole.addRoundRect(watchOn ? watchRect : trailerRect, rr, rr, Path.Direction.CW);
                fill.setShader(null);
                fill.setColor(ground);
                c.drawPath(hole, fill);
            } else {
                c.drawColor(ground);
            }
            if (detail != null && detail.alpha >= 1) {
                drawDetail(c);
            } else {
                // Right of the sidebar's icons (a row's card peeking in at the left stops there).
                c.save();
                c.clipRect(SIDE_W * k, 0, w, h);
                if (focusRow < 0) drawHome(c); else drawRows(c);
                c.restore();
                drawNav(c);
                drawSide(c);
                if (detail != null) drawDetail(c);   // fading in over the home screen
            }
            drawFinding(c);
            drawMenu(c);
        }
    }

    /** While Stremio's streams are asked for: the pressed card dims and a thin ring turns on it. */
    private void drawFinding(Canvas c) {
        if (findingSince == 0) return;
        float t = (android.os.SystemClock.uptimeMillis() - findingSince) / 1000f;
        float in = Math.min(1, t / 0.2f), rad = 22 * k, s = 26 * k;
        fill.setColor(android.graphics.Color.argb(Math.round(110 * in), 0, 0, 0));
        c.drawRoundRect(findingRect, rad, rad, fill);
        findingArc.set(findingRect.centerX() - s, findingRect.centerY() - s, findingRect.centerX() + s, findingRect.centerY() + s);
        spinner.setAlpha(Math.round(255 * in));
        c.drawArc(findingArc, t * 360 % 360, 100, false, spinner);
        screen.postInvalidateOnAnimation();
    }

    /** The platforms on top, in a capsule: "For you", then each installed one by its logo (white; dark on its pill). */
    private void drawNav(Canvas c) {
        float cy = NAV_H / 2f * k, ih = 52 * k, pad = 8 * k, gap = 4 * k;
        float total = pad * 2 - gap;
        for (Platform p : platforms) total += platformWidth(p) + gap;
        r1.set(LEFT * k, cy - ih / 2 - pad, LEFT * k + total, cy + ih / 2 + pad);
        fill.setShader(null);
        fill.setColor(0x1AFFFFFF);
        c.drawRoundRect(r1, r1.height() / 2, r1.height() / 2, fill);
        float x = r1.left + pad;
        for (int i = 0; i < platforms.size(); i++) {
            Platform p = platforms.get(i);
            float pw = platformWidth(p);
            r2.set(x, cy - ih / 2, x + pw, cy + ih / 2);
            boolean focus = focusRow == -2 && navIndex == i, on = focus || i == platformIndex;
            if (on) {
                fill.setColor(0xFFFFFFFF);
                c.drawRoundRect(r2, ih / 2, ih / 2, fill);
            }
            Bitmap logo = p.logo == null ? null : recs.image(p.logo, "w300");
            if (logo != null) {
                float lh = logoH(logo) * k, lw = Math.min(LOGO_W * k, lh * logo.getWidth() / logo.getHeight());
                lh = lw * logo.getHeight() / logo.getWidth();
                logoRect.set(r2.centerX() - lw / 2, cy - lh / 2, r2.centerX() + lw / 2, cy + lh / 2);
                c.drawBitmap(logo, null, logoRect, on ? logoDark : logoLight);
            } else {
                Paint tp = on ? navFocus : navOn;
                c.drawText(p.name, r2.centerX() - tp.measureText(p.name) / 2, cy + Ui.halfCap(tp), tp);
            }
            x += pw + gap;
        }
        // Weather and time, right.
        HomeData.Weather wx = data.weather;
        String right = Ui.time(System.currentTimeMillis()).toLowerCase(Locale.ROOT);
        if (!Double.isNaN(wx.temp)) {
            String city = Config.CITY.trim();
            right = HomeData.degrees(wx.temp) + (city.isEmpty() ? "" : "  " + city) + "   ·   " + right;
        }
        float rx = w - MARGIN * k - weatherText.measureText(right);
        c.drawText(right, rx, cy + Ui.halfCap(weatherText), weatherText);
        if (!Double.isNaN(wx.temp)) {
            float size = 30 * k;
            Symbols.draw(getContext(), c, Symbols.weather(wx.code, wx.isDay), rx - 14 * k - size / 2, cy, size, 0xFFD4D4D8);
        }
    }

    private static final float LOGO_H = 26, LOGO_W = 120;

    private float platformWidth(Platform p) {
        Bitmap logo = p.logo == null ? null : recs.image(p.logo, "w300");
        if (logo == null) return navOn.measureText(p.name) + 48 * k;
        return Math.min(LOGO_W * k, logoH(logo) * k * logo.getWidth() / logo.getHeight()) + 44 * k;
    }

    /** Wide wordmarks 26 high; squarer logos (Disney+) taller, so they read the same size. */
    private static float logoH(Bitmap logo) {
        return logo.getWidth() < 2.5f * logo.getHeight() ? 40 : LOGO_H;
    }

    /**
     * The sidebar: its icons on the left (the tab you're on lit); open, over a shade, with their names and the focused
     * one in a white pill.
     */
    private void drawSide(Canvas c) {
        float gapY = 78 * k, cy0 = h / 2f - (SIDE_ITEMS - 1) * gapY / 2;
        if (sideOpen) {
            fill.setColor(0xFF000000);   // opaque: the paint's alpha would fade the shade
            fill.setShader(new LinearGradient(0, 0, 900 * k, 0, new int[] {ground, ground & 0xF2FFFFFF, ground & 0x00FFFFFF},
                    new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, 900 * k, h, fill);
            fill.setShader(null);
        }
        for (int i = 0; i < SIDE_ITEMS; i++) {
            float cy = cy0 + i * gapY;
            boolean here = i >= 1 && i < SIDE_APPS && i - 1 == tab;
            if (!sideOpen) {
                if (here) {
                    fill.setColor(0x1FFFFFFF);
                    c.drawCircle(SIDE_W / 2f * k, cy, 28 * k, fill);
                }
                drawSideIcon(c, i, SIDE_W / 2f * k, cy, 28 * k, here ? 0xFFFFFFFF : 0xFF8E8E96);
                continue;
            }
            boolean focus = i == sideIndex;
            String label = sideLabel(i);
            Paint tp = focus ? navFocus : here ? navOn : navOff;
            float left = 40 * k;
            r1.set(left, cy - 32 * k, left + 22 * k + 30 * k + 20 * k + tp.measureText(label) + 30 * k, cy + 32 * k);
            if (focus) {
                fill.setColor(0xFFFFFFFF);
                c.drawRoundRect(r1, 32 * k, 32 * k, fill);
            }
            drawSideIcon(c, i, left + 22 * k + 15 * k, cy, 30 * k, focus ? 0xFF000000 : here ? 0xFFFFFFFF : 0xFFA1A1AA);
            c.drawText(label, left + 22 * k + 30 * k + 20 * k, cy + Ui.halfCap(tp), tp);
        }
    }

    /** Search, Home, Shows, Movies, Apps and Settings as line icons (24-unit drawings, scaled to `size`). */
    private void drawSideIcon(Canvas c, int i, float cx, float cy, float size, int color) {
        float u = size / 24f, x0 = cx - 12 * u, y0 = cy - 12 * u;
        icon.setColor(color);
        icon.setStrokeWidth(2 * u);
        iconPath.reset();
        switch (i) {
            case 0:
                c.drawCircle(x0 + 11 * u, y0 + 11 * u, 7 * u, icon);
                iconPath.moveTo(x0 + 16 * u, y0 + 16 * u);
                iconPath.lineTo(x0 + 20.5f * u, y0 + 20.5f * u);
                break;
            case 1:
                iconPath.moveTo(x0 + 3 * u, y0 + 11 * u);
                iconPath.lineTo(x0 + 12 * u, y0 + 4 * u);
                iconPath.lineTo(x0 + 21 * u, y0 + 11 * u);
                iconPath.moveTo(x0 + 5.5f * u, y0 + 9.5f * u);
                iconPath.lineTo(x0 + 5.5f * u, y0 + 20 * u);
                iconPath.lineTo(x0 + 18.5f * u, y0 + 20 * u);
                iconPath.lineTo(x0 + 18.5f * u, y0 + 9.5f * u);
                break;
            case 2:
                iconPath.addRoundRect(x0 + 3 * u, y0 + 7 * u, x0 + 21 * u, y0 + 19.5f * u, 2 * u, 2 * u, Path.Direction.CW);
                iconPath.moveTo(x0 + 8 * u, y0 + 3 * u);
                iconPath.lineTo(x0 + 12 * u, y0 + 7 * u);
                iconPath.lineTo(x0 + 16 * u, y0 + 3 * u);
                break;
            case 3:
                iconPath.addRoundRect(x0 + 4 * u, y0 + 4 * u, x0 + 20 * u, y0 + 20 * u, 2 * u, 2 * u, Path.Direction.CW);
                iconPath.moveTo(x0 + 8 * u, y0 + 4 * u);
                iconPath.lineTo(x0 + 8 * u, y0 + 20 * u);
                iconPath.moveTo(x0 + 16 * u, y0 + 4 * u);
                iconPath.lineTo(x0 + 16 * u, y0 + 20 * u);
                iconPath.moveTo(x0 + 4 * u, y0 + 12 * u);
                iconPath.lineTo(x0 + 20 * u, y0 + 12 * u);
                break;
            case SIDE_APPS:
                for (int gy = 0; gy < 2; gy++)
                    for (int gx = 0; gx < 2; gx++)
                        iconPath.addRoundRect(x0 + (4 + gx * 9) * u, y0 + (4 + gy * 9) * u, x0 + (11 + gx * 9) * u, y0 + (11 + gy * 9) * u,
                                2 * u, 2 * u, Path.Direction.CW);
                break;
            default:
                c.drawCircle(cx, cy, 3.2f * u, icon);
                c.drawCircle(cx, cy, 7.5f * u, icon);
                for (int a = 0; a < 8; a++) {
                    double t = a * Math.PI / 4;
                    iconPath.moveTo(cx + (float) Math.cos(t) * 7.5f * u, cy + (float) Math.sin(t) * 7.5f * u);
                    iconPath.lineTo(cx + (float) Math.cos(t) * 10.5f * u, cy + (float) Math.sin(t) * 10.5f * u);
                }
                break;
        }
        c.drawPath(iconPath, icon);
    }

    /** The featured title in a big rounded card, with the first row peeking below. */
    private void drawHome(Canvas c) {
        float dy = slideY * k;
        c.save();
        c.translate(0, dy);
        RectF b = billboardRect(r2);
        float rad = 22 * k;
        Recs.Pick p = featured;
        float ca = coverAlpha();
        if (ca > 0) {
            Bitmap img = p == null ? null : recs.image(p.backdrop, "w1280");
            drawFading(c, img, b, rad, 0xFF1C1C1F, ca);
        }
        // Soft shade from the left and the bottom, so white text reads even on a white picture.
        fill.setColor(0xFF000000);   // opaque: a leftover alpha would fade the shade
        fill.setShader(new LinearGradient(b.left, 0, b.left + b.width() * 0.75f, 0,
                new int[] {0xCC000000, 0x99000000, 0x40000000, 0x00000000}, new float[] {0, 0.4f, 0.7f, 1}, Shader.TileMode.CLAMP));
        c.drawRoundRect(b, rad, rad, fill);
        fill.setColor(0xFF000000);   // opaque: a leftover alpha would fade the shade
        fill.setShader(new LinearGradient(0, b.bottom - b.height() * 0.65f, 0, b.bottom,
                new int[] {0x00000000, 0x4D000000, 0xA6000000}, new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawRoundRect(b, rad, rad, fill);
        fill.setShader(null);
        if (focusRow == -1) c.drawRoundRect(b, rad, rad, ring);
        if (p == null && platform != null) {
            // Nothing of this platform to show (yet): its logo and a button that opens it.
            Platform pl = platforms.get(platformIndex);
            float x = b.left + 64 * k, bottom = b.bottom - 64 * k;
            String cta = Ui.t("Abrir ", "Open ", "Abrir ") + pl.name;
            float bh = 62 * k, bw = button.measureText(cta) + 92 * k;
            r1.set(x, bottom - bh, x + bw, bottom);
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(r1, bh / 2, bh / 2, fill);
            c.drawPath(playTriangle(x + 34 * k, r1.centerY(), 20 * k), button);
            c.drawText(cta, x + 64 * k, r1.centerY() + Ui.halfCap(button), button);
            Bitmap logo = pl.logo == null ? null : recs.image(pl.logo, "w300");
            if (logo != null) {
                float lw = Math.min(560 * k, 120 * k * logo.getWidth() / logo.getHeight()), lh = lw * logo.getHeight() / logo.getWidth();
                logoRect.set(x, r1.top - 48 * k - lh, x + lw, r1.top - 48 * k);
                c.drawBitmap(logo, null, logoRect, logoLight);
            } else {
                c.drawText(pl.name, x, r1.top - 48 * k, billTitle);
            }
        }
        if (p != null) {
            float x = b.left + 64 * k, bottom = b.bottom - 64 * k;
            // Bottom up: buttons, overview, meta, title, label.
            String cta = p.resuming ? Ui.t("Reanudar", "Resume", "Continuar") : Ui.t("Reproducir", "Play", "Assistir");
            float bh = 62 * k, bw = button.measureText(cta) + 92 * k;
            r1.set(x, bottom - bh, x + bw, bottom);
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(r1, bh / 2, bh / 2, fill);
            c.drawPath(playTriangle(x + 34 * k, r1.centerY(), 20 * k), button);
            c.drawText(cta, x + 64 * k, r1.centerY() + Ui.halfCap(button), button);
            float y = r1.top - 30 * k;
            y = drawBlockUp(c, p.overview, billOverview, x, y, 780 * k, 3) - 18 * k;
            y = drawBlockUp(c, info(p), billMeta, x, y, 900 * k, 1) - 16 * k;
            if (namedApart(p) && recs.image(p.logo, "w500") != null) y = drawBlockUp(c, p.title, nameUnder, x, y, 900 * k, 1) - 14 * k;
            float logoTop = drawLogo(c, p, x, y, 620 * k, 170 * k, false);
            y = (Float.isNaN(logoTop) ? drawBlockUp(c, p.title, billTitle, x, y, 1000 * k, 2) : logoTop) - 18 * k;
            fill.setColor(accent);
            c.drawRect(x, y - 20 * k, x + 6 * k, y + 2 * k, fill);
            c.drawText("tv".equals(p.kind) ? Ui.t("SERIE", "SERIES", "SÉRIE") : Ui.t("PELÍCULA",
                    "FILM", "FILME"), x + 18 * k, y, kindLabel);
            // Which of the featured titles this is (← → or wait for the next).
            int n = featuredList.size();
            float dot = 10 * k, dx = b.right - 56 * k - (n - 1) * 22 * k;
            for (int i = 0; n > 1 && i < n; i++) {
                fill.setColor(i == featuredIndex ? 0xFFFFFFFF : 0x66FFFFFF);
                c.drawCircle(dx + i * 22 * k, b.bottom - 48 * k, i == featuredIndex ? dot * 0.7f : dot * 0.5f, fill);
            }
            drawPill(c, badgeText(p), b.right - 28 * k, b.top + 58 * k);
        }
        if (!rows.isEmpty()) {
            Row first = rows.get(0);
            float top = b.bottom + 34 * k;
            c.drawText(first.title, LEFT * k, top + 30 * k, rowPeek);
            drawPeekCards(c, first, top + 52 * k);
        }
        c.restore();
    }

    /** The focused row at the top (its focused title first and wide), its details below, the next row peeking. */
    private void drawRows(Canvas c) {
        Row r = rows.get(focusRow);
        float dy = slideY * k;
        c.save();
        c.translate(0, dy);
        float titleY = 168 * k, top = 190 * k;
        c.drawText(r.title, LEFT * k, titleY, rowTitle);
        int col = focusCol[focusRow], n = r.cards.size();
        // Growing: the focused card from poster to wide, the one it came from the other way; the row moves so the
        // old card starts where it was (→: it shrinks into the peek on the left; ←: the new one comes from there).
        float g = grow, fw = POSTER_W + (WIDE_W - POSTER_W) * g, sw = WIDE_W - (WIDE_W - POSTER_W) * g;
        float off = growDir > 0 ? (sw + GAP) * (1 - g) : growDir < 0 ? -(GAP + POSTER_W) * (1 - g) : 0;
        float x = (LEFT + (r.apps ? 0 : LEAD + off)) * k;
        if (!r.apps && n > 1) {
            // The one before, peeking in at the left (the last one, before the first).
            int before = (col - 1 + n) % n;
            float bw = before == shrinkIdx ? sw : POSTER_W;
            r1.set(x - (GAP + bw) * k, top, x - GAP * k, top + POSTER_H * k);
            drawPickCard(c, r.cards.get(before).pick, r1, false);
        }
        int from = r.apps ? firstCol[focusRow] : col, count = r.apps ? n - from : n - (n > 1 ? 1 : 0);
        for (int j = 0; j < count && x < w; j++) {
            int i = r.apps ? from + j : (col + j) % n;
            boolean on = i == col;
            Card card = r.cards.get(i);
            float cw = (r.apps ? APP_W : on ? fw : i == shrinkIdx ? sw : POSTER_W) * k;
            float ch = (r.apps ? APP_H : POSTER_H) * k;
            r1.set(x, top, x + cw, top + ch);
            if (card.pick != null) drawPickCard(c, card.pick, r1, on);
            else drawAppCard(c, card.app, r1, on);
            if (on && moving && r.shelf) {
                badge.setColor(0xFF000000);
                r2.set(r1.left + 12 * k, r1.top + 12 * k, r1.left + 150 * k, r1.top + 46 * k);
                fill.setShader(null);
                fill.setColor(accent);
                c.drawRoundRect(r2, 8 * k, 8 * k, fill);
                c.drawText(Ui.t("← Mover →",
                        "← Move →", "← Mover →"), r2.left + 14 * k, r2.centerY() + Ui.halfCap(badge), badge);
                badge.setColor(0xFFFFFFFF);
            }
            x += cw + GAP * k;
        }
        float below = top + (r.apps ? APP_H : POSTER_H) * k + 52 * k;
        Card f = focused();
        if (f != null && f.pick != null) {
            float y = below;
            // Always its name first (same height for every title, so nothing moves into the next row).
            y = drawBlockDown(c, f.pick.title, nameUnder, LEFT * k, y, 1000 * k, 1) + 6 * k;
            y = drawBlockDown(c, info(f.pick), meta, LEFT * k, y, 1000 * k, 1) + 10 * k;
            drawBlockDown(c, f.pick.overview, overview, LEFT * k, y, 940 * k, 2);
        } else if (f != null) {
            drawBlockDown(c, f.app.label, meta, LEFT * k, below, 1000 * k, 1);
        }
        if (focusRow + 1 < rows.size()) {
            Row next = rows.get(focusRow + 1);
            float ny = below + 150 * k;   // under the name, info and two lines of overview
            c.drawText(next.title, LEFT * k, ny + 30 * k, rowPeek);
            drawPeekCards(c, next, ny + 52 * k);
        }
        c.restore();
    }

    private void drawPeekCards(Canvas c, Row r, float top) {
        int n = r.cards.size(), at = rows.indexOf(r), col = r.apps || at < 0 ? 0 : Math.min(focusCol[at], Math.max(0, n - 1));
        float x = (LEFT + (r.apps ? 0 : LEAD)) * k;
        if (!r.apps && n > 1) {
            r1.set(x - (GAP + POSTER_W) * k, top, x - GAP * k, top + POSTER_H * k);
            drawPickCard(c, r.cards.get((col - 1 + n) % n).pick, r1, false);
        }
        for (int j = 0; j < n - (!r.apps && n > 1 ? 1 : 0); j++) {
            Card card = r.cards.get(r.apps ? j : (col + j) % n);
            if (x > w) break;
            float cw = (r.apps ? APP_W : POSTER_W) * k, ch = (r.apps ? APP_H : POSTER_H) * k;
            r1.set(x, top, x + cw, top + ch);
            if (card.pick != null) drawPickCard(c, card.pick, r1, false);
            else drawAppCard(c, card.app, r1, false);
            x += cw + GAP * k;
        }
    }

    /** The same picture vertical (its middle) and wide; the title's logo, or its name, at the bottom. */
    private void drawPickCard(Canvas c, Recs.Pick p, RectF r, boolean wide) {
        float rad = 12 * k;
        boolean playing = wide && (trailerOn || watchOn) && focusRow >= 0;
        float ca = playing ? coverAlpha() : 1;
        if (ca > 0) drawFading(c, recs.image(p.backdrop, "w780"), r, rad, 0xFF26262A, ca);
        fill.setColor(0xFF000000);   // opaque: a leftover alpha would fade the shade
        fill.setShader(new LinearGradient(0, r.bottom - r.height() * (playing ? 0.45f : 0.5f), 0, r.bottom, 0x00000000,
                playing ? 0xB0000000 : 0xD0000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, fill);
        fill.setShader(null);
        if (wide) {
            float x = r.left + 20 * k, y = r.bottom - 22 * k;
            float top = drawLogo(c, p, x, y, r.width() * 0.42f, r.height() * 0.26f, false);
            if (Float.isNaN(top)) {
                String t = TextUtils.ellipsize(p.title, new TextPaint(wideTitle), r.width() * 0.62f, TextUtils.TruncateAt.END).toString();
                c.drawText(t, x, y, wideTitle);
                top = y - 40 * k;
            }
            c.drawText("tv".equals(p.kind) ? Ui.t("SERIE", "SERIES", "SÉRIE") : Ui.t("PELÍCULA",
                    "FILM", "FILME"), x, top - 14 * k, kindLabel);
            float bx = r.right - 16 * k;
            bx = drawBadge(c, Recs.serviceName(p), bx, r.bottom - 22 * k, 0x99000000, 0xFFFFFFFF);
            if (p.resuming) drawBadge(c, Ui.t("Continuar viendo",
                    "Continue watching", "Continuar assistindo"), bx - 10 * k, r.bottom - 22 * k, accent, 0xFF000000);
            else if (p.sagaNext) drawBadge(c, Ui.t("Sigue en la saga",
                    "Next in the saga", "Continua na saga"), bx - 10 * k, r.bottom - 22 * k, accent, 0xFF000000);
            c.drawRoundRect(r, rad, rad, ring);
        } else if (Float.isNaN(drawLogo(c, p, r.centerX(), r.bottom - 24 * k, r.width() * 0.8f, r.height() * 0.2f, true))) {
            String t = TextUtils.ellipsize(p.title, new TextPaint(peekTitle), r.width() - 24 * k, TextUtils.TruncateAt.END).toString();
            c.drawText(t, r.centerX() - peekTitle.measureText(t) / 2, r.bottom - 22 * k, peekTitle);
        }
        drawPill(c, badgeText(p), r.right - 12 * k, r.top + 42 * k);
    }

    private final RectF pillRect = new RectF();

    /** A dark pill right-aligned at `right` with the text in the accent color (the card's badge). */
    private void drawPill(Canvas c, String text, float right, float base) {
        if (text == null) return;
        float tw = badge.measureText(text), ph = 32 * k;
        pillRect.set(right - tw - 24 * k, base - ph + 6 * k, right, base + 6 * k);
        fill.setShader(null);
        fill.setColor(0xCC000000);
        c.drawRoundRect(pillRect, 8 * k, 8 * k, fill);
        badge.setColor(0xFFF5A524);
        c.drawText(text, pillRect.left + 12 * k, pillRect.centerY() + Ui.halfCap(badge), badge);
        badge.setColor(0xFFFFFFFF);
    }

    /**
     * The title's logo fit in w × h with its bottom at `bottom`, starting at x (or centered on it), in white when
     * it's dark (it wouldn't show on the dark fade); returns its top, or NaN with no logo (none or still loading):
     * then the name is written.
     */
    private float drawLogo(Canvas c, Recs.Pick p, float x, float bottom, float w, float h, boolean center) {
        Bitmap logo = p.logo == null ? null : recs.image(p.logo, "w500");
        if (logo == null) return Float.NaN;
        float s = Math.min(w / logo.getWidth(), h / logo.getHeight()), lw = logo.getWidth() * s, lh = logo.getHeight() * s;
        float left = center ? x - lw / 2 : x;
        logoRect.set(left, bottom - lh, left + lw, bottom);
        c.drawBitmap(logo, null, logoRect, dark(logo) ? whiteLogo : bitmapPaint);
        return bottom - lh;
    }

    /** Mostly black (on the visible pixels). Worked out once per logo. */
    private boolean dark(Bitmap logo) {
        Boolean d = darkLogos.get(logo);
        if (d != null) return d;
        long sum = 0, n = 0;
        for (int y = 0; y < logo.getHeight(); y += Math.max(1, logo.getHeight() / 24)) {
            for (int x = 0; x < logo.getWidth(); x += Math.max(1, logo.getWidth() / 48)) {
                int px = logo.getPixel(x, y);
                if ((px >>> 24) < 128) continue;
                sum += Math.max((px >> 16) & 0xFF, Math.max((px >> 8) & 0xFF, px & 0xFF));   // brightness: red counts as bright
                n++;
            }
        }
        d = n > 0 && sum / n < 60;
        darkLogos.put(logo, d);
        return d;
    }

    private void drawAppCard(Canvas c, HomeApps.Item it, RectF r, boolean on) {
        float rad = 12 * k;
        if (it.tile != null) {
            drawCover(c, it.tile, r, rad, 0xFF1C1C1F);
        } else {
            fill.setShader(null);
            fill.setColor(on ? 0xFF3A3A40 : 0xFF1C1C1F);
            c.drawRoundRect(r, rad, rad, fill);
            if (it.symbol != null) Symbols.draw(getContext(), c, it.symbol, r.centerX(), r.top + r.height() * 0.42f, 40 * k, 0xFFFFFFFF);
            String t = TextUtils.ellipsize(it.label, new TextPaint(cardTitle), r.width() - 24 * k, TextUtils.TruncateAt.END).toString();
            boolean dim = it.kind == HomeApps.INPUT && !it.connected;
            cardTitle.setColor(dim ? 0xFF8E8E93 : 0xFFFFFFFF);
            c.drawText(t, r.centerX() - cardTitle.measureText(t) / 2, r.top + r.height() * 0.8f, cardTitle);
            cardTitle.setColor(0xFFFFFFFF);
        }
        if (on) c.drawRoundRect(r, rad, rad, ring);
    }

    /** Its logo is in another language (a Korean show's, an English one): its name in yours goes under it too. */
    private static boolean namedApart(Recs.Pick p) {
        return p.logo != null && !p.logoMine;
    }

    private final java.util.Map<Recs.Pick, String> infos = new java.util.WeakHashMap<>();

    /** "2025 · ★ 8.7 · Serie · Drama · 1 temporada · Stremio" (made once per title, not every frame). */
    private String info(Recs.Pick p) {
        String s = infos.get(p);
        if (s != null) return s;
        StringBuilder b = new StringBuilder();
        if (p.year != null && !p.year.isEmpty()) b.append(p.year);
        if (p.rating > 0) b.append(b.length() > 0 ? " · " : "").append("★ ").append(String.format(java.util.Locale.US, "%.1f", p.rating));
        if (p.meta != null && !p.meta.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(p.meta);
        s = b.toString();
        infos.put(p, s);
        return s;
    }

    /** A pill right-aligned at `right`, baseline `base`; returns its left edge. */
    private float drawBadge(Canvas c, String text, float right, float base, int bg, int ink) {
        if (text == null || text.isEmpty()) return right;
        float tw = badge.measureText(text), ph = 32 * k;
        r2.set(right - tw - 24 * k, base - ph + 6 * k, right, base + 6 * k);
        fill.setShader(null);
        fill.setColor(bg);
        c.drawRoundRect(r2, 7 * k, 7 * k, fill);
        badge.setColor(ink);
        c.drawText(text, r2.left + 12 * k, r2.centerY() + Ui.halfCap(badge), badge);
        badge.setColor(0xFFFFFFFF);
        return r2.left;
    }

    /** Center-crops `img` into `r` with rounded corners; a flat color while it loads. */
    /** How much of the picture still covers the playing video: it fades out over 450 ms once the video starts. */
    private float coverAlpha() {
        if (!watchOn) return trailerOn ? 0 : 1;
        float a = 1 - (android.os.SystemClock.uptimeMillis() - watchOnAt) / 450f;
        if (a > 0) screen.postInvalidateOnAnimation();
        return Math.max(0, a);
    }

    private void drawFading(Canvas c, Bitmap img, RectF r, float rad, int placeholder, float alpha) {
        if (alpha >= 1) {
            drawCover(c, img, r, rad, placeholder);
            return;
        }
        int layer = c.saveLayerAlpha(r, Math.round(alpha * 255));
        drawCover(c, img, r, rad, placeholder);
        c.restoreToCount(layer);
    }

    private void drawCover(Canvas c, Bitmap img, RectF r, float rad, int placeholder) {
        if (img == null) {
            fill.setShader(null);
            fill.setColor(placeholder);
            c.drawRoundRect(r, rad, rad, fill);
            return;
        }
        float ir = img.getWidth() / (float) img.getHeight(), rr = r.width() / r.height();
        if (ir > rr) {
            // Narrower than the picture: the slice around its subject.
            int sw = Math.round(img.getHeight() * rr);
            int left = Math.max(0, Math.min(img.getWidth() - sw, Math.round(recs.focus(img) * img.getWidth() - sw / 2f)));
            src.set(left, 0, left + sw, img.getHeight());
        } else {
            int sh = Math.round(img.getWidth() / rr);
            src.set(0, (img.getHeight() - sh) / 2, img.getWidth(), (img.getHeight() + sh) / 2);
        }
        hole.reset();
        hole.setFillType(Path.FillType.WINDING);
        hole.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.save();
        c.clipPath(hole);
        c.drawBitmap(img, src, r, bitmapPaint);
        c.restore();
    }

    /** Text wrapped to `width`, up to `lines`, drawn ending at `bottom`; returns its top. */
    private float drawBlockUp(Canvas c, String text, TextPaint p, float x, float bottom, float width, int lines) {
        if (text == null || text.isEmpty()) return bottom;
        StaticLayout l = layout(text, p, width, lines);
        c.save();
        c.translate(x, bottom - l.getHeight());
        l.draw(c);
        c.restore();
        return bottom - l.getHeight();
    }

    /** Text wrapped to `width`, up to `lines`, drawn from `top`; returns its bottom. */
    private float drawBlockDown(Canvas c, String text, TextPaint p, float x, float top, float width, int lines) {
        if (text == null || text.isEmpty()) return top;
        StaticLayout l = layout(text, p, width, lines);
        c.save();
        c.translate(x, top);
        l.draw(c);
        c.restore();
        return top + l.getHeight();
    }

    private static StaticLayout layout(String text, TextPaint p, float width, int lines) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), p, Math.max(1, Math.round(width)))
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0, 1.1f).setIncludePad(false).build();
    }

    private static Path playTriangle(float cx, float cy, float size) {
        Path t = new Path();
        t.moveTo(cx - size * 0.4f, cy - size * 0.5f);
        t.lineTo(cx + size * 0.5f, cy);
        t.lineTo(cx - size * 0.4f, cy + size * 0.5f);
        t.close();
        return t;
    }
}
