package com.yildiz.mangatakip;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * lib/parse.js, lib/sites.js ve lib/chapters.js'in Java karşılığı.
 * Android'e bağımlı değil; bilgisayarda da derlenip test edilebilir.
 */
public final class Parser {

    public static final class Chapter {
        public final double num;
        public final String url;
        public final String label;
        public Chapter(double num, String url, String label) { this.num = num; this.url = url; this.label = label; }
    }

    public static final class Series {
        public String title;
        public String cover;
        public List<Chapter> chapters = new ArrayList<>();
    }

    private Parser() {}

    private static final Pattern SKIP = Pattern.compile("(^|/)(page|sayfa|feed|comments?|yorum(lar)?|amp|reviews?|incelemeler?)(/|$)", Pattern.CASE_INSENSITIVE);
    public static final Pattern LOCKED = Pattern.compile("sonra\\s+açılır|kilitli|erken\\s+erişim|premium|coin\\s*ile", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ANCHOR = Pattern.compile("<a\\b[^>]*?href\\s*=\\s*[\"']([^\"'#]+)[^\"']*[\"'][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LOC = Pattern.compile("<loc>\\s*([^<\\s]+)\\s*</loc>", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUM_PATH = Pattern.compile("(\\d+)(?:[.\\-_](\\d+))?");
    private static final Pattern NUM_TEXT = Pattern.compile("(?:bölüm|bolum|chapter|ch\\.?|ep\\.?)\\s*(\\d+(?:[.,]\\d+)?)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Siteye özel sitemap yolları (lib/sites.js ile aynı). */
    public static List<String> sitemapPaths(String host) {
        List<String> out = new ArrayList<>();
        String h = host.replaceFirst("^www\\.", "");
        if (h.equals("mangawt.com")) out.add("/sitemap-chapters.xml");
        return out;
    }

    public static String decodeEntities(String s) {
        if (s == null) return "";
        Matcher m = Pattern.compile("&#(\\d+);").matcher(s
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
            .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " "));
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1)))));
        m.appendTail(sb);
        return sb.toString();
    }

    private static String stripTags(String s) {
        return decodeEntities(s.replaceAll("<[^>]*>", " ")).replaceAll("\\s+", " ").trim();
    }

    static String meta(String html, String prop) {
        Pattern p1 = Pattern.compile("<meta[^>]+(?:property|name)=[\"']" + Pattern.quote(prop) + "[\"'][^>]*content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
        Pattern p2 = Pattern.compile("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']" + Pattern.quote(prop) + "[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m = p1.matcher(html);
        if (m.find()) return decodeEntities(m.group(1)).trim();
        m = p2.matcher(html);
        if (m.find()) return decodeEntities(m.group(1)).trim();
        return null;
    }

    public static String cleanTitle(String raw, String siteName) {
        if (raw == null || raw.isEmpty()) return null;
        String t = raw;
        if (siteName != null && !siteName.isEmpty()) {
            t = Pattern.compile("\\s*[-–—|]\\s*" + Pattern.quote(siteName) + ".*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(t).replaceAll("");
        }
        t = Pattern.compile("\\s*[-–—|]\\s*(Türkçe\\s+)?(Manga|Webtoon|Manhwa)\\s+Oku.*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(t).replaceAll("");
        t = t.replaceAll("\\s*[-–—|]\\s*$", "").trim();
        return t.isEmpty() ? raw.trim() : t;
    }

    public static Double numFromPath(String rest) {
        String[] parts = rest.split("/");
        String last = "";
        for (String p : parts) if (!p.isEmpty()) last = p;
        Matcher m = NUM_PATH.matcher(last);
        if (!m.find()) return null;
        return Double.parseDouble(m.group(2) != null ? m.group(1) + "." + m.group(2) : m.group(1));
    }

    public static Double numFromText(String text) {
        Matcher m = NUM_TEXT.matcher(text);
        return m.find() ? Double.parseDouble(m.group(1).replace(',', '.')) : null;
    }

    public static String seriesPath(String url) {
        try { return new URL(url).getPath().replaceAll("/+$", ""); } catch (Exception e) { return ""; }
    }

    public static Series parseSeriesPage(String html, String pageUrl) {
        Series s = new Series();
        URL base;
        try { base = new URL(pageUrl); } catch (Exception e) { return s; }
        String prefix = seriesPath(pageUrl);
        String siteName = meta(html, "og:site_name");

        String title = cleanTitle(meta(html, "og:title"), siteName);
        if (title == null) {
            Matcher h1 = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
            if (h1.find()) title = cleanTitle(stripTags(h1.group(1)), siteName);
        }
        if (title == null) {
            Matcher tt = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
            if (tt.find()) title = cleanTitle(stripTags(tt.group(1)), siteName);
        }
        s.title = title;
        s.cover = meta(html, "og:image");
        if (s.cover == null) s.cover = meta(html, "twitter:image");

        Map<Double, Chapter> byNum = new LinkedHashMap<>();
        Matcher m = ANCHOR.matcher(html);
        while (m.find()) {
            URL abs;
            try { abs = new URL(base, decodeEntities(m.group(1))); } catch (Exception e) { continue; }
            if (!abs.getHost().equals(base.getHost())) continue;
            if (abs.getQuery() != null && !abs.getQuery().isEmpty()) continue;
            String path = abs.getPath().replaceAll("/+$", "");
            if (!path.startsWith(prefix + "/")) continue;
            String rest = path.substring(prefix.length() + 1);
            if (rest.isEmpty() || SKIP.matcher(rest).find()) continue;

            String text = stripTags(m.group(2));
            if (LOCKED.matcher(text).find()) continue;

            Double num = numFromPath(rest);
            if (num == null) num = numFromText(text);
            if (num == null || num.isNaN()) continue;

            if (!byNum.containsKey(num)) {
                String href = abs.getProtocol() + "://" + abs.getAuthority() + abs.getPath();
                String label = text.length() > 120 ? text.substring(0, 120) : text;
                byNum.put(num, new Chapter(num, href, label.isEmpty() ? "Bölüm " + fmt(num) : label));
            }
        }
        s.chapters = new ArrayList<>(byNum.values());
        s.chapters.sort((a, b) -> Double.compare(b.num, a.num));
        return s;
    }

    public static List<Chapter> chaptersFromSitemap(String xml, String pageUrl) {
        List<Chapter> out = new ArrayList<>();
        URL base;
        try { base = new URL(pageUrl); } catch (Exception e) { return out; }
        String prefix = seriesPath(pageUrl);
        Map<Double, Chapter> byNum = new LinkedHashMap<>();
        Matcher m = LOC.matcher(xml);
        while (m.find()) {
            URL u;
            try { u = new URL(decodeEntities(m.group(1))); } catch (Exception e) { continue; }
            if (!u.getHost().equals(base.getHost())) continue;
            String path = u.getPath().replaceAll("/+$", "");
            if (!path.startsWith(prefix + "/")) continue;
            String rest = path.substring(prefix.length() + 1);
            if (rest.isEmpty() || SKIP.matcher(rest).find()) continue;
            Double num = numFromPath(rest);
            if (num == null) continue;
            if (!byNum.containsKey(num)) byNum.put(num, new Chapter(num, u.toString(), "Bölüm " + fmt(num)));
        }
        out.addAll(byNum.values());
        out.sort((a, b) -> Double.compare(b.num, a.num));
        return out;
    }

    public static List<String> sitemapChildren(String xml) {
        List<String> out = new ArrayList<>();
        if (!Pattern.compile("<sitemapindex", Pattern.CASE_INSENSITIVE).matcher(xml).find()) return out;
        Matcher m = LOC.matcher(xml);
        while (m.find()) out.add(decodeEntities(m.group(1)));
        return out;
    }

    /** Adında bölüm geçen alt sitemapleri, yoksa (kategori vb. hariç) hepsini seçer. */
    public static List<String> pickSitemapChildren(List<String> all) {
        List<String> named = new ArrayList<>();
        for (String c : all) if (Pattern.compile("chapter|bolum|bölüm", Pattern.CASE_INSENSITIVE).matcher(c).find()) named.add(c);
        List<String> pick = named;
        if (pick.isEmpty()) {
            pick = new ArrayList<>();
            Pattern skip = Pattern.compile("categor|kategori|tag|etiket|page|sayfa|post|author", Pattern.CASE_INSENSITIVE);
            for (String c : all) if (!skip.matcher(c).find()) pick.add(c);
        }
        return pick.size() > 25 ? pick.subList(0, 25) : pick;
    }

    /** Eski ve yeni bölümleri birleştirir (yeni olan önceliklidir). */
    public static List<Chapter> mergeChapters(List<Chapter> fresh, List<Chapter> previous) {
        Map<Double, Chapter> byNum = new HashMap<>();
        if (previous != null) for (Chapter c : previous) byNum.put(c.num, c);
        for (Chapter c : fresh) byNum.put(c.num, c);
        List<Chapter> out = new ArrayList<>(byNum.values());
        out.sort((a, b) -> Double.compare(b.num, a.num));
        return out;
    }

    private static boolean isInt(double d) { return d == Math.rint(d) && !Double.isInfinite(d); }

    static String templateOf(Chapter c) {
        if (!isInt(c.num)) return null;
        try {
            URL u = new URL(c.url);
            String n = String.valueOf((long) c.num);
            String path = u.getPath();
            Matcher m = Pattern.compile("(^|[^0-9])" + n + "(?![0-9])").matcher(path);
            int last = -1;
            while (m.find()) last = m.start() + m.group(1).length();
            if (last < 0) return null;
            return u.getProtocol() + "://" + u.getAuthority() + path.substring(0, last) + "{n}" + path.substring(last + n.length());
        } catch (Exception e) {
            return null;
        }
    }

    /** Tam sayı bölümler arasındaki boşlukları, alttaki bölümün link kalıbıyla doldurur. */
    public static List<Chapter> fillGaps(List<Chapter> chapters) {
        List<Chapter> ints = new ArrayList<>();
        for (Chapter c : chapters) if (isInt(c.num)) ints.add(c);
        if (ints.size() < 2) return chapters;
        ints.sort((a, b) -> Double.compare(a.num, b.num));

        Map<String, Integer> counts = new HashMap<>();
        Map<Double, String> tpl = new HashMap<>();
        for (Chapter c : ints) {
            String t = templateOf(c);
            tpl.put(c.num, t);
            if (t != null) counts.merge(t, 1, Integer::sum);
        }
        boolean any = false;
        for (int v : counts.values()) if (v >= 2) any = true;
        if (!any) return chapters;

        Set<Double> have = new HashSet<>();
        for (Chapter c : chapters) have.add(c.num);
        List<Chapter> added = new ArrayList<>();
        for (int i = 0; i < ints.size() - 1 && added.size() < 3000; i++) {
            Chapter lo = ints.get(i), hi = ints.get(i + 1);
            String tLo = tpl.get(lo.num), tHi = tpl.get(hi.num);
            String t = tLo != null ? tLo : tHi;
            if (t == null) continue;
            if (counts.getOrDefault(t, 0) < 2 && !t.equals(tHi)) continue;
            for (long n = (long) lo.num + 1; n < (long) hi.num && added.size() < 3000; n++) {
                if (!have.contains((double) n)) added.add(new Chapter(n, t.replace("{n}", String.valueOf(n)), "Bölüm " + n));
            }
        }
        if (added.isEmpty()) return chapters;
        List<Chapter> out = new ArrayList<>(chapters);
        out.addAll(added);
        out.sort((a, b) -> Double.compare(b.num, a.num));
        return out;
    }

    public static boolean isChallenge(String body) {
        return Pattern.compile("<title>\\s*(Just a moment|Attention Required|Bir dakika)", Pattern.CASE_INSENSITIVE).matcher(body).find()
            || body.contains("cf_chl_opt") || body.contains("cf-chl-widget");
    }

    public static String fmt(double n) {
        return isInt(n) ? String.valueOf((long) n) : String.valueOf(n);
    }

    public static String titleFromSlug(String url) {
        String p = seriesPath(url);
        String slug = p.substring(p.lastIndexOf('/') + 1);
        StringBuilder sb = new StringBuilder();
        for (String w : slug.split("-")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.substring(0, 1).toUpperCase(new Locale("tr"))).append(w.substring(1));
        }
        return sb.toString();
    }
}
