package com.yildiz.mangatakip;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;

/**
 * Uygulama içi güncelleme.
 * Her derlemede GitHub'daki indirme sayfasına "version.json" konur. Uygulama açılınca
 * (en fazla 3 saatte bir) ona bakar; yeni sürüm varsa sorar, APK'yı kendisi indirir ve
 * Android'in kurulum onayını açar. Kullanıcıya sadece "Güncelle" ve "Yükle"ye basmak kalır.
 */
public final class Updater {
    private static final String BASE = "https://github.com/yildizkumlamaboya-pixel/Manga-takip/releases/download/";
    private static final String PREFS = "updater";
    private static final long EVERY = 3 * 60 * 60 * 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean busy = false;

    private Updater() { }

    private static boolean rafi(Context c) { return c.getPackageName().endsWith("mangarafi"); }
    private static String tag(Context c) { return rafi(c) ? "manga-rafi" : "apk"; }
    private static String apkName(Context c) { return rafi(c) ? "manga-rafi.apk" : "manga-takip.apk"; }
    private static String appName(Context c) { return rafi(c) ? "Manga Rafı" : "Manga Takip"; }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    @SuppressWarnings("deprecation")
    static long installedCode(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) { return 0; }
    }

    static String installedName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    /** Açılışta çağrılır: zamanı geldiyse sessizce bakar, yeni sürüm varsa sorar. */
    static void autoCheck(Activity a) {
        long last = prefs(a).getLong("last", 0);
        if (System.currentTimeMillis() - last < EVERY) {
            // Bekleyen kurulum varsa (izin ekranından dönüldüyse) devam et
            resumePending(a);
            return;
        }
        check(a, false);
    }

    /** Ayarlardaki "Güncellemeleri denetle" düğmesi. */
    static void check(Activity a, boolean manual) {
        if (busy) return;
        busy = true;
        if (manual) Toast.makeText(a, "Güncelleme aranıyor…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            JSONObject v = null;
            String err = null;
            try {
                HttpURLConnection c = Net.open(a.getApplicationContext(),
                    BASE + tag(a) + "/version.json?t=" + System.currentTimeMillis());
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                c.setRequestProperty("Cache-Control", "no-cache");
                if (c.getResponseCode() == 200) v = new JSONObject(Net.readAll(c.getInputStream()));
                else err = "HTTP " + c.getResponseCode();
                c.disconnect();
            } catch (Exception e) { err = e.getMessage(); }
            final JSONObject fv = v;
            final String ferr = err;
            MAIN.post(() -> {
                busy = false;
                if (a.isFinishing()) return;
                if (fv == null) {
                    if (manual) Toast.makeText(a, "Güncelleme bilgisine ulaşılamadı. İnternetini kontrol et.", Toast.LENGTH_LONG).show();
                    return;
                }
                prefs(a).edit().putLong("last", System.currentTimeMillis()).apply();
                long remote = fv.optLong("versionCode", 0);
                if (remote > installedCode(a)) {
                    if (!manual && remote == prefs(a).getLong("skip", -1)) return;
                    ask(a, fv);
                } else if (manual) {
                    Toast.makeText(a, "Uygulama güncel (sürüm " + installedName(a) + ")", Toast.LENGTH_LONG).show();
                }
            });
        }, "updater").start();
    }

    private static AlertDialog.Builder dlg(Activity a) {
        return new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
    }

    private static void ask(Activity a, JSONObject v) {
        String notes = v.optString("notes", "").trim();
        String msg = "Sürüm " + v.optString("versionName", "") + " hazır (şu an " + installedName(a) + ")."
            + (notes.isEmpty() ? "" : "\n\nYenilikler:\n" + notes)
            + "\n\nListen ve kaldığın yerler silinmez.";
        dlg(a)
            .setTitle("Yeni sürüm var")
            .setMessage(msg)
            .setPositiveButton("Güncelle", (d, w) -> start(a, v))
            .setNegativeButton("Sonra", null)
            .setNeutralButton("Bu sürümü atla", (d, w) -> prefs(a).edit().putLong("skip", v.optLong("versionCode")).apply())
            .show();
    }

    /** Kurulum izni yoksa izin ekranını açar; varsa indirir. */
    private static void start(Activity a, JSONObject v) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            prefs(a).edit().putString("pending", v.toString()).apply();
            dlg(a)
                .setTitle("Bir kerelik izin")
                .setMessage("Uygulamanın kendini güncelleyebilmesi için açılan ekranda \"Bu kaynaktan izin ver\" seçeneğini aç, sonra geri dön. Güncelleme kendiliğinden devam eder.")
                .setPositiveButton("Ayarları aç", (d, w) -> {
                    try {
                        a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + a.getPackageName())));
                    } catch (Exception e) { openInBrowser(a); }
                })
                .setNegativeButton("Vazgeç", (d, w) -> prefs(a).edit().remove("pending").apply())
                .show();
            return;
        }
        download(a, v);
    }

    /** İzin ekranından dönünce (onResume) bekleyen güncellemeyi sürdürür. */
    static void resumePending(Activity a) {
        String p = prefs(a).getString("pending", null);
        if (p == null || busy) return;
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) return;
        prefs(a).edit().remove("pending").apply();
        try { download(a, new JSONObject(p)); } catch (Exception ignored) { }
    }

    private static void download(Activity a, JSONObject v) {
        busy = true;
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(true);
        TextView txt = new TextView(a);
        txt.setTextColor(Color.parseColor("#cfc9dc"));
        txt.setGravity(Gravity.CENTER_HORIZONTAL);
        txt.setPadding(0, pad / 2, 0, 0);
        txt.setText("İndiriliyor…");
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        box.addView(txt, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog prog = dlg(a).setTitle(appName(a) + " güncelleniyor").setView(box).setCancelable(false).show();

        new Thread(() -> {
            File f = new File(a.getCacheDir(), "update.apk");
            String err = null;
            try {
                String url = v.optString("url", BASE + tag(a) + "/" + apkName(a));
                HttpURLConnection c = Net.open(a.getApplicationContext(), url + (url.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis());
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                long total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0; int r, lastPct = -1;
                    while ((r = in.read(buf)) > 0) {
                        out.write(buf, 0, r);
                        done += r;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                final long d0 = done;
                                MAIN.post(() -> {
                                    bar.setIndeterminate(false);
                                    bar.setProgress(pct);
                                    txt.setText(String.format(java.util.Locale.ROOT, "%d%%  ·  %.1f / %.1f MB", pct, d0 / 1048576.0, total / 1048576.0));
                                });
                            }
                        }
                    }
                }
                c.disconnect();
                if (total > 0 && f.length() != total) throw new Exception("indirme yarım kaldı");
                // İnen dosya gerçekten bu uygulamanın daha yeni sürümü mü?
                PackageInfo pi = a.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
                if (pi == null || !a.getPackageName().equals(pi.packageName)) throw new Exception("dosya bozuk");
                MAIN.post(() -> txt.setText("Kuruluyor…"));
                install(a, f);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String ferr = err;
            MAIN.post(() -> {
                busy = false;
                try { prog.dismiss(); } catch (Exception ignored) { }
                if (ferr != null && !a.isFinishing()) {
                    dlg(a).setTitle("Güncellenemedi")
                        .setMessage("Bir sorun oldu (" + ferr + "). Tekrar dene ya da indirme sayfasından kur.")
                        .setPositiveButton("Tekrar dene", (d, w) -> download(a, v))
                        .setNeutralButton("İndirme sayfası", (d, w) -> openInBrowser(a))
                        .setNegativeButton("Kapat", null).show();
                }
            });
        }, "updater-dl").start();
    }

    private static void install(Context a, File f) throws Exception {
        PackageInstaller pi = a.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(a.getPackageName());
        p.setSize(f.length());
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("base.apk", 0, f.length())) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                s.fsync(out);
            }
            Intent i = new Intent(a, Receiver.class).setPackage(a.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pend = PendingIntent.getBroadcast(a, id, i, flags);
            s.commit(pend.getIntentSender());
        }
    }

    static void openInBrowser(Context a) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BASE + tag(a) + "/" + apkName(a)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }

    /** Android'in kurulum sonucunu alır; onay gerekiyorsa onay ekranını açar. */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        @SuppressWarnings("deprecation")
        public void onReceive(Context ctx, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { ctx.startActivity(confirm); } catch (Exception e) { openInBrowser(ctx); }
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Toast.makeText(ctx, "Güncelleme kurulamadı" + (m != null ? ": " + m : ""), Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    /** Güncelleme bitince Android uygulamayı kapatır; bu bildirim tek dokunuşla yeniden açar. */
    public static final class Replaced extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
            try {
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (Build.VERSION.SDK_INT >= 26) {
                    nm.createNotificationChannel(new NotificationChannel("guncelleme", "Uygulama güncellemesi", NotificationManager.IMPORTANCE_DEFAULT));
                }
                Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
                PendingIntent pi = PendingIntent.getActivity(ctx, 7, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, "guncelleme") : new Notification.Builder(ctx);
                b.setSmallIcon(ctx.getApplicationInfo().icon)
                    .setContentTitle(appName(ctx) + " güncellendi")
                    .setContentText("Sürüm " + installedName(ctx) + " · Açmak için dokun")
                    .setContentIntent(pi)
                    .setAutoCancel(true);
                nm.notify(7707, b.build());
            } catch (Exception ignored) { }
        }
    }
}
