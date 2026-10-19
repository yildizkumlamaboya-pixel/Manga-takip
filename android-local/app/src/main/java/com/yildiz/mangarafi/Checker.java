package com.yildiz.mangarafi;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Telefonda çalışan kontrol: listedeki her seriyi kendi sitesinden okur,
 * sonucu telefon hafızasına (state.json) yazar, yeni bölüm varsa bildirim gösterir.
 * Hiçbir sunucu ya da hesap kullanılmaz.
 */
public final class Checker {

    static final String CHANNEL = "yeni-bolum";
    static final String PREFS = "rafi";
    static final int NEWS_KEEP = 30;

    private static final String UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36";
    private static final Object RUN_LOCK = new Object();

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Map<String, String> sitemapCache = Collections.synchronizedMap(new HashMap<>());

    static Context appCtx;

    public Checker(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        appCtx = this.ctx;
        this.prefs = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Tüm serileri kontrol eder. */
    public String run() { return run(false); }

    /**
     * onlyMissing=true: sadece henüz hiç okunmamış seriler (yeni eklenenler).
     * Aynı anda tek kontrol çalışır; kısa bir özet döndürür.
     */
    public String run(boolean onlyMissing) {
        synchronized (RUN_LOCK) {
            String summary;
            try {
                summary = doRun(onlyMissing);
            } catch (Exception e) {
                summary = "Kontrol hatası: " + e.getMessage();
            }
            if (!onlyMissing) {
                prefs.edit()
                    .putString("lastRun", iso(new Date()))
                    .putString("lastSummary", summary)
                    .apply();
            }
            return summary;
        }
    }

    private String doRun(boolean onlyMissing) throws Exception {
        JSONArray list = Store.list(ctx);
        JSONObject state0 = Store.state(ctx);
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            String url = list.getJSONObject(i).optString("url", "");
            if (url.isEmpty()) continue;
            if (onlyMissing && state0.has(url)) continue;
            urls.add(url);
        }
        if (urls.isEmpty()) return onlyMissing ? "" : "Takip listesi boş";

        AtomicInteger ok = new AtomicInteger(), fail = new AtomicInteger(), fresh = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        for (String url : urls) {
            pool.execute(() -> {
                JSONObject prev = Store.state(ctx).optJSONObject(url);
                JSONObject entry;
                try {
                    entry = readSeries(url, prev);
                    ok.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                    entry = errorEntry(url, prev, e);
                }
                if (entry == null) return;
                int n = saveEntry(url, entry);
                if (n > 0) fresh.addAndGet(n);
            });
        }
        pool.shutdown();
        pool.awaitTermination(20, TimeUnit.MINUTES);

        StringBuilder sb = new StringBuilder();
        sb.append("Kontrol edildi: ").append(ok.get());
        if (fail.get() > 0) sb.append(", okunamayan: ").append(fail.get());
        if (fresh.get() > 0) sb.append(", yeni bölüm: ").append(fresh.get());
        return sb.toString();
    }

    /** Seriyi state.json'a yazar; yeni bölüm varsa kayda ekler ve bildirim gösterir. */
    private int saveEntry(String url, JSONObject entry) {
        int notified = 0;
        synchronized (Store.LOCK) {
            try {
                // Kontrol sürerken seri listeden çıkarıldıysa yazma
                boolean still = false, dropped = false;
                JSONArray list = Store.list(ctx);
                for (int i = 0; i < list.length(); i++) {
                    JSONObject it = list.getJSONObject(i);
                    if (!url.equals(it.optString("url"))) continue;
                    still = true;
                    String shelf = it.optString("shelf");
                    dropped = "dropped".equals(shelf) || "inbox".equals(shelf); // Arkadaştan rafı: bildirim yok
                }
                if (!still) return 0;

                JSONObject state = Store.state(ctx);
                JSONObject prev = state.optJSONObject(url);
                JSONArray log = new JSONArray();
                List<double[]> freshNums = new ArrayList<>();
                if (entry.isNull("error") && prev != null && prev.has("latest") && !prev.isNull("latest")) {
                    double before = prev.getDouble("latest");
                    String now = iso(new Date());
                    JSONArray chs = entry.getJSONArray("chapters");
                    for (int j = 0; j < chs.length(); j++) {
                        double num = chs.getJSONArray(j).getDouble(0);
                        if (num > before) {
                            freshNums.add(new double[]{num, j});
                            if (log.length() < NEWS_KEEP) {
                                JSONArray n = new JSONArray();
                                n.put(num);
                                n.put(now);
                                log.put(n);
                            }
                        }
                    }
                }
                JSONArray old = prev == null ? null : prev.optJSONArray("news");
                if (old != null) for (int j = 0; j < old.length() && log.length() < NEWS_KEEP; j++) log.put(old.get(j));
                if (log.length() > 0) entry.put("news", log);
                state.put(url, entry);
                Store.write(ctx, "state.json", state.toString());

                if (!freshNums.isEmpty() && !dropped) {
                    notify(url, entry, freshNums);
                    notified = freshNums.size();
                }
            } catch (Exception ignored) { }
        }
        return notified;
    }

