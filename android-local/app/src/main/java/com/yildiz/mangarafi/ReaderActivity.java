package com.yildiz.mangarafi;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
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

import org.json.JSONArray;

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
        updateBar();
        web.loadUrl(urls.get(i));
    }

    private void step(int dir) {
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
