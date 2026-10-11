// Service worker: uygulama kabuğunu önbellekler, bildirimleri gösterir.
const CACHE = 'mt-v16';
const SHELL = ['./', './index.html', './style.css', './app.js', './config.js', './url.js', './manifest.webmanifest', './icons/icon-192.png'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;
  // Önce ağ, internet yoksa önbellek
  e.respondWith(
    fetch(e.request)
      .then((res) => {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(e.request, copy));
        return res;
      })
      .catch(() => caches.match(e.request).then((r) => r || caches.match('./'))),
  );
});

self.addEventListener('push', (e) => {
  let d = {};
  try { d = e.data.json(); } catch { d = { title: 'Manga Takip', body: e.data?.text() }; }
  e.waitUntil(
    self.registration.showNotification(d.title || 'Yeni bölüm', {
      body: d.body || '',
      icon: 'icons/icon-192.png',
      badge: 'icons/badge-96.png',
      tag: d.tag,
      renotify: true,
      data: { url: d.url || './' },
    }),
  );
});

self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  e.waitUntil(self.clients.openWindow(e.notification.data?.url || './'));
});
