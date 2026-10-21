package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * "Watch": every direct stream (debrid) of the title from the add-ons on your Stremio account, best first for our own
 * player: Latin American Spanish audio, then the original with Spanish subtitles, then Spain's Spanish, then anything.
 * Torrents are left out (they need Stremio's engine). Also your place in it, read from and saved to your Stremio
 * library, so "Continue watching" is the same here, on Stremio and on your phone.
 */
final class Streams {
    private Streams() {}

    private static final Pattern LATAM = Pattern.compile(
            "latino|latam|lat-am|\\blat\\b|hispano|🇲🇽|🇦🇷|🇨🇴|🇨🇱|🇵🇪|🇻🇪");
    private static final Pattern SPAIN = Pattern.compile("castellano|español|espanol|spanish|\\besp?\\b|\\bspa\\b|🇪🇸");

    /** 4K and Dolby Vision: this TV's decoder fails on the first and shows nothing with the second. */
    private static final Pattern HEAVY = Pattern.compile("2160|\\b4k\\b|\\buhd\\b|dolby ?vision|\\bdovi\\b|\\bdv\\b");

    /** A 4K or Dolby Vision stream (by its name): tried only when nothing else of that kind is left. */
    static boolean heavy(String text) {
        return HEAVY.matcher(text.toLowerCase(Locale.ROOT)).find();
    }

    /** 0 Latin American audio, 1 no Spanish audio (original), 2 Spain's Spanish audio. */
    static int audio(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        // A long row of flags lists the subtitles too ("🇬🇧 / 🇯🇵 / 🇪🇸 / 🇰🇷 …"): only words count then.
        if (flags(t) >= 4) t = FLAG.matcher(t).replaceAll("");
        if (LATAM.matcher(t).find()) return 0;
        return SPAIN.matcher(t).find() ? 2 : 1;
    }

    /** A flag emoji: two regional indicator letters (U+1F1E6…U+1F1FF). */
    private static final Pattern FLAG = Pattern.compile("[\\x{1F1E6}-\\x{1F1FF}]{2}");

    private static final Pattern SPANISH_FLAG = Pattern.compile("🇪🇸|🇲🇽|🇦🇷|🇨🇴|🇨🇱|🇵🇪|🇻🇪|\\bmulti-?subs?\\b");

    /** A row of 4+ flags with a Spanish-speaking one (or "multi-sub"): the file carries Spanish subtitles. */
    static boolean embeddedSubs(String t) {
        String l = t.toLowerCase(Locale.ROOT);
        return (flags(l) >= 4 && SPANISH_FLAG.matcher(l).find()) || l.matches("(?s).*\\bmulti-?subs?\\b.*");
    }

    private static int flags(String t) {
        int n = 0;
        for (java.util.regex.Matcher m = FLAG.matcher(t); m.find(); ) n++;
        return n;
    }

    /** A subtitle language code in Spanish ("spa", "spn", "es", "es-419", "Spanish"…). */
    static boolean spanish(String lang) {
        String l = lang.toLowerCase(Locale.ROOT);
        return l.startsWith("sp") || l.equals("es") || l.startsWith("es-") || l.startsWith("esp") || l.equals("lat");
    }

    /** OpenSubtitles' "spn" and "es-419"/"es-MX"…: Spanish from Latin America. */
    static boolean latamSubs(String lang) {
        String l = lang.toLowerCase(Locale.ROOT);
        return l.equals("spn") || l.equals("lat") || l.contains("419") || l.contains("latin") || (l.startsWith("es-") && !l.equals("es-es"));
    }

    /** One stream to try. */
    static final class Stream {
        String url;
        boolean spanishAudio, heavy;
        int kind;                           // audio(): 0 Latin American, 1 original, 2 Spain's
        boolean cached;                     // already in your debrid account: plays at once ("[TB+]", "⚡")
        boolean embeddedSubs;               // Spanish subtitles inside the file (a Spanish flag in a long row of them)
        String quality = "", size = "";     // "1080p", "2.28 GB" (from its name, for the player's Version list)
    }

    private static final Pattern CACHED = Pattern.compile("\\[(?:tb|rd|ad|pm|dl|ed|oc|pkp|trb)\\+\\]|⚡|\\binstant\\b");

    static boolean cached(String t) {
        return CACHED.matcher(t.toLowerCase(Locale.ROOT)).find();
    }

    private static final Pattern QUALITY = Pattern.compile("2160p|\\b4k\\b|1080p|720p|576p|480p");
    private static final Pattern SIZE = Pattern.compile("💾\\s*([\\d.,]+\\s*[GM]B)");

    private static String quality(String t) {
        java.util.regex.Matcher m = QUALITY.matcher(t.toLowerCase(Locale.ROOT));
        if (!m.find()) return "";
        String q = m.group();
        return q.equals("2160p") || q.equals("4k") ? "4K" : q;
    }

    private static String size(String t) {
        java.util.regex.Matcher m = SIZE.matcher(t);
        return m.find() ? m.group(1) : "";
    }

    /** What to play: the streams to try in order (the next when one fails or is only a notice), and where you were. */
    static final class Choice {
        String type, id, video;             // "series", "tt1", "tt1:2:5"
        ArrayList<Stream> streams = new ArrayList<>();
        String subtitles;                   // .srt in Spanish (Latin American first), downloaded: its path, or null
        long startAt;                       // ms: where you left this movie or episode, 0 = from the start
        int latino, spain;                  // how many Latin American / Spain's Spanish streams were found

        Choice copy() {
            Choice c = new Choice();
            c.type = type;
            c.id = id;
            c.video = video;
            c.streams = new ArrayList<>(streams);
            c.subtitles = subtitles;
            c.startAt = startAt;
            c.latino = latino;
            c.spain = spain;
            return c;
        }
    }

    private static final long ADDONS_FOR = 30 * 60_000L, STREAMS_FOR = 20 * 60_000L;
    private static JSONArray addonsCache;
    private static long addonsAt;
    private static final java.util.Map<String, Choice> found = new java.util.HashMap<>();
    private static final java.util.Map<String, Long> foundAt = new java.util.HashMap<>();

    /** Looks a title up ahead (Continue watching), so its video starts at once when you get to it. */
    static void prefetch(String detailLink, java.io.File cacheDir) {
        synchronized (found) {
            Long at = foundAt.get(detailLink);
            if (at != null && System.currentTimeMillis() - at < STREAMS_FOR) return;
        }
        best(detailLink, cacheDir);
    }

    /**
     * "stremio:///detail/movie/tt1" or ".../series/tt1/tt1:2:5" → the streams to play, or null (no direct one).
     * The streams found in the last 20 min are reused; only your place is read again.
     */
    static Choice best(String detailLink, java.io.File cacheDir) {
        long t0 = System.currentTimeMillis();
        Choice known;
        synchronized (found) {
            Long at = foundAt.get(detailLink);
            known = at != null && t0 - at < STREAMS_FOR ? found.get(detailLink) : null;
        }
        if (known != null) {
            try {
                Choice c = known.copy();
                c.startAt = startAt(libraryItem(Config.STREMIO_KEY.trim(), c.id), c.video);
                AppLog.w("streams for " + c.video + ": known, from " + c.startAt / 1000 + " s, in " + (System.currentTimeMillis() - t0) + " ms");
                return c;
            } catch (Exception e) {
                AppLog.w("streams: " + e.getMessage());
            }
        }
        Choice c = find(detailLink, cacheDir);
        if (c != null) {
            synchronized (found) {
                found.put(detailLink, c);
                foundAt.put(detailLink, System.currentTimeMillis());
            }
            AppLog.w("streams for " + c.video + " in " + (System.currentTimeMillis() - t0) + " ms");
            c = c.copy();
        }
        return c;
    }

    private static long startAt(JSONObject item, String video) {
        JSONObject state = item == null ? null : item.optJSONObject("state");
        return state != null && video.equals(state.optString("video_id")) ? Math.max(0, state.optLong("timeOffset")) : 0;
    }

    private static JSONArray addons(String key) throws Exception {
        synchronized (Streams.class) {
            if (addonsCache != null && System.currentTimeMillis() - addonsAt < ADDONS_FOR) return addonsCache;
        }
        JSONArray a = post("https://api.strem.io/api/addonCollectionGet",
                new JSONObject().put("type", "AddonCollectionGet").put("authKey", key).put("update", true))
                .getJSONObject("result").getJSONArray("addons");
        synchronized (Streams.class) {
            addonsCache = a;
            addonsAt = System.currentTimeMillis();
        }
        return a;
    }

    /** What the home screen shows on a card: in your debrid already, which Spanish, Spanish subtitles. */
    static final class Badge {
        boolean cached, latino, spain, subs, none;
    }

    private static final long BADGES_FOR = 6 * 3600_000L;
    private static final java.util.Map<String, Badge> badges = new java.util.HashMap<>();
    private static final java.util.Map<String, Long> badgesAt = new java.util.HashMap<>();

    /** The badge if known in the last 6 h (or from a lookup for playing), else null. */
    static Badge knownBadge(String detailLink) {
        synchronized (found) {
            Long at = badgesAt.get(detailLink);
            if (at != null && System.currentTimeMillis() - at < BADGES_FOR) return badges.get(detailLink);
            Long f = foundAt.get(detailLink);
            if (f != null && System.currentTimeMillis() - f < STREAMS_FOR) return badge(found.get(detailLink));
        }
        return null;
    }

    /**
     * Asks the add-ons what they have for a title without opening anything (no link followed, so a debrid service
     * never starts downloading a title you only went past; no subtitles downloaded). Off the UI thread.
     */
    static Badge peek(String detailLink) {
        Badge known = knownBadge(detailLink);
        if (known != null) return known;
        Badge b = badge(find(detailLink, null));
        synchronized (found) {
            badges.put(detailLink, b);
            badgesAt.put(detailLink, System.currentTimeMillis());
        }
        return b;
    }

    private static Badge badge(Choice c) {
        Badge b = new Badge();
        b.none = c == null || c.streams.isEmpty();
        if (b.none) return b;
        b.cached = c.streams.get(0).cached;   // the one that would play
        b.latino = c.latino > 0;
        b.spain = c.spain > 0;
        b.subs = c.subtitles != null;
        for (Stream st : c.streams) b.subs |= st.embeddedSubs;
        return b;
    }

    /** cacheDir null = only looking (peek): nothing followed, nothing downloaded. */
    private static Choice find(String detailLink, java.io.File cacheDir) {
        final boolean peek = cacheDir == null;
        String key = Config.STREMIO_KEY.trim();
        String prefix = "stremio:///detail/";
        if (key.isEmpty() || detailLink == null || !detailLink.startsWith(prefix)) return null;
        String[] p = detailLink.substring(prefix.length()).split("/");
        if (p.length < 2) return null;
        final Choice c = new Choice();
        c.type = p[0];
        c.id = p[1];
        c.video = p.length > 2 ? p[2] : c.id;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            Future<JSONObject> saved = pool.submit(() -> libraryItem(key, c.id));
            JSONArray addons = addons(key);
            List<String> streamAddons = new ArrayList<>(), subAddons = new ArrayList<>();
            for (int i = 0; i < addons.length(); i++) {
                JSONObject a = addons.getJSONObject(i);
                String url = a.optString("transportUrl");
                if (!url.endsWith("/manifest.json") || !url.startsWith("http")) continue;
                if (serves(a.optJSONObject("manifest"), "stream", c.type, c.id)) streamAddons.add(url);
                if (serves(a.optJSONObject("manifest"), "subtitles", c.type, c.id)) subAddons.add(url);
            }
            String path = "/" + c.type + "/" + URLEncoder.encode(c.video, "UTF-8") + ".json";
            List<Future<JSONArray>> streams = new ArrayList<>(), subs = new ArrayList<>();
            for (String u : streamAddons) streams.add(pool.submit(() -> get(base(u) + "/stream" + path).optJSONArray("streams")));
            for (String u : subAddons) subs.add(pool.submit(() -> get(base(u) + "/subtitles" + path).optJSONArray("subtitles")));

            // Each kind in your add-ons' order (each add-on already sorts its own by quality), as Stremio lists them.
            List<List<Stream>> kinds = new ArrayList<>();
            for (int k = 0; k < 3; k++) kinds.add(new ArrayList<>());
            for (Future<JSONArray> f : streams) {
                // Latino in 1080p found: the add-ons after can only add streams further down the list, so no waiting
                // for them (the ones already back still count, as spares).
                boolean enough = false;
                for (Stream st : kinds.get(0)) enough |= !st.heavy;
                JSONArray list = !enough || f.isDone() ? await(f) : null;
                for (int j = 0; list != null && j < list.length(); j++) {
                    JSONObject s = list.optJSONObject(j);
                    if (s == null || !s.optString("url").startsWith("http")) continue;
                    Stream st = new Stream();
                    st.url = s.optString("url");
                    String text = s.optString("name") + " " + s.optString("title") + " " + s.optString("description");
                    int a = audio(text);
                    st.spanishAudio = a != 1;
                    st.heavy = heavy(text);
                    st.kind = a;
                    st.quality = quality(text);
                    st.size = size(text);
                    st.cached = cached(text);
                    st.embeddedSubs = a == 1 && embeddedSubs(text);
                    kinds.get(a).add(st);
                }
            }
            // 1080p and below first in each kind (your add-ons' order kept within each).
            for (List<Stream> kind : kinds) {
                List<Stream> heavy = new ArrayList<>();
                for (java.util.Iterator<Stream> it = kind.iterator(); it.hasNext(); ) {
                    Stream st = it.next();
                    if (st.heavy) {
                        heavy.add(st);
                        it.remove();
                    }
                }
                kind.addAll(heavy);
            }
            String subsUrl = kinds.get(0).isEmpty() ? spanishSubs(subs) : null;
            if (subsUrl != null) c.subtitles = peek ? subsUrl : download(subsUrl, new java.io.File(cacheDir, "subtitles.srt"));
            c.latino = kinds.get(0).size();
            c.spain = kinds.get(2).size();
            c.streams.addAll(kinds.get(0));
            if (c.subtitles != null) c.streams.addAll(kinds.get(1));
            c.streams.addAll(kinds.get(2));
            if (c.subtitles == null) c.streams.addAll(kinds.get(1));   // nothing in Spanish: the original anyway
            c.startAt = startAt(await(saved), c.video);
            // The first streams' links, followed ahead (add-on → debrid → file): the player goes straight to the file.
            for (int i = 0; !peek && i < Math.min(2, c.streams.size()); i++) c.streams.get(i).url = resolve(c.streams.get(i).url);
            AppLog.w("streams for " + c.video + (peek ? " (looking only)" : "") + ": " + kinds.get(0).size() + " latino, " + kinds.get(1).size() + " original, "
                    + kinds.get(2).size() + " Spain" + (c.subtitles != null ? ", Spanish subtitles" : "") + ", from " + c.startAt / 1000 + " s");
            return c.streams.isEmpty() ? null : c;
        } catch (Exception e) {
            AppLog.w("streams: " + e.getMessage());
            return null;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Where a link ends up after its redirects (or the link itself if that can't be known). */
    static String resolve(String url) {
        String at = url;
        try {
            for (int hop = 0; hop < 5; hop++) {
                HttpURLConnection c = (HttpURLConnection) new URL(at).openConnection();
                c.setInstanceFollowRedirects(false);
                // Generous: it runs ahead, and a debrid service may take a while to hand over a file it has to fetch.
                c.setConnectTimeout(10_000);
                c.setReadTimeout(12_000);
                c.setRequestProperty("Range", "bytes=0-0");
                try {
                    int code = c.getResponseCode();
                    String next = c.getHeaderField("Location");
                    if (code < 300 || code >= 400 || next == null) return at;
                    at = new URL(new URL(at), next).toString();
                } finally {
                    c.disconnect();
                }
            }
        } catch (Exception e) {
            AppLog.w("resolving a stream: " + e.getMessage());
        }
        return at;
    }

    /** The best Spanish subtitles' link (Latin American first), or null. */
    private static String spanishSubs(List<Future<JSONArray>> subs) {
        String any = null;
        for (Future<JSONArray> f : subs) {
            JSONArray list = await(f);
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject s = list.optJSONObject(i);
                if (s == null || !spanish(s.optString("lang")) || !s.optString("url").startsWith("http")) continue;
                if (latamSubs(s.optString("lang"))) return s.optString("url");
                if (any == null) any = s.optString("url");
            }
        }
        return any;
    }

    /** The subtitles to a file (the player reads them from there: a link failing mid-film would stop the video). */
    private static String download(String url, java.io.File to) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            try (InputStream in = c.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(to)) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            } finally {
                c.disconnect();
            }
            return to.getPath();
        } catch (Exception e) {
            AppLog.w("subtitles: " + e.getMessage());
            return null;
        }
    }

    // MARK: - Your place, in your Stremio library

    private static JSONObject libraryItem(String key, String id) throws Exception {
        JSONObject res = post("https://api.strem.io/api/datastoreGet", new JSONObject().put("authKey", key)
                .put("collection", "libraryItem").put("ids", new JSONArray().put(id)).put("all", false));
        JSONArray items = res.optJSONArray("result");
        return items != null && items.length() > 0 ? items.getJSONObject(0) : null;
    }

    /**
     * Saves where you are in this movie or episode to your Stremio library (as Stremio's own player does). Near the end
     * it counts as watched and leaves "Continue watching". Call off the UI thread.
     */
    static void saveProgress(Choice c, long position, long duration, String name, String poster) {
        String key = Config.STREMIO_KEY.trim();
        if (key.isEmpty() || duration <= 0) return;
        try {
            String now = java.time.Instant.now().toString();
            JSONObject item = libraryItem(key, c.id);
            if (item == null) {
                // Watched but not added to the library: Stremio's "temp" item, which shows in Continue watching.
                item = new JSONObject().put("_id", c.id).put("type", c.type).put("name", name).put("poster", poster)
                        .put("posterShape", "poster").put("removed", true).put("temp", true).put("_ctime", now)
                        .put("behaviorHints", new JSONObject().put("defaultVideoId", JSONObject.NULL)
                                .put("featuredVideoId", JSONObject.NULL).put("hasScheduledVideos", false));
            }
            JSONObject st = item.optJSONObject("state");
            if (st == null) {
                st = new JSONObject().put("timeWatched", 0).put("overallTimeWatched", 0).put("timesWatched", 0)
                        .put("flaggedWatched", 0).put("watched", JSONObject.NULL).put("noNotif", false);
                item.put("state", st);
            }
            if (!c.video.equals(st.optString("video_id"))) st.put("timeWatched", 0);
            st.put("video_id", c.video).put("lastWatched", now).put("duration", duration);
            String[] v = c.video.split(":");
            boolean tt = c.id.startsWith("tt");   // Kitsu's ("kitsu:46043:13") have no season: Stremio leaves both at 0
            if (tt && v.length >= 3) st.put("season", Integer.parseInt(v[v.length - 2])).put("episode", Integer.parseInt(v[v.length - 1]));
            boolean done = position > duration * 0.92;
            st.put("timeOffset", done ? 0 : position);
            if (done) st.put("flaggedWatched", 1).put("timesWatched", st.optInt("timesWatched") + 1);
            // An episode finished: on to the next one, as Stremio does (offset 1 keeps the show in Continue watching).
            String next = done && "series".equals(c.type) ? nextEpisode(c.id, c.video) : null;
            if (next != null) {
                String[] n = next.split(":");
                st.put("video_id", next).put("timeOffset", 1).put("timeWatched", 0);
                if (tt && n.length >= 3) st.put("season", Integer.parseInt(n[n.length - 2])).put("episode", Integer.parseInt(n[n.length - 1]));
            }
            item.put("_mtime", now);
            JSONObject res = post("https://api.strem.io/api/datastorePut", new JSONObject().put("authKey", key)
                    .put("collection", "libraryItem").put("changes", new JSONArray().put(item)));
            if (res.has("error")) throw new IOException(res.getJSONObject("error").optString("message"));
        } catch (Exception e) {
            AppLog.w("saving your place: " + e.getMessage());
        }
    }

    /** A show's details from Stremio: Cinemeta's for IMDb ids, the Kitsu add-on's for anime ("kitsu:46043"). */
    static JSONObject meta(String id) throws Exception {
        String base = id.startsWith("kitsu:") ? "https://anime-kitsu.strem.fun" : "https://v3-cinemeta.strem.io";
        return get(base + "/meta/series/" + id + ".json").getJSONObject("meta");
    }

    /** The episode after `video` ("tt1:2:6", "kitsu:46043:12"), by season and number; null after the last one. */
    static String nextEpisode(String id, String video) {
        try {
            JSONArray videos = meta(id).optJSONArray("videos");
            int season = -1, episode = -1;
            for (int i = 0; videos != null && i < videos.length(); i++) {
                JSONObject o = videos.getJSONObject(i);
                if (video.equals(o.optString("id"))) {
                    season = o.optInt("season");
                    episode = o.optInt("episode", o.optInt("number"));
                }
            }
            if (season < 0) return null;
            String best = null;
            int bs = 0, be = 0;
            for (int i = 0; videos != null && i < videos.length(); i++) {
                JSONObject o = videos.getJSONObject(i);
                int vs = o.optInt("season"), ve = o.optInt("episode", o.optInt("number"));
                if (vs <= 0 || (vs < season || vs == season && ve <= episode)) continue;   // specials, or already behind
                if (best == null || vs < bs || vs == bs && ve < be) {
                    best = o.optString("id");
                    bs = vs;
                    be = ve;
                }
            }
            return best == null || best.isEmpty() ? null : best;
        } catch (Exception e) {
            AppLog.w("next episode: " + e.getMessage());
            return null;
        }
    }

    /** Whether the add-on's manifest offers this resource for this type and id ("tt…"). */
    private static boolean serves(JSONObject m, String resource, String type, String id) {
        if (m == null) return false;
        JSONArray res = m.optJSONArray("resources"), types = m.optJSONArray("types"), prefixes = m.optJSONArray("idPrefixes");
        for (int i = 0; res != null && i < res.length(); i++) {
            Object r = res.opt(i);
            if (resource.equals(r)) return has(types, type) && prefixed(prefixes, id);
            if (r instanceof JSONObject && resource.equals(((JSONObject) r).optString("name"))) {
                JSONObject o = (JSONObject) r;
                JSONArray t = o.has("types") ? o.optJSONArray("types") : types, pr = o.has("idPrefixes") ? o.optJSONArray("idPrefixes") : prefixes;
                return has(t, type) && prefixed(pr, id);
            }
        }
        return false;
    }

    private static boolean has(JSONArray a, String v) {
        for (int i = 0; a != null && i < a.length(); i++) if (v.equals(a.optString(i))) return true;
        return false;
    }

    private static boolean prefixed(JSONArray prefixes, String id) {
        if (prefixes == null) return true;
        for (int i = 0; i < prefixes.length(); i++) if (id.startsWith(prefixes.optString(i))) return true;
        return false;
    }

    /** Waits for an add-on at most a few seconds; a slow or failing one just doesn't count. */
    private static <T> T await(Future<T> f) {
        try {
            return f.get(7, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private static String base(String manifestUrl) {
        return manifestUrl.substring(0, manifestUrl.length() - "/manifest.json".length());
    }

    private static JSONObject get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject post(String url, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try {
            c.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
            if (c.getResponseCode() != 200) throw new IOException("Stremio returned HTTP " + c.getResponseCode());
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Self-check of the language rules (no TV needed): java -cp out/classes io.freemytv.Streams */
    public static void main(String[] args) {
        check(audio("Torrentio 1080p\nThe Movie 2024 🇲🇽 🇬🇧") == 0);
        check(audio("[RD+] 4K Dual Latino-Inglés") == 0);
        check(audio("1080p WEB-DL\n👤 120 💾 2 GB") == 1);
        check(audio("1080p WEB H264-KBBQ\n🇬🇧 / 🇯🇵 / 🇮🇹 / 🇵🇹 / 🇪🇸 / 🇰🇷 / 🇫🇷") == 1);   // subtitles, not the audio
        check(audio("1080p Dual Latino 🇲🇽 / 🇬🇧 / 🇯🇵 / 🇰🇷") == 0);
        check(audio("1080p 🇪🇸 / 🇬🇧") == 2);
        check(quality("A S01E07 1080p WEB H264").equals("1080p") && quality("Movie 2160p HDR").equals("4K") && quality("XviD").isEmpty());
        check(size("👤 38 💾 2.28 GB ⚙️ EZTV").equals("2.28 GB") && size("no size").isEmpty());
        check(embeddedSubs("1080p WEB H264-KBBQ\n🇬🇧 / 🇯🇵 / 🇮🇹 / 🇵🇹 / 🇪🇸 / 🇰🇷") && !embeddedSubs("1080p 🇬🇧 / 🇯🇵 / 🇰🇷 / 🇫🇷")
                && !embeddedSubs("1080p 🇪🇸") && embeddedSubs("Show S01 1080p Multi-Sub"));
        check(cached("[TB+] Torrentio\n1080p") && cached("[TB⚡] Comet") && !cached("[TB download] Torrentio") && !cached("Torrentio 1080p"));
        check(audio("Película 🇪🇸 Castellano") == 2);
        check(audio("Movie.2024.1080p.SPA.ENG") == 2);
        check(audio("Platinum Edition 1080p") == 1);   // "lat" only as a whole word
        check(latamSubs("spn") && latamSubs("es-419") && latamSubs("es-MX") && !latamSubs("spa") && !latamSubs("es-ES"));
        check(heavy("Torrentio\n4k DV | HDR") && heavy("Movie.2024.2160p.WEB-DL") && heavy("UHD BluRay") && heavy("Dolby Vision"));
        check(!heavy("1080p WEB-DL HDR10") && !heavy("720p") && !heavy("Devil.May.Cry.1080p") && !heavy("Advent"));
        check(spanish("spa") && spanish("spn") && spanish("es-419") && spanish("Spanish") && !spanish("eng") && !spanish("kor"));
        System.out.println("ok");
    }

    private static void check(boolean ok) {
        if (!ok) throw new AssertionError();
    }
}
