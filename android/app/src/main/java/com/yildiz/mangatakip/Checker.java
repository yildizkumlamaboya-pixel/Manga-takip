package com.yildiz.mangatakip;

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
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Telefondan yapılan kontrol:
 *  1) GitHub'daki list.json ve state.json okunur.
 *  2) GitHub'ın okuyamadığı (engelli) seriler telefonun internetiyle okunur,
 *     sonuç state.json'a yazılır.
 *  3) Tüm seriler için yeni bölüm varsa telefonda bildirim gösterilir.
 */
public final class Checker {

    static final String OWNER = "yildizkumlamaboya-pixel";
    static final String REPO = "Manga-takip";
    static final String BRANCH = "data";
    static final String CHANNEL = "yeni-bolum";
    static final String PREFS = "mt";

    private static final String UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36";
    private static final Object LOCK = new Object();

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Map<String, String> sitemapCache = new HashMap<>();

    public Checker(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Kontrolü çalıştırır, kısa bir özet döndürür. Aynı anda tek kontrol çalışır. */
    public String run() {
        synchronized (LOCK) {
            String summary;
            try {
                summary = doRun();
            } catch (Exception e) {
                summary = "Kontrol hatası: " + e.getMessage();
            }
            prefs.edit()
                .putString("lastRun", iso(new Date()))
                .putString("lastSummary", summary)
                .apply();
            return summary;
        }
    }

    private String doRun() throws Exception {
        String token = prefs.getString("token", "");
        if (token.isEmpty()) return "GitHub anahtarı yok";

        GhFile listFile = ghGet(token, "list.json");
        JSONArray list = new JSONArray(listFile.text == null ? "[]" : listFile.text);
        GhFile stateFile = ghGet(token, "state.json");
        JSONObject state = new JSONObject(stateFile.text == null ? "{}" : stateFile.text);

        Map<String, JSONObject> updated = new HashMap<>();
        int ok = 0, fail = 0;
        for (int i = 0; i < list.length(); i++) {
            String url = list.getJSONObject(i).getString("url");
            JSONObject prev = state.optJSONObject(url);
            // Telefon her seriyi kendisi kontrol eder (GitHub'ın zamanlanmış kontrolü saatlerce gecikebiliyor)
            try {
                JSONObject entry = readSeries(url, prev);
                state.put(url, entry);
                updated.put(url, entry);
                ok++;
            } catch (Exception e) {
                fail++;
                // GitHub da okuyamıyorsa telefonun hatasını da yaz (ekranda gerçek sebep görünsün)
                if (prev == null || (!prev.isNull("error") && prev.optString("error", "").length() > 0)) {
                    try {
                        JSONObject x = prev != null ? new JSONObject(prev.toString()) : new JSONObject();
                        if (prev == null) {
                            x.put("title", Parser.titleFromSlug(url));
                            x.put("cover", JSONObject.NULL);
                            x.put("latest", JSONObject.NULL);
                            x.put("first", JSONObject.NULL);
                            x.put("chapters", new JSONArray());
                            x.put("error", "okunamadı");
                        }
                        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        if (!msg.equals(x.optString("phoneError"))) {
                            x.put("phoneError", msg);
                            state.put(url, x);
                            updated.put(url, x);
                        }
                    } catch (Exception ignored) { }
                }
            }
        }

        if (!updated.isEmpty()) saveState(token, state, stateFile.sha, updated);
        int notified = notifyNew(list, state);

        StringBuilder sb = new StringBuilder();
        sb.append("Telefondan okunan: ").append(ok);
        if (fail > 0) sb.append(", okunamayan: ").append(fail);
        if (notified > 0) sb.append(", yeni bölüm: ").append(notified);
        return sb.toString();
    }

    /** GitHub okuyamadıysa (hata var), hiç okunmadıysa ya da daha önce telefondan okunduysa. */
    private static boolean needsPhone(JSONObject prev) {
        if (prev == null) return true;
        if ("phone".equals(prev.optString("via"))) return true;
        return !prev.isNull("error") && prev.optString("error", "").length() > 0;
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
        e.put("title", s.title != null ? s.title : Parser.titleFromSlug(url));
        String cover = s.cover;
        e.put("cover", cover == null ? JSONObject.NULL : cover);
        e.put("latest", top.num);
        e.put("first", prev != null && !prev.isNull("first") && prev.has("first") ? prev.getDouble("first") : top.num);
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
        e.put("via", "phone");
        e.put("phoneAt", iso(new Date()));

        // Yeni bölümler sekmesi için kayıt: [[num, zaman], ...] en yeni başta
        JSONArray log = new JSONArray();
        if (prev != null && !prev.isNull("latest") && prev.has("latest")) {
            double before = prev.getDouble("latest");
            String now = iso(new Date());
            for (Parser.Chapter c : chapters) {
                if (c.num > before && log.length() < 30) {
                    JSONArray n = new JSONArray();
                    n.put(c.num);
                    n.put(now);
                    log.put(n);
                }
            }
        }
        JSONArray old = prev == null ? null : prev.optJSONArray("news");
        if (old != null) for (int j = 0; j < old.length() && log.length() < 30; j++) log.put(old.get(j));
        if (log.length() > 0) e.put("news", log);
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

    private static String readAll(InputStream in, int max) throws IOException {
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
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
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

    /* ---------------- GitHub ---------------- */

    static final class GhFile {
        String text;
        String sha;
    }

    static final class ConflictException extends IOException {
        ConflictException(String m) { super(m); }
    }

    private static HttpURLConnection gh(String token, String path, String method, String accept) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + OWNER + "/" + REPO + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "manga-takip-android");
        return c;
    }

    static GhFile ghGet(String token, String name) throws Exception {
        GhFile f = new GhFile();
        HttpURLConnection c = gh(token, "/contents/" + name + "?ref=" + BRANCH, "GET", "application/vnd.github+json");
        try {
            int code = c.getResponseCode();
            if (code == 404) return f;
            if (code != 200) throw new IOException("GitHub " + code);
            JSONObject j = new JSONObject(readAll(c.getInputStream(), 50_000_000));
            f.sha = j.optString("sha", null);
            String content = j.optString("content", "");
            if (!content.isEmpty()) {
                f.text = new String(Base64.decode(content, Base64.DEFAULT), StandardCharsets.UTF_8);
                return f;
            }
        } finally {
            c.disconnect();
        }
        // 1 MB üstü dosyalar ham olarak alınır
        HttpURLConnection raw = gh(token, "/contents/" + name + "?ref=" + BRANCH, "GET", "application/vnd.github.raw+json");
        try {
            if (raw.getResponseCode() != 200) throw new IOException("GitHub " + raw.getResponseCode());
            f.text = readAll(raw.getInputStream(), 50_000_000);
        } finally {
            raw.disconnect();
        }
        return f;
    }

    static void ghPut(String token, String name, String text, String sha, String message) throws Exception {
        JSONObject body = new JSONObject();
        body.put("message", message);
        body.put("branch", BRANCH);
        body.put("content", Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        if (sha != null) body.put("sha", sha);
        HttpURLConnection c = gh(token, "/contents/" + name, "PUT", "application/vnd.github+json");
        try {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = c.getResponseCode();
            if (code == 409 || code == 422) throw new ConflictException("çakışma");
            if (code != 200 && code != 201) throw new IOException("GitHub yazma " + code);
        } finally {
            c.disconnect();
        }
    }

    /** state.json'u yazar; arada GitHub da yazdıysa güncelini alıp telefondan okunanları tekrar ekler. */
    private void saveState(String token, JSONObject state, String sha, Map<String, JSONObject> updated) throws Exception {
        try {
            ghPut(token, "state.json", state.toString(1) + "\n", sha, "Telefondan kontrol");
        } catch (ConflictException e) {
            GhFile fresh = ghGet(token, "state.json");
            JSONObject merged = new JSONObject(fresh.text == null ? "{}" : fresh.text);
            for (Map.Entry<String, JSONObject> en : updated.entrySet()) merged.put(en.getKey(), en.getValue());
            ghPut(token, "state.json", merged.toString(1) + "\n", fresh.sha, "Telefondan kontrol");
        }
    }

    /** Okuyucuda açılan seri sayfasından bölümleri alıp kaydeder. Bulunan bölüm sayısını döndürür. */
    public int saveFromPage(String url, String html) throws Exception {
        Parser.Series s = Parser.parseSeriesPage(html, url);
        if (s.chapters.isEmpty()) return 0;
        synchronized (LOCK) {
            String token = prefs.getString("token", "");
            if (token.isEmpty()) return 0;
            GhFile stateFile = ghGet(token, "state.json");
            JSONObject state = new JSONObject(stateFile.text == null ? "{}" : stateFile.text);
            JSONObject entry = buildEntry(url, state.optJSONObject(url), s);
            state.put(url, entry);
            Map<String, JSONObject> updated = new HashMap<>();
            updated.put(url, entry);
            saveState(token, state, stateFile.sha, updated);
            return entry.getJSONArray("chapters").length();
        }
    }

    /* ---------------- Bildirim ---------------- */

    private int notifyNew(JSONArray list, JSONObject state) throws Exception {
        JSONObject seen = new JSONObject(prefs.getString("notified", "{}"));
        int count = 0;
        for (int i = 0; i < list.length(); i++) {
            String url = list.getJSONObject(i).getString("url");
            JSONObject st = state.optJSONObject(url);
            if (st == null || st.isNull("latest")) continue;
            double latest = st.getDouble("latest");
            if ("dropped".equals(list.getJSONObject(i).optString("shelf"))) {
                seen.put(url, latest); // bıraktığın seri: bildirim yok
                continue;
            }
            if (!seen.has(url)) {
                seen.put(url, latest); // ilk kez görülüyor: bildirim yok
                continue;
            }
            double before = seen.getDouble(url);
            if (latest <= before) continue;

            JSONArray chs = st.optJSONArray("chapters");
            int n = 0;
            double oldest = latest;
            String oldestUrl = null;
            if (chs != null) {
                for (int j = 0; j < chs.length(); j++) {
                    JSONArray c = chs.getJSONArray(j);
                    double num = c.getDouble(0);
                    if (num > before) {
                        n++;
                        if (num <= oldest) { oldest = num; oldestUrl = c.getString(1); }
                    }
                }
            }
            String title = st.optString("title", url);
            String text = n <= 1
                ? "Bölüm " + Parser.fmt(latest) + " çıktı"
                : n + " yeni bölüm (" + Parser.fmt(oldest) + "–" + Parser.fmt(latest) + ")";
            show(url.hashCode(), title, text, url, chs == null ? "[]" : chs.toString(), oldest);
            seen.put(url, latest);
            count++;
        }
        prefs.edit().putString("notified", seen.toString()).apply();
        return count;
    }

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

    private void show(int id, String title, String text, String seriesUrl, String chaptersJson, double num) {
        if (Build.VERSION.SDK_INT >= 33
            && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        ensureChannel(ctx);
        // Bildirime dokununca bölüm uygulama içi okuyucuda açılır
        Intent open = ReaderActivity.intent(ctx, seriesUrl, title, chaptersJson, num);
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
