package se.spela.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
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
 * Enkel meny enligt skissen:
 *   statusruta "Välj musiktyp", tabell genre × decennium, Start / Stop / Close app.
 * Hela gränssnittet byggs i kod – inga layoutfiler eller bibliotek.
 */
public class MainActivity extends Activity implements PlayerService.Listener {

    private static final String[] GENRES = {"Rock", "Jazz", "Country", "Blues", "Pop"};
    private static final String[] GENRE_TAGS = {"rock", "jazz", "country", "blues", "pop"};
    private static final String[] DECADES = {"1980-1990", "1990-2000", "2000->"};
    /** Taggar i Radio Browser som motsvarar respektive decennium. */
    private static final String[][] DECADE_TAGS = {
            {"80s"},
            {"90s"},
            {"2000s", "00s"}
    };

    private static final int GREEN = Color.parseColor("#2E7D32");
    private static final int GRAY = Color.parseColor("#D9D9D9");
    private static final int SELECTED = Color.parseColor("#C8E6C9");
    private static final int RED = Color.parseColor("#E53935");

    private TextView status;
    private final TextView[][] cells = new TextView[GENRES.length][DECADES.length];
    private int selRow = -1;
    private int selCol = -1;
    private boolean playing = false;
    private Button speakerBtn;
    private TextView nowPlayingView;
    private Button saveSongBtn;
    private String nowPlaying;
    /** Bakgrundstråd för Sonos-sökning och volym (nätverk får inte köras på UI-tråden). */
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private volatile int sonosVolume = -1;   // senast kända volym, -1 = okänd

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            // Notisen visar vad som spelas och har Stopp-knapp. Appen fungerar även utan.
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        sonosVolume = -1;
        updateSpeakerButton();
        PlayerService.setListener(this);
    }

    @Override
    protected void onPause() {
        PlayerService.setListener(null);
        super.onPause();
    }

    @Override
    public void onNowPlaying(String title) {
        nowPlaying = title;
        if (nowPlayingView == null) return;
        if (title == null) {
            nowPlayingView.setText(PlayerService.isPlaying() ? "♪ Stationen visar inte låttitel" : "");
            saveSongBtn.setEnabled(false);
            saveSongBtn.setAlpha(0.4f);
        } else {
            nowPlayingView.setText("♪ " + title);
            saveSongBtn.setEnabled(true);
            saveSongBtn.setAlpha(1f);
        }
    }

    @Override
    public void onStatus(String text, boolean isPlaying) {
        playing = isPlaying;
        status.setText(text);
    }

    // ---------------- Gränssnitt ----------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(16));
        root.setBackgroundColor(Color.WHITE);

        // Statusruta
        status = new TextView(this);
        status.setText("Välj musiktyp");
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        status.setTextColor(Color.BLACK);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        status.setMinHeight(dp(88));
        GradientDrawable box = new GradientDrawable();
        box.setColor(GRAY);
        box.setStroke(dp(2), GREEN);
        status.setBackground(box);
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Nu spelas + Spara låt / Låtlista
        nowPlayingView = new TextView(this);
        nowPlayingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        nowPlayingView.setTextColor(GREEN);
        nowPlayingView.setTypeface(Typeface.DEFAULT_BOLD);
        nowPlayingView.setPadding(dp(4), dp(8), dp(4), dp(4));
        root.addView(nowPlayingView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout songRow = new LinearLayout(this);
        songRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(songRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        saveSongBtn = pill("+ Spara låt", v -> saveSong());
        saveSongBtn.setEnabled(false);
        saveSongBtn.setAlpha(0.4f);
        songRow.addView(saveSongBtn, pillParams());
        songRow.addView(pill("Låtlista", v ->
                startActivity(new Intent(this, PlaylistActivity.class))), pillParams());

        // Tabell
        LinearLayout table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(12);
        root.addView(table, tlp);

        for (int r = 0; r < GENRES.length; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            table.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

            row.addView(cell(GENRES[r], false), weight(1.4f));
            for (int c = 0; c < DECADES.length; c++) {
                TextView t = cell(DECADES[c], false);
                t.setTextColor(GREEN);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                final int rr = r, cc = c;
                t.setOnClickListener(v -> select(rr, cc));
                cells[r][c] = t;
                row.addView(t, weight(1.2f));
            }
        }

        // Knappar Start / Stop / Nästa
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(24);
        root.addView(buttons, blp);

        buttons.addView(pill("Start", v -> start()), pillParams());
        buttons.addView(pill("Stop", v -> stop()), pillParams());
        buttons.addView(pill("Nästa", v -> next()), pillParams());

        TextView credit = new TextView(this);
        credit.setText("Stationer: radio-browser.info");
        credit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        credit.setTextColor(Color.GRAY);
        credit.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams crp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        crp.topMargin = dp(20);
        root.addView(credit, crp);

        // Mellanrum som trycker ner bottenraden till skärmens nederkant
        root.addView(new View(this), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Bottenrad: Close app längst ner till vänster, högtalarval längst ner till höger
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(16);
        root.addView(bottom, bp);

        LinearLayout closeBox = new LinearLayout(this);
        closeBox.setOrientation(LinearLayout.VERTICAL);
        closeBox.setGravity(Gravity.CENTER_HORIZONTAL);
        closeBox.setContentDescription("Stäng appen");
        closeBox.setOnClickListener(v -> closeApp());
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        close.setTextColor(Color.BLACK);
        close.setGravity(Gravity.CENTER);
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(RED);
        oval.setStroke(dp(1), Color.BLACK);
        close.setBackground(oval);
        closeBox.addView(close, new LinearLayout.LayoutParams(dp(44), dp(30)));
        TextView closeLabel = new TextView(this);
        closeLabel.setText("Close app");
        closeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        closeLabel.setTextColor(Color.BLACK);
        closeBox.addView(closeLabel);
        bottom.addView(closeBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        bottom.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        // Högtalarval: telefonen eller en Sonos i hemnätet
        speakerBtn = pill("Högtalare: Telefonen", v -> chooseSpeaker());
        speakerBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        speakerBtn.setSingleLine(true);
        speakerBtn.setEllipsize(android.text.TextUtils.TruncateAt.END);
        speakerBtn.setMaxWidth(getResources().getDisplayMetrics().widthPixels * 6 / 10);
        speakerBtn.setPadding(dp(14), 0, dp(14), 0);
        speakerBtn.setMinWidth(0);
        speakerBtn.setMinimumWidth(0);
        speakerBtn.setMinHeight(0);
        speakerBtn.setMinimumHeight(0);
        bottom.addView(speakerBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.addView(root);
        return scroll;
    }

    private TextView cell(String text, boolean center) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(Color.BLACK);
        t.setGravity(center ? Gravity.CENTER : Gravity.CENTER_VERTICAL | Gravity.START);
        t.setPadding(dp(6), 0, dp(4), 0);
        t.setMaxLines(1);
        t.setBackground(cellBg(Color.WHITE));
        return t;
    }

    private GradientDrawable cellBg(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(1), Color.BLACK);
        return g;
    }

    private LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, w);
    }

    private Button pill(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTextColor(Color.BLACK);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.WHITE, Color.parseColor("#E6E6E6")});
        g.setCornerRadius(dp(30));
        g.setStroke(dp(1), Color.parseColor("#BDBDBD"));
        b.setBackground(g);
        b.setElevation(dp(4));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams pillParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(56), 1f);
        p.setMargins(dp(6), 0, dp(6), 0);
        return p;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    // ---------------- Logik ----------------

    private void select(int r, int c) {
        if (selRow >= 0) cells[selRow][selCol].setBackground(cellBg(Color.WHITE));
        selRow = r;
        selCol = c;
        cells[r][c].setBackground(cellBg(SELECTED));
        if (playing) {
            start();            // spelar redan: byt direkt till ny musiktyp
        } else {
            status.setText("Vald: " + label() + "\nTryck Start");
        }
    }

    private String label() {
        return GENRES[selRow] + " " + DECADES[selCol];
    }

    private void start() {
        if (selRow < 0) {
            status.setText("Välj musiktyp i tabellen först");
            return;
        }
        Intent i = new Intent(this, PlayerService.class)
                .setAction(PlayerService.ACTION_PLAY)
                .putExtra(PlayerService.EXTRA_GENRE, GENRE_TAGS[selRow])
                .putExtra(PlayerService.EXTRA_DECADES, DECADE_TAGS[selCol])
                .putExtra(PlayerService.EXTRA_LABEL, label());
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void next() {
        if (selRow < 0) {
            status.setText("Välj musiktyp i tabellen först");
            return;
        }
        if (!playing) {         // inget spelas: Nästa fungerar som Start
            start();
            return;
        }
        startService(new Intent(this, PlayerService.class).setAction(PlayerService.ACTION_NEXT));
    }

    private void stop() {
        startService(new Intent(this, PlayerService.class).setAction(PlayerService.ACTION_STOP));
    }

    // ---------------- Låtlista ----------------

    private void saveSong() {
        if (nowPlaying == null) return;
        boolean added = Playlist.add(this, Playlist.Song.parse(nowPlaying));
        toast(added ? "Sparad i låtlistan: " + nowPlaying : "Finns redan i låtlistan");
    }

    // ---------------- Högtalare ----------------

    private void updateSpeakerButton() {
        if (speakerBtn == null) return;
        Sonos.Device d = Sonos.saved(this);
        speakerBtn.setText("Högtalare: " + (d != null ? d.name : "Telefonen"));
    }

    private void chooseSpeaker() {
        final AlertDialog searching = new AlertDialog.Builder(this)
                .setTitle("Högtalare")
                .setMessage("Söker Sonos i hemnätet…")
                .setCancelable(true)
                .show();
        bg.execute(() -> {
            List<Sonos.Device> found;
            String error = null;
            try {
                found = Sonos.discover(this);
            } catch (Exception e) {
                found = new java.util.ArrayList<>();
                error = "Sökningen misslyckades. Är telefonen ansluten till wifi?";
            }
            final List<Sonos.Device> list = found;
            final String err = error;
            runOnUiThread(() -> {
                if (isFinishing() || !searching.isShowing()) return;   // avbruten
                searching.dismiss();
                showSpeakerList(list, err);
            });
        });
    }

    private void showSpeakerList(List<Sonos.Device> list, String error) {
        Sonos.Device current = Sonos.saved(this);
        String[] items = new String[list.size() + 2];
        int checked = 0;
        items[0] = "Telefonen";
        for (int i = 0; i < list.size(); i++) {
            items[i + 1] = list.get(i).name;
            if (current != null && current.uuid.equals(list.get(i).uuid)) checked = i + 1;
        }
        items[items.length - 1] = "Ange IP-adress…";
        String title = list.isEmpty()
                ? (error != null ? error : "Ingen Sonos hittades")
                : "Välj högtalare";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(items, checked, (dlg, which) -> {
                    dlg.dismiss();
                    if (which == 0) {
                        applySpeaker(null);
                    } else if (which == items.length - 1) {
                        askForIp();
                    } else {
                        applySpeaker(list.get(which - 1));
                    }
                })
                .setNegativeButton("Avbryt", null)
                .show();
    }

    private void askForIp() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_PHONE);   // siffror och punkter
        input.setHint("t.ex. 192.168.1.23");
        Sonos.Device cur = Sonos.saved(this);
        if (cur != null) input.setText(cur.ip);
        new AlertDialog.Builder(this)
                .setTitle("Sonos IP-adress")
                .setMessage("Finns i Sonos-appen: Inställningar → System → Om mitt system.")
                .setView(input)
                .setPositiveButton("OK", (d, w) -> {
                    String ip = input.getText().toString().trim();
                    if (ip.isEmpty()) return;
                    bg.execute(() -> {
                        try {
                            Sonos.Device dev = Sonos.describe(ip);
                            runOnUiThread(() -> applySpeaker(dev));
                        } catch (Exception e) {
                            runOnUiThread(() -> toast("Ingen Sonos svarade på " + ip));
                        }
                    });
                })
                .setNegativeButton("Avbryt", null)
                .show();
    }

    private void applySpeaker(Sonos.Device d) {
        Sonos.Device old = Sonos.saved(this);
        boolean same = (old == null && d == null)
                || (old != null && d != null && old.uuid.equals(d.uuid));
        Sonos.save(this, d);
        sonosVolume = -1;
        updateSpeakerButton();
        if (!same && PlayerService.isRunning()) {
            startService(new Intent(this, PlayerService.class).setAction(PlayerService.ACTION_SWITCH));
        }
    }

    /** Volymknapparna styr Sonos-volymen när musiken spelar där. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN) {
            final Sonos.Device d = Sonos.saved(this);
            if (d != null && PlayerService.isRunning()) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    final int step = key == KeyEvent.KEYCODE_VOLUME_UP ? 2 : -2;
                    bg.execute(() -> {
                        try {
                            if (sonosVolume < 0) sonosVolume = Sonos.getVolume(d);
                            sonosVolume = Math.max(0, Math.min(100, sonosVolume + step));
                            Sonos.setVolume(d, sonosVolume);
                            final int v = sonosVolume;
                            runOnUiThread(() -> toast(d.name + " volym: " + v));
                        } catch (Exception e) {
                            sonosVolume = -1;
                        }
                    });
                }
                return true;   // låt inte telefonen ändra sin egen volym
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private Toast lastToast;

    private void toast(String text) {
        if (lastToast != null) lastToast.cancel();
        lastToast = Toast.makeText(this, text, Toast.LENGTH_SHORT);
        lastToast.show();
    }

    @Override
    protected void onDestroy() {
        bg.shutdown();
        super.onDestroy();
    }

    private void closeApp() {
        stop();
        finishAndRemoveTask();
    }
}
