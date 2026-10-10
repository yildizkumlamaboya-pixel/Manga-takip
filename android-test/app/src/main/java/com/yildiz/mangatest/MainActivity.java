package com.yildiz.mangatest;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Teşhis uygulaması: engelli sitelere telefonun kendi internetinden
 * iki yolla istek atar ve sonucu ekranda gösterir.
 *   1) Düz istek (arka plan işinin kullanacağı yöntem)
 *   2) Gizli tarayıcı / WebView (Cloudflare doğrulamasını geçebiliyor mu)
 */
public class MainActivity extends Activity {

    private static final String[] URLS = {
        "https://uzaymanga.com/manga/efsanevi-buyu-imparatoru",
        "https://mangawt.com/manga/iskelet-asker",
        "https://eskimangalar.com/manga/gecmise-donen-polisin-ikinci-sansi",
        "https://tenshimanga.com/manga/tanri-arenasinda-bir-oyuncu",
        "https://golgebahcesi.com/manga/olumun-oglu" // kontrol: engelsiz site
    };

    private static final String UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36";

    private TextView out;
    private Button runBtn;
    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final StringBuilder log = new StringBuilder();
    private final String[] httpResults = new String[URLS.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#14121a"));
        int pad = dp(16);
        root.setPadding(pad, dp(32), pad, pad);

        TextView title = new TextView(this);
        title.setText("Manga Takip – bağlantı testi");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Wi-Fi veya mobil veriyle (VPN kapalı) testi başlat. Bitince sonucu paylaş.");
        hint.setTextColor(Color.parseColor("#a39cb3"));
        hint.setPadding(0, dp(6), 0, dp(12));
        root.addView(hint);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        runBtn = new Button(this);
        runBtn.setText("Testi başlat");
        runBtn.setOnClickListener(v -> startTests());
        buttons.addView(runBtn);
        Button share = new Button(this);
        share.setText("Sonucu paylaş");
        share.setOnClickListener(v -> shareResult());
        buttons.addView(share);
        root.addView(buttons);

        ScrollView scroll = new ScrollView(this);
        out = new TextView(this);
        out.setTextColor(Color.parseColor("#f1eef6"));
        out.setTextSize(14);
        out.setTextIsSelectable(true);
        out.setPadding(0, dp(12), 0, dp(12));
        scroll.addView(out);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Gizli tarayıcı: ekranda 1 piksel, kullanıcı görmez
        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        root.addView(web, new LinearLayout.LayoutParams(1, 1));

        setContentView(root);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void append(String s) {
        main.post(() -> {
            log.append(s).append('\n');
            out.setText(log.toString());
        });
    }

    private void startTests() {
        runBtn.setEnabled(false);
        log.setLength(0);
        out.setText("");
        append("1) DÜZ İSTEK (arka plan yöntemi)");
        final int[] done = {0};
        for (int i = 0; i < URLS.length; i++) {
            final int idx = i;
            pool.execute(() -> {
                String r = httpTest(URLS[idx]);
                main.post(() -> {
                    httpResults[idx] = r;
                    done[0]++;
                    if (done[0] == URLS.length) {
                        for (String line : httpResults) append(line);
                        append("");
                        append("2) GİZLİ TARAYICI (WebView)");
                        webTest(0);
                    }
                });
            });
        }
    }

    private static String host(String url) {
        try { return new URL(url).getHost(); } catch (Exception e) { return url; }
    }

    private static String prefix(String url) {
        try { return new URL(url).getPath().replaceAll("/+$", ""); } catch (Exception e) { return ""; }
    }

    private static boolean isChallenge(String body) {
        return Pattern.compile("<title>\\s*(Just a moment|Attention Required|Bir dakika)", Pattern.CASE_INSENSITIVE).matcher(body).find()
            || body.contains("cf_chl_opt") || body.contains("cf-chl-widget");
    }

    private static int countChapterLinks(String html, String url) {
        String p = prefix(url);
        Matcher m = Pattern.compile("href\\s*=\\s*[\"']([^\"'#]+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        int n = 0;
        while (m.find()) {
            String href = m.group(1);
            try {
                String path = new URL(new URL(url), href).getPath().replaceAll("/+$", "");
                if (path.startsWith(p + "/") && path.substring(p.length()).matches(".*\\d.*")) n++;
            } catch (Exception ignored) { }
        }
        return n;
    }

    private String httpTest(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.6");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = "";
            if (in != null) {
                StringBuilder sb = new StringBuilder();
                BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                char[] buf = new char[8192];
                int r;
                while ((r = br.read(buf)) > 0 && sb.length() < 3_000_000) sb.append(buf, 0, r);
                body = sb.toString();
            }
            int links = countChapterLinks(body, url);
            boolean ok = code == 200 && !isChallenge(body) && links > 0;
            String why = isChallenge(body) ? "tarayıcı doğrulaması istedi" : (links == 0 ? "bölüm linki yok" : links + " bölüm linki");
            return (ok ? "✓ " : "✗ ") + host(url) + " — HTTP " + code + ", " + why;
        } catch (Exception e) {
            return "✗ " + host(url) + " — hata: " + e.getClass().getSimpleName() + " " + e.getMessage();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void webTest(int i) {
        if (i >= URLS.length) {
            append("");
            append("Test bitti. 'Sonucu paylaş' ile gönder.");
            runBtn.setEnabled(true);
            return;
        }
        final String url = URLS[i];
        final String p = prefix(url);
        final long start = System.currentTimeMillis();
        web.loadUrl(url);
        final String js =
            "(function(){var P=" + JSONObject.quote(p) + ";var n=0;" +
            "document.querySelectorAll('a[href]').forEach(function(a){try{var u=new URL(a.href);" +
            "var q=u.pathname.replace(/\\/+$/,'');if(q.indexOf(P+'/')===0&&/\\d/.test(q.slice(P.length)))n++;}catch(e){}});" +
            "return JSON.stringify({t:document.title,n:n});})()";

        Runnable poll = new Runnable() {
            @Override public void run() {
                web.evaluateJavascript(js, value -> {
                    String title = "";
                    int n = 0;
                    try {
                        String inner = new JSONArray("[" + value + "]").getString(0);
                        JSONObject o = new JSONObject(inner);
                        title = o.optString("t");
                        n = o.optInt("n");
                    } catch (Exception ignored) { }
                    long sec = (System.currentTimeMillis() - start) / 1000;
                    if (n > 0) {
                        append("✓ " + host(url) + " — " + n + " bölüm linki (" + sec + " sn)");
                        webTest(i + 1);
                    } else if (sec >= 25) {
                        append("✗ " + host(url) + " — 25 sn'de açılmadı, başlık: \"" + title + "\"");
                        webTest(i + 1);
                    } else {
                        main.postDelayed(this, 2000);
                    }
                });
            }
        };
        main.postDelayed(poll, 3000);
    }

    private void shareResult() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, log.toString());
        startActivity(Intent.createChooser(send, "Sonucu paylaş"));
    }
}
