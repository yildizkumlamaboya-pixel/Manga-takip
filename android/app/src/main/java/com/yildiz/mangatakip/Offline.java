package com.yildiz.mangatakip;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Çevrimdışı okuma: her manga için seçilen sayıda sıradaki bölüm uygulamanın kendi
 * klasörüne (başka uygulamaların göremediği yere) kaydedilir ve 7 gün sonra kendiliğinden silinir.
 * İnternet yokken okuyucu bölümü bu kopyadan gösterir.
 */
final class Offline {
    private Offline() { }

    static final long KEEP_MS = 7L * 24 * 60 * 60 * 1000;
    static final String HOST = "offline.local";
    private static final String PREFS = "offline";

    private static final ExecutorService pool = Executors.newSingleThreadExecutor();
    private static final Set<String> busy = Collections.synchronizedSet(new HashSet<>());

    /* ---------------- Ayarlar ---------------- */

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int wanted(Context ctx, String series) { return prefs(ctx).getInt("n:" + series, 0); }

    static void setWanted(Context ctx, String series, int n) {
        prefs(ctx).edit().putInt("n:" + series, Math.max(0, Math.min(100, n))).apply();
    }

    static boolean wifiOnly(Context ctx) { return prefs(ctx).getBoolean("wifi", true); }

    static void setWifiOnly(Context ctx, boolean on) { prefs(ctx).edit().putBoolean("wifi", on).apply(); }

    static boolean isBusy(String series) { return busy.contains(series); }

    /* ---------------- Dosyalar ---------------- */

