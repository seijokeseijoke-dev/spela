package se.spela.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Sökning via YouTube Data API v3 (officiellt, gratis med egen nyckel).
 * En sökning kostar 100 enheter av gratiskvoten 10 000/dygn ≈ 100 sökningar per dygn.
 * Nyckeln matas in i appen och sparas bara i telefonen – den ligger inte i GitHub-repot.
 */
final class YouTube {

    static final class Hit {
        final String videoId;
        final String title;
        final String channel;

        Hit(String videoId, String title, String channel) {
            this.videoId = videoId;
            this.title = title;
            this.channel = channel;
        }
    }

    static final class QuotaException extends Exception {
        QuotaException(String m) {
            super(m);
        }
    }

    private YouTube() {
    }

    static String key(Context c) {
        return c.getApplicationContext().getSharedPreferences("spela", Context.MODE_PRIVATE)
                .getString("youtube_key", "");
    }

    static void saveKey(Context c, String k) {
        c.getApplicationContext().getSharedPreferences("spela", Context.MODE_PRIVATE)
                .edit().putString("youtube_key", k.trim()).apply();
    }

    static List<Hit> search(Context c, String query, int max) throws Exception {
        String key = key(c);
        if (key.isEmpty()) throw new QuotaException("Ingen YouTube-nyckel inlagd");
        String url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video"
                + "&videoEmbeddable=true&videoCategoryId=10&maxResults=" + max
                + "&q=" + URLEncoder.encode(query, "UTF-8")
                + "&key=" + URLEncoder.encode(key, "UTF-8");
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        // Krävs om nyckeln är begränsad till Android-appar i Google Cloud.
        con.setRequestProperty("X-Android-Package", c.getPackageName());
        try {
            int code = con.getResponseCode();
            String body = read(code < 400 ? con.getInputStream() : con.getErrorStream());
            if (code == 403) {
                String reason = body.contains("quotaExceeded") ? "Dagens YouTube-kvot är slut (nollställs kl 09 svensk tid)"
                        : "YouTube nekade nyckeln. Kontrollera att YouTube Data API v3 är aktiverat.";
                throw new QuotaException(reason);
            }
            if (code == 400) throw new QuotaException("Ogiltig YouTube-nyckel");
            if (code != 200) throw new Exception("HTTP " + code);
            JSONArray items = new JSONObject(body).optJSONArray("items");
            List<Hit> hits = new ArrayList<>();
            if (items == null) return hits;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                String id = it.optJSONObject("id") != null ? it.getJSONObject("id").optString("videoId") : "";
                JSONObject sn = it.optJSONObject("snippet");
                if (id.isEmpty() || sn == null) continue;
                hits.add(new Hit(id, unescape(sn.optString("title")), unescape(sn.optString("channelTitle"))));
            }
            return hits;
        } finally {
            con.disconnect();
        }
    }

    /** API:t returnerar titlar HTML-kodade, t.ex. &amp;#39; för apostrof. */
    private static String unescape(String s) {
        return s.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&");
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = is.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }
}
