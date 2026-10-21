package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, poster, overview = "", meta = "", app, pkg;
        String link;            // opens the title itself in the app (Stremio), or null = the app's home
        String logo;            // the title's logo (a transparent PNG), or null: its name is written instead
        boolean logoMine;       // the logo is in your language (else the name is written under it too)
        String year = "";       // of release or first episode
        double rating;          // TMDB's, 0 = too few votes
        String imdb;            // from the details (not saved): to leave out what you already watched
        boolean resuming;       // Stremio's title in progress (the "Continue watching" card)
        String services = "";   // the services it's on in your country ("netflix,disney"), for the platform filter
        boolean anime;          // Japanese animation (the Anime tab)
        boolean sagaNext;       // the next one to watch in its saga (set by the home screen, not saved)
        int[] genres = {};      // TMDB genre ids, while the list is made (for your taste; not saved)
        String released = "", lang = "";   // first air or release date, original language (while the list is made)
        boolean detailed;
    }

    /** A row under the apps (K-dramas, Anime, Trending on Netflix...). */
    static final class Row {
        String title;
        boolean saga;           // a saga's films in release order
        boolean top;            // new episodes and premieres: shown first, under My list
        volatile List<Pick> picks = new ArrayList<>();
        /** Where more of it comes from ("tv:/discover/tv?…", one page more of each every time), or null: it's all there. */
        String[] more;
        int page;               // pages already in it
        String app, pkg;        // "New on …": it opens that service
        volatile boolean loading, done;
    }

    interface Listener { void onRecs(); }

    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMAGES = "https://image.tmdb.org/t/p/original";
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 32;
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    static final String[] PERIOD_LABEL = {"PARA ESTA MANHÃ", "PARA ESTA TARDE", "PARA ESTA NOITE"};
    static final String[] PERIOD_LABEL_EN = {"THIS MORNING", "THIS AFTERNOON", "TONIGHT"};
    static final String[] PERIOD_LABEL_ES = {"PARA ESTA MAÑANA", "PARA ESTA TARDE", "PARA ESTA NOCHE"};
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"paramount", "paramount", "com.cbs.ca"},                       // Paramount+ in Canada and Latin America
        {"crunchyroll", "crunchyroll", "com.crunchyroll.crunchyroid"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
        {"viki", "viki", "com.viki.android"},
    };
    /** Which app a pick opens when it's on several: first in this list wins, then the rest in SERVICES order. */
    private static final List<String> PRIORITY = Arrays.asList("stremio", "netflix", "disney", "prime");   // Stremio first: it opens on the episode
    /** Stremio has every title (through its add-ons): it opens straight to the title's page, by its IMDb id. */
    private static final String STREMIO = "com.stremio.one";
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
        SERVICE_NAME.put("crunchyroll", "Crunchyroll");
        SERVICE_NAME.put("viki", "Viki");
        SERVICE_NAME.put("stremio", "Stremio");
    }
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    private static final String REGION = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> images = new LinkedHashMap<>();   // this hour's, built and ready to draw
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp;
    private volatile boolean listEnglish;   // the saved list came in English (titles, genres and texts)
    private volatile List<Pick> resumes = new ArrayList<>();
    /** The IMDb ids you've watched on Stremio (for "Next in the saga"). */
    private volatile java.util.Set<String> watched = new java.util.HashSet<>();

    boolean watched(Pick p) {
        return p.imdb != null && watched.contains(p.imdb);
    }   // Stremio's titles in progress, last watched first ("stremio_key")
    private final Map<String, JSONObject> foundByImdb = new HashMap<>();   // TMDB's match for each, asked once
    private volatile List<Row> rows = new ArrayList<>();
    private final ExecutorService thumbIo = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> thumbs = new LinkedHashMap<>(16, 0.75f, true);   // row cards, small (TMDB w500); least recently drawn go first
    private final java.util.Set<String> thumbPending = new java.util.HashSet<>();
    private volatile long resumeCheckedAt;

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listEnglish = cache.getBoolean("english", false);
            keyStamp = cache.getLong("keyStamp", 0);
            String r = cache.getString("resumes", null);
            if (r != null) resumes = parse(new JSONArray(r));
            for (Pick p : resumes) p.resuming = true;
            JSONObject t = new JSONObject(cache.getString("taste", "{}"));
            Map<Integer, Integer> liked = new HashMap<>();
            for (java.util.Iterator<String> it = t.keys(); it.hasNext(); ) {
                String g = it.next();
                liked.put(Integer.parseInt(g), t.getInt(g));
            }
            taste = liked;
            String savedRows = cache.getString("rows", null);
            if (savedRows != null) rows = parseRows(new JSONArray(savedRows));
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        return (Ui.en ? PERIOD_LABEL_EN : Ui.LANG.equals("es") ? PERIOD_LABEL_ES : PERIOD_LABEL)[period(System.currentTimeMillis())];
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** Picks per tab: three on screen, the rest one press of → away. */
    static final int PER_TAB = 30;

    /** This hour's picks, movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). */
    List<Pick> now(String kind) {
        List<Pick> out = kind == null ? continueCards() : new ArrayList<>();   // Explore starts with Continue watching
        if (!hasKey(context)) return out;     // no TMDB key: no recommendations
        List<Pick> list = new ArrayList<>();
        for (Pick p : picks) {
            if ((kind == null || kind.equals(p.kind)) && (!p.detailed || p.pkg != null)) list.add(p);   // app missing: skip
        }
        if (list.isEmpty()) return out;
        long hour = System.currentTimeMillis() / 3600_000L;
        // Rotating every hour, never two from the same franchise ("Law & Order" and "Law & Order: Special…").
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : out) seen.add(franchise(p.title));
        for (int i = 0; i < list.size() && out.size() < PER_TAB; i++) {
            Pick p = list.get((int) ((hour * 3 + i) % list.size()));
            if (seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    // MARK: - Continue watching

    /** Stremio's titles in progress (each resumes that movie or episode); other apps keep their progress to themselves. */
    private List<Pick> continueCards() {
        return new ArrayList<>(resumes);
    }

    private static final int RESUME_MAX = 12;

    /** Out of Continue watching: here at once, on Stremio in the background (see Streams.forget). */
    void forgetResume(Pick p) {
        String prefix = "stremio:///detail/";
        if (p.link == null || !p.link.startsWith(prefix)) return;
        String[] parts = p.link.substring(prefix.length()).split("/");   // "series/tt123/tt123:1:2"
        if (parts.length < 2) return;
        List<Pick> left = new ArrayList<>();
        JSONArray saved = new JSONArray();
        try {
            for (Pick r : resumes) {
                if (key(r).equals(key(p))) continue;
                left.add(r);
                saved.put(json(r));
            }
        } catch (Exception e) {
            return;
        }
        resumes = left;
        cache.edit().putString("resumes", saved.toString()).apply();
        resumeCheckedAt = System.currentTimeMillis();   // not read back before Stremio has it
        io.execute(() -> Streams.forget(parts[1]));
    }

    /** Stremio's library (with the account key): the titles not finished, watched most recently first. Every 2 min. */
    private void refreshResume() {
        final String key = Config.STREMIO_KEY.trim();
        long now = System.currentTimeMillis();
        if (key.isEmpty() || (now - resumeCheckedAt >= 0 && now - resumeCheckedAt < 2 * 60_000L)) return;
        resumeCheckedAt = now;
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("authKey", key).put("collection", "libraryItem")
                        .put("ids", new JSONArray()).put("all", true);
                JSONObject res = post("https://api.strem.io/api/datastoreGet", body);
                if (res.has("error")) throw new IOException(res.getJSONObject("error").optString("message"));
                JSONArray items = res.getJSONArray("result");
                List<JSONObject> going = new ArrayList<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    JSONObject st = it.optJSONObject("state");
                    if (st != null && (st.optInt("timesWatched") > 0 || st.optInt("flaggedWatched") > 0)) seen.add(it.optString("_id"));
                    // In progress: a position saved, and either in the library or only watched (temp).
                    String id = it.optString("_id");
                    if (st == null || st.optLong("timeOffset") <= 0 || !(id.startsWith("tt") || id.startsWith("kitsu:"))) continue;
                    if (it.optBoolean("removed") && !it.optBoolean("temp")) continue;
                    going.add(it);
                }
                java.util.Collections.sort(going, (a, b) -> Long.compare(
                        time(b.optJSONObject("state").optString("lastWatched")), time(a.optJSONObject("state").optString("lastWatched"))));
                List<Pick> out = new ArrayList<>();
                JSONArray saved = new JSONArray();
                for (JSONObject it : going.subList(0, Math.min(RESUME_MAX, going.size()))) {
                    Pick p = resumePick(it);
                    if (p.backdrop != null) download(p);
                    out.add(p);
                    saved.put(json(p));
                }
                resumes = out;
                watched = seen;
                cache.edit().putString("resumes", saved.toString()).remove("resume").apply();
            } catch (Exception e) {
                AppLog.w("continue watching: " + e.getMessage());
            }
            notifyListeners();
        });
    }

    /** A Stremio library item as a card: TMDB's title and photo, and a link that reopens that movie or episode. */
    private Pick resumePick(JSONObject it) throws Exception {
        String id = it.getString("_id"), type = it.optString("type");
        boolean series = type.equals("series");
        JSONObject st = it.getJSONObject("state");
        Pick p = new Pick();
        p.kind = series ? "tv" : "movie";
        JSONObject o;
        synchronized (foundByImdb) { o = foundByImdb.get(id); }
        if (o == null && hasKey(context)) {
            // Anime from Stremio's Kitsu catalog: the Kitsu add-on knows its IMDb id, which TMDB finds.
            String imdb = id;
            if (id.startsWith("kitsu:")) {
                try {
                    imdb = Streams.meta(id).optString("imdb_id", "");
                } catch (Exception e) {
                    AppLog.w("kitsu " + id + ": " + e.getMessage());
                    imdb = null;   // not saved: asked again next time
                }
            }
            JSONArray found = imdb == null || !imdb.startsWith("tt") ? null : get("/find/" + imdb + "?external_source=imdb_id&language=" + lang())
                    .optJSONArray(series ? "tv_results" : "movie_results");
            o = found != null && found.length() > 0 ? found.getJSONObject(0) : new JSONObject();
            if (o.length() > 0) {
                try {
                    o.put("_images", get("/" + (series ? "tv" : "movie") + "/" + o.optInt("id") + "/images"));
                } catch (Exception e) {
                    AppLog.w("logo: " + e.getMessage());
                }
            }
            if (imdb != null) synchronized (foundByImdb) { foundByImdb.put(id, o); }
        }
        if (o != null) {
            if (o.length() > 0) {
                p.id = o.optInt("id");
                p.anime = isAnime(o);
                p.title = o.optString(series ? "name" : "title");
                p.backdrop = path(o, "backdrop_path");
                p.poster = path(o, "poster_path");
                p.overview = o.optString("overview", "");
                setLogo(p, o.optJSONObject("_images"));
                meta(p, o);
            }
        }
        if (p.title == null || p.title.isEmpty()) p.title = it.optString("name");
        p.app = "stremio";
        p.pkg = STREMIO;
        p.detailed = true;
        p.resuming = true;
        p.link = "stremio:///detail/" + (series ? "series" : "movie") + "/" + id + "/" + st.optString("video_id", id);
        List<String> parts = new ArrayList<>();
        int season = st.optInt("season"), episode = st.optInt("episode");
        String[] v = st.optString("video_id").split(":");
        if (id.startsWith("kitsu:") && v.length >= 3) {   // Stremio keeps no season for these: the episode is in the id
            season = 1;
            try {
                episode = Integer.parseInt(v[v.length - 1]);
            } catch (NumberFormatException ignored) {
            }
        }
        if (series && episode > 0) parts.add(Ui.t("T", "S", "T") + season + " E" + episode);
        long left = (st.optLong("duration") - st.optLong("timeOffset")) / 60_000L;
        if (st.optLong("duration") > 0 && left > 0)
            parts.add(Ui.t("faltan " + left + " min", left + " min left", "faltam " + left + " min"));
        parts.add("Stremio");
        p.meta = String.join(" · ", parts);
        return p;
    }

    private static long time(String iso) {
        try {
            return java.time.Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    private static JSONObject post(String url, JSONObject body) throws IOException, org.json.JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try {
            c.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Stremio returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : SERVICE_NAME.get(p.app);
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), p.backdrop.replace("/", ""));
    }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        refreshResume();
        if (extra == 0 && !rows.isEmpty()) moreRows();   // the first extra rows come at once, not only when you get to the end
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = (Config.TMDB_KEY.hashCode() * 31L + REGION.hashCode()) * 31L + Ui.LANG.hashCode();   // a new key, country or language refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || listEnglish != Ui.en;
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || (pick.backdrop != null && !hasImage(pick));
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) fetchList(p);
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        if (pick.pkg != null && pick.backdrop != null) download(pick);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        for (Pick p : picks) {
            if (p.detailed && p.pkg != null && hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (!p.detailed || (p.pkg != null && !hasImage(p))) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (p.pkg != null) download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** This hour's picks for the three tabs (Explore, Movies, Shows), without repeats. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {null, "movie", "tv"}) {
            for (Pick p : now(kind)) if (seen.add(key(p))) out.add(p);
        }
        return out;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    private void fetchList(int p) throws Exception {
        // The services the TV has, by their TMDB ids in your region.
        Map<Integer, String[]> services = new HashMap<>();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"));
            if (s != null && installed(s[2])) services.put(o.getInt("provider_id"), s);
        }
        if (services.isEmpty()) throw new IOException(Ui.t("ninguna app de streaming de la TV está en TMDB", "none of the TV's streaming services are on TMDB",
                "nenhum app de streaming da TV está no TMDB"));
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        JSONObject byApp = new JSONObject();   // each service's TMDB ids and app, for its own rows (rows(platform))
        for (Map.Entry<Integer, String[]> e : services.entrySet()) {
            JSONObject o = byApp.optJSONObject(e.getValue()[1]);
            if (o == null) byApp.put(e.getValue()[1], o = new JSONObject().put("ids", "").put("pkg", e.getValue()[2]));
            o.put("ids", o.getString("ids") + (o.getString("ids").isEmpty() ? "" : "|") + e.getKey());
        }
        cache.edit().putString("providers", ids.toString()).putString("providersByApp", byApp.toString()).apply();
        // With Stremio (which opens anything) the releases are everyone's; without it, only what the TV's services have.
        String common = "&language=" + lang() + "&watch_region=" + REGION + "&include_adult=false&sort_by=popularity.desc"
                + (installed(STREMIO) ? "" : "&with_watch_monetization_types=flatrate&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8"));
        // 1. Trending this week (TMDB goes by what people are watching and searching for right now), only what is
        //    on one of the TV's services: the details say where (and are then ready).
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray hot = get("/trending/" + kind + "/week?language=" + lang()).getJSONArray("results");
            List<Pick> candidates = new ArrayList<>();
            for (int i = 0; i < hot.length() && i < 20; i++) add(candidates, hot.getJSONObject(i), kind);
            for (Pick pick : candidates) {
                try {
                    fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                if (pick.pkg != null) (kind.equals("movie") ? hotMovies : hotShows).add(pick);
            }
        }
        // 2. Recent releases on the TV's services: movies from the past year and shows with a new episode in the
        //    past 3 months.
        JSONArray movies = get("/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365)
                + "&primary_release_date.lte=" + day(0) + common).getJSONArray("results");
        JSONArray shows = get("/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0)
                + common).getJSONArray("results");
        List<Pick> newMovies = new ArrayList<>(), newShows = new ArrayList<>();
        for (int i = 0; i < movies.length(); i++) add(newMovies, movies.getJSONObject(i), "movie");
        for (int i = 0; i < shows.length(); i++) add(newShows, shows.getJSONObject(i), "tv");
        // Trending first, then the new releases; movies and shows alternating, without repeats, up to 24.
        List<Pick> others = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        alternate(others, seen, hotMovies, hotShows, 60);
        alternate(others, seen, newMovies, newShows, 60);
        // 3. Like what you watch on Stremio, every other pick (when there's a Stremio key).
        List<Pick> mine = new ArrayList<>();
        try {
            mine = likeWhatYouWatch();
        } catch (Exception e) {
            AppLog.w("recommendations from Stremio: " + e.getMessage());
        }
        others = byTaste(others);   // after likeWhatYouWatch: it learns your taste
        List<Pick> list = new ArrayList<>();
        alternate(list, new java.util.HashSet<>(), mine, others, 60);
        if (list.isEmpty()) throw new IOException(Ui.t("TMDB no devolvió recomendaciones", "TMDB returned no recommendations", "o TMDB não devolveu sugestões"));
        List<Row> newRows = rows;
        try {
            newRows = fetchRows(services);
            newRows.addAll(0, because);
        } catch (Exception e) {
            AppLog.w("recommendation rows: " + e.getMessage());
        }
        try {
            List<Pick> all = new ArrayList<>(list);
            for (Row r : newRows) all.addAll(r.picks);
            addLinks(all);
            addImages(all);
            int linked = 0, stremio = 0;
            for (Pick q : all) {
                if (q.link != null) linked++;
                if ("stremio".equals(q.app)) stremio++;
            }
            AppLog.w("title links: " + linked + " of " + all.size() + " (" + stremio + " in Stremio)");
            cache.edit().putString("rows", rowsJson(newRows).toString()).apply();
        } catch (Exception e) {
            AppLog.w("title links: " + e.getMessage());
        }
        rows = newRows;
        extra = 0;
        recipes = null;
        synchronized (byPlatform) {
            byPlatform.clear();
            platformAt.clear();
        }
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listEnglish = Ui.en;
    }

    /** Language of titles and genres; the services come from the region. */
    private static String lang() {
        if (Ui.LANG.equals("es")) return "ES".equals(REGION) ? "es-ES" : "es-MX";
        if (Ui.LANG.equals("pt")) return "PT".equals(REGION) ? "pt-PT" : "pt-BR";
        return "en-US";
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        p.poster = path(o, "poster_path");
        p.overview = o.optString("overview", "");
        p.released = o.optString(kind.equals("movie") ? "release_date" : "first_air_date", "");
        p.lang = o.optString("original_language", "");
        JSONArray g = o.optJSONArray("genre_ids");
        if (g != null) {
            p.genres = new int[g.length()];
            for (int i = 0; i < g.length(); i++) p.genres[i] = g.optInt(i);
        }
        meta(p, o);
        list.add(p);
    }

    // MARK: - Your taste: the genres of what you watched on Stremio

    /** Genre id → how many of your last titles have it (movie and show ids counted the same); 0 → how many titles. */
    private volatile Map<Integer, Integer> taste = new HashMap<>();

    /** Shows' genres as the movies' ("Action & Adventure" counts as Action and Adventure). */
    private static int[] same(int g) {
        switch (g) {
            case 10759: return new int[] {28, 12};
            case 10765: return new int[] {878, 14};
            case 10768: return new int[] {10752};
            default: return new int[] {g};
        }
    }

    private static Map<String, Object> stringKeys(Map<Integer, Integer> m) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<Integer, Integer> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    /** Original language → how many of your last titles are in it ("ko" for K-dramas). */
    private volatile Map<String, Integer> langs = new HashMap<>();
    /** The shows in your Stremio history (their IMDb ids), for "New episodes of your shows". */
    private volatile List<String> followed = new ArrayList<>();

    private static void count(Map<Integer, Integer> taste, JSONObject found) {
        taste.put(0, taste.containsKey(0) ? taste.get(0) + 1 : 1);
        JSONArray g = found.optJSONArray("genre_ids");
        for (int i = 0; g != null && i < g.length(); i++)
            for (int s : same(g.optInt(i))) taste.put(s, taste.containsKey(s) ? taste.get(s) + 1 : 1);
    }

    /** Genres that set the mood: a title with one of them is left out unless it's yours, even if it's also a comedy. */
    private static final java.util.Set<Integer> MOOD = new java.util.HashSet<>(Arrays.asList(27, 10752, 37, 99, 10402));

    /** In at least 15% of what you watched (one horror film among 20 doesn't make horror yours). */
    private boolean yours(int genre) {
        Integer titles = taste.get(0), n = taste.get(genre);
        return titles != null && n != null && n >= Math.max(1, Math.round(titles * 0.15f));
    }

    /** How much a genre row is yours: its count if yours, else 0 (it goes to the end). */
    private int score(int genre) {
        int n = 0;
        for (int s : same(genre)) if (yours(s)) n += taste.get(s);
        return n;
    }

    /** One of your genres, and no mood genre you don't watch (no genres known: kept). */
    private boolean forYou(int[] genres) {
        if (genres.length == 0) return true;
        boolean any = false;
        for (int g : genres) {
            for (int s : same(g)) {
                if (MOOD.contains(s) && !yours(s)) return false;
                any |= yours(s);
            }
        }
        return any;
    }

    /**
     * Trending and new for you: what shares a genre with what you watch first (in trending's order); what shares none
     * (horror in October, when you never watch horror) left out, as long as 24 are left.
     */
    private List<Pick> byTaste(List<Pick> list) {
        if (!taste.containsKey(0)) return list;
        List<Pick> yes = new ArrayList<>(), no = new ArrayList<>();
        for (Pick p : list) (forYou(p.genres) ? yes : no).add(p);
        if (yes.size() < 24) yes.addAll(no);
        AppLog.w("for you: " + yes.size() + " by your taste, " + (yes.size() >= 24 ? no.size() + " left out" : "none left out"));
        return yes;
    }

    /** Year and rating from a TMDB result or details. */
    private static void meta(Pick p, JSONObject o) {
        String date = o.optString("release_date", o.optString("first_air_date", ""));
        if (date.length() >= 4) p.year = date.substring(0, 4);
        if (o.optInt("vote_count") >= 20) p.rating = o.optDouble("vote_average", 0);
    }

    /** The rows under the apps. */
    List<Row> rows() {
        return hasKey(context) ? rows : new ArrayList<>();
    }

    /**
     * K-dramas and anime (each opens in the app that has it, by the usual priority), then what's popular on each of
     * the TV's big services (opens that service).
     */
    private List<Row> fetchRows(Map<Integer, String[]> services) throws Exception {
        List<Row> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();   // each title in one row only
        try {
            newEpisodes(out, seen);
            premieres(out, seen);
        } catch (Exception e) {
            AppLog.w("what's new: " + e.getMessage());
        }
        listRow(out, seen, Ui.t("Tendencias de hoy",
                "Trending today", "Em alta hoje"), 15, "all:/trending/all/day?language=" + lang());
        String ko = KO, ja = JA;
        category(out, seen, Ui.t("K-dramas de romance",
                "Romance K-dramas", "K-dramas de romance"), ko + "&with_keywords=9840");
        category(out, seen, Ui.t("K-dramas de comedia romántica",
                "Romantic comedy K-dramas",
                "K-dramas de comédia romântica"), ko + "&with_genres=35&with_keywords=9840");
        category(out, seen, Ui.t("K-dramas de suspenso y crimen",
                "Thriller and crime K-dramas", "K-dramas de suspense e crime"), ko + "&with_genres=80|9648");
        category(out, seen, Ui.t("K-dramas de fantasía",
                "Fantasy K-dramas", "K-dramas de fantasia"), ko + "&with_genres=10765");
        category(out, seen, Ui.t("Anime de acción",
                "Action anime", "Anime de ação"), ja.replace("with_genres=16", "with_genres=16,10759"));
        category(out, seen, Ui.t("Anime isekai", "Isekai anime", "Anime isekai"), ja + "&with_keywords=237451");
        category(out, seen, Ui.t("Anime de romance", "Romance anime", "Anime de romance"), ja + "&with_keywords=9840");
        category(out, seen, Ui.t("Anime de la vida cotidiana",
                "Slice of life anime", "Anime slice of life"), ja + "&with_keywords=9914");
        // Well loved but not famous: to find something new.
        String gem = "&vote_average.gte=7.4&sort_by=vote_average.desc&without_genres=10762,10764,10767,99";
        listRow(out, seen, Ui.t("Joyas escondidas",
                "Hidden gems",
                "Joias escondidas"), ROW_MAX, discoverPath("movie", gem + "&vote_count.gte=300&vote_count.lte=4000"),
                discoverPath("tv", gem + "&vote_count.gte=150&vote_count.lte=2500"));
        String great = "&vote_average.gte=8&without_genres=10762,10764,10767,99";
        listRow(out, seen, Ui.t("Aclamadas por la crítica",
                "Critically acclaimed",
                "Aclamados pela crítica"), 15, discoverPath("movie", great + "&vote_count.gte=2000"),
                discoverPath("tv", great + "&vote_count.gte=800"));
        listRow(out, seen, Ui.t("Algo corto",
                "Something short",
                "Algo curto"), 15, discoverPath("movie", "&with_runtime.lte=95&with_runtime.gte=60&vote_count.gte=500"),
                discoverPath("tv", "&with_runtime.lte=30&vote_count.gte=200&without_genres=10762,10764,10767"));
        sagas(out);
        // Each big service: what came out this past year and people watch most (not decades-old shows on repeat).
        for (String app : new String[] {"netflix", "disney", "prime"}) {
            StringBuilder ids = new StringBuilder();
            String pkg = null;
            for (Map.Entry<Integer, String[]> e : services.entrySet()) {
                if (!e.getValue()[1].equals(app)) continue;
                ids.append(ids.length() == 0 ? "" : "|").append(e.getKey());
                pkg = e.getValue()[2];
            }
            if (pkg == null) continue;
            String with = "&with_watch_monetization_types=flatrate&vote_count.gte=40&with_watch_providers="
                    + URLEncoder.encode(ids.toString(), "UTF-8");
            String[] more = {discoverPath("tv", with + "&first_air_date.gte=" + day(-365)),
                    discoverPath("movie", with + "&primary_release_date.gte=" + day(-365))};
            List<Pick> mixed = new ArrayList<>();
            alternate(mixed, seen, pages(more[0], 1, 2), pages(more[1], 1, 2), ROW_MAX);
            Row r = row(out, Ui.t("Lo nuevo en ",
                    "New on ", "Novo em ") + SERVICE_NAME.get(app), onService(mixed, app, pkg));
            if (r != null) {
                r.more = more;
                r.page = 2;
                r.app = app;
                r.pkg = pkg;
            }
        }
        return out;
    }

    private static final String KO = "&with_original_language=ko&without_genres=10762,10751,10764,10767&vote_count.gte=30";
    private static final String JA = "&with_original_language=ja&with_genres=16&without_genres=10762,10751&vote_count.gte=30";

    // MARK: - What's new: new episodes of your shows, and premieres

    /** "hoy", "ayer", "hace 3 días", or "18 oct" for an ISO date ("2026-10-18"). */
    private static String when(String date) {
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(date), today = java.time.LocalDate.now();
            long days = java.time.temporal.ChronoUnit.DAYS.between(d, today);
            if (days <= 0) return Ui.t("hoy", "today", "hoje");
            if (days == 1) return Ui.t("ayer", "yesterday", "ontem");
            if (days < 7) return Ui.t("hace " + days + " días", days + " days ago", "há " + days + " dias");
            return d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Ui.LOCALE)).replace(".", "");
        } catch (Exception e) {
            return date;
        }
    }

    /**
     * The shows you're watching, have in My list or watched lately on Stremio that got an episode in the past week,
     * newest first ("Nuevo episodio T2 E5 · ayer").
     */
    private void newEpisodes(List<Row> out, java.util.Set<String> seen) throws Exception {
        java.util.LinkedHashSet<Integer> ids = new java.util.LinkedHashSet<>();
        for (Pick p : resumes) if ("tv".equals(p.kind) && p.id > 0) ids.add(p.id);
        for (Pick p : favorites()) if ("tv".equals(p.kind) && p.id > 0) ids.add(p.id);
        for (String imdb : followed) {
            if (ids.size() >= 30) break;
            JSONArray found = get("/find/" + imdb + "?external_source=imdb_id").optJSONArray("tv_results");
            if (found != null && found.length() > 0) ids.add(found.getJSONObject(0).optInt("id"));
        }
        List<Pick> fresh = new ArrayList<>();
        String week = day(-7), today = day(0);
        for (int id : ids) {
            JSONObject o = get("/tv/" + id + "?language=" + lang());
            JSONObject last = o.optJSONObject("last_episode_to_air");
            String date = last == null ? "" : last.optString("air_date", "");
            if (date.compareTo(week) < 0 || date.compareTo(today) > 0) continue;
            o.put("first_air_date", date);   // newest first, by this episode's date
            List<Pick> one = new ArrayList<>();
            add(one, o, "tv");
            if (one.isEmpty() || !seen.add(key(one.get(0)))) continue;
            Pick p = one.get(0);
            try {
                fetchDetails(p);
            } catch (Exception e) {
                continue;
            }
            if (p.pkg == null) continue;
            p.meta = Ui.t("Nuevo episodio ",
                    "New episode ",
                    "Novo episódio ") + Ui.t("T", "S", "T") + last.optInt("season_number") + " E" + last.optInt("episode_number")
                    + " · " + when(date) + (p.meta.isEmpty() ? "" : " · " + p.meta);
            fresh.add(p);
        }
        java.util.Collections.sort(fresh, (a, b) -> b.released.compareTo(a.released));
        Row r = row(out, Ui.t("Nuevos episodios de tus series",
                "New episodes of your shows", "Novos episódios das suas séries"), fresh);
        if (r != null) r.top = true;
        AppLog.w("new episodes: " + fresh.size() + " of " + ids.size() + " shows");
    }

    /**
     * Shows that premiered in the past 2 weeks and movies of the past month on any streaming service, by your taste
     * (your genres; your languages first: K-dramas if you watch them), "Estreno · hace 2 días · Netflix".
     */
    private void premieres(List<Row> out, java.util.Set<String> seen) throws Exception {
        String on = "&with_watch_monetization_types=flatrate";
        String[] more = {discoverPath("tv", on + "&first_air_date.gte=" + day(-14) + "&first_air_date.lte=" + day(0) + "&without_genres=10762,10763,10764,10767"),
                discoverPath("movie", on + "&primary_release_date.gte=" + day(-30) + "&primary_release_date.lte=" + day(0))};
        List<Pick> mixed = new ArrayList<>();
        alternate(mixed, new java.util.HashSet<>(seen), pages(more[0], 1, 2), pages(more[1], 1, 2), 80);
        List<Pick> liked = new ArrayList<>();
        for (Pick p : mixed) if (forYou(p.genres)) liked.add(p);
        if (liked.size() < 10) liked = mixed;   // too few by your taste: all of them
        final Map<String, Integer> spoken = langs;
        java.util.Collections.sort(liked, (a, b) -> Integer.compare(spoken.getOrDefault(b.lang, 0), spoken.getOrDefault(a.lang, 0)));
        List<Pick> picks = detailed(liked, 30);
        for (Pick p : picks) {
            seen.add(key(p));
            p.meta = Ui.t("Estreno",
                    "New", "Estreia") + " · " + when(p.released) + (p.meta.isEmpty() ? "" : " · " + p.meta);
        }
        Row r = row(out, Ui.t("Novedades", "What's new", "Novidades"), picks);
        if (r != null) {
            r.top = true;
            r.more = more;
            r.page = 2;
        }
    }

    /** Sagas: TMDB collections (and the Marvel films by their keyword), each a row in release order. */
    private static final String[][] SAGAS = {
        {"Universo Cinematográfico de Marvel", "Marvel Cinematic Universe", "keyword", "180547", "Universo Cinematográfico Marvel"},
        {"Star Wars", "Star Wars", "collection", "10", "Star Wars"},
        {"Harry Potter", "Harry Potter", "collection", "1241", "Harry Potter"},
        {"El Señor de los Anillos y El Hobbit", "The Lord of the Rings and The Hobbit", "collection", "119,121938", "O Senhor dos Anéis e O Hobbit"},
        {"Rápido y Furioso", "Fast & Furious", "collection", "9485", "Velozes e Furiosos"},
        {"John Wick", "John Wick", "collection", "404609", "John Wick"},
        {"Jurassic Park", "Jurassic Park", "collection", "328", "Jurassic Park"},
    };

    private void sagas(List<Row> out) {
        for (String[] s : SAGAS) {
            try {
                List<Pick> films = new ArrayList<>();
                if (s[2].equals("keyword")) {
                    films = discover("movie", "&with_keywords=" + s[3] + "&vote_count.gte=1000&sort_by=primary_release_date.asc");
                } else {
                    List<JSONObject> parts = new ArrayList<>();
                    for (String id : s[3].split(",")) {
                        JSONArray a = get("/collection/" + id + "?language=" + lang()).optJSONArray("parts");
                        for (int i = 0; a != null && i < a.length(); i++) {
                            JSONObject o = a.getJSONObject(i);
                            String date = o.optString("release_date", "");
                            if (!date.isEmpty() && date.compareTo(day(0)) <= 0) parts.add(o);
                        }
                    }
                    java.util.Collections.sort(parts, (a, b) -> a.optString("release_date").compareTo(b.optString("release_date")));
                    for (JSONObject o : parts) add(films, o, "movie");
                }
                List<Pick> found = detailed(films);
                Row r = row(out, Ui.t(s[0], s[1], s[4]), found);
                if (r != null) r.saga = true;
                AppLog.w("saga " + s[1] + ": " + films.size() + " films, " + found.size() + " you can open");
            } catch (Exception e) {
                AppLog.w("saga " + s[1] + ": " + e.getMessage());
            }
        }
    }

    /** A row of shows from TMDB's discover (popular first), each opening in the app that has it. */
    private void category(List<Row> out, java.util.Set<String> seen, String title, String filters) throws Exception {
        listRow(out, seen, title, ROW_MAX, discoverPath("tv", filters));
    }

    /** "New on …": they open that service (no details asked: discover already filtered by it). */
    private static List<Pick> onService(List<Pick> list, String app, String pkg) {
        for (Pick p : list) {
            p.app = app;
            p.pkg = pkg;
            p.services = app;
            p.detailed = true;
        }
        return list;
    }

    /**
     * A row from one or two TMDB lists (movies and shows alternating), the first two pages of each, up to `max` that
     * the TV can open; it can grow later (more), a page at a time.
     */
    private Row listRow(List<Row> out, java.util.Set<String> seen, String title, int max, String... sources) throws Exception {
        List<Pick> a = pages(sources[0], 1, 2), b = sources.length > 1 ? pages(sources[1], 1, 2) : new ArrayList<>();
        List<Pick> mixed = new ArrayList<>();
        alternate(mixed, seen, a, b, 80);
        Row r = row(out, title, detailed(mixed, max));
        if (r != null) {
            r.more = sources;
            r.page = 2;
        }
        return r;
    }

    // MARK: - More: rows that grow sideways, and more rows as you go down

    /** Extra rows past the fixed ones, until the list is fetched again: then you reach the apps (a soft end). */
    private static final int EXTRA_MAX = 60, EXTRA_BATCH = 6;
    private volatile int extra, recipeAt;
    private volatile List<String[]> recipes;   // {title, source, source?}
    private volatile boolean loadingRows;
    /** "Because you watched …" for the titles past the first three (their own rows come later, as you go down). */
    private volatile List<String[]> becauseMore = new ArrayList<>();

    /** The next page of a row (the home screen asks when you're near its end). */
    void more(final Row r) {
        if (r.more == null || r.loading || r.done || !hasKey(context)) return;
        r.loading = true;
        io.execute(() -> {
            try {
                int page = r.page + 1;
                List<Pick> a = page(r.more[0], page), b = r.more.length > 1 ? page(r.more[1], page) : new ArrayList<>();
                if (a.isEmpty() && b.isEmpty()) {
                    r.done = true;
                    return;
                }
                List<Pick> mixed = new ArrayList<>();
                alternate(mixed, keysInRows(), a, b, 80);
                List<Pick> fresh = r.app != null ? onService(mixed, r.app, r.pkg) : detailed(mixed, ROW_MAX);
                links(fresh);
                List<Pick> all = new ArrayList<>(r.picks);
                all.addAll(fresh);
                r.picks = all;
                r.page = page;
                notifyListeners();
            } catch (Exception e) {
                AppLog.w("more of " + r.title + ": " + e.getMessage());
            } finally {
                r.loading = false;
            }
        });
    }

    /** A few more rows at the bottom (the home screen asks when you're near the last one), each shown as it's ready. */
    void moreRows() {
        if (!hasKey(context) || loadingRows || extra >= EXTRA_MAX) return;
        loadingRows = true;
        io.execute(() -> {
            try {
                if (recipes == null) {
                    recipes = recipes("", null);
                    recipeAt = 0;
                }
                java.util.Set<String> seen = keysInRows();
                int added = 0;
                while (added < EXTRA_BATCH && extra < EXTRA_MAX && recipeAt < recipes.size()) {
                    String[] rc = recipes.get(recipeAt++);
                    List<Row> out = new ArrayList<>();
                    Row r;
                    try {
                        r = listRow(out, seen, rc[0], 15, Arrays.copyOfRange(rc, 1, rc.length));
                    } catch (Exception e) {
                        AppLog.w("row " + rc[0] + ": " + e.getMessage());
                        continue;
                    }
                    if (r == null || r.picks.size() < 5) continue;   // too few to be a row
                    links(r.picks);
                    List<Row> next = new ArrayList<>(rows);
                    next.add(r);
                    rows = next;
                    extra++;
                    added++;
                    notifyListeners();
                }
                prune(new File(context.getCacheDir(), "thumbs"), 800);
            } finally {
                loadingRows = false;
            }
        });
    }

    // MARK: - A platform's own rows (Netflix, Disney+…): asked to TMDB for that service, not filtered from the rest

    private final Map<String, List<Row>> byPlatform = new HashMap<>();   // guarded by itself; each list replaced, never changed
    private final Map<String, List<String[]>> platformRecipes = new HashMap<>();
    private final Map<String, Integer> platformAt = new HashMap<>();     // recipes used, per platform
    private final java.util.Set<String> platformLoading = new java.util.HashSet<>();
    private static final int PLATFORM_MAX = 45, PLATFORM_FIRST = 4;

    /** A service's TMDB ids and app ({"ids":"8|1796","pkg":…}), or null if TMDB doesn't list it in your country. */
    private JSONObject serviceIds(String platform) {
        try {
            return new JSONObject(cache.getString("providersByApp", "{}")).optJSONObject(platform);
        } catch (Exception e) {
            return null;
        }
    }

    /** The rows for a platform ("netflix"); null or Stremio (which opens anything) = everyone's. Loads them the first time. */
    List<Row> rows(String platform) {
        if (platform == null || platform.equals("stremio") || !hasKey(context)
                || (!platform.equals("anime") && serviceIds(platform) == null)) return rows();
        synchronized (byPlatform) {
            List<Row> l = byPlatform.get(platform);
            if (l != null) return l;
        }
        moreRows(platform);
        return new ArrayList<>();
    }

    /** More rows for a platform: its own news, popular and acclaimed first, then K-dramas, anime and the genres. */
    void moreRows(final String platform) {
        if (platform == null || platform.equals("stremio")) {
            moreRows();
            return;
        }
        // Anime isn't a service: every anime, each opening in the app that has it (details asked, like everyone's rows).
        final boolean anime = platform.equals("anime");
        JSONObject service = anime ? null : serviceIds(platform);
        if ((!anime && service == null) || !hasKey(context)) return;
        final String ids = anime ? "" : service.optString("ids"), pkg = anime ? null : service.optString("pkg");
        synchronized (byPlatform) {
            List<Row> have = byPlatform.get(platform);
            if (!platformLoading.add(platform) || (have != null && have.size() >= PLATFORM_MAX)) return;
            if (have == null) byPlatform.put(platform, new ArrayList<>());
        }
        io.execute(() -> {
            try {
                String on = "&with_watch_monetization_types=flatrate&with_watch_providers=" + ids.replace("|", "%7C");
                List<String[]> recipes;
                int at;
                synchronized (byPlatform) {
                    recipes = platformRecipes.get(platform);
                    if (recipes == null) platformRecipes.put(platform, recipes = anime ? animeRecipes() : recipes(on, SERVICE_NAME.get(platform)));
                    at = platformAt.containsKey(platform) ? platformAt.get(platform) : 0;
                }
                java.util.Set<String> seen = new java.util.HashSet<>();
                synchronized (byPlatform) {
                    for (Row r : byPlatform.get(platform)) for (Pick p : r.picks) seen.add(key(p));
                }
                int want = byPlatform.get(platform).isEmpty() ? PLATFORM_FIRST : EXTRA_BATCH, added = 0;
                while (added < want && at < recipes.size()) {
                    String[] rc = recipes.get(at++);
                    try {
                        String[] sources = Arrays.copyOfRange(rc, 1, rc.length);
                        List<Pick> a = pages(sources[0], 1, 1), b = sources.length > 1 ? pages(sources[1], 1, 1) : new ArrayList<>();
                        List<Pick> mixed = new ArrayList<>();
                        alternate(mixed, seen, a, b, 40);
                        if (mixed.size() < 5) continue;   // too few to be a row
                        Row r = new Row();
                        r.title = rc[0];
                        r.picks = anime ? detailed(mixed, 15) : onService(mixed, platform, pkg);
                        if (r.picks.size() < 5) continue;
                        r.more = sources;
                        r.page = 1;
                        r.app = anime ? null : platform;
                        r.pkg = pkg;
                        try {
                            addLinks(r.picks);
                        } catch (Exception e) {
                            AppLog.w("title links: " + e.getMessage());
                        }
                        synchronized (byPlatform) {
                            List<Row> next = new ArrayList<>(byPlatform.get(platform));
                            next.add(r);
                            byPlatform.put(platform, next);
                            platformAt.put(platform, at);
                        }
                        added++;
                        notifyListeners();
                    } catch (Exception e) {
                        AppLog.w("row " + rc[0] + " on " + platform + ": " + e.getMessage());
                    }
                }
                synchronized (byPlatform) { platformAt.put(platform, at); }
                AppLog.w("rows on " + platform + ": " + byPlatform.get(platform).size());
            } finally {
                synchronized (byPlatform) { platformLoading.remove(platform); }
            }
        });
    }

    /** Netflix/Disney/Prime links and logos for titles that just came (errors leave them opening the app's home). */
    private void links(List<Pick> list) {
        try {
            addLinks(list);
        } catch (Exception e) {
            AppLog.w("title links: " + e.getMessage());
        }
        addImages(list);
    }

    private java.util.Set<String> keysInRows() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Row r : rows) for (Pick p : r.picks) seen.add(key(p));
        for (Pick p : picks) seen.add(key(p));
        return seen;
    }

    /** Genres: {Spanish, English (lowercase), movie ids, show ids ("" = none for shows)}. */
    private static final String[][] GENRES = {
        {"Comedia", "comedy", "35", "35", "Comédia"}, {"Drama", "drama", "18", "18", "Drama"}, {"Acción", "action", "28", "10759", "Ação"},
        {"Suspenso", "thrillers", "53", "9648", "Suspense"}, {"Crimen", "crime", "80", "80", "Crime"}, {"Ciencia ficción", "sci-fi", "878", "10765", "Ficção científica"},
        {"Terror", "horror", "27", "", "Terror"}, {"Romance", "romance", "10749", "", "Romance"}, {"Misterio", "mystery", "9648", "9648", "Mistério"},
        {"Animación", "animation", "16", "16", "Animação"}, {"Aventura", "adventure", "12", "10759", "Aventura"}, {"Fantasía", "fantasy", "14", "10765", "Fantasia"},
        {"Documentales", "documentaries", "99", "99", "Documentários"}, {"Historia", "history", "36", "", "História"}, {"Bélicas", "war", "10752", "10768", "Guerra"},
        {"Familia", "family", "10751", "10751", "Família"},
    };
    /** What a genre is combined with: {Spanish "%s …", English "… %s", movie filters, show filters}. */
    private static final String[][] TWISTS = {
        {"%s de los 80", "80s %s", "&primary_release_date.gte=1980-01-01&primary_release_date.lte=1989-12-31", "&first_air_date.gte=1980-01-01&first_air_date.lte=1989-12-31", "%s dos anos 80"},
        {"%s de los 90", "90s %s", "&primary_release_date.gte=1990-01-01&primary_release_date.lte=1999-12-31", "&first_air_date.gte=1990-01-01&first_air_date.lte=1999-12-31", "%s dos anos 90"},
        {"%s de los 2000", "2000s %s", "&primary_release_date.gte=2000-01-01&primary_release_date.lte=2009-12-31", "&first_air_date.gte=2000-01-01&first_air_date.lte=2009-12-31", "%s dos anos 2000"},
        {"%s de los 2010", "2010s %s", "&primary_release_date.gte=2010-01-01&primary_release_date.lte=2019-12-31", "&first_air_date.gte=2010-01-01&first_air_date.lte=2019-12-31", "%s dos anos 2010"},
        {"%s: lo más aclamado", "acclaimed %s", "&vote_average.gte=7.8&vote_count.gte=500", "&vote_average.gte=7.8&vote_count.gte=200", "%s: os mais aclamados"},
        {"%s de Corea", "Korean %s", "&with_origin_country=KR", "&with_origin_country=KR", "%s da Coreia"},
        {"%s de Japón", "Japanese %s", "&with_origin_country=JP", "&with_origin_country=JP", "%s do Japão"},
        {"%s de España", "Spanish %s", "&with_origin_country=ES", "&with_origin_country=ES", "%s da Espanha"},
        {"%s de México", "Mexican %s", "&with_origin_country=MX", "&with_origin_country=MX", "%s do México"},
        {"%s de Argentina", "Argentine %s", "&with_origin_country=AR", "&with_origin_country=AR", "%s da Argentina"},
        {"%s de Colombia", "Colombian %s", "&with_origin_country=CO", "&with_origin_country=CO", "%s da Colômbia"},
        {"%s del Reino Unido", "British %s", "&with_origin_country=GB", "&with_origin_country=GB", "%s do Reino Unido"},
        {"%s de Francia", "French %s", "&with_origin_country=FR", "&with_origin_country=FR", "%s da França"},
        {"%s de la India", "Indian %s", "&with_origin_country=IN", "&with_origin_country=IN", "%s da Índia"},
        {"%s de Turquía", "Turkish %s", "&with_origin_country=TR", "&with_origin_country=TR", "%s da Turquia"},
    };

    /**
     * The rows to come, in order: "because you watched" first, then each genre on its own, then genres with a decade,
     * a country or "acclaimed", shuffled the same way all day.
     * ponytail: not weighted by what you watch yet; count genres in your Stremio library if the order feels random.
     */
    private List<String[]> recipes(String on, String service) {
        List<String[]> out = new ArrayList<>();
        if (service == null) {
            out.addAll(becauseMore);
            // Without Stremio (which opens anything), only what's on the TV's services.
            String providers = cache.getString("providers", "");
            on = installed(STREMIO) || providers.isEmpty() ? "" : "&with_watch_monetization_types=flatrate&with_watch_providers=" + providers.replace("|", "%7C");
        } else {
            // A platform: what it has that's new, popular and acclaimed, then K-dramas and anime on it.
            String year = day(-365);
            out.add(new String[] {Ui.t("Lo nuevo en ",
                    "New on ",
                    "Novo em ") + service, discoverPath("tv", on + "&vote_count.gte=10&first_air_date.gte=" + year),
                    discoverPath("movie", on + "&vote_count.gte=10&primary_release_date.gte=" + year)});
            out.add(new String[] {Ui.t("Popular en ",
                    "Popular on ", "Popular em ") + service, discoverPath("tv", on + "&vote_count.gte=50"),
                    discoverPath("movie", on + "&vote_count.gte=100")});
            out.add(new String[] {Ui.t("Aclamadas en ",
                    "Acclaimed on ",
                    "Aclamados em ") + service, discoverPath("tv", on + "&vote_average.gte=7.8&vote_count.gte=150"),
                    discoverPath("movie", on + "&vote_average.gte=7.5&vote_count.gte=500")});
            out.add(new String[] {Ui.t("K-dramas de romance",
                    "Romance K-dramas",
                    "K-dramas de romance"), discoverPath("tv", on + KO.replace("vote_count.gte=30", "vote_count.gte=5") + "&with_keywords=9840")});
            out.add(new String[] {Ui.t("K-dramas",
                    "K-dramas",
                    "K-dramas"), discoverPath("tv", on + KO.replace("vote_count.gte=30", "vote_count.gte=5"))});
            out.add(new String[] {Ui.t("Anime",
                    "Anime", "Anime"), discoverPath("tv", on + JA.replace("vote_count.gte=30", "vote_count.gte=5"))});
            out.add(new String[] {Ui.t("Películas de anime",
                    "Anime movies",
                    "Filmes de anime"), discoverPath("movie", on + "&with_original_language=ja&with_genres=16&vote_count.gte=20")});
            out.add(new String[] {Ui.t("Joyas escondidas",
                    "Hidden gems",
                    "Joias escondidas"), discoverPath("tv", on + "&vote_average.gte=7.4&vote_count.gte=60&vote_count.lte=1500&sort_by=vote_average.desc"),
                    discoverPath("movie", on + "&vote_average.gte=7.2&vote_count.gte=150&vote_count.lte=3000&sort_by=vote_average.desc")});
        }
        String movie = "&vote_count.gte=80" + on, tv = "&vote_count.gte=40&without_genres=10762,10763,10764,10767" + on;
        List<String[]> plain = new ArrayList<>(), twisted = new ArrayList<>();
        for (String[] g : GENRES) {
            plain.add(recipe(cap(Ui.t(g[0], g[1], g[4])), g, movie, tv));
            for (String[] tw : TWISTS) {
                twisted.add(recipe(cap(Ui.t(String.format(tw[0], g[0]),
                        String.format(tw[1], g[1]), String.format(tw[4], g[4]))), g, movie + tw[2], tv + tw[3]));
            }
        }
        java.util.Random random = new java.util.Random(System.currentTimeMillis() / 86_400_000L);
        java.util.Collections.shuffle(plain, random);
        java.util.Collections.shuffle(twisted, random);
        // Your genres first (shuffled among the same), the ones you never watch at the very end.
        java.util.Comparator<String[]> liked = (a, b) -> Integer.compare(recipeScore(b), recipeScore(a));
        java.util.Collections.sort(plain, liked);
        java.util.Collections.sort(twisted, liked);
        // A plain genre, then two combined, so it doesn't feel like a list of genres.
        for (int i = 0, j = 0; i < plain.size() || j < twisted.size(); ) {
            if (i < plain.size()) out.add(plain.get(i++));
            for (int n = 0; n < 2 && j < twisted.size(); n++) out.add(twisted.get(j++));
        }
        return out;
    }

    /** The Anime platform's rows: popular and new, the kinds, the decades, the films; then kinds by decade. */
    private static List<String[]> animeRecipes() {
        List<String[]> out = new ArrayList<>();
        String year = day(-365);
        out.add(new String[] {Ui.t("Anime popular",
                "Popular anime", "Anime popular"), anime("", "&vote_count.gte=100")});
        out.add(new String[] {Ui.t("Anime nuevo",
                "New anime", "Anime novo"), anime("", "&vote_count.gte=5&first_air_date.gte=" + year)});
        out.add(new String[] {Ui.t("Películas de anime",
                "Anime movies",
                "Filmes de anime"), discoverPath("movie", "&with_original_language=ja&with_genres=16&vote_count.gte=50")});
        out.add(new String[] {Ui.t("Anime de acción", "Action anime", "Anime de ação"), anime(",10759", "")});
        out.add(new String[] {Ui.t("Anime isekai",
                "Isekai anime", "Anime isekai"), anime("", "&with_keywords=237451")});
        out.add(new String[] {Ui.t("Anime de romance",
                "Romance anime", "Anime de romance"), anime("", "&with_keywords=9840")});
        out.add(new String[] {Ui.t("Anime aclamado",
                "Acclaimed anime", "Anime aclamado"), anime("", "&vote_average.gte=8&vote_count.gte=150"),
                discoverPath("movie", "&with_original_language=ja&with_genres=16&vote_average.gte=7.8&vote_count.gte=300")});
        out.add(new String[] {Ui.t("Comedia anime", "Comedy anime", "Comédia anime"), anime(",35", "")});
        out.add(new String[] {Ui.t("Anime de la vida cotidiana",
                "Slice of life anime", "Anime slice of life"), anime("", "&with_keywords=9914")});
        out.add(new String[] {Ui.t("Anime de fantasía y ciencia ficción",
                "Fantasy and sci-fi anime", "Anime de fantasia e ficção científica"), anime(",10765", "")});
        out.add(new String[] {Ui.t("Anime de misterio", "Mystery anime", "Anime de mistério"), anime(",9648", "")});
        out.add(new String[] {Ui.t("Drama anime", "Drama anime", "Drama anime"), anime(",18", "")});
        out.add(new String[] {Ui.t("Studio Ghibli",
                "Studio Ghibli",
                "Studio Ghibli"), discoverPath("movie", "&with_companies=10342&sort_by=primary_release_date.desc")});
        String[][] decades = {{"90", "1990"}, {"2000", "2000"}, {"2010", "2010"}, {"2020", "2020"}};
        for (String[] d : decades) {
            int from = Integer.parseInt(d[1]);
            out.add(new String[] {Ui.t("Anime de los " + d[0], d[0] + "s anime", "Anime dos anos " + d[0]),
                    anime("", "&first_air_date.gte=" + from + "-01-01&first_air_date.lte=" + (from + 9) + "-12-31&vote_count.gte=40")});
        }
        String[][] kinds = {{",10759", "Acción", "action", "de ação"}, {",35", "Comedia", "comedy", "de comédia"}, {",18", "Drama", "drama", "de drama"}, {",10765", "Fantasía", "fantasy", "de fantasia"}};
        for (String[] k : kinds) {
            for (String[] d : decades) {
                int from = Integer.parseInt(d[1]);
                out.add(new String[] {Ui.t(k[1] + " anime de los " + d[0],
                        d[0] + "s " + k[2] + " anime", "Anime " + k[3] + " dos anos " + d[0]),
                        anime(k[0], "&first_air_date.gte=" + from + "-01-01&first_air_date.lte=" + (from + 9) + "-12-31&vote_count.gte=20")});
            }
        }
        return out;
    }

    /** Japanese animated shows (no kids' or family ones), with more genres (",10759") and filters. */
    private static String anime(String genres, String filters) {
        return discoverPath("tv", "&with_original_language=ja&with_genres=16" + genres + "&without_genres=10762,10751"
                + (filters.contains("vote_count") ? "" : "&vote_count.gte=20") + filters);
    }

    /** A genre row's taste score, from the genre ids in its sources ("with_genres=35"). */
    private int recipeScore(String[] rc) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("with_genres=(\\d+)").matcher(rc[1]);
        return m.find() ? score(Integer.parseInt(m.group(1))) : 0;
    }

    private static String[] recipe(String title, String[] genre, String movie, String tv) {
        String m = discoverPath("movie", "&with_genres=" + genre[2] + movie);
        return genre[3].isEmpty() ? new String[] {title, m} : new String[] {title, m, discoverPath("tv", "&with_genres=" + genre[3] + tv)};
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Netflix, Disney+ and Prime open on the title itself with its id there, which Wikidata has (by TMDB id): one query for
     * movies, one for shows. Without an id, the app opens on its home.
     */
    private int addLinks(List<Pick> all) throws Exception {
        int linked = 0;
        boolean stremio = installed(STREMIO);
        for (String kind : new String[] {"movie", "tv"}) {
            Map<String, List<Pick>> byId = new HashMap<>();
            for (Pick p : all) {
                if (!kind.equals(p.kind) || p.link != null || (p.pkg == null && !stremio)) continue;
                List<Pick> l = byId.get(String.valueOf(p.id));
                if (l == null) byId.put(String.valueOf(p.id), l = new ArrayList<>());
                l.add(p);
            }
            if (byId.isEmpty()) continue;
            StringBuilder values = new StringBuilder();
            for (String id : byId.keySet()) values.append(" \"").append(id).append('"');
            String query = "SELECT ?tm ?nf ?dm ?ds ?pv ?imdb WHERE { VALUES ?tm {" + values + " } ?i wdt:"
                    + (kind.equals("movie") ? "P4947" : "P4983") + " ?tm . OPTIONAL { ?i wdt:P1874 ?nf } "
                    + "OPTIONAL { ?i wdt:P7595 ?dm } OPTIONAL { ?i wdt:P7596 ?ds } OPTIONAL { ?i wdt:P8055 ?pv } OPTIONAL { ?i wdt:P345 ?imdb } }";
            HttpURLConnection c = (HttpURLConnection) new URL("https://query.wikidata.org/sparql?format=json&query="
                    + URLEncoder.encode(query, "UTF-8")).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "FreeMyTV/1.0 (home screen; github.com/simbasjungles/free-my-tv)");
            JSONArray results;
            try {
                results = new JSONObject(read(c.getInputStream())).getJSONObject("results").getJSONArray("bindings");
            } finally {
                c.disconnect();
            }
            for (int i = 0; i < results.length(); i++) {
                JSONObject b = results.getJSONObject(i);
                List<Pick> l = byId.get(b.getJSONObject("tm").getString("value"));
                if (l == null) continue;
                String nf = value(b, "nf"), disney = value(b, kind.equals("movie") ? "dm" : "ds"), imdb = value(b, "imdb"), prime = value(b, "pv");
                for (Pick p : l) {
                    if ("prime".equals(p.app) && prime != null) p.link = "https://app.primevideo.com/detail?asin=" + prime;
                    if ("netflix".equals(p.app) && nf != null) p.link = "https://www.netflix.com/title/" + nf;
                    if ("disney".equals(p.app) && disney != null)
                        p.link = "https://www.disneyplus.com/" + (kind.equals("movie") ? "movies" : "series") + "/x/" + disney;
                    if (p.imdb == null && imdb != null && imdb.startsWith("tt")) p.imdb = imdb;
                }
            }
            // Stremio first (it opens on the episode); the service's own app when Stremio can't find it.
            for (List<Pick> l : byId.values()) {
                for (Pick p : l) {
                    if (stremio && p.imdb != null) toStremio(p);
                    if (p.link != null) linked++;
                }
            }
        }
        return linked;
    }

    /**
     * A show's seasons (season -1; TMDB's season objects, without specials) or one season's episodes (name,
     * episode_number, still_path, overview, runtime, air_date); `done` gets null if it couldn't (on a background thread).
     */
    void episodes(final Pick p, final int season, final java.util.function.Consumer<List<JSONObject>> done) {
        trailerIo.execute(() -> {
            try {
                List<JSONObject> out = new ArrayList<>();
                JSONArray a = season < 0 ? get("/tv/" + p.id + "?language=" + lang()).getJSONArray("seasons")
                        : get("/tv/" + p.id + "/season/" + season + "?language=" + lang()).getJSONArray("episodes");
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    if (season >= 0 || (o.optInt("season_number") > 0 && o.optInt("episode_count") > 0)) out.add(o);
                }
                done.accept(out);
            } catch (Exception e) {
                AppLog.w("episodes: " + e.getMessage());
                done.accept(null);
            }
        });
    }

    private static void toStremio(Pick p) {
        String old = SERVICE_NAME.get(p.app);
        p.app = "stremio";
        p.pkg = STREMIO;
        p.link = "stremio:///detail/" + (p.kind.equals("tv") ? "series" : "movie") + "/" + p.imdb;
        if (p.meta == null) p.meta = "";
        if (old != null && p.meta.endsWith(old)) p.meta = p.meta.substring(0, p.meta.length() - old.length()) + "Stremio";
        else if (!p.meta.endsWith("Stremio")) p.meta += (p.meta.isEmpty() ? "" : " · ") + "Stremio";
    }

    private static String value(JSONObject binding, String name) {
        JSONObject v = binding.optJSONObject(name);
        return v == null ? null : v.optString("value", null);
    }

    /** TMDB's discover, two pages (40 titles), most popular first unless `filters` sorts otherwise. */
    private List<Pick> discover(String kind, String filters) throws Exception {
        return pages(discoverPath(kind, filters), 1, 2);
    }

    /** A discover list as a source ("tv:/discover/tv?…") that can be read a page at a time. */
    private static String discoverPath(String kind, String filters) {
        return kind + ":/discover/" + kind + "?language=" + lang() + "&watch_region=" + REGION + "&include_adult=false"
                + (filters.contains("sort_by=") ? "" : "&sort_by=popularity.desc") + filters;
    }

    /** Pages `from`..`to` of a source; stops early at its last page. */
    private List<Pick> pages(String source, int from, int to) throws Exception {
        List<Pick> out = new ArrayList<>();
        for (int page = from; page <= to; page++) {
            List<Pick> got = page(source, page);
            if (got.isEmpty()) break;
            out.addAll(got);
        }
        return out;
    }

    /** One page of a source: "movie:" or "tv:" then a TMDB path; "all:" takes each result's media_type. */
    private List<Pick> page(String source, int page) throws Exception {
        int colon = source.indexOf(':');
        String kind = source.substring(0, colon), path = source.substring(colon + 1);
        JSONArray results = get(path + (path.contains("?") ? "&" : "?") + "page=" + page).optJSONArray("results");
        List<Pick> out = new ArrayList<>();
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject o = results.getJSONObject(i);
            String k = kind.equals("all") ? o.optString("media_type") : kind;
            if (k.equals("movie") || k.equals("tv")) add(out, o, k);
        }
        return out;
    }

    private static final int ROW_MAX = 30;

    /** Details for each (which app has it), keeping the ones the TV can open. */
    private List<Pick> detailed(List<Pick> list) {
        return detailed(list, ROW_MAX);
    }

    private List<Pick> detailed(List<Pick> list, int max) {
        List<Pick> out = new ArrayList<>();
        for (Pick p : list) {
            if (out.size() >= max) break;
            try {
                fetchDetails(p);
            } catch (Exception e) {
                continue;
            }
            if (p.pkg != null) out.add(p);
        }
        return out;
    }

    private static Row row(List<Row> out, String title, List<Pick> picks) {
        if (picks.isEmpty()) return null;
        Row r = new Row();
        r.title = title;
        r.picks = picks;
        out.add(r);
        return r;
    }

    private static JSONArray rowsJson(List<Row> rows) throws org.json.JSONException {
        JSONArray a = new JSONArray();
        for (Row r : rows) {
            JSONArray picks = new JSONArray();
            for (Pick p : r.picks) picks.put(json(p));
            JSONObject o = new JSONObject().put("title", r.title).put("saga", r.saga).put("top", r.top).put("picks", picks).put("page", r.page);
            if (r.more != null) o.put("more", new JSONArray(Arrays.asList(r.more)));
            if (r.app != null) o.put("app", r.app).put("pkg", r.pkg);
            a.put(o);
        }
        return a;
    }

    private static List<Row> parseRows(JSONArray a) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optJSONArray("picks") == null) continue;
            Row r = row(out, o.optString("title"), parse(o.optJSONArray("picks")));
            if (r == null) continue;
            r.saga = o.optBoolean("saga");
            r.top = o.optBoolean("top");
            r.page = o.optInt("page");
            JSONArray more = o.optJSONArray("more");
            if (more != null) {
                r.more = new String[more.length()];
                for (int j = 0; j < more.length(); j++) r.more[j] = more.optString(j);
            }
            if (o.has("app")) {
                r.app = o.optString("app");
                r.pkg = o.optString("pkg");
            }
        }
        return out;
    }

    /** A row card's image (TMDB's 500 px backdrop, kept on disk), or null while it loads. */
    Bitmap thumb(Pick p) {
        return p == null ? null : image(p.backdrop, "w500");
    }

    private final Map<Bitmap, Float> focus = new java.util.WeakHashMap<>();

    /** Where a picture's subject is, across (0 left … 1 right): a vertical card is cut around it. 0.5 if unknown. */
    float focus(Bitmap b) {
        synchronized (focus) {
            Float x = b == null ? null : focus.get(b);
            return x == null ? 0.5f : x;
        }
    }

    /**
     * The middle of the 2:3 slice of a wide picture to keep, and how well it holds as a vertical card. Where its
     * detail is (the subject is sharp, the background soft), seen small (96 px wide) so people and objects count
     * more than fine texture like a building's windows; each spot weighs the detail around it (a bell as wide as the
     * slice), so the subject lands in the middle; a little toward the picture's middle. Fit: how much more detail
     * the slice has than its share (1 = spread evenly, nothing to center on).
     */
    private static float[] framing(Bitmap b) {
        int w = 96, h = Math.max(2, Math.round(96f * b.getHeight() / b.getWidth()));
        Bitmap half = Bitmap.createScaledBitmap(b, w * 2, h * 2, true);   // two steps: smoother than one
        Bitmap small = Bitmap.createScaledBitmap(half, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (half != b) half.recycle();
        if (small != b) small.recycle();
        float[] col = new float[w];
        float total = 0;
        for (int y = 1; y < h; y++) {
            for (int x = 1; x < w; x++) {
                int a = px[y * w + x];
                float d = Math.abs(luma(a) - luma(px[y * w + x - 1])) + Math.abs(luma(a) - luma(px[(y - 1) * w + x]));
                col[x] += d;
                total += d;
            }
        }
        float slice = h * 2f / 3 / w, win = slice * w, sigma = win / 2.5f;
        float[] bell = new float[w];
        for (int d = 0; d < w; d++) bell[d] = (float) Math.exp(-(d / sigma) * (d / sigma) / 2);
        int center = w / 2;
        float best = -1;
        for (int c = 0; c < w; c++) {
            float sum = 0;
            for (int x = 0; x < w; x++) sum += col[x] * bell[Math.abs(x - c)];
            sum *= 1 - 0.3f * Math.abs((c + 0.5f) / w - 0.5f) * 2;   // up to 30% less at the edges
            if (sum > best) {
                best = sum;
                center = c;
            }
        }
        float x = (center + 0.5f) / w;
        int lo = Math.round(Math.max(0, Math.min(w - win, x * w - win / 2)));
        float in = 0;
        for (int i = lo; i < Math.min(w, lo + Math.round(win)); i++) in += col[i];
        return new float[] {x, total > 0 ? in / total / slice : 1};
    }

    private static int luma(int c) {
        return (((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF)) / 10;
    }

    /** A TMDB image at a size ("w342" poster, "w780" or "w1280" backdrop), kept on disk; null while it loads. */
    Bitmap image(final String imagePath, final String size) {
        if (imagePath == null) return null;
        final String key = size + imagePath;
        synchronized (thumbs) {
            Bitmap b = thumbs.get(key);
            if (b != null) return b;
            if (!thumbPending.add(key)) return null;
        }
        thumbIo.execute(() -> {
            try {
                File f = new File(new File(context.getCacheDir(), "thumbs"), key.replace("/", "_"));
                if (!f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.getParentFile().mkdirs();
                    HttpURLConnection c = (HttpURLConnection) new URL("https://image.tmdb.org/t/p/" + size + imagePath).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(15000);
                    File part = new File(f.getPath() + ".part");
                    try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                        byte[] buffer = new byte[16384];
                        int n;
                        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                    } finally {
                        c.disconnect();
                    }
                    if (!part.renameTo(f)) throw new IOException("couldn't save the image");
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = imagePath.endsWith(".png") ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;   // logos: transparent
                Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
                if (b == null) throw new IOException("unreadable image");
                if (!imagePath.endsWith(".png")) {
                    float x = framing(b)[0];
                    synchronized (focus) { focus.put(b, x); }
                }
                synchronized (thumbs) {
                    thumbs.put(key, b);
                    while (thumbs.size() > 120) thumbs.remove(thumbs.keySet().iterator().next());
                }
                notifyListeners();
            } catch (Exception e) {
                AppLog.w("row image: " + e.getMessage());
            } finally {
                synchronized (thumbs) { thumbPending.remove(key); }
            }
        });
        return null;
    }

    /** Stremio's titles in progress, last watched first. */
    List<Pick> resumes() {
        return resumes;
    }

    // MARK: - My list (held OK on a title) and search

    private volatile List<Pick> favorites;

    /** Your list, last added first (kept on the TV). */
    List<Pick> favorites() {
        if (favorites == null) {
            try {
                favorites = parse(new JSONArray(cache.getString("favorites", "[]")));
            } catch (Exception e) {
                favorites = new ArrayList<>();
            }
        }
        return favorites;
    }

    boolean isFavorite(Pick p) {
        for (Pick f : favorites()) if (key(f).equals(key(p))) return true;
        return false;
    }

    /** Adds it (first) or takes it out; true = it's in your list now. A show in progress goes in as the show. */
    boolean toggleFavorite(Pick p) {
        List<Pick> next = new ArrayList<>(favorites());
        for (int i = 0; i < next.size(); i++) {
            if (key(next.get(i)).equals(key(p))) {
                next.remove(i);
                saveFavorites(next);
                return false;
            }
        }
        try {
            Pick copy = parse(new JSONArray().put(json(p))).get(0);
            String prefix = "stremio:///detail/series/";
            if (copy.link != null && copy.link.startsWith(prefix)) {
                String[] parts = copy.link.substring(prefix.length()).split("/");
                copy.link = prefix + parts[0];   // the show, not the episode you were on
            }
            next.add(0, copy);
        } catch (Exception e) {
            return false;
        }
        saveFavorites(next);
        return true;
    }

    private void saveFavorites(List<Pick> list) {
        favorites = list;
        try {
            JSONArray a = new JSONArray();
            for (Pick f : list) a.put(json(f));
            cache.edit().putString("favorites", a.toString()).apply();
        } catch (Exception ignored) {
        }
        notifyListeners();
    }

    /** Movies and shows whose name matches (TMDB, in your language), most relevant first. Off the UI thread. */
    List<Pick> search(String query) throws Exception {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context) || query.trim().isEmpty()) return out;
        JSONArray results = get("/search/multi?language=" + lang() + "&include_adult=false&query="
                + URLEncoder.encode(query.trim(), "UTF-8")).optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject o = results.getJSONObject(i);
            String kind = o.optString("media_type");
            if (!kind.equals("movie") && !kind.equals("tv") || path(o, "poster_path") == null) continue;
            if (path(o, "backdrop_path") == null) o.put("backdrop_path", o.optString("poster_path"));   // the page needs a picture
            add(out, o, kind);
        }
        return out;
    }

    /** Which app opens it (its details), then `done` on the UI thread (pkg stays null if nothing on the TV has it). */
    void prepare(final Pick p, final java.util.function.Consumer<Pick> done) {
        trailerIo.execute(() -> {
            try {
                if (!p.detailed) fetchDetails(p);
            } catch (Exception e) {
                AppLog.w("search: " + e.getMessage());
            }
            main.post(() -> done.accept(p));
        });
    }

    private final ExecutorService trailerIo = Executors.newSingleThreadExecutor();
    private final Map<String, String> trailers = new HashMap<>();   // pick key → YouTube id ("" = none)

    /**
     * The pick's trailer on YouTube as "id|language" (TMDB videos: your language first, then English); `done` gets
     * null if none.
     */
    void trailer(final Pick p, final java.util.function.Consumer<String> done) {
        if (p == null || p.id == 0 || "app".equals(p.kind) || !hasKey(context)) {
            done.accept(null);
            return;
        }
        final String k = key(p);
        synchronized (trailers) {
            String v = trailers.containsKey(k) ? trailers.get(k) : savedTrailers().getString(k, null);
            if (v != null) {
                trailers.put(k, v);
                done.accept(v.isEmpty() ? null : v);
                return;
            }
        }
        trailerIo.execute(() -> {
            String found = "", tag;
            boolean searched = false, youtubeDown = false;
            String mine = lang().substring(0, 2);
            tag = mine;
            try {
                found = tmdbVideo(p, mine, "", TRAILERS, true);
                JSONObject en = null;
                if (found.isEmpty()) en = get("/" + p.kind + "/" + p.id + "?language=en-US");
                String enTitle = en == null ? p.title : en.optString(p.kind.equals("tv") ? "name" : "title", p.title);
                String original = en == null ? "" : en.optString("original_language", "");
                if (found.isEmpty() && !Config.YOUTUBE_KEY.trim().isEmpty()) {
                    // YouTube down or out of searches for today: the fallbacks below, kept only until tomorrow's search.
                    try {
                        found = youtubeTrailer(p, mine, p.title + (es(mine) ? " tráiler oficial español latino" : pt(mine) ? " trailer oficial dublado" : " official trailer"), enTitle, original);
                        // Its English title, subtitled: Korean and Japanese shows (an English one's must be dubbed).
                        if (found.isEmpty() && (es(mine) || pt(mine)) && !original.equals("en"))
                            found = youtubeTrailer(p, mine, enTitle + (es(mine) ? " trailer sub español" : " trailer legendado"), enTitle, original);
                        searched = true;
                    } catch (IOException e) {
                        AppLog.w("trailer: " + e.getMessage());
                        youtubeDown = true;
                    }
                }
                // No trailer in your language: a clip in it; an anime's opening (a song); last, the trailer without sound.
                if (found.isEmpty()) found = tmdbVideo(p, mine, "", CLIPS, true);
                if (found.isEmpty() && "ja".equals(original) && p.kind.equals("tv")) {
                    found = tmdbVideo(p, mine, original, OPENINGS, false);
                    tag = "music";
                }
                if (found.isEmpty()) {
                    found = tmdbVideo(p, mine, original, TRAILERS, false);
                    tag = "mute";
                }
            } catch (Exception e) {
                AppLog.w("trailer: " + e.getMessage());
            }
            final String result = found.isEmpty() ? "" : found + "|" + tag;
            synchronized (trailers) { trailers.put(k, result); }
            // Kept on disk (YouTube searches are counted); "none" only once YouTube was asked too.
            if ((!result.isEmpty() && !youtubeDown) || searched) savedTrailers().edit().putString(k, result).apply();
            main.post(() -> done.accept(result.isEmpty() ? null : result));
        });
    }

    private SharedPreferences savedTrailers() {
        SharedPreferences saved = context.getSharedPreferences("trailers4-" + lang(), Context.MODE_PRIVATE);   // 4: clips and openings too
        if (!saved.getBoolean("v5", false)) {
            // Once: the muted ones were in any language (a Korean trailer for a Hollywood movie).
            SharedPreferences.Editor e = saved.edit();
            for (Map.Entry<String, ?> it : saved.getAll().entrySet())
                if (String.valueOf(it.getValue()).endsWith("|mute")) e.remove(it.getKey());
            e.putBoolean("v5", true).apply();
        }
        return saved;
    }

    /** Spain's Spanish is fine only when the country is Spain (tmdb_region=ES). */
    private static boolean spainOk() {
        return "ES".equals(REGION);
    }

    /**
     * TMDB's trailers and teasers in your language only (clips and behind-the-scenes aren't the show's trailer); a
     * show without any: its first season's. Returns the YouTube id, or "".
     */
    private static final List<String> TRAILERS = Arrays.asList("Trailer", "Teaser");
    private static final List<String> CLIPS = Arrays.asList("Clip", "Featurette", "Opening Credits");
    private static final List<String> OPENINGS = Arrays.asList("Opening Credits");

    /**
     * TMDB's videos of these types (earlier in the list = better), only in your language (`mineOnly`, never Spain's
     * dub) or else in the title's own language (English when unknown), not another country's version (a Korean
     * trailer for a Hollywood movie); a show without any: its first season's. Returns the YouTube id, or "".
     */
    private String tmdbVideo(Pick p, String mine, String original, List<String> types, boolean mineOnly) throws Exception {
        String base = "/" + p.kind + "/" + p.id;
        String langs = mineOnly ? mine : mine + "," + (original.isEmpty() ? "en" : original) + ",null";
        for (String where : p.kind.equals("tv") ? new String[] {base, base + "/season/1"} : new String[] {base}) {
            JSONArray v = get(where + "/videos?include_video_language=" + langs).getJSONArray("results");
            String found = "";
            int best = -1;
            for (int i = 0; i < v.length(); i++) {
                JSONObject o = v.getJSONObject(i);
                int rank = types.indexOf(o.optString("type"));
                if (!"YouTube".equals(o.optString("site")) || rank < 0) continue;
                boolean native_ = mine.equals(o.optString("iso_639_1"));
                if (mineOnly && !native_) continue;
                if (native_ && mine.equals("es") && !spainOk() && "ES".equals(o.optString("iso_3166_1"))) continue;   // Spain's dub
                int score = (types.size() - rank) * 4 + (native_ ? 2 : 0) + (REGION.equals(o.optString("iso_3166_1")) ? 1 : 0)
                        + (o.optBoolean("official") ? 1 : 0);
                if (score > best) {
                    best = score;
                    found = o.optString("key");
                }
            }
            if (!found.isEmpty()) return found;
        }
        return "";
    }

    /**
     * YouTube search for the title's trailer in your language (config.properties "youtube_key", 100 searches a day for
     * free; each title is searched once). Dubbed beats subtitled; reviews, reactions and fan edits are skipped.
     */
    private static boolean es(String lang) {
        return lang.equals("es");
    }

    private static boolean pt(String lang) {
        return lang.equals("pt");
    }

    private String youtubeTrailer(Pick p, String mine, String q, String enTitle, String original) throws Exception {
        boolean es = es(mine), pt = pt(mine);
        String url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=8&videoEmbeddable=true"
                + "&relevanceLanguage=" + mine + "&regionCode=" + REGION + "&q=" + URLEncoder.encode(q, "UTF-8")
                + "&key=" + URLEncoder.encode(Config.YOUTUBE_KEY.trim(), "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        JSONArray items;
        try {
            if (c.getResponseCode() != 200) throw new IOException("YouTube search returned HTTP " + c.getResponseCode());
            items = new JSONObject(read(c.getInputStream())).getJSONArray("items");
        } finally {
            c.disconnect();
        }
        String[] want = {cut(plain(p.title)), cut(plain(enTitle))};
        String best = "";
        int bestScore = -1;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            String id = it.optJSONObject("id") == null ? "" : it.getJSONObject("id").optString("videoId");
            String raw = it.optJSONObject("snippet") == null ? "" : it.getJSONObject("snippet").optString("title");
            String channel = plain(it.optJSONObject("snippet") == null ? "" : it.getJSONObject("snippet").optString("channelTitle"));
            String t = plain(raw);
            if (id.isEmpty() || !(t.contains(want[0]) || (!want[1].isEmpty() && t.contains(want[1])))) continue;
            if (!(t.contains("trailer") || t.contains("teaser") || t.contains("avance"))) continue;
            // Fan-made "season 2" trailers, fan dubs, reviews and reactions aren't the trailer.
            if (t.matches(".*\\b(reaccion|reaction|review|resena|explicad[oa]|fan|fandub|concept|parodia|edit)\\b.*")) continue;
            // Dubbed beats subtitled; subtitled is fine when the audio is Korean, Japanese... never English.
            boolean subs = pt ? t.matches(".*\\b(legendad[oa]|leg)\\b.*") : t.matches(".*\\b(subtitulad[oa]|sub esp[a-z]*|subs?|subtitulos)\\b.*");
            boolean dubbed = !subs && (pt ? t.matches(".*\\b(dublad[oa]|dub|portugues|pt br)\\b.*")
                    : t.matches(".*\\b(latino|espanol|doblad[oa]|castellano)\\b.*"));
            boolean inYours = dubbed || subs || t.matches(".*\\boficial\\b.*") || (es && raw.toLowerCase(Locale.ROOT).contains("tráiler"));
            if ((es || pt) && !inYours) continue;
            if (subs && original.equals("en")) continue;
            // Latin American Spanish, not Spain's: Spain's channels and "castellano" are out, Latin American ones first.
            boolean spain = (t + " " + channel).matches(".*\\b(castellano|espana)\\b.*");
            if (es && spain && !spainOk()) continue;
            // Brazil's Portuguese, not Portugal's, unless the country is Portugal.
            if (pt && !"PT".equals(REGION) && (t + " " + channel).matches(".*\\bportugal\\b.*")) continue;
            boolean latam = (t + " " + channel).matches(es ? ".*\\b(latino|latinoamerica|latam|mexico|hispanoamerica)\\b.*" : ".*\\b(brasil|br)\\b.*");
            int score = (dubbed ? 4 : 0) + (latam ? 3 : 0) + (t.matches(".*\\b(oficial|official)\\b.*") ? 1 : 0) + (subs ? 1 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = id;
            }
        }
        return best;
    }

    private static String cut(String s) {
        return s.length() > 24 ? s.substring(0, 24) : s;
    }

    /** Lowercase, no accents or punctuation: "¡Tráiler!" → "trailer". */
    private static String plain(String s) {
        String n = java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replace("&#39;", "'").replace("&quot;", "").replaceAll("[^a-z0-9 ]", " ").replaceAll(" +", " ").trim();
    }

    /**
     * TMDB's "if you liked this" for the 6 titles you watched last on Stremio, leaving out everything already in your
     * Stremio library. Empty without a Stremio key.
     */
    /** "Because you watched …": a row for each of the last 3 titles you watched on Stremio (from likeWhatYouWatch). */
    private List<Row> because = new ArrayList<>();

    private List<Pick> likeWhatYouWatch() throws Exception {
        List<Pick> out = new ArrayList<>();
        because = new ArrayList<>();
        String key = Config.STREMIO_KEY.trim();
        if (key.isEmpty()) return out;
        JSONObject res = post("https://api.strem.io/api/datastoreGet", new JSONObject().put("authKey", key)
                .put("collection", "libraryItem").put("ids", new JSONArray()).put("all", true));
        if (res.has("error")) throw new IOException(res.getJSONObject("error").optString("message"));
        JSONArray items = res.getJSONArray("result");
        java.util.Set<String> library = new java.util.HashSet<>();
        List<JSONObject> recent = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            String id = it.optString("_id");
            if (!id.startsWith("tt")) continue;
            library.add(id);
            JSONObject st = it.optJSONObject("state");
            if (st != null && time(st.optString("lastWatched")) > 0) recent.add(it);
        }
        java.util.Collections.sort(recent, (a, b) -> Long.compare(time(b.optJSONObject("state").optString("lastWatched")),
                time(a.optJSONObject("state").optString("lastWatched"))));
        java.util.Set<String> seen = new java.util.HashSet<>();
        int n = 0;
        List<String[]> later = new ArrayList<>();
        Map<Integer, Integer> tasted = new HashMap<>();
        Map<String, Integer> spoken = new HashMap<>();
        List<String> shows = new ArrayList<>();
        for (int i = 0; i < Math.min(30, recent.size()); i++)
            if (recent.get(i).optString("type").equals("series")) shows.add(recent.get(i).getString("_id"));
        followed = shows;
        for (int i = 8; i < Math.min(20, recent.size()); i++) {
            JSONObject it = recent.get(i);
            String kind = it.optString("type").equals("series") ? "tv" : "movie";
            JSONArray found = get("/find/" + it.getString("_id") + "?external_source=imdb_id&language=" + lang())
                    .optJSONArray(kind.equals("tv") ? "tv_results" : "movie_results");
            if (found == null || found.length() == 0) continue;
            JSONObject f = found.getJSONObject(0);
            count(tasted, f);
            spoken.merge(f.optString("original_language"), 1, Integer::sum);
            later.add(new String[] {Ui.t("Porque viste ",
                    "Because you watched ", "Porque você viu ") + f.optString(kind.equals("tv") ? "name" : "title"),
                    kind + ":/" + kind + "/" + f.optInt("id") + "/recommendations?language=" + lang()});
        }
        becauseMore = later;
        taste = tasted;
        langs = spoken;
        cache.edit().putString("taste", new JSONObject(new HashMap<String, Object>(stringKeys(tasted))).toString()).apply();
        AppLog.w("your taste: " + tasted);
        for (JSONObject it : recent.subList(0, Math.min(8, recent.size()))) {
            boolean ownRow = n++ < 3;
            String kind = it.optString("type").equals("series") ? "tv" : "movie";
            JSONArray found = get("/find/" + it.getString("_id") + "?external_source=imdb_id&language=" + lang())
                    .optJSONArray(kind.equals("tv") ? "tv_results" : "movie_results");
            if (found == null || found.length() == 0) continue;
            count(tasted, found.getJSONObject(0));
            spoken.merge(found.getJSONObject(0).optString("original_language"), 1, Integer::sum);
            taste = tasted;   // so far (the rows below are ordered by it)
            JSONArray similar = get("/" + kind + "/" + found.getJSONObject(0).optInt("id") + "/recommendations?language="
                    + lang()).getJSONArray("results");
            List<Pick> candidates = new ArrayList<>(), row = new ArrayList<>();
            for (int i = 0; i < similar.length() && candidates.size() < (ownRow ? 20 : 8); i++) add(candidates, similar.getJSONObject(i), kind);
            for (Pick pick : candidates) {
                if (!seen.add(key(pick))) continue;
                try {
                    fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                if (pick.pkg == null || (pick.imdb != null && library.contains(pick.imdb))) continue;
                if (ownRow) row.add(pick); else out.add(pick);
            }
            // Its own row; a few of them in "For you" too.
            if (ownRow) {
                String name = found.getJSONObject(0).optString(kind.equals("tv") ? "name" : "title", it.optString("name"));
                row(because, Ui.t("Porque viste ", "Because you watched ", "Porque você viu ") + name, row);
                out.addAll(row.subList(0, Math.min(4, row.size())));
            }
        }
        return out;
    }

    /** An image path from a TMDB result ("/abc.jpg"), or null. */
    private static String path(JSONObject o, String key) {
        String v = o.optString(key, "");
        return v.isEmpty() || v.equals("null") ? null : v;
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images,external_ids&include_image_language=null," + lang().substring(0, 2) + ",en");
        if (p.poster == null) p.poster = path(o, "poster_path");
        String best = mostVertical(bestBackdrops(o.optJSONObject("images")));
        if (best != null) p.backdrop = best;
        setLogo(p, o.optJSONObject("images"));
        meta(p, o);
        if (p.overview == null || p.overview.isEmpty()) p.overview = o.optString("overview", "");
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        // Written in TMDB's language (Spanish, Portuguese or English).
        boolean es = Ui.LANG.equals("es"), en = Ui.en;
        if (p.kind.equals("tv")) parts.add(Ui.t("Serie", "Series", "Série"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(en ? name : genre(name, es ? GENRES_ES : GENRES_PT));
        }
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0 && en) parts.add(minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m");
            else if (minutes > 0) parts.add(minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min");
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0 && en) parts.add(seasons == 1 ? "1 season" : seasons + " seasons");
            else if (seasons > 0) parts.add(seasons == 1 ? "1 temporada" : seasons + " temporadas");
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        p.app = p.pkg = p.link = null;
        p.anime = isAnime(o);
        StringBuilder on = new StringBuilder();
        for (int i = 0; flat != null && i < flat.length(); i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"));
            if (s != null && !("," + on + ",").contains("," + s[1] + ",")) on.append(on.length() == 0 ? "" : ",").append(s[1]);
            if (s != null && installed(s[2]) && (p.app == null || rank(s[1]) < rank(p.app))) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        p.services = on.toString();
        JSONObject ids = o.optJSONObject("external_ids");
        String imdb = ids == null ? "" : ids.optString("imdb_id", "");
        p.imdb = imdb.startsWith("tt") ? imdb : null;
        if (imdb.startsWith("tt") && installed(STREMIO) && (p.app == null || rank("stremio") < rank(p.app))) {
            p.app = "stremio";
            p.pkg = STREMIO;
            p.link = "stremio:///detail/" + (p.kind.equals("tv") ? "series" : "movie") + "/" + imdb;
        }
        if (p.app != null) parts.add(SERVICE_NAME.get(p.app));
        p.meta = String.join(" · ", parts);
        p.detailed = true;
    }

    /** TV genres that TMDB returns in English even in Spanish or Portuguese. */
    private static final String[][] GENRES_ES = {
        {"Action & Adventure", "Acción y aventura"}, {"Sci-Fi & Fantasy", "Ciencia ficción y fantasía"},
        {"War & Politics", "Bélica y política"}, {"Kids", "Infantil"}, {"News", "Noticias"}, {"Soap", "Telenovela"},
        {"Talk", "Entrevistas"},
    };

    private static final String[][] GENRES_PT = {
        {"Action & Adventure", "Ação e Aventura"}, {"Sci-Fi & Fantasy", "Ficção Científica e Fantasia"},
        {"War & Politics", "Guerra e Política"}, {"Kids", "Infantil"}, {"News", "Notícias"}, {"Soap", "Novela"},
        {"Talk", "Entrevistas"},
    };

    private static String genre(String name, String[][] table) {
        for (String[] g : table) if (g[0].equalsIgnoreCase(name)) return g[1];
        return name;
    }

    /** The same for lists saved earlier ("Série · Action & Adventure · …" in Portuguese). */
    private static String fixGenres(String meta) {
        if (!lang().startsWith("pt")) return meta;
        for (String[] g : GENRES_PT) {
            meta = meta.replace(" · " + g[0] + " ·", " · " + g[1] + " ·");
            if (meta.endsWith(" · " + g[0])) meta = meta.substring(0, meta.length() - g[0].length()) + g[1];
        }
        return meta;
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), at the highest resolution
     * (up to 3840, TMDB's 4K) and, on a tie, the highest rated.
     */
    private static List<String> bestBackdrops(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        List<Object[]> scored = new ArrayList<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 3840) * 100 + b.optDouble("vote_average", 0);
            scored.add(new Object[] {score, b.optString("file_path")});
        }
        java.util.Collections.sort(scored, (a, b) -> Double.compare((double) b[0], (double) a[0]));
        // The ones as good as the best: textless if it is, and at least HD (score: 1e7 textless + width × 100).
        List<String> out = new ArrayList<>();
        double top = scored.isEmpty() ? 0 : (double) scored.get(0)[0];
        for (Object[] o : scored)
            if (out.isEmpty() || ((double) o[0] >= 1e7) == (top >= 1e7) && (double) o[0] % 1e7 >= 1280 * 100) out.add((String) o[1]);
        return out;
    }

    /** Of the 3 best backdrops, the one that holds best as a vertical card too (its subject fits the slice). */
    private static String mostVertical(List<String> backdrops) {
        if (backdrops.isEmpty()) return null;
        String best = backdrops.get(0);
        float bestFit = 0;
        for (String path : backdrops.subList(0, Math.min(3, backdrops.size()))) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://image.tmdb.org/t/p/w300" + path).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                Bitmap b;
                try {
                    b = BitmapFactory.decodeStream(c.getInputStream());
                } finally {
                    c.disconnect();
                }
                if (b == null) continue;
                float fit = framing(b)[1];
                b.recycle();
                if (fit > bestFit + 0.05f) {   // a tie keeps the better rated
                    bestFit = fit;
                    best = path;
                }
            } catch (Exception e) {
                AppLog.w("backdrop: " + e.getMessage());
            }
        }
        return best;
    }

    /**
     * The title's logo in your language, else English, else without one, else any (its original's, a Korean
     * show's); the best rated. PNG only (not SVG).
     */
    private static void setLogo(Pick p, JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("logos");
        String mine = lang().substring(0, 2), best = null, bestIn = "";
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject l = list.optJSONObject(i);
            String path = l == null ? "" : l.optString("file_path", "");
            if (!path.endsWith(".png")) continue;
            String in = l.optString("iso_639_1", "");
            double score = (mine.equals(in) ? 40 : "en".equals(in) ? 30 : in.isEmpty() || "null".equals(in) ? 20 : 10)
                    + l.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = path;
                bestIn = in;
            }
        }
        p.logo = best;
        p.logoMine = mine.equals(bestIn);
    }

    /** Logo and backdrop for the titles that came without TMDB's images (the rows from discover). */
    private void addImages(List<Pick> all) {
        for (Pick p : all) {
            if (p.logo != null || p.id == 0) continue;
            try {
                JSONObject images = get("/" + p.kind + "/" + p.id + "/images");   // every language: a logo in any beats none
                setLogo(p, images);
                String best = mostVertical(bestBackdrops(images));
                if (best != null) p.backdrop = best;
            } catch (Exception e) {
                AppLog.w("logo: " + e.getMessage());
            }
        }
    }

    /** The service's row, preferring the one whose app is installed (one service can have several apps). */
    /** Japanese animation: TMDB's Animation genre (16) in Japanese (details' "genres" or a search result's "genre_ids"). */
    static boolean isAnime(JSONObject o) {
        if (!"ja".equals(o.optString("original_language"))) return false;
        JSONArray g = o.optJSONArray("genres");
        for (int i = 0; g != null && i < g.length(); i++) if (g.optJSONObject(i) != null && g.optJSONObject(i).optInt("id") == 16) return true;
        JSONArray ids = o.optJSONArray("genre_ids");
        for (int i = 0; ids != null && i < ids.length(); i++) if (ids.optInt(i) == 16) return true;
        return false;
    }

    private String[] service(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] first = null;
        for (String[] s : SERVICES) {
            if (!(s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0]))) continue;
            if (installed(s[2])) return s;
            if (first == null) first = s;
        }
        return first;
    }

    private static int rank(String app) {
        int i = PRIORITY.indexOf(app);
        return i >= 0 ? i : PRIORITY.size();
    }

    private boolean installed(String pkg) {
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = readKey();
        if (key == null) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private String readKey() {
        String key = Config.TMDB_KEY.trim();
        return key.isEmpty() ? null : key;
    }

    // MARK: - Images (TMDB originals on disk, up to 24 so the whole list fits; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        File f = imageFile(p);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(IMAGES + p.backdrop).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Reads the original (≥ 1920 wide) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight);
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight);
            if (forLight != light) return;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                while (images.size() > (forLight ? 15 : 4)) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    private static void prune(File dir) {
        prune(dir, 24);
    }

    /** Keeps the newest `max` files. */
    private static void prune(File dir, int max) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= max) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - max; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) a.put(json(p));
            cache.edit().putString("picks", a.toString()).putInt("period", period).putLong("listAt", listAt)
                    .putBoolean("english", listEnglish)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION).apply();
        } catch (Exception ignored) {
        }
    }

    private static JSONObject json(Pick p) throws org.json.JSONException {
        return new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop)
                .put("meta", p.meta).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                .put("link", p.link == null ? "" : p.link).put("detailed", p.detailed)
                .put("poster", p.poster == null ? "" : p.poster).put("logo", p.logo == null ? "" : p.logo).put("logoMine", p.logoMine).put("year", p.year).put("rating", p.rating).put("overview", p.overview == null ? "" : p.overview)
                .put("services", p.services == null ? "" : p.services).put("anime", p.anime)
                .put("imdb", p.imdb == null ? "" : p.imdb);
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.meta = fixGenres(o.optString("meta"));
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.link = o.optString("link").isEmpty() ? null : o.optString("link");
            p.poster = o.optString("poster").isEmpty() ? null : o.optString("poster");
            p.logo = o.optString("logo").isEmpty() ? null : o.optString("logo");
            p.logoMine = o.optBoolean("logoMine");
            p.year = o.optString("year");
            p.rating = o.optDouble("rating", 0);
            p.overview = o.optString("overview");
            p.detailed = o.optBoolean("detailed");
            p.services = o.optString("services");
            p.anime = o.optBoolean("anime");
            p.imdb = o.optString("imdb").isEmpty() ? null : o.optString("imdb");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
