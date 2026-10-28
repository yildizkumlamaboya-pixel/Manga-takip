package com.yildiz.mangatakip;

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
import android.webkit.WebResourceResponse;
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
    static final String EXTRA_PAGE = "page"; // seri sayfasını aç, bölümleri oradan al

    private WebView web;
    private WebView pre;                // sıradaki bölümün arkada önden yüklendiği sayfa
    private String preUrl = null;
    private boolean preReady = false;
    private FrameLayout root;
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
    private boolean pageMode = false, harvested = false;
    private boolean offlineShown = false; // şu an çevrimdışı kopya gösteriliyor
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

    /** Seri sayfasını okuyucuda açar; sayfa yüklenince bölüm listesi oradan alınır. */
    static Intent pageIntent(Context ctx, String seriesUrl, String title) {
        Intent i = intent(ctx, seriesUrl, title, "[]", -1);
        i.putExtra(EXTRA_PAGE, true);
        return i;
    }

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
        Net.applyWebView(this); // gömülü güvenli DNS

        // Katmanlar: sayfa, filtreler, üst şerit (sayfanın üstünde durur), yüzen düğmeler, menü
        root = new FrameLayout(this);
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

        // Arkada (görünmez) sıradaki bölüm, önde okunan bölüm
        pre = makeWeb(bg);
        if (android.os.Build.VERSION.SDK_INT >= 26) pre.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true);
        root.addView(pre, new FrameLayout.LayoutParams(-1, -1));
        web = makeWeb(bg);
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

        pageMode = in.getBooleanExtra(EXTRA_PAGE, false);
        if (pageMode) openSeriesPage();
        else open(in.getDoubleExtra(EXTRA_NUM, -1));
    }

    /* ---------- Seri sayfasından bölümleri alma ---------- */

    private void openSeriesPage() {
        label.setText((seriesTitle == null ? "" : seriesTitle + " · ") + "seri sayfası");
        prevBtn.setEnabled(false);
        prevBtn.setAlpha(0.35f);
        nextBtn.setEnabled(false);
        nextBtn.setAlpha(0.35f);
        noSaveUntil = Long.MAX_VALUE;
        Toast.makeText(this, "Sayfa açılınca bölümleri alacağım…", Toast.LENGTH_SHORT).show();
        web.loadUrl(seriesUrl);
    }

    private int harvestLast = -1;
    private final Object harvestToken = new Object();

    /**
     * Seri sayfası açıkken HTML'i aralıklarla okur; bölüm sayısı artmayı bırakınca
     * (liste sonradan yükleniyor olabilir) bölümleri kaydeder.
     */
    private void harvest(int attempt) {
        if (harvested || isFinishing()) return;
        String cur = web.getUrl();
        String curPath = "";
        try { curPath = new URL(cur).getPath().replaceAll("/+$", ""); } catch (Exception ignored) { }
        if (!curPath.equals(seriesPath)) return; // kullanıcı başka sayfaya geçti
        web.evaluateJavascript("document.documentElement?document.documentElement.outerHTML:''", v -> {
            String html;
            try { html = new JSONArray("[" + v + "]").getString(0); } catch (Exception e) { html = ""; }
            int count = html.isEmpty() ? 0 : Parser.parseSeriesPage(html, seriesUrl).chapters.size();
            boolean stable = count > 0 && count == harvestLast && web.getProgress() == 100;
            harvestLast = count;
            if (!stable) {
                if (attempt < 30) handler.postAtTime(() -> harvest(attempt + 1), harvestToken, android.os.SystemClock.uptimeMillis() + 1500);
                else if (count > 0) { harvestLast = -2; harvestNow(html); }
                else Toast.makeText(this, "Bu sayfada bölüm listesi bulunamadı", Toast.LENGTH_LONG).show();
                return;
            }
            harvestNow(html);
        });
    }

    private void harvestNow(String html) {
        {
            harvested = true;
            final String page = html;
            final Context ctx = getApplicationContext();
            new Thread(() -> {
                int n;
                String err = null;
                try { n = new Checker(ctx).saveFromPage(seriesUrl, page); }
                catch (Exception e) { n = -1; err = e.getMessage(); }
                final int count = n;
                final String error = err;
                Parser.Series ps = Parser.parseSeriesPage(page, seriesUrl);
                runOnUiThread(() -> {
                    if (count > 0) {
                        nums.clear();
                        urls.clear();
                        List<Parser.Chapter> cs = new ArrayList<>(ps.chapters);
                        cs.sort((a, b) -> Double.compare(a.num, b.num));
                        for (Parser.Chapter c : cs) { nums.add(new double[]{c.num}); urls.add(c.url); }
                        Toast.makeText(this, count + " bölüm bulundu, listene eklendi. Bir bölüme dokunarak okumaya başlayabilirsin.", Toast.LENGTH_LONG).show();
                    } else if (count == 0) {
                        Toast.makeText(this, "Bu sayfada bölüm bulunamadı", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Kaydedilemedi: " + error, Toast.LENGTH_LONG).show();
                    }
                });
            }).start();
        }
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

    private WebView makeWeb(int bg) {
        WebView w = new WebView(this);
        w.setBackgroundColor(bg);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false); // açılır reklam pencereleri açılmaz
        s.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (Offline.HOST.equals(u.getHost())) return false;
                // Sitenin kendi sayfaları okuyucuda kalır; reklam yönlendirmeleri engellenir
                return u.getHost() == null || !u.getHost().endsWith(host.replaceFirst("^www\\.", ""));
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail d) {
                // Tarayıcı bileşeni çöktü: o sayfayı yenisiyle değiştir (beyaz ekranda kalmasın)
                handler.post(() -> replaceWeb(view));
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                if (Offline.HOST.equals(req.getUrl().getHost())) {
                    WebResourceResponse r = Offline.serve(getApplicationContext(), req.getUrl());
                    return r != null ? r : empty();
                }
                if (adsBlocked() && isAdHost(req.getUrl().getHost())) return empty();
                return null;
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                addTopSpace(view);
                if (adsBlocked()) { view.evaluateJavascript(AD_CSS_JS, null); view.evaluateJavascript(AD_CLEAN_JS, null); }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if ("about:blank".equals(url)) return;
                addTopSpace(view);
                view.evaluateJavascript(AUTO_JS, null);
                if (adsBlocked()) { view.evaluateJavascript(AD_CSS_JS, null); view.evaluateJavascript(AD_CLEAN_JS, null); }
                if (view != web) { onPreloaded(view); return; } // arkadaki önden yükleme
                offlineShown = url.startsWith("https://" + Offline.HOST) || url.startsWith("file:");
                if (pageMode && !harvested) {
                    handler.removeCallbacksAndMessages(harvestToken);
                    harvestLast = -1;
                    harvest(0);
                }
                if (restoreY >= 0) restoreScroll();
                else noSaveUntil = System.currentTimeMillis() + 1500;
                onChapterShown(url);
            }
        });
        w.setOnScrollChangeListener((v, x, y, ox, oy) -> { if (v == web) onPageScroll(y, y - oy); });
        GestureDetector taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                // Tam ekranda dokununca düğmeler gelir/gider
                if (rp.getBoolean("r_full", true)) setHeaderHidden(!headerHidden);
                return false;
            }
        });
        w.setOnTouchListener((v, e) -> {
            if (v != web) return true;
            if (e.getAction() == MotionEvent.ACTION_DOWN && scrolling) pauseScroll();
            taps.onTouchEvent(e);
            return false;
        });
        return w;
    }

    /* ---------- Reklam engelleyici ---------- */
    // Bilinen reklam/açılır pencere ağlarına giden istekler boş yanıtla kesilir.
    // Sitenin kendi adresi ve Cloudflare doğrulaması asla engellenmez (site güvenliğine takılmamak için).
    private static final String[] AD_HOSTS = {
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "googletagservices.com", "pagead2.googlesyndication.com", "amazon-adsystem.com", "adnxs.com",
        "criteo.com", "criteo.net", "pubmatic.com", "rubiconproject.com", "openx.net", "taboola.com",
        "outbrain.com", "mgid.com", "popads.net", "popcash.net", "propellerads.com", "propellerclick.com",
        "onclickads.net", "onclkds.com", "adsterra.com", "highperformanceformat.com",
        "profitabledisplaynetwork.com", "effectivegatecpm.com", "exoclick.com", "exosrv.com",
        "realsrv.com", "tsyndicate.com", "juicyads.com", "trafficjunky.net", "clickadu.com",
        "hilltopads.net", "a-ads.com", "adcash.com", "ad-maven.com", "admaven.com", "monetag.com",
        "adfox.ru", "an.yandex.ru", "yandexadexchange.net", "adskeeper.com", "adskeeper.co.uk",
        "bidvertiser.com", "revcontent.com", "zedo.com", "smartadserver.com", "adform.net",
        "yieldmo.com", "media.net", "infolinks.com", "chitika.com", "vidoomy.com", "ezoic.net",
        "adsco.re", "fundingchoicesmessages.google.com", "googleads.g.doubleclick.net", "tpc.googlesyndication.com", "ep1.adtrafficquality.google", "ep2.adtrafficquality.google", "ad.plus", "adtelligent.com", "betweendigital.com", "admatic.com.tr",
        "reklamstore.com", "reklamaction.com", "tr.adserver", "adgager.com",
        "acscdn.com", "aclib.net", "adtng.com", "pemsrv.com", "onclicka.com", "onclickalgo.com", "bidgear.com",
        "galaksion.com", "richads.com", "rtmark.net", "propu.sh", "pubfuture-ad.com", "pubfuture.com",
        "adpushup.com", "push-sdk.com", "pushame.com", "clickunder.ru", "admitad.com", "adsco.re",
        "dolohen.com", "whos.amung.us", "histats.com", "popmyads.com", "poptival.com", "popunder.net",
        "adxpremium.services", "notifpush.com", "pushwhy.com", "displayvertising.com", "wpadmngr.com",
        "landingtrk.com", "trafficstars.com", "tsyndicate.com", "magsrv.com", "a.magsrv.com"
    };

    /** Reklam kutularını gizleyen stil (sitenin kendi reklam alanları). */
    private static final String AD_CSS_JS =
        "(function(){if(document.getElementById('__mtAds'))return;var s=document.createElement('style');s.id='__mtAds';"
        + "s.textContent='ins.adsbygoogle,.adsbygoogle,[id^=div-gpt-ad],[id*=google_ads],iframe[src*=doubleclick],"
        + "iframe[src*=googlesyndication],iframe[src*=\"/ads\"],.ad-container,.ads-container,.advert,.advertisement,"
        + ".reklam,.reklamlar,[class*=reklam-],[id*=reklam],.ads-banner,.banner-ads,.ad-banner,.sticky-ads,"
        + ".popup-ads,.adsbox{display:none!important;visibility:hidden!important;height:0!important}';"
        + "(document.head||document.documentElement).appendChild(s);})()";

    /**
     * Ekranı kaplayan reklam pencerelerini temizler: sayfaya sonradan eklenen sabit (fixed) katmanlar
     * "indir / kazandın / bahis" gibi yazı ya da başka siteden çerçeve içeriyorsa, ya da sayfayı karartan
     * boş perde ise gizlenir. Okunan manga resimleri ve sitenin kendi menüleri dokunulmaz.
     */
    private static final String AD_CLEAN_JS =
        "(function(){if(window.__mtClean)return;window.__mtClean=1;"
        + "var bad=/(dosyay\\u0131 indir|dosyayi indir|indirme haz\\u0131r|indirme hazir|indir\\b|download|kazand\\u0131n|tebrikler|bahis|casino|bonus|\\u00f6d\\u00fcl|hemen t\\u0131kla|virus|vir\\u00fcs|g\\u00fcncelle)/i;"
        + "var AD='ins.adsbygoogle,.adsbygoogle-noablate,[data-vignette-loaded],[id*=google_vignette],iframe[id^=aswift],iframe[name^=aswift],iframe[id^=google_ads],iframe[src*=googleads],iframe[src*=doubleclick],iframe[src*=googlesyndication]';"
        + "function hide(e){e.style.setProperty('display','none','important');e.style.setProperty('visibility','hidden','important');e.__mtHid=1;}"
        + "function manga(e){return !!e.querySelector('img[src*=chapter],img[data-src],img[src*=uploads],img[src*=manga]');}"
        + "function check(e){if(!e||e.__mtHid||e.tagName==='SCRIPT'||e.tagName==='STYLE'||e.tagName==='LINK')return false;var cs=getComputedStyle(e);"
        + "if(cs.position!=='fixed'&&cs.position!=='sticky')return false;if(cs.display==='none')return false;var z=parseInt(cs.zIndex)||0;var r=e.getBoundingClientRect();"
        + "if(r.width<40||r.height<30)return false;var raw=(e.innerText||'').slice(0,500).replace(/\\u0130/g,'i');var txt=raw.toLowerCase()+' '+raw.replace(/I/g,'\\u0131').toLowerCase();"
        + "var area=r.width*r.height/(innerWidth*innerHeight);var ifr=e.tagName==='IFRAME'||!!e.querySelector('iframe');var m=manga(e);"
        + "var hit=(bad.test(txt)&&(z>=10||area>0.15))||(ifr&&!m&&(z>=10||area>0.25))||(area>0.5&&z>=100&&raw.trim().length<40&&!m&&!e.querySelector('img'));"
        + "if(hit){hide(e);return true;}return false;}"
        + "function walk(n,d){var hid=false;for(var i=0;i<n.children.length&&i<60;i++){var k=n.children[i];if(check(k))hid=true;else if(d<4&&k.children.length)hid=walk(k,d+1)||hid;}return hid;}"
        + "function clean(){if(!document.body)return;var hid=false;var a=document.querySelectorAll(AD);for(var i=0;i<a.length;i++){if(!manga(a[i])){hide(a[i]);hid=true;}}"
        + "if(walk(document.body,0))hid=true;"
        + "if(location.hash.indexOf('google_vignette')>=0){history.replaceState(null,'',location.href.split('#')[0]);hid=true;}"
        + "if(hid){document.documentElement.style.overflow='';document.body.style.overflow='';document.body.style.position='';document.documentElement.style.position='';}}"
        + "clean();setInterval(clean,700);var t=0;try{new MutationObserver(function(){if(t)return;t=setTimeout(function(){t=0;clean();},60);}).observe(document.documentElement,{childList:true,subtree:true});}catch(x){}"
        + "addEventListener('hashchange',clean);})()";

    private boolean adsBlocked() { return rp == null || rp.getBoolean("r_ads", true); }

    private boolean isAdHost(String h) {
        if (h == null) return false;
        h = h.toLowerCase(java.util.Locale.ROOT);
        String site = host.replaceFirst("^www\\.", "");
        if (h.endsWith(site) || h.endsWith("cloudflare.com") || h.endsWith("cloudflareinsights.com")) return false;
        for (String a : AD_HOSTS) if (h.equals(a) || h.endsWith("." + a)) return true;
        return false;
    }

    private static WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
    }

    /** Çöken sayfayı yenisiyle değiştirir; okunan sayfaysa bölümü yeniden açar. */
    private void replaceWeb(WebView dead) {
        int bg = Color.parseColor("#14121a");
        int idx = root.indexOfChild(dead);
        if (idx < 0) return;
        boolean wasFront = dead == web;
        root.removeView(dead);
        try { dead.destroy(); } catch (Exception ignored) { }
        WebView fresh = makeWeb(bg);
        root.addView(fresh, idx, new FrameLayout.LayoutParams(-1, -1));
        if (wasFront) {
            web = fresh;
            if (android.os.Build.VERSION.SDK_INT >= 26) web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
            Toast.makeText(this, "Sayfa yeniden yükleniyor…", Toast.LENGTH_SHORT).show();
            if (pageMode) web.loadUrl(seriesUrl); else open(current);
        } else {
            pre = fresh;
            preUrl = null;
            preReady = false;
        }
    }

    /* ---------- Sıradaki bölümü önden yükleme ---------- */

    /** Arkadaki sayfayı baştan sona kaydırıp resimlerin yüklenmesini sağlar, sonra başa döner. */
    private static final String PRE_JS =
        "(function(){if(window.__mtPre)return;var y=0;window.__mtPre=setInterval(function(){"
        + "document.querySelectorAll('img[loading=lazy]').forEach(function(i){i.loading='eager';});"
        + "y+=innerHeight;scrollTo(0,y);if(y>document.documentElement.scrollHeight+innerHeight){"
        + "clearInterval(window.__mtPre);window.__mtPre=-1;scrollTo(0,0);}},400);})()";

    private final Runnable preloadNext = this::startPreload;

    private void schedulePreload() {
        handler.removeCallbacks(preloadNext);
        if (pageMode || !rp.getBoolean("r_pre", true)) {
            if (preUrl != null) { preUrl = null; preReady = false; pre.stopLoading(); pre.loadUrl("about:blank"); }
            return;
        }
        handler.postDelayed(preloadNext, 800); // okunan bölüm açılır açılmaz sıradaki hazırlanır
    }

    private void startPreload() {
        int i = indexOf(current);
        if (i < 0 || i >= nums.size() - 1) return;
        String next = urls.get(i + 1);
        if (next.equals(preUrl) || Offline.has(this, seriesUrl, nums.get(i + 1)[0])) return;
        preUrl = next;
        preReady = false;
        pre.stopLoading();
        pre.loadUrl(next);
    }

    private void onPreloaded(WebView v) {
        if (preUrl == null) return;
        preReady = true;
        v.evaluateJavascript(PRE_JS, null);
    }

    /** Önden yüklenen sayfayı öne alır (Sonraki'ye basınca anında açılır). */
    private void showPreloaded(double num) {
        WebView old = web;
        web = pre;
        pre = old;
        root.removeView(old);
        root.addView(old, 0, new FrameLayout.LayoutParams(-1, -1));
        boolean ready = preReady;
        preUrl = null;
        preReady = false;
        pre.stopLoading();
        pre.loadUrl("about:blank");

        current = num;
        offlineShown = false;
        stopAutoScroll();
        endFab.setVisibility(View.GONE);
        setHeaderHidden(false);
        web.evaluateJavascript("if(window.__mtPre>0)clearInterval(window.__mtPre);window.__mtPre=-1;scrollTo(0,0)", null);
        web.scrollTo(0, 0);
        loadSavedPos(num);
        if (ready) {
            if (restoreY >= 0) restoreScroll();
            else noSaveUntil = System.currentTimeMillis() + 1500;
        }
        updateBar();
        markRead(current);
        schedulePreload();
        ensureOffline();
    }

    /** Bu manga için çevrimdışı açıksa kaldığın yerden sonraki bölümleri arka planda indir. */
    private void ensureOffline() {
        if (current < 0) return;
        if (Offline.wanted(this, seriesUrl) <= 0) { Offline.markReadUpTo(this, seriesUrl, current); return; }
        JSONArray arr = new JSONArray();
        for (int k = 0; k < nums.size(); k++) {
            JSONArray c = new JSONArray();
            try { c.put(nums.get(k)[0]); } catch (Exception ignored) { }
            c.put(urls.get(k));
            arr.put(c);
        }
        Offline.ensure(this, seriesUrl, arr, current);
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
        addCheck(box, "Sıradaki bölümü önden yükle", "r_pre", true, this::schedulePreload);
        addCheck(box, "Reklamları engelle", "r_ads", true, () -> web.reload());

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

        // Arka plan müziği
        View line2 = new View(this);
        line2.setBackgroundColor(Color.parseColor("#2f2a3b"));
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(-1, dp(1));
        l2.setMargins(0, dp(10), 0, dp(8));
        box.addView(line2, l2);
        box.addView(panelText("Arka plan müziği", 14, true));
        addCheck(box, "Okurken müzik çal", Music.ON, false, () -> {
            applyMusic();
        });
        musicSrcBtn = navButton(musicLabel(), false);
        musicSrcBtn.setTextSize(14);
        musicSrcBtn.setOnClickListener(v -> chooseMusicSource());
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, dp(46));
        mp.bottomMargin = dp(6);
        box.addView(musicSrcBtn, mp);
        addSlider(box, "Müzik sesi", Music.VOL, 40, 100, v -> {
            Music.setVolume(this, v);
            if (yt != null) yt.evaluateJavascript("window.player&&player.setVolume&&player.setVolume(" + v + ")", null);
        });

        Button reset = navButton("Ayarları sıfırla", false);
        reset.setTextSize(15);
        reset.setOnClickListener(v -> resetLook());
        LinearLayout.LayoutParams zp = new LinearLayout.LayoutParams(-1, dp(46));
        zp.topMargin = dp(12);
        box.addView(reset, zp);

        Button ok = navButton("Tamam", true);
        ok.setOnClickListener(v -> togglePanel());
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, dp(48));
        op.topMargin = dp(12);
        box.addView(ok, op);

        sv.addView(box);
        sv.setVisibility(View.GONE);
        return sv;
    }

    /* ---------- Müzik ---------- */
    private Button musicSrcBtn;
    private static final int REQ_MUSIC = 31;

    private String musicLabel() {
        String code = Music.source(this);
        String name = Music.sourceName(code);
        if ("files".equals(code)) name += " (" + Music.uris(this).size() + " parça)";
        if ("spotify".equals(code)) name = "Spotify · " + Music.spotifyName(this);
        if ("youtube".equals(code)) { String[] c = Music.ytCurrent(this); name = "YouTube · " + (c == null ? "video seçilmedi" : c[0]); }
        return "Kaynak: " + name;
    }

    private void chooseMusicSource() {
        String[] codes = Music.sourceCodes();
        String[] names = new String[codes.length];
        int checked = 0;
        for (int i = 0; i < codes.length; i++) {
            names[i] = Music.sourceName(codes[i]);
            if (codes[i].equals(Music.source(this))) checked = i;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Müzik kaynağı")
            .setSingleChoiceItems(names, checked, (d, which) -> {
                d.dismiss();
                if ("files".equals(codes[which])) { pickMusicFiles(); return; }
                if ("spotify".equals(codes[which])) { chooseSpotify(); return; }
                if ("youtube".equals(codes[which])) { chooseYouTube(); return; }
                Music.setSource(this, codes[which]);
                musicSrcBtn.setText(musicLabel());
                applyMusic();
            })
            .setNegativeButton("Vazgeç", null)
            .show();
    }

    /** Müziği ayara göre başlat/durdur (YouTube okuyucudaki küçük oynatıcıda çalar). */
    private void applyMusic() {
        if (Music.enabled(this)) Music.start(this); else Music.stop(this);
        updateYouTube();
    }

    /* ---------- YouTube küçük oynatıcı ---------- */
    private WebView yt;
    private LinearLayout ytBox;
    private String ytLoaded = null;

    private void updateYouTube() {
        boolean on = Music.enabled(this) && "youtube".equals(Music.source(this));
        String[] cur = on ? Music.ytCurrent(this) : null;
        if (cur == null) {
            if (ytBox != null) { ytBox.setVisibility(View.GONE); if (yt != null) yt.loadUrl("about:blank"); ytLoaded = null; }
            return;
        }
        if (ytBox == null) buildYtBox();
        ytBox.setVisibility(View.VISIBLE);
        String key = cur[1] + "|" + cur[2];
        if (key.equals(ytLoaded)) { yt.evaluateJavascript("window.player&&player.playVideo&&player.playVideo()", null); return; }
        ytLoaded = key;
        int vol = Music.prefs(this).getInt(Music.VOL, 40);
        String vid = cur[1], list = cur[2];
        String vars = "autoplay:1,playsinline:1,rel:0,modestbranding:1,origin:'https://mangatakip.app'"
            + (list.isEmpty() ? ",loop:1,playlist:'" + vid + "'" : ",listType:'playlist',list:'" + list + "',loop:1");
        String html = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<meta name=referrer content='strict-origin-when-cross-origin'>"
            + "<style>html,body{margin:0;background:#000;height:100%;overflow:hidden}#p{position:absolute;inset:0;width:100%;height:100%}</style>"
            + "</head><body><div id=p></div><script src='https://www.youtube.com/iframe_api'></script><script>"
            + "var player;function onYouTubeIframeAPIReady(){player=new YT.Player('p',{"
            + (vid.isEmpty() ? "" : "videoId:'" + vid + "',")
            + "playerVars:{" + vars + "},events:{onReady:function(e){e.target.setVolume(" + vol + ");e.target.playVideo();},"
            + "onError:function(e){MangaYT.error(String(e.data));}}});}</script></body></html>";
        yt.loadDataWithBaseURL("https://mangatakip.app/", html, "text/html", "utf-8", null);
    }

    private void ytPause() {
        if (yt != null) yt.evaluateJavascript("window.player&&player.pauseVideo&&player.pauseVideo()", null);
    }

    /** Sürüklenebilir küçük pencere: üstte tutma şeridi (♪ ad, ✕), altında YouTube oynatıcısı. */
    private void buildYtBox() {
        ytBox = new LinearLayout(this);
        ytBox.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1f1c28"));
        bg.setCornerRadius(dp(12));
        ytBox.setBackground(bg);
        ytBox.setClipToOutline(true);
        ytBox.setElevation(dp(10));

        LinearLayout strip = new LinearLayout(this);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(8), 0, dp(2), 0);
        TextView t = new TextView(this);
        t.setText("♪ müzik · sürükle");
        t.setTextColor(Color.parseColor("#a9a3b8"));
        t.setTextSize(11);
        strip.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = new TextView(this);
        x.setText("✕");
        x.setTextColor(Color.parseColor("#f1eef6"));
        x.setTextSize(15);
        x.setPadding(dp(10), dp(2), dp(10), dp(2));
        x.setOnClickListener(v -> {
            Music.prefs(this).edit().putBoolean(Music.ON, false).apply();
            applyMusic();
            Toast.makeText(this, "Müzik kapatıldı (Aa menüsünden açabilirsin)", Toast.LENGTH_SHORT).show();
        });
        strip.addView(x);
        ytBox.addView(strip, new LinearLayout.LayoutParams(-1, dp(26)));

        yt = new WebView(this);
        yt.setBackgroundColor(Color.BLACK);
        WebSettings s = yt.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        yt.setWebChromeClient(new android.webkit.WebChromeClient());
        yt.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                // YouTube logosuna basılırsa uygulama dışına çıkma
                return true;
            }
        });
        yt.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface public void error(String code) {
                runOnUiThread(() -> Toast.makeText(ReaderActivity.this,
                    "Bu video uygulama içinde oynatılamıyor (sahibi izin vermemiş). Başka bir video seç.", Toast.LENGTH_LONG).show());
            }
        }, "MangaYT");
        // 16:9 ve YouTube'un en küçük oynatıcı boyutundan büyük
        ytBox.addView(yt, new LinearLayout.LayoutParams(dp(192), dp(108)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = dp(12);
        lp.bottomMargin = dp(90);
        root.addView(ytBox, root.indexOfChild(panel), lp);

        // Şeritten tutup sürükle
        final float[] down = new float[4];
        strip.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX(); down[1] = e.getRawY(); down[2] = ytBox.getTranslationX(); down[3] = ytBox.getTranslationY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    ytBox.setTranslationX(down[2] + e.getRawX() - down[0]);
                    ytBox.setTranslationY(down[3] + e.getRawY() - down[1]);
                    return true;
                default:
                    return false;
            }
        });
    }

    /** Kayıtlı YouTube videoları/listeleri: birini seç ya da link ekle. */
    private void chooseYouTube() {
        JSONArray lists = Music.ytLists(this);
        String[] items = new String[lists.length() + 1];
        for (int i = 0; i < lists.length(); i++) items[i] = lists.optJSONArray(i).optString(0);
        items[lists.length()] = "＋ YouTube linki ekle (video ya da oynatma listesi)";
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("YouTube")
            .setItems(items, (d, which) -> {
                if (which == lists.length()) { addYouTube(); return; }
                Music.selectYt(this, which);
                musicSrcBtn.setText(musicLabel());
                applyMusic();
            })
            .setNegativeButton("Vazgeç", null)
            .show();
    }

    private void addYouTube() {
        android.widget.EditText in = new android.widget.EditText(this);
        in.setHint("https://youtu.be/…");
        in.setSingleLine(true);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("YouTube linki ekle")
            .setMessage("YouTube'da videoda Paylaş → Kopyala, sonra buraya yapıştır. Oynatma listesi linki de olur.")
            .setView(in)
            .setPositiveButton("Ekle", (d, w) -> {
                String link = in.getText().toString().trim();
                String[] ids = Music.ytParse(link);
                if (ids == null) { Toast.makeText(this, "Bu bir YouTube linki değil", Toast.LENGTH_LONG).show(); return; }
                final Context ctx = getApplicationContext();
                new Thread(() -> {
                    String name = ids[0].isEmpty() ? "YouTube listesi" : "YouTube videosu";
                    try {
                        String u = ids[0].isEmpty() ? "https://www.youtube.com/playlist?list=" + ids[1] : "https://www.youtube.com/watch?v=" + ids[0];
                        java.net.HttpURLConnection c = Net.open(ctx, "https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode(u, "UTF-8"));
                        c.setConnectTimeout(8000);
                        c.setReadTimeout(8000);
                        if (c.getResponseCode() == 200) name = new JSONObject(Net.readAll(c.getInputStream())).optString("title", name);
                        c.disconnect();
                    } catch (Exception ignored) { }
                    JSONArray a = Music.ytLists(ctx);
                    JSONArray item = new JSONArray();
                    item.put(name);
                    item.put(ids[0]);
                    item.put(ids[1]);
                    a.put(item);
                    Music.saveYtLists(ctx, a);
                    final String n = name;
                    runOnUiThread(() -> {
                        Music.selectYt(this, a.length() - 1);
                        musicSrcBtn.setText(musicLabel());
                        Toast.makeText(this, "Eklendi: " + n, Toast.LENGTH_SHORT).show();
                        applyMusic();
                    });
                }).start();
            })
            .setNegativeButton("Vazgeç", null)
            .show();
    }

    /** Kayıtlı Spotify listeleri: birini seç ya da yeni link ekle. */
    private void chooseSpotify() {
        JSONArray lists = Music.spotifyLists(this);
        String[] items = new String[lists.length() + 1];
        for (int i = 0; i < lists.length(); i++) items[i] = lists.optJSONArray(i).optString(0);
        items[lists.length()] = "＋ Spotify listesi ekle (link yapıştır)";
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Spotify listesi")
            .setItems(items, (d, which) -> {
                if (which == lists.length()) { addSpotifyList(); return; }
                Music.selectSpotify(this, which);
                musicSrcBtn.setText(musicLabel());
                Toast.makeText(this, "Spotify açılacak; müzik başlayınca geri dön", Toast.LENGTH_LONG).show();
                applyMusic();
            })
            .setNegativeButton("Vazgeç", null)
            .show();
    }

    private void addSpotifyList() {
        android.widget.EditText in = new android.widget.EditText(this);
        in.setHint("https://open.spotify.com/playlist/…");
        in.setSingleLine(true);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Spotify listesi ekle")
            .setMessage("Spotify'da listede ⋯ → Paylaş → Bağlantıyı kopyala, sonra buraya yapıştır.")
            .setView(in)
            .setPositiveButton("Ekle", (d, w) -> {
                String link = in.getText().toString().trim();
                String id = Music.playlistId(link);
                if (id == null) { Toast.makeText(this, "Bu bir Spotify liste linki değil", Toast.LENGTH_LONG).show(); return; }
                final Context ctx = getApplicationContext();
                new Thread(() -> {
                    // Liste adını Spotify'ın herkese açık bilgisinden al
                    String name = "Spotify listesi";
                    try {
                        java.net.HttpURLConnection c = Net.open(ctx, "https://open.spotify.com/oembed?url=https://open.spotify.com/playlist/" + id);
                        c.setConnectTimeout(8000);
                        c.setReadTimeout(8000);
                        if (c.getResponseCode() == 200) name = new JSONObject(Net.readAll(c.getInputStream())).optString("title", name);
                        c.disconnect();
                    } catch (Exception ignored) { }
                    JSONArray a = Music.spotifyLists(ctx);
                    JSONArray item = new JSONArray();
                    item.put(name);
                    item.put("https://open.spotify.com/playlist/" + id);
                    a.put(item);
                    Music.saveSpotifyLists(ctx, a);
                    final String n = name;
                    runOnUiThread(() -> {
                        Music.selectSpotify(this, a.length() - 1);
                        musicSrcBtn.setText(musicLabel());
                        Toast.makeText(this, "Eklendi: " + n, Toast.LENGTH_SHORT).show();
                        applyMusic();
                    });
                }).start();
            })
            .setNegativeButton("Vazgeç", null)
            .show();
    }

    private void pickMusicFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, REQ_MUSIC); } catch (Exception e) {
            Toast.makeText(this, "Dosya seçici açılamadı", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_MUSIC || res != RESULT_OK || data == null) return;
        JSONArray arr = new JSONArray();
        List<Uri> picked = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int k = 0; k < data.getClipData().getItemCount(); k++) picked.add(data.getClipData().getItemAt(k).getUri());
        } else if (data.getData() != null) picked.add(data.getData());
        for (Uri u : picked) {
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
            arr.put(u.toString());
        }
        if (arr.length() == 0) return;
        Music.setSource(this, "files");
        Music.prefs(this).edit().putString(Music.URIS, arr.toString()).apply();
        if (musicSrcBtn != null) musicSrcBtn.setText(musicLabel());
        Toast.makeText(this, arr.length() + " parça seçildi", Toast.LENGTH_SHORT).show();
    }

    /** Görünüm menüsündeki ayarları ilk haline döndürür (filtre, karartma, parlaklık vb.). */
    private void resetLook() {
        rp.edit()
            .remove("r_warm").remove("r_dim").remove("r_bright").remove("r_brightSys")
            .remove("r_speed").remove("r_full").remove("r_awake").remove("r_vol")
            .remove("r_pre").remove("r_ads").remove("r_break")
            .apply();
        stopAutoScroll();
        applyLook();
        // Menüyü yeni değerlerle yeniden kur
        int idx = root.indexOfChild(panel);
        root.removeView(panel);
        panel = buildPanel();
        panel.setVisibility(View.VISIBLE);
        root.addView(panel, idx, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        schedulePreload();
        Toast.makeText(this, "Görünüm ayarları sıfırlandı", Toast.LENGTH_SHORT).show();
    }

    private void togglePanel() {
        boolean show = panel.getVisibility() != View.VISIBLE;
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) { pauseScroll(); setHeaderHidden(false); }
    }

    /* ---------- Tam ekran ---------- */

    /** Üst şerit sayfanın üstünde durduğu için sayfanın başına o kadar boşluk eklenir. */
    private void addTopSpace(WebView w) {
        int hp = header.getHeight();
        int ww = w.getWidth();
        if (hp <= 0 || ww <= 0) { header.post(() -> addTopSpace(w)); return; }
        w.evaluateJavascript("(function(hp,ww){if(!document.body||document.getElementById('__mtTop'))return;"
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
        if (offlineShown) {
            // Çevrimdışı kopyadan sitedeki canlı sayfaya geç
            int i = indexOf(current);
            if (i >= 0) {
                offlineShown = false;
                updateBar();
                Toast.makeText(this, "Sitedeki sayfa açılıyor…", Toast.LENGTH_SHORT).show();
                web.loadUrl(urls.get(i));
            }
            return;
        }
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
        java.io.File archive = Offline.archiveFile(this, seriesUrl, current);
        if (archive != null) {
            // Sayfa olarak kaydedilmiş bölüm
            offlineShown = true;
            updateBar();
            web.getSettings().setAllowFileAccess(true);
            web.loadUrl(android.net.Uri.fromFile(archive).toString());
        } else if (Offline.has(this, seriesUrl, current)) {
            // Çevrimdışı kopya var: beklemeden telefondan aç (internet gerekmez)
            offlineShown = true;
            updateBar();
            web.loadDataWithBaseURL(Offline.baseUrl(seriesUrl, current), Offline.pageHtml(this, seriesUrl, current),
                "text/html", "utf-8", null);
        } else {
            web.loadUrl(urls.get(i));
        }
    }

    private void step(int dir) {
        savePos(current);
        int i = indexOf(current);
        int j = i + dir;
        if (i < 0 || j < 0 || j >= nums.size()) return;
        if (Offline.has(this, seriesUrl, nums.get(j)[0])) { open(nums.get(j)[0]); return; } // telefonda kayıtlı
        if (dir == 1 && urls.get(j).equals(preUrl)) { showPreloaded(nums.get(j)[0]); return; }
        open(nums.get(j)[0]);
    }

    private void updateBar() {
        int i = indexOf(current);
        label.setText((seriesTitle == null ? "" : seriesTitle + " · ") + "Bölüm " + Parser.fmt(current)
            + (offlineShown ? " · çevrimdışı" : ""));
        prevBtn.setEnabled(i > 0);
        prevBtn.setAlpha(i > 0 ? 1f : 0.35f);
        nextBtn.setEnabled(i >= 0 && i < nums.size() - 1);
        nextBtn.setAlpha(i >= 0 && i < nums.size() - 1 ? 1f : 0.35f);
    }

    /** Sayfa yüklendi: sitenin kendi düğmesiyle geçildiyse bölüm numarasını linkten bul. */
    private void onChapterShown(String url) {
        try {
            String path = new URL(url).getPath().replaceAll("/+$", "");
            if (path.equals(seriesPath) || current < 0 && !path.startsWith(seriesPath + "/")) return; // seri sayfası
            if (path.startsWith(seriesPath + "/")) {
                Double n = Parser.numFromPath(path.substring(seriesPath.length() + 1));
                if (n != null) current = n;
            }
        } catch (Exception ignored) { }
        updateBar();
        markRead(current);
        schedulePreload();
    }

    private void markRead(double num) {
        if (num < 0) return;
        final String series = seriesUrl;
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            SharedPreferences prefs = ctx.getSharedPreferences(Checker.PREFS, MODE_PRIVATE);
            String token = prefs.getString("token", "");
            if (token.isEmpty()) return;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    Checker.GhFile f = Checker.ghGet(token, "list.json");
                    JSONArray list = new JSONArray(f.text == null ? "[]" : f.text);
                    boolean changed = false;
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject it = list.getJSONObject(i);
                        if (!series.equals(it.optString("url"))) continue;
                        double prev = it.isNull("lastRead") ? -1 : it.optDouble("lastRead", -1);
                        if (num > prev) { it.put("lastRead", num); changed = true; }
                    }
                    if (!changed) return;
                    Checker.ghPut(token, "list.json", list.toString(1) + "\n", f.sha, "Okundu: " + Parser.fmt(num));
                    return;
                } catch (Checker.ConflictException e) {
                    // arada başka yerden yazıldı, bir kez daha dene
                } catch (Exception e) {
                    return;
                }
            }
        }).start();
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
        applyMusic();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(saver);
        handler.removeCallbacks(breakAlert);
        Music.stop(this);
        ytPause();
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
        handler.removeCallbacksAndMessages(null);
        if (yt != null) yt.destroy();
        if (pre != null) pre.destroy();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
