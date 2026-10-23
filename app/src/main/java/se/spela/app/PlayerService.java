package se.spela.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Spelar radioströmmen i en förgrundstjänst, så att musiken fortsätter
 * när skärmen släcks eller appen ligger i bakgrunden.
 */
public class PlayerService extends Service {

    static final String ACTION_PLAY = "se.spela.app.PLAY";
    static final String ACTION_NEXT = "se.spela.app.NEXT";
    static final String ACTION_STOP = "se.spela.app.STOP";
    static final String EXTRA_GENRE = "genre";
    static final String EXTRA_DECADES = "decades";
    static final String EXTRA_LABEL = "label";

    /** Tid att vänta på att en ström startar innan nästa station prövas. */
    private static final long PREPARE_TIMEOUT_MS = 15000;
    private static final int MAX_FAILED_IN_ROW = 6;
    private static final String CHANNEL_ID = "spela_playback";
    private static final int NOTIF_ID = 1;

    // ---- Enkel statuskanal till MainActivity (samma process) ----
    interface Listener {
        void onStatus(String text, boolean playing);
    }

    private static Listener listener;
    private static String lastStatus = "Välj musiktyp";
    private static boolean lastPlaying = false;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void setListener(Listener l) {
        listener = l;
        if (l != null) l.onStatus(lastStatus, lastPlaying);
    }

    private static void publish(String text, boolean playing) {
        MAIN.post(() -> {
            lastStatus = text;
            lastPlaying = playing;
            if (listener != null) listener.onStatus(text, playing);
        });
    }

    // ---- Tillstånd ----
    private MediaPlayer player;
    private List<RadioBrowser.Station> stations = new ArrayList<>();
    private int index = -1;
    private int failedInRow = 0;
    private String label = "";
    private int generation = 0;          // ökar vid varje nytt val, så att gamla trådar ignoreras
    private WifiManager.WifiLock wifiLock;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private final Runnable prepareTimeout = () -> {
        publish("Stationen svarar inte – provar nästa…", false);
        playNext(true);
    };

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "spela:wifi");
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Uppspelning", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_PLAY.equals(action)) {
            goForeground("Söker stationer…");
            startSearch(intent.getStringExtra(EXTRA_GENRE),
                    intent.getStringArrayExtra(EXTRA_DECADES),
                    intent.getStringExtra(EXTRA_LABEL));
        } else if (ACTION_NEXT.equals(action)) {
            if (stations.isEmpty()) {
                stopEverything("Välj musiktyp och tryck Start");
            } else {
                goForeground("Byter station…");
                playNext(false);
            }
        } else {
            stopEverything();
        }
        return START_NOT_STICKY;
    }

    private void startSearch(String genre, String[] decades, String newLabel) {
        final int gen = ++generation;
        releasePlayer();
        label = newLabel != null ? newLabel : "";
        stations = new ArrayList<>();
        index = -1;
        failedInRow = 0;
        publish("Söker " + label + "…", false);

        new Thread(() -> {
            try {
                List<RadioBrowser.Station> found = RadioBrowser.find(genre, decades);
                MAIN.post(() -> {
                    if (gen != generation) return;
                    if (found.isEmpty()) {
                        stopEverything("Inga stationer hittades för " + label);
                        return;
                    }
                    stations = found;
                    playNext(false);
                });
            } catch (Exception e) {
                MAIN.post(() -> {
                    if (gen != generation) return;
                    stopEverything("Ingen kontakt med radiokatalogen. Kontrollera internet.");
                });
            }
        }, "spela-search").start();
    }

    private void playNext(boolean afterFailure) {
        MAIN.removeCallbacks(prepareTimeout);
        if (afterFailure) {
            failedInRow++;
            if (failedInRow >= MAX_FAILED_IN_ROW) {
                stopEverything("Flera stationer svarade inte. Försök igen senare.");
                return;
            }
        }
        if (stations.isEmpty()) {
            stopEverything();
            return;
        }
        index = (index + 1) % stations.size();
        playStation(stations.get(index));
    }

    private void playStation(RadioBrowser.Station s) {
        releasePlayer();
        final int gen = generation;
        final String info = label + "  (" + (index + 1) + "/" + stations.size() + ")";
        publish("Ansluter: " + s.name + "\n" + info, false);
        updateNotification("Ansluter: " + s.name);

        MediaPlayer mp = new MediaPlayer();
        player = mp;
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        mp.setOnPreparedListener(p -> {
            if (gen != generation || p != player) return;
            MAIN.removeCallbacks(prepareTimeout);
            if (!requestFocus()) {
                stopEverything("Ljudet används av en annan app");
                return;
            }
            p.start();
            failedInRow = 0;
            publish("Spelar: " + s.name + "\n" + info, true);
            updateNotification("Spelar: " + s.name);
            new Thread(() -> RadioBrowser.reportClick(s.uuid), "spela-click").start();
        });
        mp.setOnErrorListener((p, what, extra) -> {
            if (gen != generation || p != player) return true;
            publish("Fel på strömmen – provar nästa…", false);
            playNext(true);
            return true;
        });
        mp.setOnCompletionListener(p -> {
            // En radioström ska inte ta slut – om den gör det, gå vidare.
            if (gen != generation || p != player) return;
            playNext(true);
        });
        try {
            mp.setDataSource(s.url);
            mp.prepareAsync();
            MAIN.postDelayed(prepareTimeout, PREPARE_TIMEOUT_MS);
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (Exception e) {
            publish("Ogiltig ström – provar nästa…", false);
            MAIN.post(() -> playNext(true));
        }
    }

    // ---- Ljudfokus: pausa vid telefonsamtal o.d. ----
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (player == null) return;
        try {
            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                stopEverything("Stoppad (annan app tog ljudet)");
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                player.setVolume(0.2f, 0.2f);
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                player.setVolume(0f, 0f);
            } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                player.setVolume(1f, 1f);
            }
        } catch (IllegalStateException ignored) {
        }
    };

    private boolean requestFocus() {
        int res;
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setOnAudioFocusChangeListener(focusListener)
                    .build();
            res = audioManager.requestAudioFocus(focusRequest);
        } else {
            res = audioManager.requestAudioFocus(focusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        } else {
            audioManager.abandonAudioFocus(focusListener);
        }
    }

    // ---- Notis (krävs för förgrundstjänst) ----
    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, PlayerService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent nextPi = PendingIntent.getService(this, 2,
                new Intent(this, PlayerService.class).setAction(ACTION_NEXT),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Spela")
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "Nästa", nextPi).build())
                .addAction(new Notification.Action.Builder(null, "Stopp", stopPi).build())
                .build();
    }

    private void goForeground(String text) {
        Notification n = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    // ---- Städning ----
    private void releasePlayer() {
        MAIN.removeCallbacks(prepareTimeout);
        if (player != null) {
            try {
                player.reset();
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    private void stopEverything() {
        stopEverything("Stoppad");
    }

    /** Stoppar allt och visar ett slutmeddelande (t.ex. ett felmeddelande). */
    private void stopEverything(String message) {
        generation++;
        releasePlayer();
        abandonFocus();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        publish(message, false);
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        generation++;
        releasePlayer();
        abandonFocus();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
