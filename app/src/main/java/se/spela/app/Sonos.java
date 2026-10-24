package se.spela.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Styr Sonos-högtalare lokalt i hemnätet via UPnP/SOAP på port 1400.
 * Inget konto, ingen molntjänst, inga bibliotek. Alla metoder är blockerande
 * och ska köras på bakgrundstråd.
 *
 * Gränssnittet är inte officiellt dokumenterat av Sonos men har fungerat i många år.
 */
final class Sonos {

    static final class Device {
        final String uuid;   // t.ex. RINCON_000E58XXXXXX01400
        final String ip;
        final String name;   // rumsnamn, t.ex. "Vardagsrum"

        Device(String uuid, String ip, String name) {
            this.uuid = uuid;
            this.ip = ip;
            this.name = name;
        }
    }

    /** Fel som Sonos själv returnerar (UPnP-felkod), t.ex. 714 = format stöds inte, 800 = grupperad. */
    static final class SonosError extends Exception {
        final int code;

        SonosError(int code) {
            super("Sonos-fel " + code);
            this.code = code;
        }
    }

    private static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String AVT_PATH = "/MediaRenderer/AVTransport/Control";
    private static final String RC = "urn:schemas-upnp-org:service:RenderingControl:1";
    private static final String RC_PATH = "/MediaRenderer/RenderingControl/Control";
    private static final String ZGT = "urn:schemas-upnp-org:service:ZoneGroupTopology:1";
    private static final String ZGT_PATH = "/ZoneGroupTopology/Control";
    private static final int HTTP_TIMEOUT_MS = 4000;

    private Sonos() {
    }

