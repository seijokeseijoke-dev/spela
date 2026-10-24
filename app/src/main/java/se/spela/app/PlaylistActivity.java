package se.spela.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Låtlista som spelas via YouTubes officiella inbäddade spelare.
 *
 * Enligt YouTubes villkor måste spelaren synas och får inte spela i bakgrunden,
 * därför pausas den när skärmen släcks eller appen lämnas, och skärmen hålls tänd under uppspelning.
 */
public class PlaylistActivity extends Activity {

    private static final int GREEN = Color.parseColor("#2E7D32");
    private static final int GRAY = Color.parseColor("#D9D9D9");
    private static final int CURRENT = Color.parseColor("#C8E6C9");
    /** Sidans ursprung. YouTube kräver en giltig avsändare (Referer), annars fel 153. */
    private static final String ORIGIN = "https://se.spela.app/";

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private List<Playlist.Song> songs;
    private int current = -1;
    private boolean playing = false;

    private LinearLayout listView;
    private TextView status;
    private WebView web;
    private LinearLayout playerBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        songs = Playlist.load(this);
        setContentView(buildUi());
        refreshList();
        if (YouTube.key(this).isEmpty()) {
            status.setText("Lägg in en YouTube-nyckel för att kunna söka och spela (knappen Nyckel).");
        }
    }

    // ================= Gränssnitt =================

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(16));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("Låtlista");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setTextColor(Color.BLACK);
        status.setPadding(dp(10), dp(10), dp(10), dp(10));
        status.setMinHeight(dp(56));
        GradientDrawable box = new GradientDrawable();
        box.setColor(GRAY);
        box.setStroke(dp(2), GREEN);
        status.setBackground(box);
        LinearLayout.LayoutParams sp = full();
        sp.topMargin = dp(8);
        root.addView(status, sp);

        // Spelare (dold tills något spelas). Höjd 16:9 av skärmbredden.
        playerBox = new LinearLayout(this);
        playerBox.setOrientation(LinearLayout.VERTICAL);
        playerBox.setVisibility(View.GONE);
        int w = getResources().getDisplayMetrics().widthPixels - dp(32);
        web = new WebView(this);
        setupWebView();
        playerBox.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, w * 9 / 16));
        LinearLayout ctrl = row();
        ctrl.addView(pill("Föregående", v -> playIndex(current - 1)), weight());
        ctrl.addView(pill("Nästa", v -> playIndex(current + 1)), weight());
        ctrl.addView(pill("Stopp", v -> stopPlayback()), weight());
        LinearLayout.LayoutParams cp = full();
        cp.topMargin = dp(8);
        playerBox.addView(ctrl, cp);
        LinearLayout.LayoutParams pp = full();
        pp.topMargin = dp(10);
        root.addView(playerBox, pp);

        LinearLayout r1 = row();
        r1.addView(pill("▶ Spela listan", v -> playIndex(0)), weight());
        r1.addView(pill("Sök låt", v -> askSearch()), weight());
        LinearLayout.LayoutParams rp = full();
        rp.topMargin = dp(12);
        root.addView(r1, rp);

        LinearLayout r2 = row();
        r2.addView(pill("Importera", v -> askImport()), weight());
        r2.addView(pill("Dela", v -> share()), weight());
        r2.addView(pill("Nyckel", v -> askKey()), weight());
        LinearLayout.LayoutParams rp2 = full();
        rp2.topMargin = dp(8);
        root.addView(r2, rp2);

        TextView hint = new TextView(this);
        hint.setText("Tryck på en låt för att spela den. Håll inne för fler val.");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setTextColor(Color.GRAY);
        hint.setPadding(dp(4), dp(12), dp(4), dp(4));
        root.addView(hint);

        listView = new LinearLayout(this);
        listView.setOrientation(LinearLayout.VERTICAL);
        root.addView(listView, full());

        TextView back = new TextView(this);
        back.setText("← Tillbaka till radion");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        back.setTextColor(GREEN);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, dp(20), 0, dp(8));
        back.setOnClickListener(v -> finish());
        root.addView(back, full());

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.addView(root);
        return scroll;
    }

    private void refreshList() {
        listView.removeAllViews();
        if (songs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Listan är tom. Spara låtar från radion, sök, eller importera.");
            empty.setTextColor(Color.GRAY);
            empty.setPadding(dp(4), dp(12), dp(4), dp(12));
            listView.addView(empty);
            return;
        }
        for (int i = 0; i < songs.size(); i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText((i + 1) + ".  " + songs.get(i).display());
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            t.setTextColor(Color.BLACK);
            t.setPadding(dp(10), dp(12), dp(10), dp(12));
            GradientDrawable g = new GradientDrawable();
            g.setColor(i == current ? CURRENT : Color.WHITE);
            g.setStroke(dp(1), Color.parseColor("#BDBDBD"));
            t.setBackground(g);
            t.setOnClickListener(v -> playIndex(idx));
            t.setOnLongClickListener(v -> {
                songMenu(idx);
                return true;
            });
            listView.addView(t, full());
        }
    }

    // ================= YouTube-spelaren =================

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView() {
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setBackgroundColor(Color.BLACK);
        web.addJavascriptInterface(new Bridge(), "Android");
        String html = "<!doctype html><html><head>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<meta name='referrer' content='strict-origin-when-cross-origin'>"
                + "<style>html,body{margin:0;height:100%;background:#000}"
                + "#wrap{position:relative;width:100%;height:100%}"
                + "iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}</style>"
                + "</head><body><div id='wrap'></div><script>"
                + "var player=null,ready=false,pending=null;"
                + "function onYouTubeIframeAPIReady(){ready=true;if(pending){var p=pending;pending=null;play(p);}}"
                + "function play(id){"
                + " if(!ready){pending=id;return;}"
                + " if(player){player.loadVideoById(id);return;}"
                + " var f=document.createElement('iframe');"
                + " f.id='yt';f.allow='autoplay; encrypted-media';"
                + " f.referrerPolicy='strict-origin-when-cross-origin';"
                + " f.src='https://www.youtube.com/embed/'+id+'?enablejsapi=1&autoplay=1&playsinline=1&rel=0&origin='"
                + "+encodeURIComponent(location.origin);"
                + " document.getElementById('wrap').appendChild(f);"
                + " player=new YT.Player('yt',{events:{"
                + "  onStateChange:function(e){if(e.data===0)Android.ended();if(e.data===1)Android.started();},"
                + "  onError:function(e){Android.error(e.data);}}});"
                + "}"
                + "function stop(){pending=null;if(player&&player.stopVideo)player.stopVideo();}"
                + "</script><script src='https://www.youtube.com/iframe_api'></script></body></html>";
        web.loadDataWithBaseURL(ORIGIN, html, "text/html", "utf-8", null);
    }

    /** Anrop från JavaScript (kommer på en bakgrundstråd). */
    private class Bridge {
        @JavascriptInterface
        public void ended() {
            runOnUiThread(() -> {
                if (playing) playIndex(current + 1);
            });
        }

        @JavascriptInterface
        public void started() {
            runOnUiThread(() -> {
                if (current >= 0 && current < songs.size()) {
                    status.setText("Spelar " + (current + 1) + "/" + songs.size() + ": " + songs.get(current).display());
                }
            });
        }

        @JavascriptInterface
        public void error(int code) {
            runOnUiThread(() -> {
                if (!playing) return;
                String why;
                if (code == 101 || code == 150) why = "får inte spelas utanför YouTube";
                else if (code == 100) why = "finns inte längre";
                else if (code == 153) why = "YouTube nekade spelaren (fel 153)";
                else why = "fel " + code;
                toast(current >= 0 && current < songs.size()
                        ? songs.get(current).display() + ": " + why + " – hoppar över" : why);
                if (code == 100 && current >= 0 && current < songs.size()) {
                    songs.get(current).videoId = null;   // slå upp på nytt nästa gång
                    Playlist.save(PlaylistActivity.this, songs);
                }
                if (code == 153) {
                    stopPlayback();
                    status.setText("YouTube tillåter inte uppspelning i appen just nu (fel 153).");
                    return;
                }
                playIndex(current + 1);
            });
        }
    }

    private void playIndex(int i) {
        if (songs.isEmpty()) {
            toast("Listan är tom");
            return;
        }
        if (i < 0) i = 0;
        if (i >= songs.size()) {
            stopPlayback();
            status.setText("Listan är slut.");
            return;
        }
        if (YouTube.key(this).isEmpty() && songs.get(i).videoId == null) {
            askKey();
            return;
        }
        stopRadio();
        current = i;
        playing = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        playerBox.setVisibility(View.VISIBLE);
        refreshList();
        final Playlist.Song song = songs.get(i);
        final int idx = i;
        status.setText("Letar upp " + song.display() + "…");
        if (song.videoId != null) {
            loadVideo(song.videoId);
            return;
        }
        bg.execute(() -> {
            try {
                List<YouTube.Hit> hits = YouTube.search(this, song.searchQuery(), 1);
                runOnUiThread(() -> {
                    if (!playing || current != idx) return;
                    if (hits.isEmpty()) {
                        toast("Hittade inte " + song.display() + " – hoppar över");
                        playIndex(idx + 1);
                        return;
                    }
                    song.videoId = hits.get(0).videoId;
                    Playlist.save(this, songs);
                    loadVideo(song.videoId);
                });
            } catch (YouTube.QuotaException e) {
                runOnUiThread(() -> {
                    stopPlayback();
                    status.setText(e.getMessage());
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    stopPlayback();
                    status.setText("Ingen kontakt med YouTube. Kontrollera internet.");
                });
            }
        });
    }

    private void loadVideo(String id) {
        web.onResume();
        web.evaluateJavascript("play('" + id.replaceAll("[^A-Za-z0-9_-]", "") + "')", null);
    }

    private void stopPlayback() {
        playing = false;
        web.evaluateJavascript("stop()", null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status.setText("Stoppad");
    }

    /** Radio och låtlista ska inte låta samtidigt. */
    private void stopRadio() {
        if (PlayerService.isRunning()) {
            startService(new Intent(this, PlayerService.class).setAction(PlayerService.ACTION_STOP));
        }
    }

    @Override
    protected void onPause() {
        // YouTubes villkor: ingen uppspelning i bakgrunden.
        if (playing) {
            stopPlayback();
            status.setText("Pausad (YouTube får inte spela i bakgrunden). Tryck på en låt för att fortsätta.");
        }
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        web.destroy();
        super.onDestroy();
    }

    // ================= Sök, importera, dela, nyckel =================

    private void askSearch() {
        if (YouTube.key(this).isEmpty()) {
            askKey();
            return;
        }
        EditText in = new EditText(this);
        in.setHint("Artist och/eller låttitel");
        in.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Sök låt")
                .setView(in)
                .setPositiveButton("Sök", (d, w) -> {
                    String q = in.getText().toString().trim();
                    if (!q.isEmpty()) search(q);
                })
                .setNegativeButton("Avbryt", null)
                .show();
    }

    private void search(String q) {
        status.setText("Söker \"" + q + "\"…");
        bg.execute(() -> {
            try {
                List<YouTube.Hit> hits = YouTube.search(this, q, 8);
                runOnUiThread(() -> showHits(q, hits));
            } catch (YouTube.QuotaException e) {
                runOnUiThread(() -> status.setText(e.getMessage()));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Ingen kontakt med YouTube. Kontrollera internet."));
            }
        });
    }

    private void showHits(String q, List<YouTube.Hit> hits) {
        if (hits.isEmpty()) {
            status.setText("Inga träffar på \"" + q + "\"");
            return;
        }
        status.setText(hits.size() + " träffar på \"" + q + "\"");
        String[] items = new String[hits.size()];
        for (int i = 0; i < hits.size(); i++) {
            items[i] = hits.get(i).title + "\n" + hits.get(i).channel;
        }
        new AlertDialog.Builder(this)
                .setTitle("Välj låt")
                .setItems(items, (d, which) -> {
                    YouTube.Hit h = hits.get(which);
                    Playlist.Song s = Playlist.Song.parse(cleanTitle(h.title));
                    s.videoId = h.videoId;
                    new AlertDialog.Builder(this)
                            .setTitle(s.display())
                            .setItems(new String[]{"Lägg till och spela", "Lägg till i listan"}, (d2, w2) -> {
                                int idx = indexOf(s);
                                if (idx < 0) {
                                    songs.add(s);
                                    Playlist.save(this, songs);
                                    idx = songs.size() - 1;
                                }
                                refreshList();
                                if (w2 == 0) playIndex(idx);
                                else toast("Tillagd: " + s.display());
                            })
                            .show();
                })
                .setNegativeButton("Avbryt", null)
                .show();
    }

    private int indexOf(Playlist.Song s) {
        for (int i = 0; i < songs.size(); i++) {
            if (songs.get(i).display().equalsIgnoreCase(s.display())) return i;
        }
        return -1;
    }

    /** Tar bort "(Official Video)", "[HD]" o.d. ur YouTube-titlar. */
    static String cleanTitle(String t) {
        return t.replaceAll("(?i)\\s*[(\\[][^)\\]]*(official|video|audio|lyric|visualizer|hd|4k|remaster)[^)\\]]*[)\\]]", "")
                .replaceAll("\\s{2,}", " ").trim();
    }

    private void songMenu(int idx) {
        Playlist.Song s = songs.get(idx);
        new AlertDialog.Builder(this)
                .setTitle(s.display())
                .setItems(new String[]{"Spela", "Flytta upp", "Flytta ner", "Sök rätt version", "Ta bort"}, (d, w) -> {
                    switch (w) {
                        case 0:
                            playIndex(idx);
                            break;
                        case 1:
                            move(idx, idx - 1);
                            break;
                        case 2:
                            move(idx, idx + 1);
                            break;
                        case 3:
                            songs.remove(idx);
                            Playlist.save(this, songs);
                            if (current == idx) current = -1;
                            refreshList();
                            search(s.searchQuery());
                            break;
                        default:
                            songs.remove(idx);
                            Playlist.save(this, songs);
                            if (current == idx) current = -1;
                            else if (current > idx) current--;
                            refreshList();
                            break;
                    }
                })
                .show();
    }

    private void move(int from, int to) {
        if (to < 0 || to >= songs.size()) return;
        Playlist.Song s = songs.remove(from);
        songs.add(to, s);
        if (current == from) current = to;
        else if (current == to) current = from;
        Playlist.save(this, songs);
        refreshList();
    }

    private void askImport() {
        EditText in = new EditText(this);
        in.setHint("En låt per rad:\nArtist - Titel");
        in.setMinLines(5);
        in.setGravity(Gravity.TOP);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(this)
                .setTitle("Importera låtar")
                .setView(in)
                .setPositiveButton("Importera", (d, w) -> {
                    int n = Playlist.importText(this, in.getText().toString());
                    songs = Playlist.load(this);
                    refreshList();
                    toast(n + " nya låtar");
                })
                .setNegativeButton("Avbryt", null)
                .show();
    }

    private void share() {
        if (songs.isEmpty()) {
            toast("Listan är tom");
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Låtlista från Spela")
                .putExtra(Intent.EXTRA_TEXT, Playlist.exportText(songs));
        startActivity(Intent.createChooser(i, "Dela låtlistan"));
    }

    private void askKey() {
        EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setHint("AIza…");
        in.setText(YouTube.key(this));
        new AlertDialog.Builder(this)
                .setTitle("YouTube-nyckel")
                .setMessage("Gratis nyckel från Google:\n"
                        + "1. console.cloud.google.com → skapa projekt\n"
                        + "2. APIs & Services → Library → \"YouTube Data API v3\" → Enable\n"
                        + "3. Credentials → Create credentials → API key\n"
                        + "4. Klistra in nyckeln här.\n\n"
                        + "Räcker till ca 100 sökningar per dygn. Nyckeln sparas bara i telefonen.")
                .setView(in)
                .setPositiveButton("Spara", (d, w) -> {
                    YouTube.saveKey(this, in.getText().toString());
                    status.setText(YouTube.key(this).isEmpty() ? "Ingen nyckel inlagd" : "Nyckeln är sparad.");
                })
                .setNegativeButton("Avbryt", null)
                .show();
    }

    // ================= Hjälpare =================

    private Toast lastToast;

    private void toast(String text) {
        if (lastToast != null) lastToast.cancel();
        lastToast = Toast.makeText(this, text, Toast.LENGTH_SHORT);
        lastToast.show();
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(52), 1f);
        p.setMargins(dp(4), 0, dp(4), 0);
        return p;
    }

    private Button pill(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(Color.BLACK);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.WHITE, Color.parseColor("#E6E6E6")});
        g.setCornerRadius(dp(26));
        g.setStroke(dp(1), Color.parseColor("#BDBDBD"));
        b.setBackground(g);
        b.setElevation(dp(3));
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
