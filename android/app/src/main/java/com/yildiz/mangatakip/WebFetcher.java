package com.yildiz.mangatakip;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Bot korumalı siteler (403 / "tarayıcı doğrulaması") için: sayfayı görünmez bir
 * tarayıcıda (WebView) açar, doğrulama kendiliğinden geçince sayfanın HTML'ini döndürür.
 * Okuyucuyla aynı çerezleri kullanır. Resimler indirilmez.
 */
final class WebFetcher {
    private WebFetcher() { }

    static String fetch(Context ctx, String url, long timeoutMs) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("ana iş parçacığında çalıştırılamaz");
        // Aynı anda tek görünmez tarayıcı (çevrimdışı indirmeyle ortak kilit)
        try {
            if (!Offline.HIDDEN_WEB.tryAcquire(2, TimeUnit.MINUTES)) throw new IOException("meşgul");
        } catch (InterruptedException e) {
            throw new IOException("kesildi");
        }
        try {
            return fetchLocked(ctx, url, timeoutMs);
        } finally {
            Offline.HIDDEN_WEB.release();
        }
    }

    private static String fetchLocked(Context ctx, String url, long timeoutMs) throws IOException {
        Handler main = new Handler(Looper.getMainLooper());
        CountDownLatch done = new CountDownLatch(1);
        String[] out = new String[1];
        WebView[] holder = new WebView[1];
        main.post(() -> {
            try {
                WebView w = new WebView(ctx.getApplicationContext());
                holder[0] = w;
                WebSettings s = w.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setLoadsImagesAutomatically(false);
                s.setBlockNetworkImage(true);
                w.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
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
                int[] last = {-1};
                Runnable poll = new Runnable() {
                    @Override public void run() {
                        if (done.getCount() == 0) return;
                        w.evaluateJavascript("document.documentElement?document.documentElement.outerHTML:''", v -> {
                            String html = unquote(v);
                            // Bölüm listesi sonradan yüklenebilir: sayı iki okumada aynı kalınca hazır say
                            int n = html.isEmpty() ? 0 : Parser.parseSeriesPage(html, url).chapters.size();
                            boolean ready = w.getProgress() == 100 && n > 0 && n == last[0];
                            last[0] = n;
                            if (ready || SystemClock.uptimeMillis() - start > timeoutMs) {
                                out[0] = html;
                                done.countDown();
                            } else {
                                main.postDelayed(this, 1000);
                            }
                        });
                    }
                };
                main.postDelayed(poll, 2000);
            } catch (Throwable t) {
                done.countDown();
            }
        });
        try {
            done.await(timeoutMs + 8000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        main.post(() -> {
            if (holder[0] != null) {
                holder[0].stopLoading();
                holder[0].destroy();
            }
        });
        String html = out[0];
        if (html == null || html.isEmpty()) throw new IOException("tarayıcıyla da açılamadı");
        if (Parser.parseSeriesPage(html, url).chapters.isEmpty() && Parser.isChallenge(html))
            throw new IOException("tarayıcı doğrulaması geçilemedi");
        return html;
    }

    private static String unquote(String v) {
        if (v == null || v.equals("null")) return "";
        try { return new JSONArray("[" + v + "]").getString(0); } catch (Exception e) { return ""; }
    }
}
