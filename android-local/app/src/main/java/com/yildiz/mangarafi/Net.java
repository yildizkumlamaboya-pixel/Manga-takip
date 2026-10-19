package com.yildiz.mangarafi;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gömülü güvenli DNS: alan adları operatörün DNS'i yerine şifreli DNS (DNS-over-HTTPS,
 * Cloudflare 1.1.1.1 / Google 8.8.8.8) ile çözülür. Uygulama içinde küçük bir yerel vekil
 * (127.0.0.1) çalışır; sayfalar ve kontroller bu vekil üzerinden gider. DNS ile yapılan
 * engeller böylece aşılır. Vekil sadece bağlantıyı aktarır, içeriğe dokunmaz (HTTPS şifreli kalır).
 */
final class Net {
    private Net() { }

    static final String PREFS = "net";

    static boolean dnsOn(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("dns_on", true);
    }

    static void setDnsOn(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("dns_on", on).apply();
        applyWebView(ctx);
    }

    /* ---------------- DNS-over-HTTPS ---------------- */

    private static final Map<String, Object[]> cache = new ConcurrentHashMap<>(); // host -> {InetAddress[], expiresAt}
    private static final String[] DOH = {
        "https://1.1.1.1/dns-query?type=A&name=",
        "https://1.0.0.1/dns-query?type=A&name=",
        "https://8.8.8.8/resolve?type=A&name="
    };

    /** Test için değiştirilebilir çözücü. */
    interface Resolver { InetAddress[] resolve(String host) throws IOException; }
    static Resolver resolver = Net::dohResolve;

    static InetAddress[] resolve(String host) throws IOException {
        if (host.matches("[0-9.]+") || host.contains(":")) return InetAddress.getAllByName(host);
        Object[] c = cache.get(host);
        if (c != null && (long) c[1] > System.currentTimeMillis()) return (InetAddress[]) c[0];
        InetAddress[] a;
        try {
            a = resolver.resolve(host);
        } catch (IOException e) {
            a = InetAddress.getAllByName(host); // şifreli DNS'e ulaşılamazsa telefonun DNS'i
        }
        cache.put(host, new Object[]{a, System.currentTimeMillis() + 10 * 60_000L});
        return a;
    }

    static InetAddress[] dohResolve(String host) throws IOException {
        IOException last = null;
        for (String base : DOH) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(base + host).openConnection(Proxy.NO_PROXY);
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setRequestProperty("Accept", "application/dns-json");
                try {
                    if (c.getResponseCode() != 200) throw new IOException("DoH " + c.getResponseCode());
                    JSONObject j = new JSONObject(readAll(c.getInputStream()));
                    JSONArray ans = j.optJSONArray("Answer");
                    List<InetAddress> out = new ArrayList<>();
                    if (ans != null) for (int i = 0; i < ans.length(); i++) {
                        JSONObject r = ans.getJSONObject(i);
                        if (r.optInt("type") == 1) out.add(InetAddress.getByName(r.getString("data")));
                    }
                    if (out.isEmpty()) throw new IOException("DoH cevap yok: " + host);
                    return out.toArray(new InetAddress[0]);
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                last = e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }
        throw last != null ? last : new IOException("DoH");
    }

    /* ---------------- Yerel vekil ---------------- */

    static synchronized int startProxy() throws IOException {
        return LocalProxy.start(Net::resolve);
    }

    /* ---------------- Kullanım ---------------- */

    /** Ayara göre vekilli ya da doğrudan bağlantı açar. */
    static HttpURLConnection open(Context ctx, String url) throws IOException {
        URL u = new URL(url);
        if (ctx != null && dnsOn(ctx)) {
            try {
                int p = startProxy();
                return (HttpURLConnection) u.openConnection(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", p)));
            } catch (IOException ignored) { }
        }
        return (HttpURLConnection) u.openConnection();
    }

    /** Uygulamadaki tüm sayfaların (WebView) vekili kullanmasını sağlar ya da kapatır. */
    static void applyWebView(Context ctx) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.PROXY_OVERRIDE)) return;
                androidx.webkit.ProxyController pc = androidx.webkit.ProxyController.getInstance();
                Runnable done = () -> { };
                if (dnsOn(ctx)) {
                    int p = startProxyQuiet();
                    if (p <= 0) return;
                    androidx.webkit.ProxyConfig cfg = new androidx.webkit.ProxyConfig.Builder()
                        .addProxyRule("127.0.0.1:" + p)
                        .addBypassRule("rafi.local")
                        .build();
                    pc.setProxyOverride(cfg, Runnable::run, done);
                } else {
                    pc.clearProxyOverride(Runnable::run, done);
                }
            } catch (Throwable ignored) { }
        });
    }

    private static int startProxyQuiet() {
        try { return startProxy(); } catch (IOException e) { return -1; }
    }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