    static String key(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format(Locale.ROOT, "%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    static File root(Context ctx) { return new File(ctx.getFilesDir(), "offline"); }

    static File seriesDir(Context ctx, String series) { return new File(root(ctx), key(series)); }

    static File chapterDir(Context ctx, String series, double num) {
        return new File(seriesDir(ctx, series), Parser.fmt(num));
    }

    static JSONObject index(Context ctx, String series, double num) {
        File f = new File(chapterDir(ctx, series, num), "index.json");
        if (!f.exists()) return null;
        try {
            JSONObject o = new JSONObject(readFile(f));
            if (System.currentTimeMillis() - o.getLong("at") > KEEP_MS) return null;
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean has(Context ctx, String series, double num) { return index(ctx, series, num) != null; }

    static List<Double> nums(Context ctx, String series) {
        List<Double> out = new ArrayList<>();
        File[] ds = seriesDir(ctx, series).listFiles();
        if (ds != null) for (File d : ds) {
            try {
                double n = Double.parseDouble(d.getName());
                if (has(ctx, series, n)) out.add(n);
            } catch (NumberFormatException ignored) { }
        }
        Collections.sort(out);
        return out;
    }

    static long size(File f) {
        if (f.isFile()) return f.length();
        long t = 0;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) t += size(c);
        return t;
    }

    static void delete(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) delete(c);
        f.delete();
    }

    /** 7 günü dolan ve yarım kalmış bölümleri siler. */
    static void cleanup(Context ctx) {
        File[] series = root(ctx).listFiles();
        if (series == null) return;
        long now = System.currentTimeMillis();
        for (File s : series) {
            File[] chs = s.listFiles();
            if (chs != null) for (File c : chs) {
                File idx = new File(c, "index.json");
                boolean old;
                try {
                    old = !idx.exists() ? now - c.lastModified() > 24 * 3600_000L
                        : now - new JSONObject(readFile(idx)).getLong("at") > KEEP_MS;
                } catch (Exception e) {
                    old = true;
                }
                if (old) delete(c);
            }
            String[] left = s.list();
            if (left == null || left.length == 0) s.delete();
        }
    }

    static void clearAll(Context ctx) { delete(root(ctx)); }

    /* ---------------- Okuyucuda gösterme ---------------- */

    static String baseUrl(String series, double num) {
        return "https://" + HOST + "/" + key(series) + "/" + Parser.fmt(num) + "/";
    }

    /** Kaydedilmiş bölümü gösteren sade sayfa. */
    static String pageHtml(Context ctx, String series, double num) {
        JSONObject idx = index(ctx, series, num);
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset=utf-8>")
          .append("<meta name=viewport content='width=device-width,initial-scale=1'>")
          .append("<style>html,body{margin:0;background:#14121a}img{display:block;width:100%;height:auto;min-height:200px}")
          .append(".end{color:#a9a3b8;text-align:center;padding:48px 16px 96px;font:15px sans-serif}</style></head><body>");
        if (idx != null) {
            JSONArray imgs = idx.optJSONArray("images");
            if (imgs != null) for (int i = 0; i < imgs.length(); i++) {
                sb.append("<img loading=lazy src='").append(imgs.optString(i)).append("'>");
            }
        }
        sb.append("<div class=end>Bölüm sonu · çevrimdışı kopya</div></body></html>");
        return sb.toString();
    }

    /** offline.local adreslerindeki resimleri dosyadan verir. */
    static WebResourceResponse serve(Context ctx, Uri u) {
        try {
            List<String> seg = u.getPathSegments();
            if (seg.size() != 3 || seg.get(2).contains("..")) return null;
            File f = new File(new File(new File(root(ctx), seg.get(0)), seg.get(1)), seg.get(2));
            if (!f.isFile()) return null;
            String name = f.getName().toLowerCase(Locale.ROOT);
            String mime = name.endsWith(".png") ? "image/png" : name.endsWith(".webp") ? "image/webp"
                : name.endsWith(".gif") ? "image/gif" : name.endsWith(".avif") ? "image/avif" : "image/jpeg";
            return new WebResourceResponse(mime, null, new FileInputStream(f));
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------------- İndirme ---------------- */

    private static boolean networkAllowed(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || cm.getActiveNetwork() == null) return false;
        return !wifiOnly(ctx) || !cm.isActiveNetworkMetered();
    }

    /**
     * Seçilen sayıda sıradaki bölümü (kaldığın yerden sonrası) indirir.
     * chapters: [[num,url],...] herhangi bir sırada. Arka planda çalışır.
     */
    static Future<?> ensure(Context ctx, String series, JSONArray chapters, double base) {
        Context app = ctx.getApplicationContext();
        int n = wanted(app, series);
        if (n <= 0 || busy.contains(series)) return null;
        List<double[]> nums = new ArrayList<>();
        Map<Double, String> urls = new HashMap<>();
        try {
            for (int i = 0; i < chapters.length(); i++) {
                JSONArray c = chapters.getJSONArray(i);
                double num = c.getDouble(0);
                if (num > base && !urls.containsKey(num)) { nums.add(new double[]{num}); urls.put(num, c.getString(1)); }
            }
        } catch (Exception e) {
            return null;
        }
        nums.sort((a, b) -> Double.compare(a[0], b[0]));
        List<Double> targets = new ArrayList<>();
        for (double[] x : nums) {
            if (targets.size() >= n) break;
            targets.add(x[0]);
        }
        boolean missing = false;
        for (double t : targets) if (!has(app, series, t)) missing = true;
        if (!missing) return null;
        busy.add(series);
        return pool.submit(() -> {
            try {
                for (double t : targets) {
                    if (wanted(app, series) <= 0 || !networkAllowed(app)) break;
                    if (has(app, series, t)) continue;
                    try { saveChapter(app, series, t, urls.get(t)); } catch (Exception ignored) { }
                }
            } finally {
                busy.remove(series);
            }
        });
    }

    /** Listede çevrimdışı açık olan tüm seriler için (arka plan kontrolünden sonra). Bitmesini bekler. */
    static void ensureAll(Context ctx, JSONArray list, JSONObject state, long maxWaitMs) {
        cleanup(ctx);
        List<Future<?>> jobs = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            try {
                JSONObject it = list.getJSONObject(i);
                String url = it.getString("url");
                if (wanted(ctx, url) <= 0) continue;
                JSONObject st = state.optJSONObject(url);
                if (st == null) continue;
                double base = !it.isNull("lastRead") && it.has("lastRead") ? it.getDouble("lastRead")
                    : st.has("first") && !st.isNull("first") ? st.getDouble("first") : -1;
                Future<?> f = ensure(ctx, url, st.optJSONArray("chapters") == null ? new JSONArray() : st.getJSONArray("chapters"), base);
                if (f != null) jobs.add(f);
            } catch (Exception ignored) { }
        }
        long until = System.currentTimeMillis() + maxWaitMs;
        for (Future<?> f : jobs) {
            try { f.get(Math.max(1, until - System.currentTimeMillis()), TimeUnit.MILLISECONDS); } catch (Exception ignored) { }
        }
    }

    private static void saveChapter(Context ctx, String series, double num, String url) throws Exception {
        List<String> imgs = pickImages(grab(ctx, url, 45000));
        if (imgs.size() < 3) throw new IOException("bölüm resimleri bulunamadı");
        File dir = chapterDir(ctx, series, num);
        File tmp = new File(dir.getPath() + ".tmp");
        delete(tmp);
        tmp.mkdirs();
        String ua = WebSettings.getDefaultUserAgent(ctx);
        JSONArray names = new JSONArray();
        for (int i = 0; i < imgs.size(); i++) {
            String name = download(ctx, imgs.get(i), url, ua, tmp, i);
            names.put(baseUrl(series, num) + name);
        }
        JSONObject idx = new JSONObject();
        idx.put("num", num);
        idx.put("url", url);
        idx.put("images", names);
        idx.put("at", System.currentTimeMillis());
        writeFile(new File(tmp, "index.json"), idx.toString());
        delete(dir);
        if (!tmp.renameTo(dir)) throw new IOException("kaydedilemedi");
    }

    private static String download(Context ctx, String img, String referer, String ua, File dir, int i) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection c = Net.open(ctx, img);
            try {
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", ua);
                c.setRequestProperty("Referer", referer);
                c.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8");
                String cookie = CookieManager.getInstance().getCookie(img);
                if (cookie != null) c.setRequestProperty("Cookie", cookie);
                int code = c.getResponseCode();
                if (code != 200) throw new IOException("resim " + code);
                String type = String.valueOf(c.getContentType()).toLowerCase(Locale.ROOT);
                String ext = type.contains("png") ? ".png" : type.contains("webp") ? ".webp" : type.contains("gif") ? ".gif"
                    : type.contains("avif") ? ".avif" : ".jpg";
                String name = String.format(Locale.ROOT, "%03d%s", i, ext);
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(new File(dir, name))) {
                    byte[] buf = new byte[32768];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                }
                return name;
            } catch (IOException e) {
                last = e;
                SystemClock.sleep(1500);
            } finally {
                c.disconnect();
            }
        }
        throw last;
    }

    /* ---------------- Bölüm sayfasından resim adreslerini bulma ---------------- */

    private static final String SCROLL_JS =
        "(function(){if(window.__mtGrab)return;window.__mtGrab=1;var y=0;var t=setInterval(function(){"
        + "document.querySelectorAll('img[loading=lazy]').forEach(function(i){i.loading='eager';});"
        + "y+=innerHeight;scrollTo(0,y);if(y>document.documentElement.scrollHeight+innerHeight){clearInterval(t);window.__mtGrab=2;}},250);})()";

    private static final String COLLECT_JS =
        "(function(){if(window.__mtGrab!==2)return '';var out=[];document.querySelectorAll('img').forEach(function(i){"
        + "var c=[i.getAttribute('data-src'),i.getAttribute('data-lazy-src'),i.getAttribute('data-original'),i.currentSrc,i.getAttribute('src')];"
        + "for(var k=0;k<c.length;k++){var v=c[k];if(v&&v.trim()&&v.indexOf('data:')!==0){try{out.push(new URL(v.trim(),location.href).href);}catch(e){}break;}}});"
        + "return JSON.stringify(out);})()";

    /** Bölüm sayfasını görünmez tarayıcıda açar, kaydırır ve sayfadaki resim adreslerini döndürür. */
    static List<String> grab(Context ctx, String url, long timeoutMs) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("ana iş parçacığı");
        Handler main = new Handler(Looper.getMainLooper());
        CountDownLatch done = new CountDownLatch(1);
        List<String> out = new ArrayList<>();
        WebView[] holder = new WebView[1];
        main.post(() -> {
            try {
                WebView w = new WebView(ctx);
                holder[0] = w;
                WebSettings s = w.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setBlockNetworkImage(true); // resimleri ayrıca indiriyoruz
                s.setUseWideViewPort(true);
                s.setLoadWithOverviewMode(true);
                w.setWebViewClient(new WebViewClient());
                // Görünmez ama boyutlu: sitenin "görününce yükle" düzeni çalışsın
                w.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY));
                w.layout(0, 0, 1080, 2340);
                w.loadUrl(url);
                long start = SystemClock.uptimeMillis();
                Runnable poll = new Runnable() {
                    @Override public void run() {
                        if (done.getCount() == 0) return;
                        if (SystemClock.uptimeMillis() - start > timeoutMs) { done.countDown(); return; }
                        if (w.getProgress() < 100) { main.postDelayed(this, 1000); return; }
                        w.evaluateJavascript(SCROLL_JS, null);
                        w.evaluateJavascript(COLLECT_JS, v -> {
                            String json;
                            try { json = new JSONArray("[" + v + "]").getString(0); } catch (Exception e) { json = ""; }
                            if (json.startsWith("[")) {
                                try {
                                    JSONArray a = new JSONArray(json);
                                    for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
                                } catch (Exception ignored) { }
                                done.countDown();
                            } else {
                                main.postDelayed(this, 1000);
                            }
                        });
                    }
                };
                main.postDelayed(poll, 2500);
            } catch (Throwable t) {
                done.countDown();
            }
        });
        try { done.await(timeoutMs + 8000, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        main.post(() -> { if (holder[0] != null) { holder[0].stopLoading(); holder[0].destroy(); } });
        return out;
    }

    private static final String[] SKIP = {"logo", "avatar", "icon", "favicon", "banner", "/ads", "reklam", "emoji",
        "gravatar", "/themes/", "thumb", "cover", "loading", "spinner", "placeholder", "button", "sponsor"};

    /**
     * Sayfadaki resimlerden bölüm sayfalarını seçer: aynı klasörde en çok resmi olan grup
     * (bölüm resimleri genelde aynı yerdedir), sayfadaki sırasıyla.
     */
    static List<String> pickImages(List<String> all) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String u : new LinkedHashSet<>(all)) {
            String low = u.toLowerCase(Locale.ROOT);
            if (!low.startsWith("http")) continue;
            boolean skip = false;
            for (String k : SKIP) if (low.contains(k)) { skip = true; break; }
            if (skip) continue;
            String path = low.split("[?#]")[0];
            if (!path.matches(".*\\.(jpe?g|png|webp|avif|gif)$") && !low.contains("image")) continue;
            String dir = u.split("[?#]")[0];
            dir = dir.substring(0, dir.lastIndexOf('/') + 1);
            groups.computeIfAbsent(dir, d -> new ArrayList<>()).add(u);
        }
        List<String> best = new ArrayList<>();
        for (List<String> g : groups.values()) if (g.size() > best.size()) best = g;
        return best;
    }

    /* ---------------- Yardımcılar ---------------- */

    static String readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) { return Net.readAll(in); }
    }

    static void writeFile(File f, String s) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) { out.write(s.getBytes("UTF-8")); }
    }

    /** Arayüz için özet: {"n":..,"nums":[..],"size":..,"busy":..} */
    static String summary(Context ctx, String series) {
        try {
            JSONObject o = new JSONObject();
            o.put("n", wanted(ctx, series));
            JSONArray a = new JSONArray();
            for (double d : nums(ctx, series)) a.put(d);
            o.put("nums", a);
            o.put("size", size(seriesDir(ctx, series)));
            o.put("busy", isBusy(series));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
