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

    /** 0 Latin American audio, 1 no Spanish audio (original), 2 Spain's Spanish audio. */
    static int audio(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (LATAM.matcher(t).find()) return 0;
        return SPAIN.matcher(t).find() ? 2 : 1;
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
        boolean spanishAudio;
    }

    /** What to play: the streams to try in order (the next when one fails or is only a notice), and where you were. */
    static final class Choice {
        String type, id, video;             // "series", "tt1", "tt1:2:5"
        ArrayList<Stream> streams = new ArrayList<>();
        String subtitles;                   // .srt in Spanish (Latin American first), downloaded: its path, or null
        long startAt;                       // ms: where you left this movie or episode, 0 = from the start
    }

    /** "stremio:///detail/movie/tt1" or ".../series/tt1/tt1:2:5" → the streams to play, or null (no direct one). */
    static Choice best(String detailLink, java.io.File cacheDir) {
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
            JSONArray addons = post("https://api.strem.io/api/addonCollectionGet",
                    new JSONObject().put("type", "AddonCollectionGet").put("authKey", key).put("update", true))
                    .getJSONObject("result").getJSONArray("addons");
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
                JSONArray list = await(f);
                for (int j = 0; list != null && j < list.length(); j++) {
                    JSONObject s = list.optJSONObject(j);
                    if (s == null || !s.optString("url").startsWith("http")) continue;
                    Stream st = new Stream();
                    st.url = s.optString("url");
                    int a = audio(s.optString("name") + " " + s.optString("title") + " " + s.optString("description"));
                    st.spanishAudio = a != 1;
                    kinds.get(a).add(st);
                }
            }
            String subsUrl = spanishSubs(subs);
            if (subsUrl != null) c.subtitles = download(subsUrl, new java.io.File(cacheDir, "subtitles.srt"));
            c.streams.addAll(kinds.get(0));
            if (c.subtitles != null) c.streams.addAll(kinds.get(1));
            c.streams.addAll(kinds.get(2));
            if (c.subtitles == null) c.streams.addAll(kinds.get(1));   // nothing in Spanish: the original anyway
            JSONObject item = await(saved);
            JSONObject state = item == null ? null : item.optJSONObject("state");
            if (state != null && c.video.equals(state.optString("video_id")) && state.optLong("timeOffset") > 0)
                c.startAt = state.optLong("timeOffset");
            AppLog.w("streams for " + c.video + ": " + kinds.get(0).size() + " latino, " + kinds.get(1).size() + " original, "
                    + kinds.get(2).size() + " Spain" + (c.subtitles != null ? ", Spanish subtitles" : "") + ", from " + c.startAt / 1000 + " s");
            return c.streams.isEmpty() ? null : c;
        } catch (Exception e) {
            AppLog.w("streams: " + e.getMessage());
            return null;
        } finally {
            pool.shutdownNow();
        }
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
            if (v.length >= 3) st.put("season", Integer.parseInt(v[v.length - 2])).put("episode", Integer.parseInt(v[v.length - 1]));
            boolean done = position > duration * 0.92;
            st.put("timeOffset", done ? 0 : position);
            if (done) st.put("flaggedWatched", 1).put("timesWatched", st.optInt("timesWatched") + 1);
            item.put("_mtime", now);
            JSONObject res = post("https://api.strem.io/api/datastorePut", new JSONObject().put("authKey", key)
                    .put("collection", "libraryItem").put("changes", new JSONArray().put(item)));
            if (res.has("error")) throw new IOException(res.getJSONObject("error").optString("message"));
        } catch (Exception e) {
            AppLog.w("saving your place: " + e.getMessage());
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
        check(audio("Película 🇪🇸 Castellano") == 2);
        check(audio("Movie.2024.1080p.SPA.ENG") == 2);
        check(audio("Platinum Edition 1080p") == 1);   // "lat" only as a whole word
        check(latamSubs("spn") && latamSubs("es-419") && latamSubs("es-MX") && !latamSubs("spa") && !latamSubs("es-ES"));
        check(spanish("spa") && spanish("spn") && spanish("es-419") && spanish("Spanish") && !spanish("eng") && !spanish("kor"));
        System.out.println("ok");
    }

    private static void check(boolean ok) {
        if (!ok) throw new AssertionError();
    }
}
