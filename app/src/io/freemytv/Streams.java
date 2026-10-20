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
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * "Watch" straight into Stremio's player on the best stream: Latin American Spanish audio first, then any stream when
 * there are Spanish subtitles (Stremio's player picks them by its own subtitle language), then Spain's Spanish audio.
 * Nothing in Spanish = null, and the title's page opens as before so you choose.
 * Asks the add-ons on your Stremio account (with "stremio_key"), whatever they are.
 */
final class Streams {
    private Streams() {}

    private static final String CINEMETA = "https://v3-cinemeta.strem.io/manifest.json";
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

    /** "stremio:///detail/movie/tt1" or ".../series/tt1/tt1:2:5" → the player link of the best stream, or null. */
    static String best(String detailLink) {
        String key = Config.STREMIO_KEY.trim();
        String prefix = "stremio:///detail/";
        if (key.isEmpty() || detailLink == null || !detailLink.startsWith(prefix)) return null;
        String[] p = detailLink.substring(prefix.length()).split("/");
        if (p.length < 2) return null;
        final String type = p[0], id = p[1], video = p.length > 2 ? p[2] : id;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            JSONArray addons = post("https://api.strem.io/api/addonCollectionGet",
                    new JSONObject().put("type", "AddonCollectionGet").put("authKey", key).put("update", true))
                    .getJSONObject("result").getJSONArray("addons");
            List<String> streamAddons = new ArrayList<>(), subAddons = new ArrayList<>();
            for (int i = 0; i < addons.length(); i++) {
                JSONObject a = addons.getJSONObject(i);
                String url = a.optString("transportUrl");
                if (!url.endsWith("/manifest.json") || !url.startsWith("http")) continue;
                if (serves(a.optJSONObject("manifest"), "stream", type, id)) streamAddons.add(url);
                if (serves(a.optJSONObject("manifest"), "subtitles", type, id)) subAddons.add(url);
            }
            String path = "/" + type + "/" + URLEncoder.encode(video, "UTF-8") + ".json";
            List<Future<JSONArray>> streams = new ArrayList<>(), subs = new ArrayList<>();
            for (String u : streamAddons) streams.add(pool.submit(() -> get(base(u) + "/stream" + path).optJSONArray("streams")));
            for (String u : subAddons) subs.add(pool.submit(() -> get(base(u) + "/subtitles" + path).optJSONArray("subtitles")));

            // The first stream of each kind, in your add-ons' order (each add-on already sorts its own by quality).
            JSONObject[] first = new JSONObject[3];
            String[] from = new String[3];
            for (int i = 0; i < streams.size(); i++) {
                JSONArray list = await(streams.get(i));
                for (int j = 0; list != null && j < list.length(); j++) {
                    JSONObject s = list.optJSONObject(j);
                    if (s == null || (!s.has("url") && !s.has("infoHash"))) continue;
                    int a = audio(s.optString("name") + " " + s.optString("title") + " " + s.optString("description"));
                    if (first[a] == null) {
                        first[a] = s;
                        from[a] = streamAddons.get(i);
                    }
                }
            }
            int pick = first[0] != null ? 0 : -1;
            if (pick < 0 && (first[1] != null || first[2] != null) && hasSpanishSubs(subs)) pick = first[1] != null ? 1 : 2;
            if (pick < 0 && first[2] != null) pick = 2;
            AppLog.w("streams for " + video + ": " + (pick < 0 ? "nothing in Spanish, opening the page" : "kind " + pick));
            if (pick < 0) return null;
            return "stremio:///player/" + enc(encode(first[pick])) + "/" + enc(from[pick]) + "/" + enc(CINEMETA) + "/"
                    + enc(type) + "/" + enc(id) + "/" + enc(video);
        } catch (Exception e) {
            AppLog.w("streams: " + e.getMessage());
            return null;
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean hasSpanishSubs(List<Future<JSONArray>> subs) {
        for (Future<JSONArray> f : subs) {
            JSONArray list = await(f);
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject s = list.optJSONObject(i);
                if (s != null && spanish(s.optString("lang"))) return true;
            }
        }
        return false;
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
    private static JSONArray await(Future<JSONArray> f) {
        try {
            return f.get(7, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private static String base(String manifestUrl) {
        return manifestUrl.substring(0, manifestUrl.length() - "/manifest.json".length());
    }

    /** As Stremio encodes a stream for its player link: the JSON in zlib (stored), then Base64. */
    private static String encode(JSONObject stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(out, new Deflater(Deflater.NO_COMPRESSION))) {
            z.write(stream.toString().getBytes(StandardCharsets.UTF_8));
        }
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
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
        check(spanish("spa") && spanish("spn") && spanish("es-419") && spanish("Spanish") && !spanish("eng") && !spanish("kor"));
        System.out.println("ok");
    }

    private static void check(boolean ok) {
        if (!ok) throw new AssertionError();
    }
}
