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
 * klasörüne (başka uygulamaların göremediği yere) kaydedilir. Okunmamış bölümler okunana kadar kalır,
 * okunan bölüm okunduktan 7 gün sonra kendiliğinden silinir.
 * İnternet yokken okuyucu bölümü bu kopyadan gösterir.
 */
final class Offline {
    private Offline() { }

    static final long KEEP_MS = 7L * 24 * 60 * 60 * 1000; // okunduktan sonra saklama süresi
    static final int ALL = 9999;                          // "okumadığım tüm bölümler"
    static final String HOST = "offline.local";
    private static final String PREFS = "offline";

    /** Bölümler tek tek, sırayla, düşük öncelikli bir iş parçacığında iner (ekran takılmasın). */
    private static final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "cevrimdisi");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final Set<String> busy = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> queued = Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    /**
     * Uygulamada aynı anda en fazla bir görünmez tarayıcı (WebView) açık olsun.
     * Birden fazlası telefonun tarayıcı bileşenini zorlayıp ekranın beyazlamasına yol açıyordu.
     */
    static final java.util.concurrent.Semaphore HIDDEN_WEB = new java.util.concurrent.Semaphore(1, true);

    // İndirme durumu (arayüzde ilerleme göstermek için)
    private static volatile String curSeries = null;
    private static volatile double curNum = -1;
    private static volatile int imgDone = 0, imgTotal = 0;

    static boolean paused(Context ctx) { return prefs(ctx).getBoolean("paused", false); }

    /** Duraklat: süren bölüm yarıda bırakılır (sonra baştan iner), yenileri başlamaz. */
    static void setPaused(Context ctx, boolean p) { prefs(ctx).edit().putBoolean("paused", p).apply(); }

    /** {"paused":..,"series":..,"num":..,"done":..,"total":..,"queue":..} */
    static String status(Context ctx) {
        try {
            JSONObject o = new JSONObject();
            o.put("paused", paused(ctx));
            o.put("series", curSeries == null ? JSONObject.NULL : curSeries);
            o.put("num", curNum);
            o.put("done", imgDone);
            o.put("total", imgTotal);
            o.put("queue", queued.size());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /* ---------------- Ayarlar ---------------- */

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int wanted(Context ctx, String series) { return prefs(ctx).getInt("n:" + series, 0); }

    static void setWanted(Context ctx, String series, int n) {
        prefs(ctx).edit().putInt("n:" + series, Math.max(0, Math.min(ALL, n))).apply();
        if (n <= 0) delete(seriesDir(ctx, series)); // kapatınca bu mangadaki kopyalar silinir
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
            if (expired(o)) return null;
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean has(Context ctx, String series, double num) { return index(ctx, series, num) != null; }

    /** Okunduysa ve üstünden 7 gün geçtiyse süresi dolmuştur; okunmamış bölüm beklemede kalır. */
    static boolean expired(JSONObject idx) {
        long readAt = idx.optLong("readAt", 0);
        return readAt > 0 && System.currentTimeMillis() - readAt > KEEP_MS;
    }

    /** Okuduğun bölüm ve ondan önceki 2 bölüm hiç silinmez (geri dönüp bakabilmen için). */
    static final int KEEP_LAST = 3;

    /**
     * Kaldığın yere kadar olan kayıtlı bölümleri "okundu" işaretler (7 günlük süre o an başlar).
     * Kaldığın bölüm ve önceki 2 bölüm işaretlenmez, telefonda kalır.
     */
    static void markReadUpTo(Context ctx, String series, double base) {
        File[] ds = seriesDir(ctx, series).listFiles();
        if (ds == null) return;
        List<double[]> read = new ArrayList<>();
        for (File d : ds) {
            try {
                double n = Double.parseDouble(d.getName());
                if (n <= base && new File(d, "index.json").exists()) read.add(new double[]{n});
            } catch (NumberFormatException ignored) { }
        }
        read.sort((x, y) -> Double.compare(y[0], x[0])); // en yeni başta
        long now = System.currentTimeMillis();
        for (int i = 0; i < read.size(); i++) {
            File f = new File(chapterDir(ctx, series, read.get(i)[0]), "index.json");
            try {
                JSONObject o = new JSONObject(readFile(f));
                boolean keep = i < KEEP_LAST;
                long readAt = o.optLong("readAt", 0);
                if (keep && readAt > 0) { o.remove("readAt"); writeFile(f, o.toString()); }
                else if (!keep && readAt == 0) { o.put("readAt", now); writeFile(f, o.toString()); }
            } catch (Exception ignored) { }
        }
    }

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
                        : expired(new JSONObject(readFile(idx)));
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

    /** Listede olmayan (takipten çıkarılmış) serilerin kopyalarını siler. */
    static void clearOthers(Context ctx, JSONArray urls) {
        Set<String> keep = new HashSet<>();
        try { for (int i = 0; i < urls.length(); i++) keep.add(key(urls.getString(i))); } catch (Exception ignored) { }
        File[] all = root(ctx).listFiles();
        if (all != null) for (File d : all) if (!keep.contains(d.getName())) delete(d);
    }

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
        markReadUpTo(app, series, base);
        int n = wanted(app, series);
        if (n <= 0 || busy.contains(series) || queued.contains(series) || paused(app)) return null;
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
        queued.add(series);
        return pool.submit(() -> {
            queued.remove(series);
            busy.add(series);
            curSeries = series;
            try {
                for (double t : targets) {
                    if (paused(app) || wanted(app, series) <= 0 || !networkAllowed(app)) break;
                    if (has(app, series, t)) continue;
                    curNum = t;
                    imgDone = 0;
                    imgTotal = 0;
                    try { saveChapter(app, series, t, urls.get(t)); } catch (Exception ignored) { }
                }
            } finally {
                busy.remove(series);
                curSeries = null;
                curNum = -1;
                imgDone = imgTotal = 0;
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
                JSONObject st = state.optJSONObject(url);
                if (st == null) continue;
                double base = !it.isNull("lastRead") && it.has("lastRead") ? it.getDouble("lastRead")
                    : st.has("first") && !st.isNull("first") ? st.getDouble("first") : -1;
                markReadUpTo(ctx, url, base);
                if (wanted(ctx, url) <= 0) continue;
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
        File dir = chapterDir(ctx, series, num);
        File tmp = new File(dir.getPath() + ".tmp");
        delete(tmp);
        tmp.mkdirs();
        JSONObject idx = new JSONObject();
        idx.put("num", num);
        idx.put("url", url);

        boolean viaImages = false;
        try {
            viaImages = saveImages(ctx, series, num, url, tmp, idx);
        } catch (IOException e) {
            if ("duraklatıldı".equals(e.getMessage())) { delete(tmp); throw e; }
        }
        if (!viaImages) {
            // Resimler bulunamadı ya da düzgün inmedi: bölümü sayfa olarak kaydet
            if (paused(ctx)) { delete(tmp); throw new IOException("duraklatıldı"); }
            for (File f : tmp.listFiles() == null ? new File[0] : tmp.listFiles()) delete(f);
            imgDone = 0;
            imgTotal = 0;
            File page = new File(tmp, "sayfa.mht");
            if (!saveArchive(ctx, url, page, 60000)) { delete(tmp); throw new IOException("bölüm kaydedilemedi"); }
            idx.put("archive", page.getName());
            idx.put("images", new JSONArray());
        }
        idx.put("at", System.currentTimeMillis());
        writeFile(new File(tmp, "index.json"), idx.toString());
        delete(dir);
        if (!tmp.renameTo(dir)) throw new IOException("kaydedilemedi");
    }

    /**
     * Resim resim indirir ve kontrol eder. Bozuk resim tekrar indirilir; küçük (ikon/reklam) resimler atılır.
     * Sonuç şüpheliyse (çok az resim, çok hata) false döner, sayfa olarak kaydedilir.
     */
    private static boolean saveImages(Context ctx, String series, double num, String url, File tmp, JSONObject idx) throws Exception {
        // Önce hafif yol: sayfayı düz indirip resim adreslerini oradan bul; olmazsa görünmez tarayıcı
        List<String> imgs = pickImages(grabFast(ctx, url));
        if (imgs.size() < 3) imgs = pickImages(grab(ctx, url, 45000));
        if (imgs.size() < 3) return false;
        imgTotal = imgs.size();
        String ua = WebSettings.getDefaultUserAgent(ctx);
        JSONArray names = new JSONArray();
        int failed = 0;
        for (int i = 0; i < imgs.size(); i++) {
            if (paused(ctx)) throw new IOException("duraklatıldı");
            try {
                String name = download(ctx, imgs.get(i), url, ua, tmp, i);
                int[] wh = imageSize(new File(tmp, name));
                // Bölüm sayfası olamayacak kadar küçük resimleri (ikon, reklam) at
                if (wh != null && (wh[0] < 250 || wh[1] < 120)) new File(tmp, name).delete();
                else names.put(baseUrl(series, num) + name);
            } catch (IOException e) {
                failed++;
            }
            imgDone = i + 1;
            SystemClock.sleep(60); // ağı ve işlemciyi tek seferde boğmamak için
        }
        // Resimlerin %15'inden fazlası inmediyse ya da 3'ten az geçerli resim kaldıysa güvenme
        if (names.length() < 3 || failed > Math.max(1, imgs.size() * 0.15)) return false;
        idx.put("images", names);
        return true;
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
                if (type.startsWith("text/") || type.contains("html") || type.contains("json")) throw new IOException("resim değil: " + type);
                String ext = type.contains("png") ? ".png" : type.contains("webp") ? ".webp" : type.contains("gif") ? ".gif"
                    : type.contains("avif") ? ".avif" : ".jpg";
                String name = String.format(Locale.ROOT, "%03d%s", i, ext);
                File f = new File(dir, name);
                long expected = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[32768];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                }
                // Yarım inen ya da bozuk dosyayı tekrar indir
                if (expected > 0 && f.length() < expected) throw new IOException("yarım indi");
                int[] wh = imageSize(f);
                if (wh == null && !ext.equals(".avif")) throw new IOException("bozuk resim");
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

    /** Resmin boyutunu çözmeden okur; resim değilse ya da bozuksa null. */
    static int[] imageSize(File f) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        return o.outWidth > 0 && o.outHeight > 0 ? new int[]{o.outWidth, o.outHeight} : null;
    }

    /* ---------------- Sayfa olarak kaydetme (resimler düzgün inmezse) ---------------- */

    /** Bölüm sayfa olarak kaydedildiyse dosyası, değilse null. */
    static File archiveFile(Context ctx, String series, double num) {
        JSONObject idx = index(ctx, series, num);
        if (idx == null || !idx.has("archive")) return null;
        File f = new File(chapterDir(ctx, series, num), idx.optString("archive"));
        return f.isFile() ? f : null;
    }

    /** Tembel yüklenen resimleri hemen yükletir; hepsi yüklenince/zaman dolunca 'ok' döner. */
    private static final String EAGER_JS =
        "(function(){document.querySelectorAll('img').forEach(function(i){i.loading='eager';"
        + "var d=i.getAttribute('data-src')||i.getAttribute('data-lazy-src')||i.getAttribute('data-original');"
        + "if(d&&(!i.getAttribute('src')||i.getAttribute('src').indexOf('data:')===0||i.src.indexOf('loading')>=0))i.src=d;});"
        + "var imgs=[].slice.call(document.images).filter(function(i){return i.src&&i.src.indexOf('data:')!==0;});"
        + "var left=imgs.filter(function(i){return !i.complete;}).length;return imgs.length+':'+left;})()";

    /** Bölüm sayfasını görünmez tarayıcıda açar, resimleri yükletir ve sayfayı tek dosya (MHTML) olarak kaydeder. */
    static boolean saveArchive(Context ctx, String url, File out, long timeoutMs) {
        try {
            if (!HIDDEN_WEB.tryAcquire(3, TimeUnit.MINUTES)) return false;
        } catch (InterruptedException e) {
            return false;
        }
        Handler main = new Handler(Looper.getMainLooper());
        CountDownLatch done = new CountDownLatch(1);
        boolean[] ok = {false};
        WebView[] holder = new WebView[1];
        try {
            main.post(() -> {
                try {
                    WebView w = new WebView(ctx);
                    holder[0] = w;
                    WebSettings st = w.getSettings();
                    st.setJavaScriptEnabled(true);
                    st.setDomStorageEnabled(true);
                    w.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                    if (android.os.Build.VERSION.SDK_INT >= 26) w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true);
                    w.setWebViewClient(new WebViewClient() {
                        @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail d) {
                            done.countDown();
                            return true;
                        }
                    });
                    w.loadUrl(url);
                    long start = SystemClock.uptimeMillis();
                    Runnable poll = new Runnable() {
                        @Override public void run() {
                            if (done.getCount() == 0) return;
                            boolean late = SystemClock.uptimeMillis() - start > timeoutMs;
                            if (w.getProgress() < 100 && !late) { main.postDelayed(this, 1000); return; }
                            w.evaluateJavascript(EAGER_JS, v -> {
                                String r = v == null ? "" : v.replace("\"", "");
                                String[] p = r.split(":");
                                int total = p.length == 2 ? safeInt(p[0]) : 0, left = p.length == 2 ? safeInt(p[1]) : 1;
                                if ((total >= 3 && left == 0) || late) {
                                    if (total < 3) { done.countDown(); return; }
                                    // Resimler yerleşsin diye kısa bekle, sonra kaydet
                                    main.postDelayed(() -> w.saveWebArchive(out.getPath(), false, path -> {
                                        ok[0] = path != null && out.length() > 50_000;
                                        done.countDown();
                                    }), 1500);
                                } else {
                                    main.postDelayed(this, 1500);
                                }
                            });
                        }
                    };
                    main.postDelayed(poll, 2500);
                } catch (Throwable t) {
                    done.countDown();
                }
            });
            try { done.await(timeoutMs + 15000, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        } finally {
            main.post(() -> { if (holder[0] != null) { holder[0].stopLoading(); holder[0].destroy(); } });
            HIDDEN_WEB.release();
        }
        return ok[0];
    }

    private static int safeInt(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }

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

    private static final java.util.regex.Pattern IMG_TAG = java.util.regex.Pattern.compile("<img\\b[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final String[] IMG_ATTRS = {"data-src", "data-lazy-src", "data-original", "data-url", "src"};

    /** Bölüm sayfasını düz HTTP ile indirip <img> adreslerini çıkarır (tarayıcı açmadan). */
    static List<String> grabFast(Context ctx, String url) {
        List<String> out = new ArrayList<>();
        try {
            HttpURLConnection c = Net.open(ctx, url);
            try {
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(ctx));
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8");
                c.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9");
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) c.setRequestProperty("Cookie", cookie);
                if (c.getResponseCode() != 200) return out;
                String html = Net.readAll(c.getInputStream());
                java.net.URL base = new java.net.URL(url);
                java.util.regex.Matcher m = IMG_TAG.matcher(html);
                while (m.find()) {
                    String tag = m.group();
                    for (String a : IMG_ATTRS) {
                        java.util.regex.Matcher v = java.util.regex.Pattern.compile("\\s" + a + "\\s*=\\s*[\"']([^\"']+)[\"']",
                            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(tag);
                        if (v.find()) {
                            String src = v.group(1).trim().replace("&amp;", "&");
                            if (!src.isEmpty() && !src.startsWith("data:")) {
                                try { out.add(new java.net.URL(base, src).toString()); } catch (Exception ignored) { }
                                break;
                            }
                        }
                    }
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Bölüm sayfasını görünmez tarayıcıda açar, kaydırır ve sayfadaki resim adreslerini döndürür. */
    static List<String> grab(Context ctx, String url, long timeoutMs) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("ana iş parçacığı");
        try {
            if (!HIDDEN_WEB.tryAcquire(3, TimeUnit.MINUTES)) throw new IOException("meşgul");
        } catch (InterruptedException e) {
            throw new IOException("kesildi");
        }
        try {
            return grabLocked(ctx, url, timeoutMs);
        } finally {
            HIDDEN_WEB.release();
        }
    }

    private static List<String> grabLocked(Context ctx, String url, long timeoutMs) {
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
                // Ekrandaki sayfalarla çizim yarışına girmesin: yazılımla çiz, ekrana hiç takma
                w.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true);
                }
                w.setWebViewClient(new WebViewClient() {
                    @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail d) {
                        done.countDown();
                        return true;
                    }
                });
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

    /**
     * Ayarlar ekranı için ayrıntı: verilen serilerden kopyası olanlar
     * [{"url":..,"nums":[..],"size":..,"busy":..}] ve listede olmayan kopyalar {"url":null,"size":..}.
     */
    static String details(Context ctx, JSONArray urls) {
        JSONArray out = new JSONArray();
        Set<String> known = new HashSet<>();
        try {
            for (int i = 0; i < urls.length(); i++) {
                String u = urls.getString(i);
                File d = seriesDir(ctx, u);
                known.add(d.getName());
                List<Double> ns = nums(ctx, u);
                if (ns.isEmpty() && !isBusy(u)) continue;
                JSONObject o = new JSONObject();
                o.put("url", u);
                JSONArray a = new JSONArray();
                for (double n : ns) a.put(n);
                o.put("nums", a);
                o.put("size", size(d));
                o.put("busy", isBusy(u));
                out.put(o);
            }
            long other = 0;
            File[] all = root(ctx).listFiles();
            if (all != null) for (File d : all) if (!known.contains(d.getName())) other += size(d);
            if (other > 0) {
                JSONObject o = new JSONObject();
                o.put("url", JSONObject.NULL);
                o.put("size", other);
                out.put(o);
            }
        } catch (Exception ignored) { }
        return out.toString();
    }

    /** Bir mangadaki tüm kopyaları siler (çevrimdışı ayarı kalır, sonra yeniden iner). */
    static void clearSeries(Context ctx, String series) { delete(seriesDir(ctx, series)); }

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
