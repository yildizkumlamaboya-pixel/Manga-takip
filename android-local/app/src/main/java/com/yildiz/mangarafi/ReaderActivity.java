package com.yildiz.mangarafi;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Uygulama içi okuyucu: bölümü sitenin kendi sayfası olarak gösterir.
 * Üst şeritte listeye dönüş ve önceki/sonraki bölüm düğmeleri vardır.
 * Açılan bölüm okundu sayılır (kaldığın yer sadece ileri gider).
 */
public class ReaderActivity extends Activity {

    static final String EXTRA_SERIES = "series";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_CHAPTERS = "chapters"; // [[num,url], ...]
    static final String EXTRA_NUM = "num";

    private WebView web;
    private TextView label;
    private Button prevBtn, nextBtn;
    private String seriesUrl, seriesTitle, seriesPath, host;
    private final List<double[]> nums = new ArrayList<>(); // artan sırada [num]
    private final List<String> urls = new ArrayList<>();
    private double current = -1;
    private int restoreY = -1;          // yenilemeden sonra inilecek yer (piksel)
    private double restoreRatio = -1;   // sayfa boyu değişirse oranla
    private long noSaveUntil = 0;       // sayfa yüklenip kaldığın yere inene kadar konum kaydedilmez
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable saver = new Runnable() {
        @Override public void run() {
            savePos(current);
            handler.postDelayed(this, 4000);
        }
    };

    /** Yüklenemeyen resimleri yerinde tekrar dener; yeniden denenen resim sayısını döndürür. */
    private static final String FIX_JS =
        "(function(){var n=0;document.querySelectorAll('img').forEach(function(i){"
        + "if(i.complete&&i.naturalWidth===0&&(i.currentSrc||i.src)){var s=i.currentSrc||i.src;"
        + "i.removeAttribute('src');setTimeout(function(){i.src=s;},60);n++;}});return n;})()";

    /** Her sayfada: resim yüklenemezse 1,5 sn sonra kendiliğinden 2 kez daha dener. */
    private static final String AUTO_JS =
        "(function(){if(window.__mtRetry)return;window.__mtRetry=1;"
        + "function retry(i){var k=+(i.dataset.mtTry||0);if(k>=2)return;i.dataset.mtTry=k+1;"
        + "var s=i.currentSrc||i.src;if(!s)return;setTimeout(function(){i.removeAttribute('src');"
        + "setTimeout(function(){i.src=s;},60);},1500);}"
        + "document.addEventListener('error',function(e){if(e.target&&e.target.tagName==='IMG')retry(e.target);},true);"
        + "document.querySelectorAll('img').forEach(function(i){if(i.complete&&i.naturalWidth===0)retry(i);});})()";

    /** Okuyucuyu açan Intent. */
    static Intent intent(Context ctx, String seriesUrl, String title, String chaptersJson, double num) {
        Intent i = new Intent(ctx, ReaderActivity.class);
        i.putExtra(EXTRA_SERIES, seriesUrl);
        i.putExtra(EXTRA_TITLE, title);
        i.putExtra(EXTRA_CHAPTERS, chaptersJson);
        i.putExtra(EXTRA_NUM, num);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int bg = Color.parseColor("#14121a");
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        Intent in = getIntent();
        seriesUrl = in.getStringExtra(EXTRA_SERIES);
        seriesTitle = in.getStringExtra(EXTRA_TITLE);
        seriesPath = Parser.seriesPath(seriesUrl);
        try { host = new URL(seriesUrl).getHost(); } catch (Exception e) { host = ""; }
        loadChapters(in.getStringExtra(EXTRA_CHAPTERS));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#1f1c28"));
        int p = dp(4);
        bar.setPadding(p, p, p, p);

        Button back = barButton("← Liste");
        back.setOnClickListener(v -> close());
        bar.addView(back);

        label = new TextView(this);
        label.setTextColor(Color.parseColor("#f1eef6"));
        label.setTextSize(14);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(dp(6), 0, dp(6), 0);
        bar.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button refresh = barButton("↻ Yenile");
        refresh.setOnClickListener(v -> refresh());
        bar.addView(refresh);

        root.addView(bar);

        // Önceki / sonraki bölüm: büyük düğmeler
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(Color.parseColor("#1f1c28"));
        nav.setPadding(dp(8), 0, dp(8), dp(8));
        prevBtn = navButton("‹  Önceki", false);
        prevBtn.setOnClickListener(v -> step(-1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.setMargins(0, 0, dp(4), 0);
        nav.addView(prevBtn, lp);
        nextBtn = navButton("Sonraki  ›", true);
        nextBtn.setOnClickListener(v -> step(1));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        rp.setMargins(dp(4), 0, 0, 0);
        nav.addView(nextBtn, rp);
        root.addView(nav);

        web = new WebView(this);
        web.setBackgroundColor(bg);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                // Sitenin kendi sayfaları okuyucuda kalır; reklam yönlendirmeleri engellenir
                return u.getHost() == null || !u.getHost().endsWith(host.replaceFirst("^www\\.", ""));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(AUTO_JS, null);
                if (restoreY >= 0) restoreScroll();
                else noSaveUntil = System.currentTimeMillis() + 1500;
                onChapterShown(url);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        open(in.getDoubleExtra(EXTRA_NUM, -1));
    }

    private Button barButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor("#f1eef6"));
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setMinWidth(dp(44));
        b.setMinimumWidth(dp(44));
        return b;
    }

