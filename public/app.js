// Manga Takip – arayüz. Veriler GitHub'daki "data" dalında JSON olarak durur:
//   list.json  -> takip ettiğin seriler + kaldığın bölüm (bu uygulama yazar)
//   state.json -> başlık, kapak, bölüm listesi (GitHub Actions yazar)
//   subs.json  -> bildirim alacak cihazlar (bu uygulama yazar)
import { OWNER, REPO, DATA_BRANCH, CHECK_WORKFLOW, VAPID_PUBLIC_KEY } from './config.js';
import { normalizeUrl } from './url.js';

const $ = (s, r = document) => r.querySelector(s);
const TOKEN_KEY = 'mt-token';
const CACHE_KEY = 'mt-cache';
let list = [];      // [{url, lastRead, added}]
let state = {};     // url -> {title, cover, latest, first, chapters:[[num,url]], error}
let filter = 'all';
let current = null;
let pollTimer = null;

// Android uygulaması içinde açıldıysa telefondaki kontrol işine köprü
const NATIVE = window.MangaApp && window.MangaApp.isApp ? window.MangaApp : null;
const PHONE_FRESH_MS = 3 * 60 * 60 * 1000;
const phoneFresh = (st) => st?.via === 'phone' && st.phoneAt && Date.now() - new Date(st.phoneAt) < PHONE_FRESH_MS;

const store = {
  get: (k) => { try { return localStorage.getItem(k); } catch { return null; } },
  set: (k, v) => { try { localStorage.setItem(k, v); } catch {} },
};
const token = () => store.get(TOKEN_KEY) || '';
const fmt = (n) => (n == null ? '–' : String(Number(n)));
const host = (u) => { try { return new URL(u).hostname.replace(/^www\./, ''); } catch { return u; } };

/* ---------- GitHub ---------- */
class GhError extends Error {}

