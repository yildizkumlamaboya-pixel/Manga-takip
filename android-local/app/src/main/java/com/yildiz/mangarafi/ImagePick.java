package com.yildiz.mangarafi;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bölüm sayfasından manga sayfalarının resim adreslerini seçer (Android'e bağlı değil, test edilebilir).
 *
 * Sıra:
 *  1) Sayfanın betiğindeki resim listesi (ts_reader "images":[...], chapter_preloaded_images vb.).
 *     Bu temalarda resimler sayfaya sonradan eklenir; düz HTML'de sadece ikon/kapak görünür.
 *  2) Okuyucu alanındaki resimler (#readerarea, .reading-content, .wp-manga-chapter-img ...).
 *  3) Hiçbiri yoksa tüm sayfa; ikon/kapak/tepki resimleri ayıklanır, aynı klasördeki en büyük grup alınır.
 */
final class ImagePick {
    private ImagePick() { }

    static final class Result {
        final List<String> urls;
        /** true: liste sitenin kendi okuyucu verisinden geldi (güvenilir). */
        final boolean trusted;
        Result(List<String> urls, boolean trusted) { this.urls = urls; this.trusted = trusted; }
    }

    private static final Pattern IMG_TAG = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final String[] IMG_ATTRS = {"data-src", "data-lazy-src", "data-original", "data-url", "data-cfsrc", "src"};
    private static final Pattern[] ATTR_RX = new Pattern[IMG_ATTRS.length];
    static {
        for (int i = 0; i < IMG_ATTRS.length; i++) {
            ATTR_RX[i] = Pattern.compile("\\s" + IMG_ATTRS[i] + "\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        }
    }

    /** Betikteki resim dizileri. */
    private static final Pattern[] SCRIPT_ARRAYS = {
        Pattern.compile("[\"']images[\"']\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL),
        Pattern.compile("chapter_preloaded_images\\s*=\\s*\\[(.*?)\\]", Pattern.DOTALL),
        Pattern.compile("(?:var|let|const)\\s+\\w*(?:[iI]mages|[pP]ages|[iI]mgs|[sS]lides)\\w*\\s*=\\s*\\[(.*?)\\]", Pattern.DOTALL),
        Pattern.compile("[\"'](?:pages|imgs|imageList|image_urls)[\"']\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL),
    };
    private static final Pattern QUOTED_URL = Pattern.compile("[\"']((?:https?:)?(?:\\\\?/){2}[^\"'\\s]+?)[\"']");

    /** Okuyucu alanının başladığını gösteren işaretler. */
    private static final String[] READER_START = {
        "id=\"readerarea\"", "id='readerarea'", "class=\"reading-content", "wp-manga-chapter-img", "ts-main-image",
        "id=\"chapter-reader", "id=\"chapter_imgs", "id=\"chapter-images", "class=\"chapter-images", "class=\"reader-area",
        "class=\"page-break", "id=\"chapterContent", "class=\"chapter-content", "class=\"container-chapter-reader",
        "id=\"vungdoc", "class=\"vung-doc", "id=\"reader\"", "class=\"reader-main", "class=\"chapter-container",
    };
    /** Okuyucu alanından sonra gelen bölümler (yorumlar, öneriler, alt bilgi). */
    private static final String[] READER_END = {
        "id=\"comments", "class=\"comments", "id=\"disqus", "class=\"bixbox", "id=\"related", "class=\"related",
        "<footer", "id=\"footer", "class=\"footer", "class=\"chapternav", "id=\"respond", "class=\"wp-manga-nav",
        "class=\"reactions", "id=\"reactions", "class=\"emoji", "class=\"chapter-reactions", "class=\"post-reactions",
    };

    static final String[] SKIP = {"logo", "avatar", "icon", "favicon", "banner", "/ads", "reklam", "emoji", "emoticon",
        "gravatar", "/themes/", "thumb", "cover", "loading", "spinner", "placeholder", "button", "sponsor", "reaction",
        "/smilies/", "/plugins/", "discord", "patreon", "kofi", "ko-fi", "badge", "flag", "/assets/img", "-150x", "-175x",
        "-110x", "-75x", "-300x", "-350x", "-193x", "-225x", "-60x", "-100x", "-125x", "-180x", "-200x", "-280x"};

    /** Ana giriş: sayfa HTML'i ve adresinden resim listesi. */
    static Result fromHtml(String html, String pageUrl) {
        URL base;
        try { base = new URL(pageUrl); } catch (Exception e) { return new Result(new ArrayList<>(), false); }

        // 1) Betikteki liste
        List<String> best = new ArrayList<>();
        for (Pattern p : SCRIPT_ARRAYS) {
            Matcher m = p.matcher(html);
            while (m.find()) {
                List<String> arr = new ArrayList<>();
                Matcher q = QUOTED_URL.matcher(m.group(1));
                while (q.find()) {
                    String u = abs(base, q.group(1).replace("\\/", "/").replace("\\u002F", "/"));
                    if (u != null && looksImage(u) && !skipped(u)) arr.add(u);
                }
                arr = dedupe(arr);
                if (arr.size() > best.size()) best = arr;
            }
        }
        if (best.size() >= 3) return new Result(best, true);

        // 2) Okuyucu alanı
        String low = html.toLowerCase(Locale.ROOT);
        int start = -1;
        for (String s : READER_START) {
            int i = low.indexOf(s.toLowerCase(Locale.ROOT));
            if (i >= 0 && (start < 0 || i < start)) start = i;
        }
        if (start >= 0) {
            int tagStart = low.lastIndexOf('<', start);
            if (tagStart >= 0) start = tagStart;
            int end = low.length();
            for (String s : READER_END) {
                int i = low.indexOf(s.toLowerCase(Locale.ROOT), start + 20);
                if (i > start && i < end) end = i;
            }
            List<String> area = pick(imgs(html.substring(start, end), base), true);
            if (area.size() >= 3) return new Result(area, true);
        }

        // 3) Tüm sayfa
        return new Result(pick(imgs(html, base), false), false);
    }

    /** WebView'den toplanan (okuyucu alanı önceliklendirilmiş) listeyi temizler. */
    static List<String> clean(List<String> all, boolean fromReaderArea) {
        return pick(all, fromReaderArea);
    }

    static List<String> imgs(String html, URL base) {
        List<String> out = new ArrayList<>();
        Matcher m = IMG_TAG.matcher(html);
        while (m.find()) {
            String tag = m.group();
            for (Pattern rx : ATTR_RX) {
                Matcher v = rx.matcher(tag);
                if (v.find()) {
                    String src = v.group(2).trim().replace("&amp;", "&");
                    if (!src.isEmpty() && !src.startsWith("data:")) {
                        String u = abs(base, src);
                        if (u != null) { out.add(u); break; }
                    }
                }
            }
        }
        return out;
    }

    /**
     * İkon/kapak gibi resimleri atar. Okuyucu alanından geldiyse sırayı korur (birden fazla sunucu olabilir);
     * değilse aynı klasördeki en kalabalık grubu seçer.
     */
    static List<String> pick(List<String> all, boolean area) {
        List<String> clean = new ArrayList<>();
        for (String u : dedupe(all)) {
            if (!u.toLowerCase(Locale.ROOT).startsWith("http")) continue;
            if (skipped(u) || !looksImage(u)) continue;
            clean.add(u);
        }
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String u : clean) {
            String path = u.split("[?#]")[0];
            String dir = path.substring(0, path.lastIndexOf('/') + 1);
            groups.computeIfAbsent(dir, d -> new ArrayList<>()).add(u);
        }
        List<String> best = new ArrayList<>();
        for (List<String> g : groups.values()) if (g.size() > best.size()) best = g;
        if (area) {
            // Okuyucu alanında: tek tük farklı klasördeki resimleri (araya konmuş ikon) at, gerisini sırayla tut
            List<String> out = new ArrayList<>();
            for (List<String> g : groups.values()) if (g.size() >= 2 || g == best) out.addAll(g);
            List<String> ordered = new ArrayList<>();
            for (String u : clean) if (out.contains(u)) ordered.add(u);
            return ordered;
        }
        return best;
    }

    static boolean skipped(String u) {
        String low = u.toLowerCase(Locale.ROOT);
        int q = low.indexOf('?');
        String path = q >= 0 ? low.substring(0, q) : low;
        // Alan adı (ör. "mangalogo.com") değil sadece yol kontrol edilsin
        int s = path.indexOf("//");
        int p = s >= 0 ? path.indexOf('/', s + 2) : 0;
        String rest = p >= 0 ? path.substring(p) : path;
        for (String k : SKIP) if (rest.contains(k)) return true;
        return rest.endsWith(".svg") || rest.endsWith(".ico");
    }

    static boolean looksImage(String u) {
        String low = u.toLowerCase(Locale.ROOT);
        String path = low.split("[?#]")[0];
        return path.matches(".*\\.(jpe?g|png|webp|avif|gif|jfif)$") || low.contains("image") || low.contains("/img/")
            || low.contains("/pages/") || low.contains("/chapter");
    }

    private static String abs(URL base, String src) {
        try {
            if (src.startsWith("//")) src = base.getProtocol() + ":" + src;
            return new URL(base, src).toString();
        } catch (Exception e) { return null; }
    }

    private static List<String> dedupe(List<String> in) { return new ArrayList<>(new LinkedHashSet<>(in)); }

    /* ---------------- İndirdikten sonra boyut kontrolü ---------------- */

    /** Tek resim: bölüm sayfası olamayacak kadar küçük ya da ikon gibi kare mi? */
    static boolean tooSmall(int w, int h) {
        if (w < 250 || h < 120) return true;
        if (Math.max(w, h) < 420) return true;            // 256x256, 400x300 gibi ikonlar
        if (w == h && w <= 640) return true;              // kare ikon / tepki resmi
        return false;
    }

    /**
     * Bütün bölüm: genişliği diğerlerinden çok farklı olanları (araya karışmış kapak/ikon) işaretler.
     * Dönüş: atılacak sıraların listesi.
     */
    static List<Integer> outliers(List<int[]> sizes) {
        List<Integer> bad = new ArrayList<>();
        List<Integer> ws = new ArrayList<>();
        for (int[] s : sizes) if (s != null) ws.add(s[0]);
        if (ws.size() < 4) return bad;
        java.util.Collections.sort(ws);
        int med = ws.get(ws.size() / 2);
        for (int i = 0; i < sizes.size(); i++) {
            int[] s = sizes.get(i);
            if (s != null && s[0] < med * 0.55) bad.add(i);
        }
        return bad;
    }
}