    private Button navButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(Color.parseColor(primary ? "#ff6b4a" : "#2a2635"));
        b.setBackground(bg);
        b.setTextColor(Color.parseColor(primary ? "#1a0d08" : "#f1eef6"));
        b.setStateListAnimator(null);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /**
     * Yenile: önce bozuk resimleri sayfayı kapatmadan tekrar dener (yerin değişmez).
     * Bozuk resim bulunamazsa sayfayı yeniden yükler ve kaldığın yere geri iner.
     */
    private void refresh() {
        web.evaluateJavascript(FIX_JS, n -> {
            int count = 0;
            try { count = Integer.parseInt(n == null ? "0" : n.trim()); } catch (Exception ignored) { }
            if (count > 0) {
                Toast.makeText(this, count + " resim yeniden yükleniyor", Toast.LENGTH_SHORT).show();
                return;
            }
            web.evaluateJavascript("scrollY+','+document.documentElement.scrollHeight", pos -> {
                try {
                    String[] p = pos.replace("\"", "").split(",");
                    restoreY = (int) Double.parseDouble(p[0]);
                    double h = Double.parseDouble(p[1]);
                    restoreRatio = h > 0 ? restoreY / h : -1;
                } catch (Exception e) {
                    restoreY = -1;
                }
                Toast.makeText(this, "Sayfa yenileniyor…", Toast.LENGTH_SHORT).show();
                noSaveUntil = Long.MAX_VALUE;
                web.reload();
            });
        });
    }

    /** Resimler yüklendikçe sayfa uzar; 10 sn boyunca kaldığın yere tekrar tekrar iner. Dokununca bırakır. */
    private void restoreScroll() {
        int y = restoreY;
        double r = restoreRatio;
        restoreY = -1;
        noSaveUntil = System.currentTimeMillis() + 11000;
        web.evaluateJavascript("(function(){var y=" + y + ",r=" + r + ",t=0,stop=false;"
            + "addEventListener('touchstart',function(){stop=true;},{once:true,passive:true});"
            + "var h=setInterval(function(){if(stop||t++>40){clearInterval(h);return;}"
            + "var H=document.documentElement.scrollHeight,target=(H>=y+innerHeight)?y:(r>0?r*H:y);"
            + "if(Math.abs(scrollY-target)>40)scrollTo(0,target);},250);})()", null);
    }

