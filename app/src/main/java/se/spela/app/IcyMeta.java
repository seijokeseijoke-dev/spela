package se.spela.app;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.net.ssl.SSLSocketFactory;

/**
 * Läser "nu spelas" (artist – titel) ur en radioström.
 *
 * De flesta Shoutcast/Icecast-strömmar skickar titeln inbäddad i ljudet när klienten
 * ber om det med "Icy-MetaData: 1". Vi öppnar en kort extra anslutning, läser fram till
 * första metadatablocket (oftast 8–32 kB) och kopplar ner. Det kostar ca 1–2 MB i timmen
 * vid kontroll var 30:e sekund, och fungerar oavsett om ljudet går i telefonen eller på Sonos.
 *
 * Råa socketar används eftersom vissa servrar svarar "ICY 200 OK", vilket HttpURLConnection inte förstår.
 */
final class IcyMeta {

    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_METAINT = 256 * 1024;

    private IcyMeta() {
    }

    /** Returnerar titeln, eller null om strömmen inte skickar någon. */
    static String fetchTitle(String streamUrl) {
        String url = streamUrl;
        for (int hop = 0; hop < 3; hop++) {      // följ högst några omdirigeringar
            Socket s = null;
            try {
                URL u = new URL(url);
                boolean tls = "https".equalsIgnoreCase(u.getProtocol());
                int port = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);
                s = new Socket();
                s.connect(new InetSocketAddress(u.getHost(), port), TIMEOUT_MS);
                s.setSoTimeout(TIMEOUT_MS);
                if (tls) {
                    // Värdnamnet skickas med (SNI), annars nekar många servrar anslutningen.
                    s = ((SSLSocketFactory) SSLSocketFactory.getDefault())
                            .createSocket(s, u.getHost(), port, true);
                    s.setSoTimeout(TIMEOUT_MS);
                }
                String path = u.getFile().isEmpty() ? "/" : u.getFile();
                String req = "GET " + path + " HTTP/1.0\r\n"
                        + "Host: " + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + "\r\n"
                        + "User-Agent: Spela/1.0 (Android)\r\n"
                        + "Icy-MetaData: 1\r\n"
                        + "Accept: */*\r\n"
                        + "Connection: close\r\n\r\n";
                OutputStream os = s.getOutputStream();
                os.write(req.getBytes(StandardCharsets.US_ASCII));
                os.flush();

                InputStream in = new BufferedInputStream(s.getInputStream());
                String status = readLine(in);
                if (status == null) return null;
                int metaint = -1;
                String location = null;
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    int c = line.indexOf(':');
                    if (c <= 0) continue;
                    String k = line.substring(0, c).trim().toLowerCase(Locale.ROOT);
                    String v = line.substring(c + 1).trim();
                    if (k.equals("icy-metaint")) {
                        try {
                            metaint = Integer.parseInt(v);
                        } catch (NumberFormatException ignored) {
                        }
                    } else if (k.equals("location")) {
                        location = v;
                    }
                }
                String[] parts = status.split(" ");
                int code = parts.length > 1 ? parseInt(parts[1]) : 0;
                if (code >= 300 && code < 400 && location != null) {
                    url = new URL(u, location).toString();
                    continue;
                }
                if (metaint <= 0 || metaint > MAX_METAINT) return null;

                skipFully(in, metaint);
                int len = in.read() * 16;
                if (len <= 0) return null;      // tomt block = titeln oförändrad/saknas
                byte[] meta = new byte[len];
                readFully(in, meta);
                return parseTitle(meta);
            } catch (Exception e) {
                return null;
            } finally {
                if (s != null) {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return null;
    }

    static String parseTitle(byte[] meta) {
        String text = new String(meta, StandardCharsets.UTF_8);
        if (text.indexOf('�') >= 0) {                 // inte giltig UTF-8 → äldre teckenkodning
            text = new String(meta, StandardCharsets.ISO_8859_1);
        }
        int i = text.indexOf("StreamTitle='");
        if (i < 0) return null;
        int start = i + 13;
        int end = text.indexOf("';", start);
        if (end < 0) end = text.lastIndexOf('\'');
        if (end <= start) return null;
        String t = text.substring(start, end).trim();
        // Filtrera bort reklam/jinglar som saknar artist-titel-form och tomma värden.
        if (t.isEmpty() || t.equals("-") || t.equalsIgnoreCase("unknown")) return null;
        return t;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) break;
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    private static void skipFully(InputStream in, int n) throws Exception {
        byte[] buf = new byte[8192];
        int left = n;
        while (left > 0) {
            int r = in.read(buf, 0, Math.min(buf.length, left));
            if (r < 0) throw new Exception("Strömmen tog slut");
            left -= r;
        }
    }

    private static void readFully(InputStream in, byte[] b) throws Exception {
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) throw new Exception("Strömmen tog slut");
            off += r;
        }
    }
}
