package se.spela.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Låtlista: låtar sparade från radion ("+ Spara låt") eller importerade som text.
 * Listan kan sorteras, rensas och delas som text (t.ex. till mejl eller anteckningar).
 */
public class PlaylistActivity extends Activity {

    private static final int GREEN = Color.parseColor("#2E7D32");

    private List<Playlist.Song> songs;
    private LinearLayout listView;
    private TextView count;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        songs = Playlist.load(this);   // kan ha ändrats via "+ Spara låt"
        refreshList();
    }

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

        count = new TextView(this);
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        count.setTextColor(Color.GRAY);
        root.addView(count);

        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.addView(pill("Importera", v -> askImport()), weight());
        r.addView(pill("Dela", v -> share()), weight());
        r.addView(pill("Töm", v -> askClear()), weight());
        LinearLayout.LayoutParams rp = full();
        rp.topMargin = dp(12);
        root.addView(r, rp);

        TextView hint = new TextView(this);
        hint.setText("Håll inne på en låt för att flytta, kopiera eller ta bort den.");
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
        count.setText(songs.size() == 1 ? "1 låt" : songs.size() + " låtar");
        if (songs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Listan är tom. Tryck \"+ Spara låt\" när radion spelar något du gillar, eller importera.");
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
            g.setColor(Color.WHITE);
            g.setStroke(dp(1), Color.parseColor("#BDBDBD"));
            t.setBackground(g);
            t.setOnClickListener(v -> songMenu(idx));
            t.setOnLongClickListener(v -> {
                songMenu(idx);
                return true;
            });
            listView.addView(t, full());
        }
    }

    private void songMenu(int idx) {
        Playlist.Song s = songs.get(idx);
        new AlertDialog.Builder(this)
                .setTitle(s.display())
                .setItems(new String[]{"Kopiera", "Flytta upp", "Flytta ner", "Ta bort"}, (d, w) -> {
                    switch (w) {
                        case 0:
                            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Låt", s.display()));
                            toast("Kopierad");
                            break;
                        case 1:
                            move(idx, idx - 1);
                            break;
                        case 2:
                            move(idx, idx + 1);
                            break;
                        default:
                            songs.remove(idx);
                            Playlist.save(this, songs);
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

    private void askClear() {
        if (songs.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Töm låtlistan?")
                .setMessage("Alla " + songs.size() + " låtar tas bort. Tips: tryck Dela först om du vill spara en kopia.")
                .setPositiveButton("Töm", (d, w) -> {
                    songs.clear();
                    Playlist.save(this, songs);
                    refreshList();
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

    // ================= Hjälpare =================

    private Toast lastToast;

    private void toast(String text) {
        if (lastToast != null) lastToast.cancel();
        lastToast = Toast.makeText(this, text, Toast.LENGTH_SHORT);
        lastToast.show();
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