async function gh(path, { method = 'GET', body, accept = 'application/vnd.github+json' } = {}) {
  let res;
  try {
    res = await fetch(`https://api.github.com/repos/${OWNER}/${REPO}${path}`, {
      method,
      cache: 'no-store',
      headers: {
        Authorization: `Bearer ${token()}`,
        Accept: accept,
        'X-GitHub-Api-Version': '2022-11-28',
        ...(body ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new GhError('GitHub\'a bağlanılamadı (internet?)');
  }
  if (!res.ok && res.status !== 404) {
    const t = await res.json().catch(() => ({}));
    const area = path.startsWith('/actions') ? 'Actions' : 'Contents';
    let msg;
    if (res.status === 401) msg = 'Anahtar geçersiz. Eksik ya da yanlış kopyalanmış olabilir.';
    else if (res.status === 403 && /rate limit/i.test(t.message || '')) msg = 'GitHub istek sınırı doldu, biraz sonra dene.';
    else if (res.status === 403) msg = `Anahtarda "${area}: Read and write" izni eksik.`;
    else msg = t.message || `GitHub ${res.status}`;
    const err = new GhError(msg);
    err.status = res.status;
    err.area = area;
    throw err;
  }
  return res;
}

const enc = (s) => btoa(String.fromCharCode(...new TextEncoder().encode(s)));
const dec = (b64) => new TextDecoder().decode(Uint8Array.from(atob(b64.replace(/\s/g, '')), (c) => c.charCodeAt(0)));

async function readFile(name, fallback) {
  const res = await gh(`/contents/${name}?ref=${DATA_BRANCH}`);
  if (res.status === 404) return { data: fallback, sha: null };
  const j = await res.json();
  let text = j.content ? dec(j.content) : null;
  if (text == null) {
    // 1 MB üstü dosyalar ham olarak alınır
    text = await (await gh(`/contents/${name}?ref=${DATA_BRANCH}`, { accept: 'application/vnd.github.raw+json' })).text();
  }
  return { data: JSON.parse(text), sha: j.sha };
}

async function writeFile(name, data, sha, message) {
  const res = await gh(`/contents/${name}`, {
    method: 'PUT',
    body: { message, branch: DATA_BRANCH, sha: sha || undefined, content: enc(JSON.stringify(data, null, 1) + '\n') },
  });
  if (res.status === 404) throw new Error('Repo ya da data dalı bulunamadı');
}

// Dosyayı oku → değiştir → yaz. Arada başka yerden değiştiyse bir kez daha dener.
async function mutate(name, fallback, fn, message) {
  for (let attempt = 0; attempt < 2; attempt++) {
    const { data, sha } = await readFile(name, fallback);
    const next = fn(structuredClone(data));
    try {
      await writeFile(name, next, sha, message);
      return next;
    } catch (e) {
      if (attempt === 0 && (e.status === 409 || e.status === 422)) continue;
      throw e;
    }
  }
}

async function runCheck() {
  const res = await gh(`/actions/workflows/${CHECK_WORKFLOW}/dispatches`, { method: 'POST', body: { ref: 'main' } });
  if (res.status === 404) throw new Error('Kontrol iş akışı bulunamadı');
}

async function lastRun() {
  const res = await gh(`/actions/workflows/${CHECK_WORKFLOW}/runs?per_page=1`);
  if (res.status === 404) return null;
  return (await res.json()).workflow_runs?.[0] || null;
}

/* ---------- Yardımcılar ---------- */
function toast(msg, ms = 3000) {
  const t = $('#toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toast.t);
  toast.t = setTimeout(() => (t.hidden = true), ms);
}

function ago(iso) {
  const m = Math.round((Date.now() - new Date(iso)) / 60000);
  if (m < 1) return 'az önce';
  if (m < 60) return `${m} dk önce`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h} sa önce`;
  return `${Math.round(h / 24)} gün önce`;
}

function view(item) {
  const st = state[item.url];
  const base = item.lastRead ?? st?.first ?? st?.latest ?? null;
  const chapters = st?.chapters || [];
  const unreadList = base == null ? [] : chapters.filter(([n]) => n > base);
  return {
    ...item,
    st,
    base,
    title: st?.title && st.title !== item.url ? st.title : host(item.url),
    unread: unreadList.length,
    pending: !st,
  };
}

/* ---------- Liste ---------- */
function render() {
  const rows = list.map(view).sort((a, b) => b.unread - a.unread || a.title.localeCompare(b.title, 'tr'));
  const shown = filter === 'unread' ? rows.filter((r) => r.unread > 0) : rows;
  renderNews();
  $('#list').hidden = filter === 'news';
  $('#news').hidden = filter !== 'news';
  $('#list').replaceChildren(
    ...shown.map((r) => {
      const li = document.createElement('li');
      li.className = 'card';
      li.innerHTML = `
        <div class="cover"><span></span><img alt="" loading="lazy" referrerpolicy="no-referrer"></div>
        <div>
          <div class="site"></div>
          <h3></h3>
          <div class="meta"></div>
          <div class="err-line" hidden></div>
        </div>
        <div class="badge"></div>`;
      setCover(li.querySelector('.cover'), r.st?.cover, r.title);
      li.querySelector('.site').textContent = host(r.url);
      li.querySelector('h3').textContent = r.title;
      li.querySelector('.meta').textContent = r.pending
        ? 'Bölümler okunuyor…'
        : r.st.latest == null ? `Okuduğun ${fmt(r.base)} · henüz okunamadı` : `Son ${fmt(r.st.latest)} · Okuduğun ${fmt(r.base)}` + (r.st.via === 'phone' ? ' · 📱' : '');
      if (r.st?.error && !phoneFresh(r.st)) {
        const e = li.querySelector('.err-line');
        e.textContent = r.st.error;
        e.hidden = false;
      }
      const b = li.querySelector('.badge');
      if (r.pending) { b.textContent = '…'; b.classList.add('zero'); }
      else if (r.st?.error && !phoneFresh(r.st) && !r.st.chapters?.length) { b.textContent = '!'; b.classList.add('warn'); }
      else if (r.unread) b.textContent = r.unread;
      else { b.textContent = '✓'; b.classList.add('zero'); }
      li.onclick = () => openSeries(r.url);
      return li;
    }),
  );
  $('#empty').hidden = list.length > 0 || filter === 'news';
  const total = rows.reduce((a, r) => a + r.unread, 0);
  document.title = total ? `(${total}) Manga Takip` : 'Manga Takip';
  if ('setAppBadge' in navigator) (total ? navigator.setAppBadge(total) : navigator.clearAppBadge()).catch(() => {});
}

async function load({ quiet = false } = {}) {
  if (!token()) return openSettings('Başlamak için GitHub anahtarını gir');
  if (NATIVE) NATIVE.setToken(token());
  try {
    const [l, s] = await Promise.all([readFile('list.json', []), readFile('state.json', {})]);
    list = l.data;
    state = s.data;
    store.set(CACHE_KEY, JSON.stringify({ list, state }));
    render();
    showLastRun();
    if (list.some((i) => !state[i.url])) startPolling();
  } catch (e) {
    if (e.status === 401 || e.status === 403) openSettings(e.message);
    else if (!quiet) toast(e.message);
  }
}

async function showLastRun() {
  try {
    const r = await lastRun();
    const el = $('#lastRun');
    if (!r) { el.textContent = 'Henüz kontrol yapılmadı'; return; }
    el.textContent = r.status === 'completed'
      ? `Son kontrol ${ago(r.updated_at)}` + (r.conclusion === 'success' ? '' : ' · hata oldu')
      : 'Kontrol ediliyor…';
    $('#btnCheck').classList.toggle('spin', r.status !== 'completed');
  } catch (e) {
    $('#lastRun').textContent = e.status === 403 ? 'Kontrol durumu okunamadı (Actions izni eksik)' : '';
  }
}

// Kontrol bitene kadar 15 sn'de bir bakar (en fazla 4 dk)
function startPolling() {
  clearInterval(pollTimer);
  const until = Date.now() + 4 * 60 * 1000;
  let sawRunning = false;
  pollTimer = setInterval(async () => {
    if (Date.now() > until) return clearInterval(pollTimer);
    const r = await lastRun().catch(() => null);
    if (r && r.status !== 'completed') sawRunning = true;
    showLastRun();
    if (r && r.status === 'completed' && sawRunning) {
      clearInterval(pollTimer);
      await load({ quiet: true });
      toast('Kontrol tamamlandı');
    }
  }, 15000);
}

function setFilter(f) {
  filter = f;
  document.querySelectorAll('.chip').forEach((x) => x.classList.toggle('active', x.dataset.filter === f));
  render();
  if (f === 'news') window.scrollTo(0, 0);
}
document.querySelectorAll('.chip').forEach((c) => c.addEventListener('click', () => setFilter(c.dataset.filter)));

/* ---------- Ekleme ---------- */
$('#btnAdd').onclick = () => {
  $('#formAdd').reset();
  $('#addErr').hidden = true;
  $('#dlgAdd').showModal();
};

$('#formAdd').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  const btn = $('#addSubmit');
  const err = $('#addErr');
  err.hidden = true;
  btn.disabled = true;
  btn.textContent = 'Ekleniyor…';
  try {
    const url = normalizeUrl(f.get('url'));
    const lr = String(f.get('lastRead') || '').trim().replace(',', '.');
    const lastRead = lr === '' ? null : Number(lr);
    if (lr !== '' && !Number.isFinite(lastRead)) throw new Error('Kaldığın bölüm bir sayı olmalı');
    if (list.some((i) => i.url === url)) throw new Error('Bu seri zaten listende');
    list = await mutate('list.json', [], (l) => {
      if (!l.some((i) => i.url === url)) l.push({ url, lastRead, added: new Date().toISOString() });
      return l;
    }, `Eklendi: ${url}`);
    $('#dlgAdd').close();
    render();
    if (NATIVE) NATIVE.checkNow();
    await runCheck();
    toast('Eklendi. Bölümler 1 dakika içinde gelecek.');
    startPolling();
  } catch (ex) {
    err.textContent = ex.message;
    err.hidden = false;
  } finally {
    btn.disabled = false;
    btn.textContent = 'Ekle';
  }
});

/* ---------- Seri detayı ---------- */
const PAGE = 60;
let shownCount = 0;

async function setRead(num) {
  const url = current;
  list = await mutate('list.json', [], (l) => {
    const it = l.find((i) => i.url === url);
    if (it) it.lastRead = num;
    return l;
  }, `Okundu: ${num}`);
  render();
  renderDetail(false);
}

function renderDetail(reset = true) {
  const item = list.find((i) => i.url === current);
  if (!item) return;
  const v = view(item);
  const chapters = v.st?.chapters || [];
  $('#dTitle').textContent = v.title;
  $('#dHost').textContent = host(v.url) + ' ↗';
  $('#dHost').href = v.url;
  setCover($('#dCoverBox'), v.st?.cover, v.title);
  $('#dStatus').textContent = v.pending
    ? 'Bölümler henüz okunmadı, kontrol bitince gelecek.'
    : `Son bölüm ${fmt(v.st.latest)} · Okuduğun ${fmt(v.base)}` + (v.st.error && !phoneFresh(v.st) ? ` · ⚠️ ${v.st.error}` : '') + (v.st.via === 'phone' ? ' · telefondan okundu' : '');

  // Sıradaki bölüm listenin derinlerindeyse oraya kadar göster
  const nextIdx = v.base == null ? -1 : chapters.findLastIndex(([n]) => n > v.base);
  if (reset) shownCount = Math.min(Math.max(PAGE, nextIdx + 15), chapters.length);
  const ul = $('#dChapters');
  ul.replaceChildren(
    ...chapters.slice(0, shownCount).map(([num, url]) => {
      const li = document.createElement('li');
      li.className = v.base != null && num <= v.base ? 'read' : 'new';
      const a = document.createElement('a');
      a.href = url;
      a.target = '_blank';
      a.rel = 'noopener';
      a.textContent = `Bölüm ${fmt(num)}`;
      li.dataset.num = num;
      a.onclick = (e) => openChapter(e, num);
      li.append(a);
      return li;
    }),
  );
  $('#dMore').hidden = shownCount >= chapters.length;

  const next = v.base == null ? null : [...chapters].reverse().find(([n]) => n > v.base);
  const nb = $('#dNext');
  if (next) {
    nb.href = next[1];
    nb.textContent = `Sıradaki: Bölüm ${fmt(next[0])}`;
    nb.removeAttribute('aria-disabled');
    nb.onclick = (e) => openChapter(e, next[0]);
  } else {
    nb.removeAttribute('href');
    nb.textContent = v.pending ? 'Bekleniyor…' : 'Güncelsin ✓';
    nb.setAttribute('aria-disabled', 'true');
    nb.onclick = null;
  }
}

// Bölümü aç: uygulamada okuyucuda, tarayıcıda sitenin kendi sayfasında
function openChapter(e, num) {
  if (NATIVE && NATIVE.openReader) {
    e.preventDefault();
    const v = view(list.find((i) => i.url === current));
    NATIVE.openReader(current, v.title, JSON.stringify(v.st?.chapters || []), num);
    // Kaldığın yeri okuyucu kaydeder; ekranda hemen göster
    const it = list.find((i) => i.url === current);
    if (it && (it.lastRead == null || num > it.lastRead)) it.lastRead = num;
    render();
    renderDetail(false);
    return;
  }
  setRead(num).catch((err) => toast(err.message));
}

function openSeries(url) {
  current = url;
  renderDetail(true);
  $('#dlgSeries').showModal();
  // Kaldığın yere kaydır
  const v = view(list.find((i) => i.url === url));
  const next = v.base == null ? null : [...(v.st?.chapters || [])].reverse().find(([n]) => n > v.base);
  const li = next && document.querySelector(`#dChapters li[data-num="${next[0]}"]`);
  if (li) requestAnimationFrame(() => li.scrollIntoView({ block: 'center' }));
}

$('#dMore').onclick = () => {
  shownCount += PAGE;
  renderDetail(false);
};

$('#dAllRead').onclick = () => {
  const st = state[current];
  if (st?.latest != null) setRead(st.latest).catch((e) => toast(e.message));
};

$('#dDelete').onclick = async () => {
  const item = list.find((i) => i.url === current);
  if (!confirm(`"${view(item).title}" takipten çıkarılsın mı?`)) return;
  try {
    const url = current;
    list = await mutate('list.json', [], (l) => l.filter((i) => i.url !== url), `Çıkarıldı: ${url}`);
    $('#dlgSeries').close();
    render();
  } catch (e) { toast(e.message); }
};

/* ---------- Şimdi kontrol et ---------- */
$('#btnCheck').onclick = async () => {
  if ($('#btnCheck').classList.contains('spin')) return toast('Kontrol zaten sürüyor');
  try {
    if (NATIVE) NATIVE.checkNow();
    await runCheck();
    $('#btnCheck').classList.add('spin');
    $('#lastRun').textContent = 'Kontrol başlatıldı…';
    startPolling();
  } catch (e) { toast(e.message); }
};

/* ---------- Ayarlar ---------- */
function openSettings(msg) {
  $('#formSettings').token.value = token();
  $('.howto').open = !token();
  updatePushState();
  if (msg) toast(msg);
  if (!$('#dlgSettings').open) $('#dlgSettings').showModal();
}
$('#btnSettings').onclick = () => openSettings();
$('#btnTest').onclick = testToken;
$('#formSettings').addEventListener('submit', (e) => {
  e.preventDefault();
  store.set(TOKEN_KEY, e.target.token.value.trim());
  if (NATIVE) NATIVE.setToken(token());
  $('#dlgSettings').close();
  load();
});

async function testToken() {
  const out = $('#tokenTest');
  const typed = $('#formSettings').token.value.trim();
  if (!typed) { out.textContent = 'Önce anahtarı yapıştır.'; return; }
  store.set(TOKEN_KEY, typed);
  out.textContent = 'Deneniyor…';
  const lines = [];
  const step = async (label, fn) => {
    try { await fn(); lines.push(`✓ ${label}`); return true; }
    catch (e) { lines.push(`✗ ${label}: ${e.message}`); return false; }
  };
  const ok1 = await step('Repo okunabiliyor', async () => {
    const r = await gh('/contents/list.json?ref=' + DATA_BRANCH);
    if (r.status === 404) throw new Error('data dalı ya da list.json bulunamadı');
  });
  if (ok1) {
    await step('Yazma izni (Contents)', () => mutate('list.json', [], (l) => l, 'Bağlantı testi'));
  }
  await step('Kontrol durumu (Actions)', () => lastRun());
  out.textContent = lines.join('\n');
  if (lines.every((l) => l.startsWith('✓'))) load({ quiet: true });
}

/* ---------- Bildirimler ---------- */
function keyBytes(b64) {
  const pad = '='.repeat((4 - (b64.length % 4)) % 4);
  return Uint8Array.from(atob((b64 + pad).replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));
}

async function currentSub() {
  if (!('serviceWorker' in navigator) || !('PushManager' in window)) return null;
  const reg = await navigator.serviceWorker.ready;
  return reg.pushManager.getSubscription();
}

async function updatePushState() {
  const st = $('#pushState');
  const btn = $('#btnPush');
  if (NATIVE) {
    st.textContent = 'Açık · bildirimleri bu uygulama gösteriyor';
    btn.hidden = true;
    $('#banner').hidden = true;
    return;
  }
  if (!('PushManager' in window)) {
    st.textContent = 'Bu tarayıcı desteklemiyor. Chrome ile aç ve ana ekrana ekle.';
    btn.disabled = true;
    return updateBanner(false);
  }
  const sub = await currentSub();
  if (Notification.permission === 'denied') {
    st.textContent = 'Engellenmiş. Telefon ayarlarından bu uygulamaya bildirim izni ver.';
    btn.disabled = true;
  } else {
    st.textContent = sub ? 'Açık · yeni bölüm gelince haber veririm' : 'Kapalı';
    btn.textContent = sub ? 'Kapat' : 'Aç';
    btn.disabled = false;
  }
  updateBanner(!!sub);
}

function updateBanner(subscribed) {
  const b = $('#banner');
  if (subscribed || !token() || !('PushManager' in window) || Notification.permission === 'denied') {
    b.hidden = true;
    return;
  }
  b.hidden = false;
  b.innerHTML = 'Yeni bölüm bildirimleri kapalı.<br><button class="primary">Bildirimleri aç</button>';
  b.querySelector('button').onclick = enablePush;
}

async function enablePush() {
  try {
    if ((await Notification.requestPermission()) !== 'granted') return updatePushState();
    const reg = await navigator.serviceWorker.ready;
    const sub = (await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(VAPID_PUBLIC_KEY) })).toJSON();
    await mutate('subs.json', [], (s) => [...s.filter((x) => x.endpoint !== sub.endpoint), sub], 'Bildirim cihazı eklendi');
    toast('Bildirimler açıldı');
  } catch (e) { toast(e.message); }
  updatePushState();
}

$('#btnPush').onclick = async () => {
  const sub = await currentSub();
  if (!sub) return enablePush();
  try {
    await mutate('subs.json', [], (s) => s.filter((x) => x.endpoint !== sub.endpoint), 'Bildirim cihazı çıkarıldı');
    await sub.unsubscribe();
    toast('Bildirimler kapatıldı');
  } catch (e) { toast(e.message); }
  updatePushState();
};

/* ---------- Başlangıç ---------- */
// Android geri tuşu: açık pencere varsa onu kapat
window.__nativeBack = () => {
  const open = [...document.querySelectorAll('dialog[open]')];
  if (open.length) { open[open.length - 1].close(); return true; }
  if (filter !== 'all') { setFilter('all'); return true; }
  return false;
};
window.__nativeCheckDone = (summary) => {
  load({ quiet: true });
  if (summary) toast(summary);
};

document.querySelectorAll('[data-close]').forEach((b) => (b.onclick = () => b.closest('dialog').close()));
document.querySelectorAll('dialog').forEach((d) => d.addEventListener('click', (e) => { if (e.target === d) d.close(); }));

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('./sw.js').then(() => updatePushState()).catch(() => {});
}
document.addEventListener('visibilitychange', () => { if (!document.hidden && token()) load({ quiet: true }); });

// Önce telefondaki son kopyayı göster, sonra GitHub'dan tazele
try {
  const c = JSON.parse(store.get(CACHE_KEY) || 'null');
  if (c) { list = c.list; state = c.state; render(); }
} catch {}
load();

// Kapak resmi: yoksa ya da yüklenemezse baş harfli renkli kutu görünür
function setCover(box, src, title) {
  const span = box.querySelector('span');
  const img = box.querySelector('img');
  const words = String(title || '?').trim().split(/\s+/).filter(Boolean);
  span.textContent = ((words[0]?.[0] || '?') + (words[1]?.[0] || '')).toLocaleUpperCase('tr');
  let h = 0;
  for (const ch of String(title || '')) h = (h * 31 + ch.codePointAt(0)) % 360;
  box.style.setProperty('--hue', h);
  img.onerror = () => { img.hidden = true; };
  img.onload = () => { img.hidden = false; };
  if (src) { img.hidden = false; img.src = src; } else { img.hidden = true; img.removeAttribute('src'); }
}

/* ---------- Yeni bölüm bildirimleri sekmesi ---------- */
// Her serinin state kaydında news: [[bölüm, bulunduğu zaman], ...] tutulur.
// "Görüldü" bilgisi bu cihazda saklanır; okuduğun bölümler zaten soluk görünür.
const NEWS_SEEN_KEY = 'mt-news-seen';
const NEWS_DAYS = 30;
function newsSeenAt() { return store.get(NEWS_SEEN_KEY) || ''; }

function newsEntries() {
  const since = Date.now() - NEWS_DAYS * 86400000;
  const out = [];
  for (const item of list) {
    const st = state[item.url];
    if (!st?.news) continue;
    const v = view(item);
    const urls = new Map((st.chapters || []).map(([n, u]) => [n, u]));
    for (const [num, at] of st.news) {
      if (new Date(at).getTime() < since) continue;
      out.push({ item, v, num, at, href: urls.get(num) || item.url, read: v.base != null && num <= v.base });
    }
  }
  return out.sort((a, b) => b.at.localeCompare(a.at) || b.num - a.num);
}

function dayLabel(iso) {
  const d = new Date(iso);
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const day = new Date(d); day.setHours(0, 0, 0, 0);
  const diff = Math.round((today - day) / 86400000);
  if (diff === 0) return 'Bugün';
  if (diff === 1) return 'Dün';
  return d.toLocaleDateString('tr-TR', { day: 'numeric', month: 'long', weekday: 'long' });
}

function renderNews() {
  const entries = newsEntries();
  const seen = newsSeenAt();
  const fresh = entries.filter((e) => !e.read && e.at > seen).length;
  const cnt = $('#newsCount');
  cnt.textContent = fresh;
  cnt.hidden = !fresh;
  $('#newsAllRead').hidden = !fresh;
  $('#newsEmpty').hidden = entries.length > 0;
  if (filter !== 'news') return;

  const items = [];
  let lastDay = '';
  for (const e of entries) {
    const day = dayLabel(e.at);
    if (day !== lastDay) {
      const h = document.createElement('li');
      h.className = 'news-day';
      h.textContent = day;
      items.push(h);
      lastDay = day;
    }
    const li = document.createElement('li');
    const a = document.createElement('a');
    a.className = 'news-item' + (e.read ? ' seen' : '');
    a.href = e.href;
    a.target = '_blank';
    a.rel = 'noopener';
    a.innerHTML = `
      <div class="cover"><span></span><img alt="" loading="lazy" referrerpolicy="no-referrer"></div>
      <div><b></b><div class="meta"></div></div>
      <div class="when"></div>`;
    setCover(a.querySelector('.cover'), e.v.st?.cover, e.v.title);
    a.querySelector('b').textContent = e.v.title;
    const meta = a.querySelector('.meta');
    if (!e.read && e.at > seen) { const dot = document.createElement('span'); dot.className = 'dot'; meta.append(dot); }
    meta.append(`Bölüm ${fmt(e.num)}` + (e.read ? ' · okundu' : '') + ' · ' + host(e.item.url));
    a.querySelector('.when').textContent = new Date(e.at).toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit' });
    a.onclick = (ev) => {
      current = e.item.url;
      if (NATIVE && NATIVE.openReader) return openChapter(ev, e.num);
      if (!e.read) setRead(e.num).catch((err) => toast(err.message));
    };
    li.append(a);
    items.push(li);
  }
  $('#newsList').replaceChildren(...items);
}

$('#newsAllRead').onclick = () => {
  store.set(NEWS_SEEN_KEY, new Date().toISOString());
  render();
};
