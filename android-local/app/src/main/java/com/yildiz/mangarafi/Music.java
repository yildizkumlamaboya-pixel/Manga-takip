package com.yildiz.mangarafi;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.net.Uri;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Okurken arka plan müziği. Kaynaklar:
 *  - internet radyoları (herkese açık yayınlar: anime OST, anime şarkıları, lo-fi)
 *  - telefondan seçilen müzik dosyaları
 *  - uygulamanın kendi ürettiği sakin lo-fi (ChillSynth, internetsiz)
 * Okuyucu açıkken çalar, çıkınca durur.
 */
final class Music {
    private Music() { }

    static final String ON = "r_music", VOL = "r_musicVol", SRC = "r_musicSrc", URIS = "r_musicUris";
    private static final int RATE = 22050;

    /** Kaynak kodu, görünen ad, yayın adresleri (ilki çalmazsa sıradaki denenir). */
    static final String[][] STATIONS = {
        {"ost", "Anime müzikleri (OST, radyo)", "https://radio.shadowfr69.eu/mp3", "http://nrf1.newradio.it:10328/alfaanime"},
        {"lofi", "Sakin lo-fi (radyo)", "https://radio.lalo-fi.com/listen/lalo-fi/radio.mp3", "http://radio.hearme.fm:8366/stream", "http://radio.hearme.fm:9230/stream"},
        {"jpop", "Anime şarkıları (LISTEN.moe, sözlü)", "https://listen.moe/fallback"},
    };

    static String[] sourceCodes() { return new String[]{"ost", "lofi", "jpop", "files", "gen"}; }

    static String sourceName(String code) {
        for (String[] st : STATIONS) if (st[0].equals(code)) return st[1];
        if ("files".equals(code)) return "Telefonumdaki müzikler";
        return "Uygulamanın sakin müziği (internetsiz)";
    }

    static String source(Context ctx) { return prefs(ctx).getString(SRC, "ost"); }

    private static Thread thread;
    private static volatile boolean playing = false;
    private static AudioTrack track;
    private static MediaPlayer player;
    private static List<Uri> queue = new ArrayList<>();
    private static int queuePos = 0;
    private static float volume = 0.4f;
    private static AudioManager.OnAudioFocusChangeListener focus;

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Checker.PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context ctx) { return prefs(ctx).getBoolean(ON, false); }

    static boolean useFiles(Context ctx) { return "files".equals(source(ctx)) && !uris(ctx).isEmpty(); }

    static List<Uri> uris(Context ctx) {
        List<Uri> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(ctx).getString(URIS, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Uri.parse(a.getString(i)));
        } catch (Exception ignored) { }
        return out;
    }

    static boolean isPlaying() { return playing; }

    static synchronized void start(Context c) {
        Context ctx = c.getApplicationContext();
        stop(ctx);
        volume = prefs(ctx).getInt(VOL, 40) / 100f;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        focus = change -> {
            if (change == AudioManager.AUDIOFOCUS_LOSS) stop(ctx);
            else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) applyVolume(volume * 0.2f);
            else if (change == AudioManager.AUDIOFOCUS_GAIN) applyVolume(volume);
        };
        am.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        playing = true;
        String src = source(ctx);
        String[] station = null;
        for (String[] st : STATIONS) if (st[0].equals(src)) station = st;
        if (station != null) startRadio(ctx, station, 2);
        else if (useFiles(ctx)) startFiles(ctx);
        else startSynth();
    }

    private static void startSynth() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, 8192), AudioTrack.MODE_STREAM);
        track = t;
        thread = new Thread(() -> {
            ChillSynth synth = new ChillSynth(RATE, System.nanoTime());
            short[] buf = new short[2048];
            long written = 0;
            try {
                t.setVolume(0f);
                t.play();
                while (playing) {
                    synth.render(buf);
                    // ilk 4 saniyede yavaşça yüksel
                    float ramp = Math.min(1f, written / (RATE * 4f));
                    t.setVolume(volume * ramp);
                    t.write(buf, 0, buf.length);
                    written += buf.length;
                }
            } catch (Exception ignored) {
            } finally {
                try { t.stop(); } catch (Exception ignored) { }
                t.release();
            }
        }, "muzik");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
    }

    /** İnternet radyosu; çalmazsa sıradaki yayın, hiçbiri olmazsa uygulamanın kendi müziği. */
    private static void startRadio(Context ctx, String[] st, int idx) {
        if (!playing) return;
        if (idx >= st.length) { startSynth(); return; }
        try {
            if (player != null) player.release();
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(st[idx]);
            mp.setVolume(volume, volume);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnErrorListener((m, w, e) -> { startRadio(ctx, st, idx + 1); return true; });
            mp.setOnCompletionListener(m -> startRadio(ctx, st, idx)); // yayın koparsa yeniden bağlan
            mp.prepareAsync();
        } catch (Exception e) {
            startRadio(ctx, st, idx + 1);
        }
    }

    private static void startFiles(Context ctx) {
        queue = uris(ctx);
        Collections.shuffle(queue);
        queuePos = 0;
        playNext(ctx);
    }

    private static void playNext(Context ctx) {
        if (!playing || queue.isEmpty()) return;
        try {
            if (player != null) player.release();
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(ctx, queue.get(queuePos % queue.size()));
            queuePos++;
            mp.setVolume(volume, volume);
            mp.setOnCompletionListener(m -> playNext(ctx));
            mp.setOnErrorListener((m, w, e) -> { playNext(ctx); return true; });
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.prepareAsync();
        } catch (Exception e) {
            if (queuePos < queue.size() * 2) { queuePos++; playNext(ctx); }
        }
    }

    static synchronized void stop(Context ctx) {
        playing = false;
        if (thread != null) {
            try { thread.join(500); } catch (InterruptedException ignored) { }
            thread = null;
        }
        track = null;
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            player.release();
            player = null;
        }
        if (focus != null) {
            ((AudioManager) ctx.getApplicationContext().getSystemService(Context.AUDIO_SERVICE)).abandonAudioFocus(focus);
            focus = null;
        }
    }

    static void setVolume(Context ctx, int v) {
        prefs(ctx).edit().putInt(VOL, v).apply();
        volume = v / 100f;
        applyVolume(volume);
    }

    private static void applyVolume(float v) {
        try { if (track != null) track.setVolume(v); } catch (Exception ignored) { }
        try { if (player != null) player.setVolume(v, v); } catch (Exception ignored) { }
    }
}
