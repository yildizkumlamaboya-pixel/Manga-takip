package com.yildiz.mangatakip;

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
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Uygulama ekranı: GitHub Pages'teki arayüzü gösterir.
 * Arayüz "MangaApp" köprüsüyle telefondaki kontrol işine ulaşır.
 */
public class MainActivity extends Activity {

    static final String APP_URL = "https://yildizkumlamaboya-pixel.github.io/Manga-takip/";
    static final String APP_HOST = "yildizkumlamaboya-pixel.github.io";

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#14121a"));
        getWindow().setNavigationBarColor(Color.parseColor("#14121a"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#14121a"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.addJavascriptInterface(new Bridge(), "MangaApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (APP_HOST.equals(u.getHost())) return false;
                // Bölüm linkleri telefondaki tarayıcıda açılsın
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
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
        // Pil tasarrufu arka plan kontrolünü saatlerce geciktirebiliyor: bir kez izin iste
        if (Build.VERSION.SDK_INT >= 23 && !prefs().getBoolean("askedBattery", false)
            && !((android.os.PowerManager) getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName())) {
            prefs().edit().putBoolean("askedBattery", true).apply();
            main.postDelayed(this::askBattery, 1500);
        }
        new Thread(() -> Offline.cleanup(getApplicationContext())).start(); // 7 günü dolan kopyalar
    }

    private boolean firstResume = true;

    @Override
    protected void onResume() {
        super.onResume();
        // Okuyucudan dönünce listeyi tazele (kaldığın yer güncellenmiş olur)
        if (!firstResume) web.evaluateJavascript("window.__nativeCheckDone && window.__nativeCheckDone('')", null);
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

    private void askBattery() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try { startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Exception ignored) { }
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(Checker.PREFS, MODE_PRIVATE);
    }

    /** Arayüzden çağrılan köprü (window.MangaApp). */
    final class Bridge {
        @JavascriptInterface
        public boolean isApp() { return true; }

        @JavascriptInterface
        public void setToken(String token) {
            String old = prefs().getString("token", "");
            prefs().edit().putString("token", token == null ? "" : token.trim()).apply();
            if (old.isEmpty() && token != null && !token.trim().isEmpty()) checkNow();
        }

        @JavascriptInterface
        public void checkNow() {
            new Thread(() -> {
                Checker c = new Checker(MainActivity.this);
                String summary = c.run();
                main.post(() -> web.evaluateJavascript(
                    "window.__nativeCheckDone && window.__nativeCheckDone(" + JSONObject.quote(summary) + ")", null));
                if (c.lastList != null) Offline.ensureAll(MainActivity.this, c.lastList, c.lastState, 0);
            }).start();
        }

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

        @JavascriptInterface
        public boolean batteryFree() {
            if (Build.VERSION.SDK_INT < 23) return true;
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm.isIgnoringBatteryOptimizations(getPackageName());
        }

        @JavascriptInterface
        public void askBatteryFree() { main.post(MainActivity.this::askBattery); }

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
    }
}
