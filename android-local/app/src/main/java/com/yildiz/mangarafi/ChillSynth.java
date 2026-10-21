package com.yildiz.mangarafi;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Uygulamanın kendi ürettiği sakin, sözsüz lo-fi müzik (telifsiz): yavaş caz akorları,
 * yumuşak elektrik piyano, geniş arka ses, seyrek pentatonik melodi, yankı ve hafif plak cızırtısı.
 * Her çalışta küçük rastgeleliklerle biraz farklı çalar.
 */
final class ChillSynth {
    private final int rate;
    private final Random rnd;
    private final double beat;          // saniye
    private long frame = 0;
    private int nextBeat = 0;
    private int chordIdx = -1;

    // Akorlar (MIDI notaları): Fmaj7, Em7, Dm9, Cmaj7 / Am7, Dm7, G7sus, Cmaj9
    private static final int[][][] PROGS = {
        {{53, 57, 60, 64}, {52, 55, 59, 62}, {50, 53, 57, 60, 64}, {48, 52, 55, 59}},
        {{57, 60, 64, 67}, {50, 53, 57, 60}, {55, 60, 62, 65}, {48, 52, 55, 59, 62}},
        {{53, 57, 60, 64}, {55, 59, 62, 65}, {52, 55, 59, 62}, {57, 60, 64, 67}},
    };
    private int[][] prog;
    private static final int[] PENTA = {72, 74, 76, 79, 81, 84}; // C majör pentatonik (üst oktav)

    private final List<Voice> voices = new ArrayList<>();
    private final float[] delay;
    private int delayPos = 0;
    private double lp = 0, lp2 = 0;     // yumuşatma süzgeci
    private double wobble = 0;          // kaset titremesi

    ChillSynth(int rate, long seed) {
        this.rate = rate;
        this.rnd = new Random(seed);
        this.beat = 60.0 / (62 + rnd.nextInt(8));    // 62–69 vuruş/dk
        this.prog = PROGS[rnd.nextInt(PROGS.length)];
        this.delay = new float[(int) (rate * beat * 0.75)]; // noktalı sekizlik yankı
    }

    private static double freq(double midi) { return 440.0 * Math.pow(2, (midi - 69) / 12.0); }

    /** Tek bir nota: türüne göre ses rengi ve zarf. */
    private final class Voice {
        final double f; final double amp; final int type; // 0 pad, 1 piyano, 2 melodi, 3 bas
        final long start; final double dur;               // saniye
        double ph = 0, ph2 = 0;
        Voice(double midi, double amp, int type, double dur) {
            this.f = freq(midi + (rnd.nextDouble() - 0.5) * 0.06);
            this.amp = amp; this.type = type; this.start = frame; this.dur = dur;
        }
        boolean done() { return (frame - start) / (double) rate > dur + 3.0; }
        double next() {
            double t = (frame - start) / (double) rate;
            double env;
            switch (type) {
                case 0: // pad: yavaş giriş, yavaş çıkış
                    env = Math.min(1, t / 2.0) * (t > dur ? Math.max(0, 1 - (t - dur) / 2.5) : 1);
                    break;
                case 3: // bas: yumuşak, uzun
                    env = Math.min(1, t / 0.25) * (0.55 + 0.45 * Math.exp(-t / 2.5)) * (t > dur ? Math.max(0, 1 - (t - dur) / 0.6) : 1);
                    break;
                default: // piyano/melodi: hızlı vuruş, yavaş sönüm
                    env = Math.min(1, t / 0.006) * Math.exp(-t / (type == 2 ? 1.6 : 1.1));
            }
            double w = 1 + wobble * 0.0025;
            ph += 2 * Math.PI * f * w / rate;
            double s;
            if (type == 0) {
                ph2 += 2 * Math.PI * f * 1.003 * w / rate;
                s = 0.5 * Math.sin(ph) + 0.5 * Math.sin(ph2) + 0.12 * Math.sin(2 * ph);
            } else if (type == 1) { // elektrik piyano: hafif FM
                double mod = Math.sin(ph * 2) * 1.2 * Math.exp(-t / 0.35);
                s = Math.sin(ph + mod) + 0.15 * Math.sin(3 * ph) * Math.exp(-t / 0.2);
            } else if (type == 2) {
                double mod = Math.sin(ph * 3) * 0.5 * Math.exp(-t / 0.25);
                s = Math.sin(ph + mod);
            } else {
                s = Math.sin(ph) + 0.25 * Math.sin(2 * ph);
            }
            return s * env * amp;
        }
    }

