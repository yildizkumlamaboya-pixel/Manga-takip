package com.yildiz.mangarafi;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Telefon hafızasındaki veriler (uygulamanın kendi klasöründe):
 *   list.json  -> takip edilen seriler + kaldığın bölüm
 *   state.json -> başlık, kapak, bölüm listesi, yeni bölüm kayıtları
 * Okuma/yazma tek kilitle yapılır; yazma önce geçici dosyaya, sonra yerine taşınır.
 */
final class Store {
    static final Object LOCK = new Object();

    private Store() { }

    private static File file(Context ctx, String name) {
        if (!name.equals("list.json") && !name.equals("state.json")) throw new IllegalArgumentException(name);
        return new File(ctx.getFilesDir(), name);
    }

    static String read(Context ctx, String name) {
        synchronized (LOCK) {
            File f = file(ctx, name);
            if (!f.exists()) return null;
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) f.length()];
                int off = 0, r;
                while (off < buf.length && (r = in.read(buf, off, buf.length - off)) > 0) off += r;
                return new String(buf, 0, off, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return null;
            }
        }
    }

    static void write(Context ctx, String name, String text) throws IOException {
        synchronized (LOCK) {
            File f = file(ctx, name);
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(f)) throw new IOException("kaydedilemedi");
        }
    }

    static JSONArray list(Context ctx) {
        try { String t = read(ctx, "list.json"); return new JSONArray(t == null ? "[]" : t); }
        catch (Exception e) { return new JSONArray(); }
    }

    static JSONObject state(Context ctx) {
        try { String t = read(ctx, "state.json"); return new JSONObject(t == null ? "{}" : t); }
        catch (Exception e) { return new JSONObject(); }
    }

    /** Kaldığın bölümü kaydeder (sadece ileri gider). */
    static void markRead(Context ctx, String seriesUrl, double num) {
        synchronized (LOCK) {
            try {
                JSONArray list = list(ctx);
                boolean changed = false;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject it = list.getJSONObject(i);
                    if (!seriesUrl.equals(it.optString("url"))) continue;
                    double prev = it.isNull("lastRead") ? -1 : it.optDouble("lastRead", -1);
                    if (num > prev) { it.put("lastRead", num); changed = true; }
                }
                if (changed) write(ctx, "list.json", list.toString(1));
            } catch (Exception ignored) { }
        }
    }
}