    private void loadChapters(String json) {
        try {
            JSONArray arr = new JSONArray(json == null ? "[]" : json);
            List<Object[]> tmp = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONArray c = arr.getJSONArray(i);
                tmp.add(new Object[]{c.getDouble(0), c.getString(1)});
            }
            tmp.sort((a, b) -> Double.compare((double) a[0], (double) b[0]));
            for (Object[] c : tmp) {
                nums.add(new double[]{(double) c[0]});
                urls.add((String) c[1]);
            }
        } catch (Exception ignored) { }
    }

    private int indexOf(double num) {
        for (int i = 0; i < nums.size(); i++) if (nums.get(i)[0] == num) return i;
        return -1;
    }

    private void open(double num) {
        int i = indexOf(num);
        if (i < 0) {
            if (nums.isEmpty()) { finish(); return; }
            i = nums.size() - 1;
        }
        current = nums.get(i)[0];
        loadSavedPos(current);
        updateBar();
        web.loadUrl(urls.get(i));
    }

    private void step(int dir) {
        savePos(current);
        int i = indexOf(current);
        int j = i + dir;
        if (i < 0 || j < 0 || j >= nums.size()) return;
        open(nums.get(j)[0]);
    }

    private void updateBar() {
        int i = indexOf(current);
        label.setText((seriesTitle == null ? "" : seriesTitle + " · ") + "Bölüm " + Parser.fmt(current));
        prevBtn.setEnabled(i > 0);
        prevBtn.setAlpha(i > 0 ? 1f : 0.35f);
        nextBtn.setEnabled(i >= 0 && i < nums.size() - 1);
        nextBtn.setAlpha(i >= 0 && i < nums.size() - 1 ? 1f : 0.35f);
    }

    /** Sayfa yüklendi: sitenin kendi düğmesiyle geçildiyse bölüm numarasını linkten bul. */
    private void onChapterShown(String url) {
        try {
            String path = new URL(url).getPath().replaceAll("/+$", "");
            if (path.startsWith(seriesPath + "/")) {
                Double n = Parser.numFromPath(path.substring(seriesPath.length() + 1));
                if (n != null) current = n;
            }
        } catch (Exception ignored) { }
        updateBar();
        markRead(current);
    }

    private void markRead(double num) {
        if (num < 0) return;
        final String series = seriesUrl;
        final Context ctx = getApplicationContext();
        new Thread(() -> Store.markRead(ctx, series, num)).start();
    }

    /* ---------- Kaldığın satır ---------- */
    // Her seri için en son okunan bölümün konumu saklanır: {num, y, h, p} (p = okunan oran)

    static String posKey(String seriesUrl) { return "pos:" + seriesUrl; }

    private void loadSavedPos(double num) {
        restoreY = -1;
        noSaveUntil = Long.MAX_VALUE; // yükleme bitene kadar
        try {
            String t = getSharedPreferences(Checker.PREFS, MODE_PRIVATE).getString(posKey(seriesUrl), null);
            if (t == null) return;
            JSONObject o = new JSONObject(t);
            if (o.getDouble("num") != num || o.getDouble("p") >= 0.95 || o.getInt("y") < 300) return;
            restoreY = o.getInt("y");
            double h = o.getDouble("h");
            restoreRatio = h > 0 ? restoreY / h : -1;
            Toast.makeText(this, "Kaldığın yere iniliyor…", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) { }
    }

    private void savePos(double num) {
        if (num < 0 || web == null || System.currentTimeMillis() < noSaveUntil) return;
        final String key = posKey(seriesUrl);
        web.evaluateJavascript("scrollY+','+document.documentElement.scrollHeight+','+innerHeight", v -> {
            try {
                if (System.currentTimeMillis() < noSaveUntil) return;
                String[] p = v.replace("\"", "").split(",");
                int y = (int) Double.parseDouble(p[0]);
                double h = Double.parseDouble(p[1]);
                double vh = Double.parseDouble(p[2]);
                if (h <= vh * 1.5) return; // sayfa daha yüklenmemiş
                JSONObject o = new JSONObject();
                o.put("num", num);
                o.put("y", y);
                o.put("h", h);
                o.put("p", Math.min(1, (y + vh) / h));
                o.put("at", System.currentTimeMillis());
                getSharedPreferences(Checker.PREFS, MODE_PRIVATE).edit().putString(key, o.toString()).apply();
            } catch (Exception ignored) { }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.postDelayed(saver, 4000);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(saver);
        savePos(current);
        super.onPause();
    }

    private void close() {
        if (isTaskRoot()) startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    public void onBackPressed() {
        close(); // geri tuşu listeye döndürür
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
