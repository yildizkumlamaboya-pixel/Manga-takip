package com.yildiz.mangarafi;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Uygulama ekranı: arayüz uygulamanın içinden (assets/web) yüklenir.
 * Veriler telefonda durur; arayüz "MangaApp" köprüsüyle okuyup yazar.
 */
public class MainActivity extends Activity {

    static final String APP_HOST = "rafi.local";
    static final String APP_URL = "https://" + APP_HOST + "/index.html";
    private static final int REQ_EXPORT = 11, REQ_IMPORT = 12;

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger running = new AtomicInteger();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int bg = Color.parseColor("#14121a");
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        web = new WebView(this);
        web.setBackgroundColor(bg);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "MangaApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (APP_HOST.equals(u.getHost())) return false;
                // Dış linkler (sitenin kendisi) telefonun tarayıcısında açılır
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!APP_HOST.equals(u.getHost())) return null;
                return asset(u.getPath());
            }
        });
        setContentView(web);
        web.loadUrl(APP_URL);

        Checker.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        CheckJob.schedule(this);
        Net.applyWebView(this);                                  // gömülü güvenli DNS
        new Thread(() -> Offline.cleanup(getApplicationContext())).start(); // 7 günü dolan kopyalar
    }

    /** Arayüz dosyalarını uygulamanın içinden verir. */
    private WebResourceResponse asset(String path) {
        if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
        if (path.contains("..")) return notFound();
        String mime = "text/plain";
        if (path.endsWith(".html")) mime = "text/html";
        else if (path.endsWith(".js")) mime = "text/javascript";
        else if (path.endsWith(".css")) mime = "text/css";
        else if (path.endsWith(".png")) mime = "image/png";
        else if (path.endsWith(".svg")) mime = "image/svg+xml";
        try {
            InputStream in = getAssets().open("web" + path);
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-store");
            return new WebResourceResponse(mime, "utf-8", 200, "OK", h, in);
        } catch (Exception e) {
            return notFound();
        }
    }

    private WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<>(),
            new java.io.ByteArrayInputStream(new byte[0]));
    }

    private boolean firstResume = true;

    @Override
    protected void onResume() {
        super.onResume();
        // Okuyucudan dönünce listeyi tazele (kaldığın yer güncellenmiş olur)
        if (!firstResume) js("window.__nativeCheckDone && window.__nativeCheckDone('')");
        firstResume = false;
    }

    private long lastBack = 0;

    /** Geri: önce açık pencereyi kapat, ana ekranda iki kez basınca çık. */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.__nativeBack ? window.__nativeBack() : false", handled -> {
            if ("true".equals(handled)) return;
            long now = System.currentTimeMillis();
            if (now - lastBack < 2000) {
                finish();
            } else {
                lastBack = now;
                Toast.makeText(this, "Çıkmak için tekrar geri bas", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void js(String code) {
        main.post(() -> web.evaluateJavascript(code, null));
    }

    private void jsToast(String msg) {
        js("window.__nativeToast && window.__nativeToast(" + JSONObject.quote(msg) + ")");
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(Checker.PREFS, MODE_PRIVATE);
    }

    private void startCheck(boolean onlyMissing) {
        running.incrementAndGet();
        new Thread(() -> {
            String summary;
            try {
                summary = new Checker(MainActivity.this).run(onlyMissing);
            } finally {
                running.decrementAndGet();
            }
            js("window.__nativeCheckDone && window.__nativeCheckDone(" + JSONObject.quote(summary) + ")");
            Offline.ensureAll(MainActivity.this, Store.list(MainActivity.this), Store.state(MainActivity.this), 0);
        }).start();
    }

    /* ---------------- Yedek ---------------- */

    private void startExport() {
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "manga-rafi-yedek-" + day + ".json");
        startActivityForResult(i, REQ_EXPORT);
    }

    private void startImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            try {
                if (req == REQ_EXPORT) {
                    JSONObject b = new JSONObject();
                    b.put("app", "manga-rafi");
                    b.put("version", 1);
                    b.put("exportedAt", Checker.iso(new Date()));
                    b.put("list", Store.list(this));
                    b.put("state", Store.state(this));
                    try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                        os.write(b.toString(1).getBytes(StandardCharsets.UTF_8));
                    }
                    jsToast("Yedek kaydedildi");
                } else if (req == REQ_IMPORT) {
                    String text;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        text = Checker.readAll(in, 100_000_000);
                    }
                    int n = importData(text.trim());
                    js("window.__nativeImported && window.__nativeImported(" + n + ")");
                }
            } catch (Exception e) {
                jsToast("Olmadı: " + e.getMessage());
            }
        }).start();
    }

    /** Yedek dosyasını (ya da sadece liste dizisini) telefona yazar. Seri sayısını döndürür. */
    private int importData(String text) throws Exception {
        JSONArray list;
        JSONObject state = new JSONObject();
        if (text.startsWith("[")) {
            list = new JSONArray(text);
        } else {
            JSONObject b = new JSONObject(text);
            list = b.getJSONArray("list");
            if (b.has("state")) state = b.getJSONObject("state");
        }
        for (int i = 0; i < list.length(); i++) {
            if (list.getJSONObject(i).optString("url", "").isEmpty()) throw new Exception("dosya tanınmadı");
        }
        synchronized (Store.LOCK) {
            Store.write(this, "list.json", list.toString(1));
            Store.write(this, "state.json", state.toString());
        }
        return list.length();
    }

    /** Arayüzden çağrılan köprü (window.MangaApp). */
    final class Bridge {
        @JavascriptInterface
        public boolean isApp() { return true; }

        @JavascriptInterface
        public boolean isLocal() { return true; }

        @JavascriptInterface
        public String read(String name) { return Store.read(MainActivity.this, name); }

        /** Boş metin: tamam; değilse hata mesajı. */
        @JavascriptInterface
        public String write(String name, String text) {
            try {
                Store.write(MainActivity.this, name, text);
                return "";
            } catch (Exception e) {
                return "Kaydedilemedi: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public void checkNow() { startCheck(false); }

        @JavascriptInterface
        public void checkNew() { startCheck(true); }

        @JavascriptInterface
        public boolean isChecking() { return running.get() > 0; }

        @JavascriptInterface
        public void openReader(String seriesUrl, String title, String chaptersJson, double num) {
            main.post(() -> startActivity(ReaderActivity.intent(MainActivity.this, seriesUrl, title, chaptersJson, num)));
        }

        /** Çevrimdışı okuma özeti: {"n":..,"nums":[..],"size":..,"busy":..} */
        @JavascriptInterface
        public String getOffline(String seriesUrl) { return Offline.summary(MainActivity.this, seriesUrl); }

        /** Bu manga için sıradaki n bölümü çevrimdışı tut (0 = kapalı). */
        @JavascriptInterface
        public void setOffline(String seriesUrl, int n, String chaptersJson, double base) {
            Offline.setWanted(MainActivity.this, seriesUrl, n);
            if (n <= 0) return;
            try { Offline.ensure(MainActivity.this, seriesUrl, new JSONArray(chaptersJson), base); } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public double offlineTotal() { return Offline.size(Offline.root(MainActivity.this)); }

        /** Ayarlar için: hangi mangada hangi bölümler kayıtlı, ne kadar yer kaplıyor. */
        @JavascriptInterface
        public String offlineDetails(String urlsJson) {
            try { return Offline.details(MainActivity.this, new JSONArray(urlsJson)); } catch (Exception e) { return "[]"; }
        }

        @JavascriptInterface
        public void clearOfflineSeries(String seriesUrl) { Offline.clearSeries(MainActivity.this, seriesUrl); }

        @JavascriptInterface
        public void clearOfflineOthers(String urlsJson) {
            try { Offline.clearOthers(MainActivity.this, new JSONArray(urlsJson)); } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void clearOffline() { new Thread(() -> Offline.clearAll(MainActivity.this)).start(); }

        /** Uygulama ayarları (sessiz saatler): quiet_on, quiet_from, quiet_to. */
        @JavascriptInterface
        public String getSetting(String key) {
            if ("dns_on".equals(key)) return String.valueOf(Net.dnsOn(MainActivity.this));
            if ("offline_wifi".equals(key)) return String.valueOf(Offline.wifiOnly(MainActivity.this));
            if ("quiet_on".equals(key)) return String.valueOf(prefs().getBoolean(key, false));
            if ("quiet_from".equals(key)) return String.valueOf(prefs().getInt(key, 0));
            if ("quiet_to".equals(key)) return String.valueOf(prefs().getInt(key, 8));
            return "";
        }

        @JavascriptInterface
        public void setSetting(String key, String value) {
            try {
                if ("dns_on".equals(key)) Net.setDnsOn(MainActivity.this, "true".equals(value));
                else if ("offline_wifi".equals(key)) Offline.setWifiOnly(MainActivity.this, "true".equals(value));
                else if ("quiet_on".equals(key)) prefs().edit().putBoolean(key, "true".equals(value)).apply();
                else if ("quiet_from".equals(key) || "quiet_to".equals(key))
                    prefs().edit().putInt(key, Math.max(0, Math.min(23, Integer.parseInt(value)))).apply();
            } catch (Exception ignored) { }
        }

        /** Metni telefonun paylaş menüsüyle gönderir (WhatsApp, Telegram vb.). */
        @JavascriptInterface
        public void share(String text) {
            main.post(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "Listeyi paylaş"));
            });
        }

        /** Telefondaki tüm yeni bölüm bildirimlerini kaldırır. */
        @JavascriptInterface
        public void clearNotifications() {
            ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancelAll();
        }

        /** Bir serinin bildirimini kaldırır (bildirim kimliği seri adresinden üretilir). */
        @JavascriptInterface
        public void clearNotification(String seriesUrl) {
            ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(seriesUrl.hashCode());
        }

        /** Okuyucunun bu seride kaydettiği konum: {"num":..,"p":..} ya da boş. */
        @JavascriptInterface
        public String getPosition(String seriesUrl) {
            return prefs().getString(ReaderActivity.posKey(seriesUrl), "");
        }

        /** Seri sayfasını uygulamada açar; bölümler sayfadan alınır (bot korumalı siteler için). */
        @JavascriptInterface
        public void openSite(String seriesUrl, String title) {
            main.post(() -> startActivity(ReaderActivity.pageIntent(MainActivity.this, seriesUrl, title)));
        }

        @JavascriptInterface
        public String lastCheck() {
            return prefs().getString("lastRun", "") + "|" + prefs().getString("lastSummary", "");
        }

        @JavascriptInterface
        public void exportBackup() { main.post(MainActivity.this::startExport); }

        @JavascriptInterface
        public void importBackup() { main.post(MainActivity.this::startImport); }

        /** Eski Manga Takip (GitHub) listesini herkese açık depodan alır: "kullanıcı/depo". */
        @JavascriptInterface
        public void importGithub(String repo) {
            new Thread(() -> {
                try {
                    String r = repo == null ? "" : repo.trim()
                        .replaceFirst("^https?://github\\.com/", "").replaceAll("/+$", "");
                    if (!r.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new Exception("\"kullanıcı/depo\" biçiminde yaz");
                    String base = "https://raw.githubusercontent.com/" + r + "/data/";
                    String list = Checker.httpGet(base + "list.json");
                    String state;
                    try { state = Checker.httpGet(base + "state.json"); } catch (Exception e) { state = "{}"; }
                    JSONObject b = new JSONObject();
                    b.put("list", new JSONArray(list));
                    JSONObject st = new JSONObject(state);
                    // GitHub sürümüne özel alanları temizle
                    JSONArray names = st.names();
                    if (names != null) for (int i = 0; i < names.length(); i++) {
                        JSONObject e = st.optJSONObject(names.getString(i));
                        if (e != null) { e.remove("via"); e.remove("phoneAt"); }
                    }
                    b.put("state", st);
                    int n = importData(b.toString());
                    js("window.__nativeImported && window.__nativeImported(" + n + ")");
                } catch (Exception e) {
                    jsToast("Alınamadı: " + e.getMessage());
                }
            }).start();
        }

        @JavascriptInterface
        public boolean batteryFree() {
            if (Build.VERSION.SDK_INT < 23) return true;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm.isIgnoringBatteryOptimizations(getPackageName());
        }

        @JavascriptInterface
        public void askBatteryFree() {
            main.post(() -> {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                    catch (Exception ignored) { }
                }
            });
        }
    }
}