    // ================= Sparat val =================

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("spela", Context.MODE_PRIVATE);
    }

    /** Vald högtalare, eller null = spela i telefonen. */
    static Device saved(Context c) {
        SharedPreferences p = prefs(c);
        String uuid = p.getString("sonos_uuid", null);
        String ip = p.getString("sonos_ip", null);
        if (uuid == null || ip == null) return null;
        return new Device(uuid, ip, p.getString("sonos_name", "Sonos"));
    }

    static void save(Context c, Device d) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (d == null) {
            e.remove("sonos_uuid").remove("sonos_ip").remove("sonos_name");
        } else {
            e.putString("sonos_uuid", d.uuid).putString("sonos_ip", d.ip).putString("sonos_name", d.name);
        }
        e.apply();
    }

    // ================= Sökning i nätverket =================

    /** Hittar Sonos-rum i nätverket (ett per rum/grupp, adresserat till rummets huvudhögtalare). */
    static List<Device> discover(Context ctx) throws Exception {
        Set<String> ips = ssdp(ctx);
        // Topologin från valfri högtalare beskriver hela systemet, inkl. stereopar och grupper.
        for (String ip : ips) {
            try {
                List<Device> list = topology(ip);
                if (!list.isEmpty()) return list;
            } catch (Exception ignored) {
                // prova nästa
            }
        }
        // Reserv: fråga varje högtalare för sig.
        Map<String, Device> byName = new LinkedHashMap<>();
        for (String ip : ips) {
            try {
                Device d = describe(ip);
                if (!byName.containsKey(d.name)) byName.put(d.name, d);
            } catch (Exception ignored) {
            }
        }
        return new ArrayList<>(byName.values());
    }

    /** Frågar en känd IP-adress om den är en Sonos (för manuell inmatning). */
    static Device describe(String ip) throws Exception {
        String xml = httpGet("http://" + ip + ":1400/xml/device_description.xml");
        String room = first(xml, "<roomName>(.*?)</roomName>");
        String udn = first(xml, "<UDN>uuid:(RINCON_[^<]+)</UDN>");
        if (udn == null) throw new Exception("Inte en Sonos");
        return new Device(udn, ip, room != null ? unescape(room) : ip);
    }

    /**
     * Kontrollerar att den sparade högtalaren svarar. Om IP-adressen har ändrats
     * (routern delar ut nya adresser) letas högtalaren upp igen via sitt ID.
     * Returnerar null om den inte finns i nätverket.
     */
    static Device resolve(Context ctx, Device saved) {
        try {
            transportState(saved);
            return saved;
        } catch (SonosError e) {
            return saved;      // svarar, bara ett tillståndsfel
        } catch (Exception e) {
            // svarar inte – leta igen
        }
        try {
            for (Device d : discover(ctx)) {
                if (d.uuid.equals(saved.uuid) || d.name.equals(saved.name)) {
                    save(ctx, d);
                    return d;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Set<String> ssdp(Context ctx) throws Exception {
        Set<String> ips = new LinkedHashSet<>();
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        if (wm != null) {
            lock = wm.createMulticastLock("spela:ssdp");
            lock.setReferenceCounted(false);
            lock.acquire();
        }
        DatagramSocket sock = new DatagramSocket();
        try {
            sock.setSoTimeout(400);
            byte[] msg = ("M-SEARCH * HTTP/1.1\r\n"
                    + "HOST: 239.255.255.250:1900\r\n"
                    + "MAN: \"ssdp:discover\"\r\n"
                    + "MX: 1\r\n"
                    + "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            byte[] buf = new byte[2048];
            long end = System.currentTimeMillis() + 3000;
            int sent = 0;
            while (System.currentTimeMillis() < end) {
                if (sent < 3) {   // UDP kan tappas – skicka några gånger
                    sock.send(new DatagramPacket(msg, msg.length, group, 1900));
                    sent++;
                }
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    sock.receive(p);
                    String r = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    Matcher m = Pattern.compile("(?im)^LOCATION:\\s*(\\S+)").matcher(r);
                    if (m.find()) {
                        ips.add(new URL(m.group(1)).getHost());
                    } else {
                        ips.add(p.getAddress().getHostAddress());
                    }
                } catch (SocketTimeoutException ignored) {
                    // fortsätt lyssna
                }
            }
        } finally {
            sock.close();
            if (lock != null) lock.release();
        }
        return ips;
    }

    private static List<Device> topology(String ip) throws Exception {
        String resp = soap(ip, ZGT_PATH, ZGT, "GetZoneGroupState", "");
        String xml = unescape(resp);
        List<Device> list = new ArrayList<>();
        Matcher g = Pattern.compile("<ZoneGroup\\b([^>]*)>(.*?)</ZoneGroup>", Pattern.DOTALL).matcher(xml);
        while (g.find()) {
            String coord = attr(g.group(1), "Coordinator");
            if (coord == null) continue;
            String coordIp = null;
            String coordName = null;
            Set<String> rooms = new LinkedHashSet<>();
            Matcher m = Pattern.compile("<ZoneGroupMember\\b([^>]*)>").matcher(g.group(2));
            while (m.find()) {
                String a = m.group(1);
                if ("1".equals(attr(a, "Invisible"))) continue;   // t.ex. sub, bakhögtalare
                String name = attr(a, "ZoneName");
                if (name != null) rooms.add(name);
                if (coord.equals(attr(a, "UUID"))) {
                    String loc = attr(a, "Location");
                    if (loc != null) coordIp = new URL(loc).getHost();
                    coordName = name;
                }
            }
            if (coordIp == null || coordName == null) continue;
            String label = rooms.size() > 1 ? coordName + " + " + (rooms.size() - 1) : coordName;
            list.add(new Device(coord, coordIp, label));
        }
        return list;
    }

    // ================= Uppspelning =================

    /**
     * Adressvarianter att pröva för en radioström. Sonos behöver ofta ett eget prefix
     * för att behandla en ström som radio och inte som en fil.
     */
    static List<String> candidateUris(String url, String codec) {
        List<String> list = new ArrayList<>();
        String lower = url.toLowerCase(Locale.ROOT);
        String noScheme = url.replaceFirst("(?i)^https?://", "");
        if (lower.startsWith("https://")) {
            list.add(url);
            list.add("x-rincon-mp3radio://" + noScheme);   // samma ström via http, om servern erbjuder det
        } else {
            list.add("x-rincon-mp3radio://" + noScheme);
            list.add(url);
        }
        if (codec != null && codec.startsWith("AAC")) {
            list.add(1, "aac://" + url);
        }
        return list;
    }

    static void play(Device d, String uri, String title) throws Exception {
        String didl = "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\""
                + " xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\""
                + " xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">"
                + "<item id=\"R:0/0/0\" parentID=\"R:0/0\" restricted=\"true\">"
                + "<dc:title>" + esc(title) + "</dc:title>"
                + "<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>"
                + "<desc id=\"cdudn\" nameSpace=\"urn:schemas-rinconnetworks-com:metadata-1-0/\">"
                + "SA_RINCON65031_</desc></item></DIDL-Lite>";
        soap(d.ip, AVT_PATH, AVT, "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>" + esc(uri) + "</CurrentURI>"
                        + "<CurrentURIMetaData>" + esc(didl) + "</CurrentURIMetaData>");
        soap(d.ip, AVT_PATH, AVT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
    }

    /**
     * Väntar tills högtalaren faktiskt spelar. Sonos accepterar ofta adressen
     * men faller tillbaka till STOPPED om strömmen inte går att spela.
     */
    static boolean waitUntilPlaying(Device d, long timeoutMs) throws Exception {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(1000);
            String st = transportState(d);
            if ("PLAYING".equals(st)) return true;
            if ("STOPPED".equals(st) && System.currentTimeMillis() - start > 3500) return false;
        }
        return false;
    }

    static String transportState(Device d) throws Exception {
        String r = soap(d.ip, AVT_PATH, AVT, "GetTransportInfo", "<InstanceID>0</InstanceID>");
        String s = first(r, "<CurrentTransportState>(.*?)</CurrentTransportState>");
        return s != null ? s : "";
    }

    static void stop(Device d) {
        try {
            soap(d.ip, AVT_PATH, AVT, "Stop", "<InstanceID>0</InstanceID>");
        } catch (Exception ignored) {
            // redan stoppad eller inte nåbar
        }
    }

    static int getVolume(Device d) throws Exception {
        String r = soap(d.ip, RC_PATH, RC, "GetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel>");
        String v = first(r, "<CurrentVolume>(\\d+)</CurrentVolume>");
        return v != null ? Integer.parseInt(v) : 20;
    }

    static void setVolume(Device d, int vol) throws Exception {
        int v = Math.max(0, Math.min(100, vol));
        soap(d.ip, RC_PATH, RC, "SetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>" + v + "</DesiredVolume>");
    }

    // ================= HTTP / SOAP =================

    private static String soap(String ip, String path, String service, String action, String args)
            throws Exception {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + service + "\">" + args + "</u:" + action + ">"
                + "</s:Body></s:Envelope>";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + ":1400" + path).openConnection();
        try {
            c.setConnectTimeout(HTTP_TIMEOUT_MS);
            c.setReadTimeout(HTTP_TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPACTION", "\"" + service + "#" + action + "\"");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            int code = c.getResponseCode();
            String text = read(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code != 200) {
                String ec = first(text, "<errorCode>(\\d+)</errorCode>");
                throw new SonosError(ec != null ? Integer.parseInt(ec) : code);
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(HTTP_TIMEOUT_MS);
            c.setReadTimeout(HTTP_TIMEOUT_MS);
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
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

    // ================= Textverktyg =================

    private static String first(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? unescape(m.group(1)) : null;
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }
}
