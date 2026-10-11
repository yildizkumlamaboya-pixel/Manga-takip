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
                String summary = new Checker(MainActivity.this).run();
                main.post(() -> web.evaluateJavascript(
                    "window.__nativeCheckDone && window.__nativeCheckDone(" + JSONObject.quote(summary) + ")", null));
            }).start();
        }

        @JavascriptInterface
        public void openReader(String seriesUrl, String title, String chaptersJson, double num) {
            main.post(() -> startActivity(ReaderActivity.intent(MainActivity.this, seriesUrl, title, chaptersJson, num)));
        }

        /** Okuyucunun bu seride kaydettiği konum: {"num":..,"p":..} ya da boş. */
        @JavascriptInterface
        public String getPosition(String seriesUrl) {
            return prefs().getString(ReaderActivity.posKey(seriesUrl), "");
        }

        @JavascriptInterface
        public String lastCheck() {
            return prefs().getString("lastRun", "") + "|" + prefs().getString("lastSummary", "");
        }
    }
}
