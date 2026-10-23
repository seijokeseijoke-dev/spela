package se.spela.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hämtar radiostationer från den öppna katalogen Radio Browser (radio-browser.info).
 * Ingen API-nyckel krävs. Alla anrop körs på bakgrundstråd.
 */
final class RadioBrowser {

    /** Speglar av API:t. Prövas i tur och ordning tills en svarar. */
    private static final String[] HOSTS = {
            "de1.api.radio-browser.info",
            "de2.api.radio-browser.info",
            "fi1.api.radio-browser.info",
            "at1.api.radio-browser.info",
            "nl1.api.radio-browser.info"
    };

    private static final String USER_AGENT = "Spela/1.0 (Android)";
    private static final int TIMEOUT_MS = 8000;

    static final class Station {
        final String uuid;
        final String name;
        final String url;
        final String codec;
        final int bitrate;

        Station(String uuid, String name, String url, String codec, int bitrate) {
            this.uuid = uuid;
            this.name = name;
            this.url = url;
            this.codec = codec;
            this.bitrate = bitrate;
        }
    }

    private RadioBrowser() {
    }

    /**
     * Söker stationer för genre + decennium.
     *
     * @param genreTag  t.ex. "rock"
     * @param decadeTags t.ex. {"80s"} eller {"2000s", "00s"}
     */
    static List<Station> find(String genreTag, String[] decadeTags) throws Exception {
        List<Station> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. Stationer som har BÅDA taggarna, t.ex. "rock" och "80s".
        for (String d : decadeTags) {
            addAll(result, seen, search("tagList=" + enc(genreTag + "," + d)));
        }
        if (result.size() >= 10) return result;   // räcker gott, spara tid
        // 2. Stationer med en sammansatt tagg, t.ex. "80s rock".
        for (String d : decadeTags) {
            addAll(result, seen, search("tag=" + enc(d + " " + genreTag)));
        }
        // 3. Stationer vars namn innehåller t.ex. "80s rock".
        for (String d : decadeTags) {
            addAll(result, seen, search("name=" + enc(d + " " + genreTag)));
        }
        // 4. Reserv: bara genren, om decennieträffarna är för få.
        if (result.size() < 3) {
            addAll(result, seen, search("tagList=" + enc(genreTag)));
        }
        return result;
    }

    /** Talar om för katalogen att stationen spelades (API:ts önskemål, används för popularitet). */
    static void reportClick(String uuid) {
        try {
            get("/json/url/" + enc(uuid));
        } catch (Exception ignored) {
            // Inte kritiskt.
        }
    }

    private static List<Station> search(String query) throws Exception {
        String path = "/json/stations/search?" + query
                + "&hidebroken=true&order=votes&reverse=true&limit=25";
        JSONArray arr = new JSONArray(get(path));
        List<Station> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String url = o.optString("url_resolved", "");
            if (url.isEmpty()) url = o.optString("url", "");
            if (url.isEmpty()) continue;
            if (o.optInt("hls", 0) == 1) continue;          // HLS stöds dåligt av MediaPlayer
            String codec = o.optString("codec", "").toUpperCase();
            if (!playable(codec)) continue;
            list.add(new Station(
                    o.optString("stationuuid", url),
                    o.optString("name", "Okänd station").trim(),
                    url,
                    codec,
                    o.optInt("bitrate", 0)));
        }
        return list;
    }

    private static boolean playable(String codec) {
        return codec.equals("MP3") || codec.startsWith("AAC") || codec.equals("OGG")
                || codec.isEmpty() || codec.equals("UNKNOWN");
    }

    private static void addAll(List<Station> target, Set<String> seen, List<Station> src) {
        for (Station s : src) {
            if (seen.add(s.uuid)) target.add(s);
        }
    }

    private static String get(String path) throws Exception {
        Exception last = null;
        for (String host : HOSTS) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL("https://" + host + path).openConnection();
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setRequestProperty("User-Agent", USER_AGENT);
                if (c.getResponseCode() != 200) {
                    throw new Exception("HTTP " + c.getResponseCode() + " från " + host);
                }
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                return sb.toString();
            } catch (Exception e) {
                last = e;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw last != null ? last : new Exception("Ingen server svarade");
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }
}