    /** Okuyucuda açılan seri sayfasından bölümleri alıp kaydeder. Bulunan bölüm sayısını döndürür. */
    public int saveFromPage(String url, String html) throws Exception {
        Parser.Series s = Parser.parseSeriesPage(html, url);
        if (s.chapters.isEmpty()) return 0;
        JSONObject entry = buildEntry(url, Store.state(ctx).optJSONObject(url), s);
        saveEntry(url, entry);
        return entry.getJSONArray("chapters").length();
    }

    private JSONObject errorEntry(String url, JSONObject prev, Exception e) {
        try {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            JSONObject x = prev != null ? new JSONObject(prev.toString()) : new JSONObject();
            if (prev == null) {
                x.put("title", Parser.titleFromSlug(url));
                x.put("cover", JSONObject.NULL);
                x.put("latest", JSONObject.NULL);
                x.put("first", JSONObject.NULL);
                x.put("chapters", new JSONArray());
            }
            x.put("error", msg);
            if (prev == null || prev.isNull("errorSince") || !prev.has("errorSince")) x.put("errorSince", iso(new Date()));
            return x;
        } catch (Exception ex) {
            return null;
        }
    }

    private JSONObject readSeries(String url, JSONObject prev) throws Exception {
        return buildEntry(url, prev, fetchSeries(url));
    }

    /** Okunan seri sayfasından state kaydını oluşturur (önceki bölümler korunur, boşluklar doldurulur). */
    JSONObject buildEntry(String url, JSONObject prev, Parser.Series s) throws Exception {
        List<Parser.Chapter> previous = new ArrayList<>();
        if (prev != null) {
            JSONArray pc = prev.optJSONArray("chapters");
            if (pc != null) {
                for (int j = 0; j < pc.length(); j++) {
                    JSONArray c = pc.getJSONArray(j);
                    previous.add(new Parser.Chapter(c.getDouble(0), c.getString(1), ""));
                }
            }
        }
        List<Parser.Chapter> chapters = Parser.fillGaps(Parser.mergeChapters(s.chapters, previous));
        Parser.Chapter top = chapters.get(0);

        JSONObject e = new JSONObject();
        String title = s.title;
        if (title == null && prev != null && !prev.isNull("title")) title = prev.optString("title", null);
        e.put("title", title != null ? title : Parser.titleFromSlug(url));
        String cover = s.cover;
        if (cover == null && prev != null && !prev.isNull("cover")) cover = prev.optString("cover", null);
        e.put("cover", cover == null ? JSONObject.NULL : cover);
        e.put("latest", top.num);
        e.put("first", prev != null && prev.has("first") && !prev.isNull("first") ? prev.getDouble("first") : top.num);
        JSONArray arr = new JSONArray();
        for (Parser.Chapter c : chapters) {
            JSONArray pair = new JSONArray();
            pair.put(c.num);
            pair.put(c.url);
            arr.put(pair);
        }
        e.put("chapters", arr);
        e.put("error", JSONObject.NULL);
        e.put("errorSince", JSONObject.NULL);
        e.put("checkedAt", iso(new Date()));
        return e;
    }

    private Parser.Series fetchSeries(String url) throws Exception {
        String html;
        String pageUrl = url;
        try {
            try {
                html = httpGet(pageUrl);
            } catch (IOException e) {
                // Bazı siteler adresin sonunda "/" yoksa 404 veriyor (ör. merlintoon)
                if (!"HTTP 404".equals(e.getMessage()) || url.endsWith("/")) throw e;
                pageUrl = url + "/";
                html = httpGet(pageUrl);
            }
        } catch (IOException e) {
            // Bot koruması (403/503/doğrulama): sayfayı görünmez tarayıcıda aç
            String m = String.valueOf(e.getMessage());
            if (!(m.contains("403") || m.contains("503") || m.contains("429") || m.contains("doğrulama"))) throw e;
            html = WebFetcher.fetch(ctx, pageUrl, 25000);
        }
        Parser.Series s = Parser.parseSeriesPage(html, url);
        URL u = new URL(url);
        List<String> paths = Parser.sitemapPaths(u.getHost());
        if (s.chapters.isEmpty() || !paths.isEmpty()) {
            List<Parser.Chapter> fromSitemap = sitemapChapters(url, paths);
            if (!fromSitemap.isEmpty()) s.chapters = fromSitemap;
        }
        if (s.chapters.isEmpty()) throw new IOException("bölüm bulunamadı");
        return s;
    }

