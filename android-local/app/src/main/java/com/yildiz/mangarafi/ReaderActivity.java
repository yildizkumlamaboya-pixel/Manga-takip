package com.yildiz.mangarafi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
    private LinearLayout header;        // üst şerit + önceki/sonraki
    private View warmView, dimView;     // gece filtresi ve karartma katmanları
    private Button endFab, scrollFab;   // bölüm sonu ve otomatik kaydırma düğmeleri
    private ScrollView panel;           // Görünüm menüsü
    private boolean headerHidden = false;
    private boolean autoScroll = false; // otomatik kaydırma açık mı (duraklatılmış olabilir)
    private boolean scrolling = false;  // şu an akıyor mu
    private float scrollAcc = 0;
    private SharedPreferences rp;       // okuyucu ayarları
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

        rp = getSharedPreferences(Checker.PREFS, MODE_PRIVATE);

        // Katmanlar: sayfa, filtreler, üst şerit (sayfanın üstünde durur), yüzen düğmeler, menü
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(bg);

        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(Color.parseColor("#1f1c28"));
        header.setElevation(dp(4));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
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

        Button refresh = barButton("↻");
        refresh.setTextSize(20);
        refresh.setOnClickListener(v -> refresh());
        bar.addView(refresh);

        Button look = barButton("Aa");
        look.setTextSize(17);
        look.setTypeface(Typeface.DEFAULT_BOLD);
        look.setOnClickListener(v -> togglePanel());
        bar.addView(look);
        header.addView(bar);

        // Önceki / sonraki bölüm: büyük düğmeler
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(8), 0, dp(8), dp(8));
        prevBtn = navButton("‹  Önceki", false);
        prevBtn.setOnClickListener(v -> step(-1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.setMargins(0, 0, dp(4), 0);
        nav.addView(prevBtn, lp);
        nextBtn = navButton("Sonraki  ›", true);
        nextBtn.setOnClickListener(v -> step(1));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        rlp.setMargins(dp(4), 0, 0, 0);
        nav.addView(nextBtn, rlp);
        header.addView(nav);

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
            public void onPageCommitVisible(WebView view, String url) {
                addTopSpace();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                addTopSpace();
                view.evaluateJavascript(AUTO_JS, null);
                if (restoreY >= 0) restoreScroll();
                else noSaveUntil = System.currentTimeMillis() + 1500;
                onChapterShown(url);
            }
        });
        web.setOnScrollChangeListener((v, x, y, ox, oy) -> onPageScroll(y, y - oy));
        GestureDetector taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                // Tam ekranda dokununca düğmeler gelir/gider
                if (rp.getBoolean("r_full", true)) setHeaderHidden(!headerHidden);
                return false;
            }
        });
        web.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN && scrolling) pauseScroll();
            taps.onTouchEvent(e);
            return false;
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        warmView = overlay(Color.rgb(255, 147, 41));
        dimView = overlay(Color.BLACK);
        root.addView(warmView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(dimView, new FrameLayout.LayoutParams(-1, -1));

        root.addView(header, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        endFab = fab("Sonraki bölüm  ›", true);
        endFab.setOnClickListener(v -> step(1));
        FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(-2, dp(52), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ep.bottomMargin = dp(24);
        root.addView(endFab, ep);

        scrollFab = fab("❚❚", false);
        scrollFab.setOnClickListener(v -> { if (scrolling) pauseScroll(); else resumeScroll(); });
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM | Gravity.END);
        sp.bottomMargin = dp(24);
        sp.rightMargin = dp(16);
        root.addView(scrollFab, sp);

        panel = buildPanel();
        root.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);
        applyLook();

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

    private View overlay(int color) {
        View v = new View(this);
        v.setBackgroundColor(color);
        v.setAlpha(0f);
        v.setClickable(false);
        v.setFocusable(false);
        return v;
    }

    private Button fab(String text, boolean primary) {
        Button b = navButton(text, primary);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(28));
        bg.setColor(Color.parseColor(primary ? "#ff6b4a" : "#2a2635"));
        b.setBackground(bg);
        b.setElevation(dp(6));
        b.setPadding(dp(22), 0, dp(22), 0);
        b.setVisibility(View.GONE);
        return b;
    }

    /* ---------- Görünüm ayarları ---------- */

    /** Kaydedilmiş ayarları ekrana uygular. */
    private void applyLook() {
        warmView.setAlpha(rp.getInt("r_warm", 0) / 100f * 0.45f);
        dimView.setAlpha(rp.getInt("r_dim", 0) / 100f * 0.7f);
        WindowManager.LayoutParams w = getWindow().getAttributes();
        w.screenBrightness = rp.getBoolean("r_brightSys", true) ? -1f : Math.max(0.01f, rp.getInt("r_bright", 50) / 100f);
        getWindow().setAttributes(w);
        if (rp.getBoolean("r_awake", true)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!rp.getBoolean("r_full", true)) setHeaderHidden(false);
        scheduleBreak();
    }

    private TextView panelText(String t, int size, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(size);
        tv.setTextColor(Color.parseColor(bold ? "#f1eef6" : "#a9a3b8"));
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private void tint(SeekBar sb) {
        ColorStateList c = ColorStateList.valueOf(Color.parseColor("#ff6b4a"));
        sb.setProgressTintList(c);
        sb.setThumbTintList(c);
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#4a4458")));
    }

    private interface IntSink { void set(int v); }

    private void addSlider(LinearLayout box, String title, String key, int def, int max, IntSink onChange) {
        box.addView(panelText(title, 14, true), new LinearLayout.LayoutParams(-1, -2));
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(rp.getInt(key, def));
        tint(sb);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (!user) return;
                rp.edit().putInt(key, v).apply();
                onChange.set(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(40));
        lp.bottomMargin = dp(6);
        box.addView(sb, lp);
    }

    private CheckBox addCheck(LinearLayout box, String title, String key, boolean def, Runnable after) {
        CheckBox cb = new CheckBox(this);
        cb.setText(title);
        cb.setTextSize(15);
        cb.setTextColor(Color.parseColor("#f1eef6"));
        cb.setButtonTintList(ColorStateList.valueOf(Color.parseColor("#ff6b4a")));
        cb.setChecked(rp.getBoolean(key, def));
        cb.setOnCheckedChangeListener((v, on) -> { rp.edit().putBoolean(key, on).apply(); if (after != null) after.run(); });
        box.addView(cb, new LinearLayout.LayoutParams(-1, dp(44)));
        return cb;
    }

    private static final int[] BREAKS = {0, 20, 30, 45, 60};

    private String breakLabel() {
        int m = rp.getInt("r_break", 0);
        return m == 0 ? "Mola hatırlatıcı: Kapalı" : "Mola hatırlatıcı: " + m + " dakikada bir";
    }

    private ScrollView buildPanel() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1f1c28"));
        float r = dp(20);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sv.setBackground(bg);
        sv.setElevation(dp(12));
        sv.setClickable(true); // alttaki sayfaya dokunuş geçmesin

        TextView title = panelText("Görünüm", 18, true);
        title.setPadding(0, 0, 0, dp(10));
        box.addView(title);

        addSlider(box, "Gece filtresi (sıcak renk)", "r_warm", 0, 100, v -> applyLook());
        addSlider(box, "Karartma", "r_dim", 0, 100, v -> applyLook());

        box.addView(panelText("Parlaklık", 14, true));
        SeekBar bright = new SeekBar(this);
        bright.setMax(100);
        bright.setProgress(rp.getInt("r_bright", 50));
        tint(bright);
        CheckBox sys = new CheckBox(this);
        bright.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (!user) return;
                rp.edit().putInt("r_bright", v).putBoolean("r_brightSys", false).apply();
                sys.setChecked(false);
                applyLook();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(bright, new LinearLayout.LayoutParams(-1, dp(40)));
        sys.setText("Telefonun parlaklığını kullan");
        sys.setTextSize(15);
        sys.setTextColor(Color.parseColor("#f1eef6"));
        sys.setButtonTintList(ColorStateList.valueOf(Color.parseColor("#ff6b4a")));
        sys.setChecked(rp.getBoolean("r_brightSys", true));
        sys.setOnCheckedChangeListener((v, on) -> { rp.edit().putBoolean("r_brightSys", on).apply(); applyLook(); });
        box.addView(sys, new LinearLayout.LayoutParams(-1, dp(44)));

        View line = new View(this);
        line.setBackgroundColor(Color.parseColor("#2f2a3b"));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, dp(1));
        ll.setMargins(0, dp(8), 0, dp(8));
        box.addView(line, ll);

        addSlider(box, "Otomatik kaydırma hızı", "r_speed", 3, 9, v -> { });
        Button auto = navButton(autoScroll ? "Otomatik kaydırmayı durdur" : "Otomatik kaydırmayı başlat", false);
        auto.setTextSize(15);
        auto.setOnClickListener(v -> {
            if (autoScroll) stopAutoScroll(); else { startAutoScroll(); togglePanel(); }
            auto.setText(autoScroll ? "Otomatik kaydırmayı durdur" : "Otomatik kaydırmayı başlat");
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(46));
        ap.bottomMargin = dp(8);
        box.addView(auto, ap);

        addCheck(box, "Tam ekran (kaydırınca düğmeleri gizle)", "r_full", true, this::applyLook);
        addCheck(box, "Okurken ekran kararmasın", "r_awake", true, this::applyLook);
        addCheck(box, "Ses tuşlarıyla sayfa kaydır", "r_vol", false, null);

        Button brk = navButton(breakLabel(), false);
        brk.setTextSize(15);
        brk.setOnClickListener(v -> {
            int m = rp.getInt("r_break", 0), i = 0;
            for (int k = 0; k < BREAKS.length; k++) if (BREAKS[k] == m) i = k;
            rp.edit().putInt("r_break", BREAKS[(i + 1) % BREAKS.length]).apply();
            brk.setText(breakLabel());
            scheduleBreak();
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(46));
        bp.topMargin = dp(4);
        box.addView(brk, bp);

        Button ok = navButton("Tamam", true);
        ok.setOnClickListener(v -> togglePanel());
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, dp(48));
        op.topMargin = dp(12);
        box.addView(ok, op);

        sv.addView(box);
        sv.setVisibility(View.GONE);
        return sv;
    }

    private void togglePanel() {
        boolean show = panel.getVisibility() != View.VISIBLE;
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) { pauseScroll(); setHeaderHidden(false); }
    }

    /* ---------- Tam ekran ---------- */

    /** Üst şerit sayfanın üstünde durduğu için sayfanın başına o kadar boşluk eklenir. */
    private void addTopSpace() {
        int hp = header.getHeight();
        int ww = web.getWidth();
        if (hp <= 0 || ww <= 0) { header.post(this::addTopSpace); return; }
        web.evaluateJavascript("(function(hp,ww){if(!document.body||document.getElementById('__mtTop'))return;"
            + "var d=document.createElement('div');d.id='__mtTop';d.style.height=Math.round(hp*innerWidth/ww)+'px';"
            + "document.body.insertBefore(d,document.body.firstChild);})(" + hp + "," + ww + ")", null);
    }

    private void setHeaderHidden(boolean hide) {
        if (hide == headerHidden) return;
        if (hide && panel != null && panel.getVisibility() == View.VISIBLE) return;
        headerHidden = hide;
        header.animate().translationY(hide ? -header.getHeight() : 0).setDuration(180).start();
    }

    private void onPageScroll(int y, int dy) {
        if (rp.getBoolean("r_full", true) && !scrolling) {
            if (dy > 12 && y > header.getHeight()) setHeaderHidden(true);
            else if (dy < -12) setHeaderHidden(false);
        }
        // Bölümün sonuna gelince "Sonraki bölüm" düğmesi
        int i = indexOf(current);
        boolean atEnd = !web.canScrollVertically(1) && y > 0;
        boolean hasNext = i >= 0 && i < nums.size() - 1;
        if (atEnd && hasNext) endFab.setText("Sonraki bölüm: " + Parser.fmt(nums.get(i + 1)[0]) + "  ›");
        endFab.setVisibility(atEnd && hasNext ? View.VISIBLE : View.GONE);
        if (atEnd && scrolling) stopAutoScroll();
    }

    /* ---------- Otomatik kaydırma ---------- */

    private final Runnable scrollStep = new Runnable() {
        @Override public void run() {
            if (!scrolling) return;
            // hız 1..10 → saniyede 20..200 dp
            float pxPerFrame = (rp.getInt("r_speed", 3) + 1) * 20f * getResources().getDisplayMetrics().density / 60f;
            scrollAcc += pxPerFrame;
            int dy = (int) scrollAcc;
            if (dy > 0) { web.scrollBy(0, dy); scrollAcc -= dy; }
            if (!web.canScrollVertically(1)) { stopAutoScroll(); return; }
            handler.postDelayed(this, 16);
        }
    };

    private void startAutoScroll() {
        autoScroll = true;
        scrollFab.setVisibility(View.VISIBLE);
        resumeScroll();
    }

    private void resumeScroll() {
        if (!autoScroll) return;
        scrolling = true;
        scrollFab.setText("❚❚");
        if (rp.getBoolean("r_full", true)) setHeaderHidden(true);
        handler.removeCallbacks(scrollStep);
        handler.post(scrollStep);
    }

    private void pauseScroll() {
        if (!scrolling) return;
        scrolling = false;
        handler.removeCallbacks(scrollStep);
        scrollFab.setText("▶");
    }

    private void stopAutoScroll() {
        pauseScroll();
        autoScroll = false;
        scrollFab.setVisibility(View.GONE);
    }

    /* ---------- Ses tuşları ---------- */

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if ((k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_UP) && rp.getBoolean("r_vol", false)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                int dir = k == KeyEvent.KEYCODE_VOLUME_DOWN ? 1 : -1;
                web.evaluateJavascript("scrollBy({top:" + dir + "*innerHeight*0.8,behavior:'smooth'})", null);
            }
            return true; // ses değişmesin
        }
        return super.dispatchKeyEvent(e);
    }

    /* ---------- Mola hatırlatıcı ---------- */

    private final Runnable breakAlert = new Runnable() {
        @Override public void run() {
            int m = rp.getInt("r_break", 0);
            if (m == 0 || isFinishing()) return;
            pauseScroll();
            new AlertDialog.Builder(ReaderActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Mola zamanı 👀")
                .setMessage(m + " dakikadır okuyorsun. 20 saniye kadar uzağa bakıp gözlerini dinlendir.")
                .setPositiveButton("Tamam", null)
                .show();
            scheduleBreak();
        }
    };

    private void scheduleBreak() {
        handler.removeCallbacks(breakAlert);
        int m = rp.getInt("r_break", 0);
        if (m > 0) handler.postDelayed(breakAlert, m * 60_000L);
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
        stopAutoScroll();
        endFab.setVisibility(View.GONE);
        setHeaderHidden(false);
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
        scheduleBreak();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(saver);
        handler.removeCallbacks(breakAlert);
        pauseScroll();
        savePos(current);
        super.onPause();
    }

    private void close() {
        if (isTaskRoot()) startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    public void onBackPressed() {
        if (panel.getVisibility() == View.VISIBLE) { togglePanel(); return; }
        close(); // geri tuşu listeye döndürür
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
