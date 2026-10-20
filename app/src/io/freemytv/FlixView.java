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
 * moment its trailer (YouTube, muted) plays inside it. Its details sit below the row. At the end: your apps, the
 * rest of the apps, and inputs and settings.
 *
 * Drawn by hand on one Canvas (in a 1920×1080 space, k = width / 1920); the trailer is a WebView underneath, showing
 * through a hole the Canvas leaves where the card is.
 */
final class FlixView extends FrameLayout implements Recs.Listener, HomeData.Listener {
    private static final float MARGIN = 72, NAV_H = 120;
    private static final float LEAD = 150;   // title rows: the card before the focused one peeks in this much
    private static final float POSTER_W = 420, POSTER_H = 630, WIDE_W = 1120, GAP = 22;   // posters 2:3; the focused card 16:9
    private static final float APP_W = 400, APP_H = 225;
    private static final float BILL_TOP = 132, BILL_H = 770;
    private static final long TRAILER_DELAY = 700, ROTATE_EVERY = 20_000;
    private static final int FEATURED_MAX = 6;
    private static final int ACCENT = 0xFFF5A524;
    private static final String[] TAB_KIND = {null, "tv", "movie"};
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    static final class Card {
        Recs.Pick pick;
        HomeApps.Item app;
    }

    static final class Row {
        String title;
        final List<Card> cards = new ArrayList<>();
        boolean apps, shelf, others;
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
    // The focused card growing to wide (0 → 1, a little past 1 and back: springy); the one it came from shrinking.
    private float grow = 1;
    private int growDir, shrinkIdx = -1;
    private android.animation.ValueAnimator slider;
    private boolean okDown, okLong, moving;

