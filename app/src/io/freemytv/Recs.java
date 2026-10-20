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
        boolean detailed;
    }

    /** A row under the apps (K-dramas, Anime, Trending on Netflix...). */
    static final class Row {
        String title;
        List<Pick> picks = new ArrayList<>();
    }

    interface Listener { void onRecs(); }

    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMAGES = "https://image.tmdb.org/t/p/original";
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 24;
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    static final String[] PERIOD_LABEL = {"PARA ESTA MANHÃ", "PARA ESTA TARDE", "PARA ESTA NOITE"};
    static final String[] PERIOD_LABEL_EN = {"THIS MORNING", "THIS AFTERNOON", "TONIGHT"};
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
    private volatile List<Pick> resumes = new ArrayList<>();   // Stremio's titles in progress, last watched first ("stremio_key")
    private final Map<String, JSONObject> foundByImdb = new HashMap<>();   // TMDB's match for each, asked once
    private volatile List<Row> rows = new ArrayList<>();
    private final ExecutorService thumbIo = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> thumbs = new LinkedHashMap<>();   // row cards, small (TMDB w500)
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
        return (Ui.en ? PERIOD_LABEL_EN : PERIOD_LABEL)[period(System.currentTimeMillis())];
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
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    JSONObject st = it.optJSONObject("state");
                    // In progress: a position saved, and either in the library or only watched (temp).
                    if (st == null || st.optLong("timeOffset") <= 0 || !it.optString("_id").startsWith("tt")) continue;
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
            JSONArray found = get("/find/" + id + "?external_source=imdb_id&language=" + lang())
                    .optJSONArray(series ? "tv_results" : "movie_results");
            o = found != null && found.length() > 0 ? found.getJSONObject(0) : new JSONObject();
            if (o.length() > 0) {
                try {
                    o.put("_images", get("/" + (series ? "tv" : "movie") + "/" + o.optInt("id") + "/images"));
                } catch (Exception e) {
                    AppLog.w("logo: " + e.getMessage());
                }
            }
            synchronized (foundByImdb) { foundByImdb.put(id, o); }
        }
        if (o != null) {
            if (o.length() > 0) {
                p.id = o.optInt("id");
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
        boolean es = lang().startsWith("es");
        if (series && episode > 0) parts.add((es ? "T" : Ui.tr("T", "S")) + season + " E" + episode);
        long left = (st.optLong("duration") - st.optLong("timeOffset")) / 60_000L;
        if (st.optLong("duration") > 0 && left > 0)
            parts.add(es ? "faltan " + left + " min" : Ui.tr("faltam " + left + " min", left + " min left"));
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
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = Config.TMDB_KEY.hashCode() * 31L + REGION.hashCode();   // a new key or country refetches
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
        if (services.isEmpty()) throw new IOException("none of the TV's streaming services are on TMDB");
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        String common = "&language=" + lang() + "&watch_region=" + REGION + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&sort_by=popularity.desc&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8");
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
        List<Pick> list = new ArrayList<>();
        alternate(list, new java.util.HashSet<>(), mine, others, 60);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
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
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listEnglish = Ui.en;
    }

    /** Language of titles and genres; the services come from the region. */
    private static String lang() {
        String lang = Config.LANGUAGE.trim().toLowerCase(Locale.ROOT);
        if (lang.startsWith("es") || lang.equals("auto") && "es".equals(Locale.getDefault().getLanguage()))
            return "ES".equals(REGION) ? "es-ES" : "es-MX";     // the UI stays English; only TMDB's text is Spanish
        return Ui.en ? "en-US" : "pt-BR";
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
        meta(p, o);
        list.add(p);
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
        String ko = "&with_original_language=ko&without_genres=10762,10751,10764,10767&vote_count.gte=30";
        String ja = "&with_original_language=ja&with_genres=16&without_genres=10762,10751&vote_count.gte=30";
        category(out, seen, t("K-dramas de romance", "Romance K-dramas"), ko + "&with_keywords=9840");
        category(out, seen, t("K-dramas de comedia romántica", "Romantic comedy K-dramas"), ko + "&with_genres=35&with_keywords=9840");
        category(out, seen, t("K-dramas de suspenso y crimen", "Thriller and crime K-dramas"), ko + "&with_genres=80|9648");
        category(out, seen, t("K-dramas de fantasía", "Fantasy K-dramas"), ko + "&with_genres=10765");
        category(out, seen, t("Anime de acción", "Action anime"), ja + ",10759");
        category(out, seen, t("Anime isekai", "Isekai anime"), ja + "&with_keywords=237451");
        category(out, seen, t("Anime de romance", "Romance anime"), ja + "&with_keywords=9840");
        category(out, seen, t("Anime de la vida cotidiana", "Slice of life anime"), ja + "&with_keywords=9914");
        // Well loved but not famous: to find something new.
        String gem = "&vote_average.gte=7.4&sort_by=vote_average.desc&without_genres=10762,10764,10767,99";
        List<Pick> gems = new ArrayList<>();
        alternate(gems, seen, detailed(discover("movie", gem + "&vote_count.gte=300&vote_count.lte=4000")),
                detailed(discover("tv", gem + "&vote_count.gte=150&vote_count.lte=2500")), ROW_MAX);
        row(out, t("Joyas escondidas", "Hidden gems"), gems);
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
            List<Pick> mixed = new ArrayList<>();
            alternate(mixed, seen, discover("tv", with + "&first_air_date.gte=" + day(-365)),
                    discover("movie", with + "&primary_release_date.gte=" + day(-365)), ROW_MAX);
            for (Pick p : mixed) {
                p.app = app;
                p.pkg = pkg;
                p.detailed = true;
            }
            row(out, t("Lo nuevo en ", "New on ") + SERVICE_NAME.get(app), mixed);
        }
        return out;
    }

    /** A row of shows from TMDB's discover (popular first), each opening in the app that has it. */
    private void category(List<Row> out, java.util.Set<String> seen, String title, String filters) throws Exception {
        List<Pick> fresh = new ArrayList<>();
        for (Pick p : discover("tv", filters)) if (seen.add(key(p))) fresh.add(p);
        row(out, title, detailed(fresh));
    }

    /** Spanish when TMDB's language is Spanish, else English (row titles). */
    private static String t(String spanish, String english) {
        return lang().startsWith("es") ? spanish : english;
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
        List<Pick> out = new ArrayList<>();
        for (int page = 1; page <= 2; page++) {
            JSONObject res = get("/discover/" + kind + "?language=" + lang() + "&watch_region=" + REGION
                    + "&include_adult=false" + (filters.contains("sort_by=") ? "" : "&sort_by=popularity.desc") + filters + "&page=" + page);
            JSONArray results = res.getJSONArray("results");
            for (int i = 0; i < results.length(); i++) add(out, results.getJSONObject(i), kind);
            if (page >= res.optInt("total_pages", 1)) break;
        }
        return out;
    }

    private static final int ROW_MAX = 30;

    /** Details for each (which app has it), keeping the ones the TV can open. */
    private List<Pick> detailed(List<Pick> list) {
        List<Pick> out = new ArrayList<>();
        for (Pick p : list) {
            if (out.size() >= ROW_MAX) break;
            try {
                fetchDetails(p);
            } catch (Exception e) {
                continue;
            }
            if (p.pkg != null) out.add(p);
        }
        return out;
    }

    private static void row(List<Row> out, String title, List<Pick> picks) {
        if (picks.isEmpty()) return;
        Row r = new Row();
        r.title = title;
        r.picks = picks;
        out.add(r);
    }

    private static JSONArray rowsJson(List<Row> rows) throws org.json.JSONException {
        JSONArray a = new JSONArray();
        for (Row r : rows) {
            JSONArray picks = new JSONArray();
            for (Pick p : r.picks) picks.put(json(p));
            a.put(new JSONObject().put("title", r.title).put("picks", picks));
        }
        return a;
    }

    private static List<Row> parseRows(JSONArray a) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optJSONArray("picks") == null) continue;
            row(out, o.optString("title"), parse(o.optJSONArray("picks")));
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
                    // ponytail: simple insertion-order cap; an LRU only if rows grow past ~120 images.
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
                        found = youtubeTrailer(p, mine, p.title + (es(mine) ? " tráiler oficial español latino" : " official trailer"), enTitle, original);
                        // Its English title, subtitled: Korean and Japanese shows (an English one's must be dubbed).
                        if (found.isEmpty() && es(mine) && !original.equals("en"))
                            found = youtubeTrailer(p, mine, enTitle + " trailer sub español", enTitle, original);
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

    private String youtubeTrailer(Pick p, String mine, String q, String enTitle, String original) throws Exception {
        boolean es = es(mine);
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
            boolean subs = t.matches(".*\\b(subtitulad[oa]|sub esp[a-z]*|subs?|subtitulos)\\b.*");
            boolean dubbed = !subs && t.matches(".*\\b(latino|espanol|doblad[oa]|castellano)\\b.*");
            boolean spanish = dubbed || subs || raw.toLowerCase(Locale.ROOT).contains("tráiler") || t.matches(".*\\boficial\\b.*");
            if (es && !spanish) continue;
            if (subs && original.equals("en")) continue;
            // Latin American Spanish, not Spain's: Spain's channels and "castellano" are out, Latin American ones first.
            boolean spain = (t + " " + channel).matches(".*\\b(castellano|espana)\\b.*");
            if (es && spain && !spainOk()) continue;
            boolean latam = (t + " " + channel).matches(".*\\b(latino|latinoamerica|latam|mexico|hispanoamerica)\\b.*");
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
        for (JSONObject it : recent.subList(0, Math.min(8, recent.size()))) {
            boolean ownRow = n++ < 3;
            String kind = it.optString("type").equals("series") ? "tv" : "movie";
            JSONArray found = get("/find/" + it.getString("_id") + "?external_source=imdb_id&language=" + lang())
                    .optJSONArray(kind.equals("tv") ? "tv_results" : "movie_results");
            if (found == null || found.length() == 0) continue;
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
                row(because, t("Porque viste ", "Because you watched ") + name, row);
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
        boolean es = lang().startsWith("es"), en = !es && Ui.en;
        if (p.kind.equals("tv")) parts.add(es ? "Serie" : Ui.tr("Série", "Series"));
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
        for (int i = 0; flat != null && i < flat.length(); i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"));
            if (s != null && installed(s[2]) && (p.app == null || rank(s[1]) < rank(p.app))) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
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
        File[] files = dir.listFiles();
        if (files == null || files.length <= 24) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 24; i++) {
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
                .put("poster", p.poster == null ? "" : p.poster).put("logo", p.logo == null ? "" : p.logo).put("logoMine", p.logoMine).put("year", p.year).put("rating", p.rating).put("overview", p.overview == null ? "" : p.overview);
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