    private void onBeat(int b) {
        int bar = b / 4, inBar = b % 4;
        if (inBar == 0 && bar % 2 == 0) {                 // her 2 ölçüde bir akor
            chordIdx = (chordIdx + 1) % prog.length;
            if (chordIdx == 0 && rnd.nextInt(3) == 0) prog = PROGS[rnd.nextInt(PROGS.length)];
            int[] ch = prog[chordIdx];
            double len = beat * 8;
            for (int n : ch) voices.add(new Voice(n - 12, 0.07, 0, len));            // pad
            voices.add(new Voice(ch[0] - 24, 0.09, 3, len * 0.9));                    // bas
            // akoru hafifçe dağıtarak piyanoyla çal
            for (int i = 0; i < ch.length; i++) {
                final int note = ch[i];
                pending.add(new long[]{frame + (long) (i * rate * 0.035), note, 1});
            }
        }
        int[] ch = prog[Math.max(0, chordIdx)];
        // seyrek piyano tekrarları (ara vuruşlarda)
        if (inBar == 2 && rnd.nextDouble() < 0.55) {
            for (int i = 1; i < ch.length; i++) pending.add(new long[]{frame + (long) (i * rate * 0.03 + rate * beat * 0.5), ch[i], 1});
        }
        // sakin melodi: ara sıra, pentatonik
        if (rnd.nextDouble() < 0.33) {
            int n = PENTA[rnd.nextInt(PENTA.length)];
            long when = frame + (long) (rate * beat * (rnd.nextBoolean() ? 0 : 0.5));
            pending.add(new long[]{when, n, 2});
            if (rnd.nextDouble() < 0.35) pending.add(new long[]{when + (long) (rate * beat * 0.5), PENTA[rnd.nextInt(PENTA.length)], 2});
        }
    }

    private final List<long[]> pending = new ArrayList<>(); // {başlama karesi, nota, tür}

    /** Sonraki ses örneklerini (mono, 16 bit) doldurur. */
    void render(short[] out) {
        for (int i = 0; i < out.length; i++) {
            if (frame >= (long) (nextBeat * beat * rate)) { onBeat(nextBeat); nextBeat++; }
            for (int k = pending.size() - 1; k >= 0; k--) {
                long[] p = pending.get(k);
                if (p[0] <= frame) {
                    pending.remove(k);
                    voices.add(new Voice(p[1], p[2] == 1 ? 0.075 : 0.06, (int) p[2], 4));
                }
            }
            double s = 0;
            for (int k = voices.size() - 1; k >= 0; k--) {
                Voice v = voices.get(k);
                if (v.done()) { voices.remove(k); continue; }
                s += v.next();
            }
            // yankı
            double d = delay[delayPos];
            delay[delayPos] = (float) (s * 0.5 + d * 0.38);
            delayPos = (delayPos + 1) % delay.length;
            s += d * 0.32;
            // sıcaklık: iki kademe yumuşak alçak geçiren
            lp += (s - lp) * 0.38;
            lp2 += (lp - lp2) * 0.55;
            s = lp2;
            // plak cızırtısı ve çok hafif hışırtı
            s += (rnd.nextDouble() - 0.5) * 0.004;
            if (rnd.nextInt(rate / 3) == 0) s += (rnd.nextDouble() - 0.5) * 0.06;
            wobble = Math.sin(2 * Math.PI * 0.35 * frame / rate);
            // yumuşak sınırlama
            s = Math.tanh(s * 1.3) * 0.85;
            out[i] = (short) (s * 32767);
            frame++;
        }
    }
}
