package se.spela.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Låtlistan. Varje låt sparas som artist + titel (oberoende av tjänst), plus ett
 * frivilligt YouTube-ID som sparas när låten slagits upp en gång – det sparar sökkvot.
 * Lagras som JSON i appens privata mapp.
 */
final class Playlist {

    static final class Song {
        String artist;
        String title;
        String videoId;     // null tills låten slagits upp

        Song(String artist, String title, String videoId) {
            this.artist = artist == null ? "" : artist.trim();
            this.title = title == null ? "" : title.trim();
            this.videoId = videoId;
        }

        /** "Artist - Titel" eller bara titeln. */
        String display() {
            return artist.isEmpty() ? title : artist + " - " + title;
        }

        String searchQuery() {
            return (artist + " " + title).trim();
        }

        /** Tolkar "Artist - Titel" (vanligt format från radio och YouTube). */
        static Song parse(String text) {
            String t = text.trim();
            for (String sep : new String[]{" - ", " – ", " — ", " | "}) {
                int i = t.indexOf(sep);
                if (i > 0) {
                    return new Song(t.substring(0, i), t.substring(i + sep.length()), null);
                }
            }
            return new Song("", t, null);
        }
    }

    private Playlist() {
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "latlista.json");
    }

    static List<Song> load(Context c) {
        List<Song> list = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return list;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r < 0) break;
                off += r;
            }
            JSONArray arr = new JSONArray(new String(b, 0, off, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Song(o.optString("artist"), o.optString("title"),
                        o.has("videoId") ? o.optString("videoId") : null));
            }
        } catch (Exception ignored) {
            // trasig fil – börja om med tom lista hellre än att krascha
        }
        return list;
    }

    static void save(Context c, List<Song> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Song s : list) {
                JSONObject o = new JSONObject();
                o.put("artist", s.artist);
                o.put("title", s.title);
                if (s.videoId != null) o.put("videoId", s.videoId);
                arr.put(o);
            }
            File tmp = new File(c.getFilesDir(), "latlista.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(arr.toString(1).getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file(c));
        } catch (Exception ignored) {
        }
    }

    /** Lägger till om låten inte redan finns. Returnerar true om den lades till. */
    static boolean add(Context c, Song s) {
        List<Song> list = load(c);
        for (Song x : list) {
            if (x.display().equalsIgnoreCase(s.display())) return false;
        }
        list.add(s);
        save(c, list);
        return true;
    }

    /** En låt per rad, "Artist - Titel" – för delning och säkerhetskopia. */
    static String exportText(List<Song> list) {
        StringBuilder sb = new StringBuilder();
        for (Song s : list) sb.append(s.display()).append('\n');
        return sb.toString();
    }

    /** Importerar text med en låt per rad. Returnerar antal nya låtar. */
    static int importText(Context c, String text) {
        List<Song> list = load(c);
        int added = 0;
        for (String line : text.split("\\r?\\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            Song s = Song.parse(l);
            boolean dup = false;
            for (Song x : list) {
                if (x.display().equalsIgnoreCase(s.display())) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                list.add(s);
                added++;
            }
        }
        save(c, list);
        return added;
    }
}
