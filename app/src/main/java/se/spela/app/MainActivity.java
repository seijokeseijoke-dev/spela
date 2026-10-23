package se.spela.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Enkel meny enligt skissen:
 *   statusruta "Välj musiktyp", tabell genre × decennium, Start / Stop / Close app.
 * Hela gränssnittet byggs i kod – inga layoutfiler eller bibliotek.
 */
public class MainActivity extends Activity implements PlayerService.Listener {

    private static final String[] LETTERS = {"A", "B", "C", "D", "E"};
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
        PlayerService.setListener(this);
    }

    @Override
    protected void onPause() {
        PlayerService.setListener(null);
        super.onPause();
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

            row.addView(cell(LETTERS[r], true), weight(0.5f));
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

        // Close app
        TextView closeLabel = new TextView(this);
        closeLabel.setText("Close app");
        closeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        closeLabel.setTextColor(Color.BLACK);
        closeLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(32);
        root.addView(closeLabel, clp);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        close.setTextColor(Color.BLACK);
        close.setGravity(Gravity.CENTER);
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(RED);
        oval.setStroke(dp(2), Color.BLACK);
        close.setBackground(oval);
        close.setContentDescription("Stäng appen");
        close.setOnClickListener(v -> closeApp());
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(dp(84), dp(60));
        olp.gravity = Gravity.CENTER_HORIZONTAL;
        olp.topMargin = dp(6);
        root.addView(close, olp);

        TextView credit = new TextView(this);
        credit.setText("Stationer: radio-browser.info");
        credit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        credit.setTextColor(Color.GRAY);
        credit.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams crp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        crp.topMargin = dp(24);
        root.addView(credit, crp);

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

    private void closeApp() {
        stop();
        finishAndRemoveTask();
    }
}
