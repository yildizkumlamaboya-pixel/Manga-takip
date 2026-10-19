package com.yildiz.mangatakip;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Uygulama içi küçük HTTP/CONNECT vekili: alan adlarını verilen çözücüyle (şifreli DNS) çözer. */
final class LocalProxy {
    private LocalProxy() { }

    interface Resolver { InetAddress[] resolve(String host) throws IOException; }


    private static volatile int port = -1;
    private static Resolver resolver;

    /** Vekili başlatır (bir kez), portunu döndürür. */
    static synchronized int start(Resolver r) throws IOException {
        resolver = r;
        if (port > 0) return port;
        ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        port = ss.getLocalPort();
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Socket s = ss.accept();
                    Thread h = new Thread(() -> handle(s), "vekil");
                    h.setDaemon(true);
                    h.start();
                } catch (IOException e) {
                    break;
                }
            }
        }, "vekil-dinle");
        t.setDaemon(true);
        t.start();
        return port;
    }

    private static Socket connect(String host, int p) throws IOException {
        IOException last = null;
        for (InetAddress a : resolver.resolve(host)) {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(a, p), 15000);
                s.setTcpNoDelay(true);
                return s;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("bağlanılamadı: " + host);
    }

    private static void handle(Socket client) {
        Socket server = null;
        try {
            client.setSoTimeout(60_000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            // İstek başlığını oku (\r\n\r\n'e kadar)
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int b, last4 = 0;
            while ((b = in.read()) != -1) {
                head.write(b);
                last4 = (last4 << 8) | (b & 0xff);
                if (last4 == 0x0D0A0D0A || head.size() > 65536) break;
            }
            String text = head.toString("ISO-8859-1");
            String line = text.split("\r\n", 2)[0];
            String[] parts = line.split(" ");
            if (parts.length < 3) { client.close(); return; }
            client.setSoTimeout(0);
            if (parts[0].equalsIgnoreCase("CONNECT")) {
                String hp = parts[1];
                int colon = hp.lastIndexOf(':');
                String host = colon > 0 ? hp.substring(0, colon) : hp;
                int p = colon > 0 ? Integer.parseInt(hp.substring(colon + 1)) : 443;
                try {
                    server = connect(host, p);
                } catch (IOException e) {
                    out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    client.close();
                    return;
                }
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            } else {
                // Düz HTTP: http://alan/yol biçimindeki isteği hedefe aynen ilet
                URL u = new URL(parts[1]);
                int p = u.getPort() > 0 ? u.getPort() : 80;
                server = connect(u.getHost(), p);
                String path = u.getFile().isEmpty() ? "/" : u.getFile();
                String rest = text.substring(line.length());
                server.getOutputStream().write((parts[0] + " " + path + " " + parts[2] + rest).getBytes(StandardCharsets.ISO_8859_1));
                server.getOutputStream().flush();
            }
            Socket srv = server;
            Thread up = new Thread(() -> pipe(client, srv), "vekil-up");
            up.setDaemon(true);
            up.start();
            pipe(srv, client);
        } catch (Exception e) {
            try { client.close(); } catch (IOException ignored) { }
            if (server != null) try { server.close(); } catch (IOException ignored) { }
        }
    }

    private static void pipe(Socket from, Socket to) {
        byte[] buf = new byte[32768];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try { from.close(); } catch (IOException ignored) { }
            try { to.close(); } catch (IOException ignored) { }
        }
    }

}
