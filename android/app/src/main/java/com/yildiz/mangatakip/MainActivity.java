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

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
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
        public String lastCheck() {
            return prefs().getString("lastRun", "") + "|" + prefs().getString("lastSummary", "");
        }
    }
}