    private List<Parser.Chapter> sitemapChapters(String url, List<String> paths) {
        String origin;
        try {
            URL u = new URL(url);
            origin = u.getProtocol() + "://" + u.getAuthority();
        } catch (Exception e) {
            return new ArrayList<>();
        }
        List<String> tries = new ArrayList<>();
        for (String p : paths) tries.add(origin + p);
        tries.add(origin + "/sitemap.xml");
        for (String sm : tries) {
            try {
                String xml = cachedGet(sm);
                for (String child : Parser.pickSitemapChildren(Parser.sitemapChildren(xml))) {
                    try {
                        List<Parser.Chapter> found = Parser.chaptersFromSitemap(cachedGet(child), url);
                        if (!found.isEmpty()) return found;
                    } catch (Exception ignored) { }
                }
                List<Parser.Chapter> found = Parser.chaptersFromSitemap(xml, url);
                if (!found.isEmpty()) return found;
            } catch (Exception ignored) { }
        }
        return new ArrayList<>();
    }

    private String cachedGet(String url) throws IOException {
        String v = sitemapCache.get(url);
        if (v == null) {
            v = httpGet(url);
            sitemapCache.put(url, v);
        }
        return v;
    }

    /* ---------------- Ağ ---------------- */

    static String readAll(InputStream in, int max) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) > 0) {
            bos.write(buf, 0, r);
            if (bos.size() > max) break;
        }
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    static String httpGet(String url) throws IOException {
        HttpURLConnection c = Net.open(appCtx, url); // güvenli DNS açıksa yerel vekil üzerinden
        try {
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.6");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            String body = readAll(c.getInputStream(), 20_000_000);
            if (Parser.isChallenge(body)) throw new IOException("tarayıcı doğrulaması istedi");
            return body;
        } finally {
            c.disconnect();
        }
    }

    /* ---------------- Bildirim ---------------- */

    static final String CHANNEL_QUIET = "yeni-bolum-sessiz";

    /** Sessiz saatlerdeysek bildirim sessiz kanaldan gider (ses/titreşim yok, yine görünür). */
    static String channelNow(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean("quiet_on", false)) return CHANNEL;
        int from = p.getInt("quiet_from", 0), to = p.getInt("quiet_to", 8);
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        boolean quiet = from <= to ? (h >= from && h < to) : (h >= from || h < to);
        return quiet ? CHANNEL_QUIET : CHANNEL;
    }

    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Yeni bölümler", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Takip ettiğin serilerde yeni bölüm çıkınca");
            nm.createNotificationChannel(ch);
            NotificationChannel q = new NotificationChannel(CHANNEL_QUIET, "Yeni bölümler (sessiz saatler)", NotificationManager.IMPORTANCE_LOW);
            q.setDescription("Sessiz saatlerde sessizce gelen bildirimler");
            nm.createNotificationChannel(q);
        }
    }

    private void notify(String url, JSONObject entry, List<double[]> fresh) throws Exception {
        if (Build.VERSION.SDK_INT >= 33
            && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        ensureChannel(ctx);
        JSONArray chs = entry.getJSONArray("chapters");
        double latest = entry.getDouble("latest");
        double oldest = latest;
        for (double[] f : fresh) if (f[0] < oldest) oldest = f[0];
        String title = entry.optString("title", url);
        String text = fresh.size() <= 1
            ? "Bölüm " + Parser.fmt(latest) + " çıktı"
            : fresh.size() + " yeni bölüm (" + Parser.fmt(oldest) + "–" + Parser.fmt(latest) + ")";
        int id = url.hashCode();
        // Bildirime dokununca ilk okunmamış yeni bölüm uygulama içi okuyucuda açılır
        Intent open = ReaderActivity.intent(ctx, url, title, chs.toString(), oldest);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, channelNow(ctx)) : new Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(id, b.build());
    }

    static String iso(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(d);
    }
}