    // The trailer playing (or about to) and where.
    private String trailerKey;
    private boolean trailerOn;
    private final RectF trailerRect = new RectF();
    private final Runnable trailerStart = this::fetchTrailer;
    /** The featured title changes by itself while it has focus and no trailer is playing. */
    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            if (running && focusRow == -1 && !trailerOn) nextFeatured(1);
            handler.postDelayed(this, ROTATE_EVERY);
        }
    };

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint navOn, navOff, navFocus, rowTitle, rowPeek, cardTitle, wideTitle, kindLabel, badge, weatherText, button, peekTitle;
    private TextPaint meta, overview, billTitle, billMeta, billOverview, nameUnder;
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
        screen = new Screen(a);
        addView(screen, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        shelf = HomeApps.shelf(a, false);
        rebuild(true);
    }

    /** Spanish when the app is set to Spanish (or the TV is, on "auto"); otherwise English. */
    private static String es(String spanish, String english) {
        String lang = Config.LANGUAGE.trim().toLowerCase(Locale.ROOT);
        boolean es = lang.startsWith("es") || (lang.equals("auto") && "es".equals(Locale.getDefault().getLanguage()));
        return es ? spanish : english;
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        recs.addListener(this);
        data.addListener(this);
        recs.refreshIfStale();
        data.refreshIfStale();
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
    }

    void destroy() {
        pause();
        click.release();
        web.destroy();
    }

    /** Home pressed while on the home screen: back to the featured title, on the first tab. */
    void reset() {
        moving = false;
        focusRow = -1;
        if (tab != 0) {
            tab = 0;
            navIndex = 0;
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
        rebuild(false);
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
            x.add(HomeApps.action(HomeApps.ANDROID_SETTINGS, "tv-settings", es("Ajustes de la TV", "TV Settings"), "tv_settings"));
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
        for (Recs.Pick p : recs.resumes()) if (kind == null || kind.equals(p.kind)) going.add(p);
        if (!going.isEmpty()) {
            Row r = row(out, es("Continuar viendo", "Continue watching"));
            for (Recs.Pick p : going) r.cards.add(card(p));
        }
        if (tab == 0) appRow(out, es("Tus apps", "Your apps"), shelf).shelf = true;   // near the top: picking an app is quick
        List<Recs.Pick> mine = new ArrayList<>();
        for (Recs.Pick p : recs.now(kind)) if (!p.resuming && p.pkg != null) mine.add(p);
        featuredList.clear();
        for (int i = 0; i < mine.size() && i < FEATURED_MAX; i++) featuredList.add(mine.get(i));
        if (featuredList.isEmpty() && !going.isEmpty()) featuredList.add(going.get(0));
        featuredIndex = featuredList.isEmpty() ? 0 : Math.min(featuredIndex, featuredList.size() - 1);
        featured = featuredList.isEmpty() ? null : featuredList.get(featuredIndex);
        if (!mine.isEmpty()) {
            Row r = row(out, es("Para ti", "For you"));
            for (Recs.Pick p : mine) r.cards.add(card(p));
        }
        for (Recs.Row rr : recs.rows()) {
            Row r = new Row();
            r.title = rr.title;
            for (Recs.Pick p : rr.picks) if (kind == null || kind.equals(p.kind)) r.cards.add(card(p));
            if (!r.cards.isEmpty()) out.add(r);
        }
        if (tab == 0) {
            appRow(out, es("Más apps", "More apps"), others).others = true;
            appRow(out, es("Entradas y ajustes", "Inputs and settings"), extras);
        }
        StringBuilder sig = new StringBuilder(String.valueOf(tab));
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
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (down) move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_UP: if (down) move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (down) move(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here; held: the app menu (move, remove, add).
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
                if (moving) moving = false;
                else if (focusRow >= 0 || focusRow == -2) focusRow = -1;
                else if (tab != 0) selectTab(0);
                else return true;
                changed(0, 0);
                return true;
            default:
                return false;
        }
    }

    private void move(int dx, int dy) {
        if (moving && focusRow >= 0 && rows.get(focusRow).shelf) {
            moveShelfApp(dx);
            return;
        }
        if (focusRow == -2) {
            if (dx != 0) {
                int next = Math.max(0, Math.min(TAB_KIND.length - 1, navIndex + dx));
                if (next == navIndex) return;
                navIndex = next;
                selectTab(next);
            } else if (dy > 0) {
                focusRow = -1;
            } else {
                return;
            }
            changed(0, 0);
            return;
        }
        if (focusRow == -1) {
            if (dx != 0) {
                if (featuredList.size() < 2) return;
                nextFeatured(dx);
                return;
            }
            if (dy < 0) {
                focusRow = -2;
                navIndex = tab;
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
        if (dx != 0) {
            int n = r.cards.size();
            // Title rows go round (after the last, the first again); apps stop at the ends.
            int next = r.apps ? Math.max(0, Math.min(n - 1, focusCol[focusRow] + dx)) : n < 2 ? 0 : (focusCol[focusRow] + dx + n) % n;
            if (next == focusCol[focusRow]) return;
            focusCol[focusRow] = next;
            if (r.apps) {
                // Apps don't slide: the ring moves, and the row scrolls only at its edges.
                int visible = Math.max(1, (int) ((1920 - 2 * MARGIN + GAP) / (APP_W + GAP)));
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
    }

    private void selectTab(int index) {
        tab = index;
        navIndex = index;
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

    private static final android.view.animation.Interpolator SPRING = new android.view.animation.OvershootInterpolator(1.3f);
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
            slider.setDuration(growing ? 340 : 220);
            slider.addUpdateListener(v -> {
                float t = (float) v.getAnimatedValue();
                slideY = fromY * (1 - EASE.getInterpolation(Math.min(1, t * 340 / 220f)));
                if (growing) grow = SPRING.getInterpolation(t);
                screen.invalidate();
            });
            slider.start();
        } else {
            grow = 1;
        }
        click.move();
        scheduleTrailer();
        screen.invalidate();
    }

    private void activate() {
        HomeApps.Item it;
        RectF r = focusRow >= 0 ? wideCardRect(new RectF()) : billboardRect(new RectF());
        if (focusRow == -2) {
            focusRow = -1;
            changed(0, 0);
            return;
        } else if (focusRow == -1) {
            if (featured == null || featured.pkg == null) return;
            it = HomeApps.app(featured.pkg, Recs.serviceName(featured));
            it.link = featured.link;
            if (pickEpisode(featured, it, r)) return;
        } else {
            Card c = focused();
            if (c == null) return;
            if (c.pick != null) {
                if (c.pick.pkg == null) return;
                it = HomeApps.app(c.pick.pkg, Recs.serviceName(c.pick));
                it.link = c.pick.link;
                if (pickEpisode(c.pick, it, r)) return;
            } else {
                it = c.app;
            }
        }
        launch(it, r);
    }

    private void launch(HomeApps.Item it, RectF r) {
        click.open();
        stopTrailer();
        if (!HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top), Math.round(r.width()), Math.round(r.height())))
            Toast.makeText(activity, es("No se pudo abrir ", "Couldn't open ") + it.label, Toast.LENGTH_SHORT).show();
    }

    /** A show in Stremio: choose the season and episode here, and Stremio opens right on that episode. */
    private boolean pickEpisode(final Recs.Pick p, final HomeApps.Item it, final RectF r) {
        final String prefix = "stremio:///detail/series/";
        if (p.resuming || p.id == 0 || p.link == null || !p.link.startsWith(prefix)) return false;
        final String imdb = p.link.substring(prefix.length()).split("/")[0];
        final RectF at = new RectF(r);
        recs.episodes(p, -1, seasons -> handler.post(() -> {
            if (seasons == null || seasons.isEmpty()) launch(it, at);
            else if (seasons.size() == 1) pickEpisodeIn(p, imdb, seasons.get(0)[0], it, at);
            else choose(p.title, seasons, s -> pickEpisodeIn(p, imdb, s[0], it, at));
        }));
        return true;
    }

    private void pickEpisodeIn(final Recs.Pick p, final String imdb, final String season, final HomeApps.Item it, final RectF at) {
        recs.episodes(p, Integer.parseInt(season), eps -> handler.post(() -> {
            if (eps == null || eps.isEmpty()) {
                launch(it, at);
                return;
            }
            for (String[] e : eps) e[1] = e[0] + ". " + e[1];
            choose(p.title + " · " + es("Temporada ", "Season ") + season, eps, e -> {
                it.link = "stremio:///detail/series/" + imdb + "/" + imdb + ":" + season + ":" + e[0];
                launch(it, at);
            });
        }));
    }

    /** A list to pick from ({value, label} rows). */
    private void choose(String title, final List<String[]> items, final java.util.function.Consumer<String[]> chosen) {
        String[] labels = new String[items.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = items.get(i)[1];
        new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setItems(labels, (d, which) -> chosen.accept(items.get(which)))
                .show();
    }

    // MARK: - Editing your apps (long press OK on an app)

    private boolean editMenu() {
        Card c = focused();
        if (c == null || c.app == null || c.app.kind != HomeApps.APP || c.app.pkg == null) return false;
        final HomeApps.Item it = c.app;
        final Context ctx = getContext();
        final List<String> pkgs = HomeApps.shelfPackages(ctx);
        final int at = pkgs.indexOf(it.pkg);
        List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (at < 0) {
            labels.add(es("Agregar a Tus apps", "Add to Your apps"));
            actions.add(() -> {
                if (pkgs.size() >= HomeApps.MAX_SHELF) {
                    Toast.makeText(ctx, es("Ya tienes " + HomeApps.MAX_SHELF + " apps: quita una primero.",
                            "You already have " + HomeApps.MAX_SHELF + " apps: remove one first."), Toast.LENGTH_LONG).show();
                    return;
                }
                pkgs.add(it.pkg);
                HomeApps.saveShelf(ctx, pkgs);
                reloadApps();
            });
        } else {
            labels.add(es("Mover", "Move"));
            actions.add(() -> {
                moving = true;
                Toast.makeText(ctx, es("← → para moverla, OK para terminar.", "← → to move it, OK when done."), Toast.LENGTH_LONG).show();
            });
            labels.add(es("Quitar de Tus apps", "Remove from Your apps"));
            actions.add(() -> {
                pkgs.remove(at);
                HomeApps.saveShelf(ctx, pkgs);
                reloadApps();
            });
        }
        labels.add(es("Cancelar", "Cancel"));
        actions.add(() -> {});
        new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(it.label)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
        return true;
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

    // MARK: - Trailer: a muted YouTube player under the focused card, shown once it really plays

    private Recs.Pick trailerPick() {
        if (focusRow == -1) return featured;
        Card c = focused();
        return c == null ? null : c.pick;
    }

    private void scheduleTrailer() {
        stopTrailer();
        if (running && trailerPick() != null) handler.postDelayed(trailerStart, TRAILER_DELAY);
    }

    private void fetchTrailer() {
        final Recs.Pick p = trailerPick();
        if (p == null) return;
        recs.trailer(p, found -> {
            if (found == null || !running || p != trailerPick()) return;
            final String key = found.substring(0, found.indexOf('|'));
            // "|es": in your language; "|music": an opening song; "|mute": the original trailer, without sound.
            final String tag = found.substring(found.indexOf('|') + 1);
            final boolean mute = tag.equals("mute");
            final boolean subs = !tag.equals(es("es", "en")) && !tag.equals("music");
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
                    subs, es("es", "en"), mute);
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
        if (trailerKey != null) web.evaluateJavascript("stop()", null);
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

    // MARK: - Layout

    private RectF billboardRect(RectF out) {
        out.set(MARGIN * k, BILL_TOP * k, w - MARGIN * k, (BILL_TOP + BILL_H) * k);
        return out;
    }

    private RectF wideCardRect(RectF out) {
        Row r = rows.get(focusRow);
        float top = 190 * k;
        if (r.apps) {
            float x = (MARGIN + (focusCol[focusRow] - firstCol[focusRow]) * (APP_W + GAP)) * k;
            out.set(x, top, x + APP_W * k, top + APP_H * k);
        } else {
            out.set((MARGIN + LEAD) * k, top, (MARGIN + LEAD + WIDE_W) * k, top + POSTER_H * k);
        }
        return out;
    }

    private void makePaints() {
        navOn = Ui.text(Ui.semibold, 24 * k, 0xFFFFFFFF);
        navOff = Ui.text(Ui.semibold, 24 * k, 0xFFB4B4BC);
        navFocus = Ui.text(Ui.semibold, 24 * k, 0xFF000000);
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
        overview = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xFFB4B4BC));
        billTitle = new TextPaint(Ui.text(Ui.displaySemibold, 92 * k, 0xFFFFFFFF));
        billMeta = new TextPaint(Ui.text(Ui.medium, 23 * k, 0xFFE4E4E7));
        billOverview = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xFFD4D4D8));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(4 * k);
        ring.setColor(0xFFFFFFFF);
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
            // Black everywhere but the playing trailer, which shows through from the WebView underneath.
            if (trailerOn) {
                float rr = (focusRow == -1 ? 22 : 12) * k;
                hole.reset();
                hole.setFillType(Path.FillType.EVEN_ODD);
                hole.addRect(0, 0, w, h, Path.Direction.CW);
                hole.addRoundRect(trailerRect, rr, rr, Path.Direction.CW);
                fill.setShader(null);
                fill.setColor(0xFF000000);
                c.drawPath(hole, fill);
            } else {
                c.drawColor(0xFF000000);
            }
            if (focusRow < 0) drawHome(c); else drawRows(c);
            drawNav(c);
        }
    }

    private void drawNav(Canvas c) {
        String[] tabs = {es("Inicio", "Home"), es("Series", "Shows"), es("Películas", "Movies")};
        float total = 0, pad = 22 * k, gap = 10 * k;
        for (String t : tabs) total += navOn.measureText(t) + 2 * pad + gap;
        float x = (w - total) / 2f, cy = NAV_H / 2f * k;
        for (int i = 0; i < tabs.length; i++) {
            float tw = navOn.measureText(tabs[i]) + 2 * pad;
            r1.set(x, cy - 26 * k, x + tw, cy + 26 * k);
            boolean focus = focusRow == -2 && navIndex == i;
            if (focus || i == tab) {
                fill.setShader(null);
                fill.setColor(focus ? 0xFFFFFFFF : 0x29FFFFFF);
                c.drawRoundRect(r1, 26 * k, 26 * k, fill);
            }
            Paint p = focus ? navFocus : i == tab ? navOn : navOff;
            c.drawText(tabs[i], x + pad, cy + Ui.halfCap(p), p);
            x += tw + gap;
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

    /** The featured title in a big rounded card, with the first row peeking below. */
    private void drawHome(Canvas c) {
        float dy = slideY * k;
        c.save();
        c.translate(0, dy);
        RectF b = billboardRect(r2);
        float rad = 22 * k;
        Recs.Pick p = featured;
        if (!trailerOn) {
            Bitmap img = p == null ? null : recs.image(p.backdrop, "w1280");
            drawCover(c, img, b, rad, 0xFF1C1C1F);
        }
        fill.setShader(new LinearGradient(b.left, 0, b.left + b.width() * 0.62f, 0, 0xE0000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(b, rad, rad, fill);
        fill.setShader(new LinearGradient(0, b.bottom - b.height() * 0.5f, 0, b.bottom, 0x00000000, 0xB0000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(b, rad, rad, fill);
        fill.setShader(null);
        if (focusRow == -1) c.drawRoundRect(b, rad, rad, ring);
        if (p != null) {
            float x = b.left + 64 * k, bottom = b.bottom - 64 * k;
            // Bottom up: buttons, overview, meta, title, label.
            String cta = (p.resuming ? es("Reanudar en ", "Resume on ") : es("Ver en ", "Watch on ")) + Recs.serviceName(p);
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
            fill.setColor(ACCENT);
            c.drawRect(x, y - 20 * k, x + 6 * k, y + 2 * k, fill);
            c.drawText("tv".equals(p.kind) ? es("SERIE", "SERIES") : es("PELÍCULA", "FILM"), x + 18 * k, y, kindLabel);
            // Which of the featured titles this is (← → or wait for the next).
            int n = featuredList.size();
            float dot = 10 * k, dx = b.right - 56 * k - (n - 1) * 22 * k;
            for (int i = 0; n > 1 && i < n; i++) {
                fill.setColor(i == featuredIndex ? 0xFFFFFFFF : 0x66FFFFFF);
                c.drawCircle(dx + i * 22 * k, b.bottom - 48 * k, i == featuredIndex ? dot * 0.7f : dot * 0.5f, fill);
            }
        }
        if (!rows.isEmpty()) {
            Row first = rows.get(0);
            float top = b.bottom + 34 * k;
            c.drawText(first.title, MARGIN * k, top + 30 * k, rowPeek);
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
        c.drawText(r.title, MARGIN * k, titleY, rowTitle);
        int col = focusCol[focusRow], n = r.cards.size();
        // Growing: the focused card from poster to wide, the one it came from the other way; the row moves so the
        // old card starts where it was (→: it shrinks into the peek on the left; ←: the new one comes from there).
        float g = grow, fw = POSTER_W + (WIDE_W - POSTER_W) * g, sw = WIDE_W - (WIDE_W - POSTER_W) * g;
        float off = growDir > 0 ? (sw + GAP) * (1 - g) : growDir < 0 ? -(GAP + POSTER_W) * (1 - g) : 0;
        float x = (MARGIN + (r.apps ? 0 : LEAD + off)) * k;
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
                fill.setColor(ACCENT);
                c.drawRoundRect(r2, 8 * k, 8 * k, fill);
                c.drawText(es("← Mover →", "← Move →"), r2.left + 14 * k, r2.centerY() + Ui.halfCap(badge), badge);
                badge.setColor(0xFFFFFFFF);
            }
            x += cw + GAP * k;
        }
        float below = top + (r.apps ? APP_H : POSTER_H) * k + 52 * k;
        Card f = focused();
        if (f != null && f.pick != null) {
            float y = below;
            // Always its name first (same height for every title, so nothing moves into the next row).
            y = drawBlockDown(c, f.pick.title, nameUnder, MARGIN * k, y, 1000 * k, 1) + 6 * k;
            y = drawBlockDown(c, info(f.pick), meta, MARGIN * k, y, 1000 * k, 1) + 10 * k;
            drawBlockDown(c, f.pick.overview, overview, MARGIN * k, y, 940 * k, 2);
        } else if (f != null) {
            drawBlockDown(c, f.app.label, meta, MARGIN * k, below, 1000 * k, 1);
        }
        if (focusRow + 1 < rows.size()) {
            Row next = rows.get(focusRow + 1);
            float ny = below + 150 * k;   // under the name, info and two lines of overview
            c.drawText(next.title, MARGIN * k, ny + 30 * k, rowPeek);
            drawPeekCards(c, next, ny + 52 * k);
        }
        c.restore();
    }

    private void drawPeekCards(Canvas c, Row r, float top) {
        int n = r.cards.size(), at = rows.indexOf(r), col = r.apps || at < 0 ? 0 : Math.min(focusCol[at], Math.max(0, n - 1));
        float x = (MARGIN + (r.apps ? 0 : LEAD)) * k;
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
        boolean playing = wide && trailerOn && focusRow >= 0;
        if (!playing) drawCover(c, recs.image(p.backdrop, "w780"), r, rad, 0xFF26262A);
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
            c.drawText("tv".equals(p.kind) ? es("SERIE", "SERIES") : es("PELÍCULA", "FILM"), x, top - 14 * k, kindLabel);
            float bx = r.right - 16 * k;
            bx = drawBadge(c, Recs.serviceName(p), bx, r.bottom - 22 * k, 0x99000000, 0xFFFFFFFF);
            if (p.resuming) drawBadge(c, es("Continuar viendo", "Continue watching"), bx - 10 * k, r.bottom - 22 * k, ACCENT, 0xFF000000);
            c.drawRoundRect(r, rad, rad, ring);
        } else if (Float.isNaN(drawLogo(c, p, r.centerX(), r.bottom - 24 * k, r.width() * 0.8f, r.height() * 0.2f, true))) {
            String t = TextUtils.ellipsize(p.title, new TextPaint(peekTitle), r.width() - 24 * k, TextUtils.TruncateAt.END).toString();
            c.drawText(t, r.centerX() - peekTitle.measureText(t) / 2, r.bottom - 22 * k, peekTitle);
        }
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
