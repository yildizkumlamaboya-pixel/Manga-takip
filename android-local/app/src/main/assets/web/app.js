// Manga Rafı – arayüz. Her şey telefonda durur, hesap ya da sunucu yok:
//   list.json  -> takip ettiğin seriler + kaldığın bölüm
//   state.json -> başlık, kapak, bölüm listesi, yeni bölüm kayıtları (telefondaki kontrol yazar)
import { normalizeUrl } from './url.js';

const $ = (s, r = document) => r.querySelector(s);
let list = [];      // [{url, lastRead, added}]
let state = {};     // url -> {title, cover, latest, first, chapters:[[num,url]], error, news}
let filter = 'all';
let current = null;
let pollTimer = null;

// Uygulamanın kendi köprüsü. Tarayıcıda denerken (köprü yoksa) localStorage kullanılır.
const NATIVE = window.MangaApp && window.MangaApp.isApp ? window.MangaApp : null;

const store = {
  get: (k) => { try { return localStorage.getItem(k); } catch { return null; } },
  set: (k, v) => { try { localStorage.setItem(k, v); } catch {} },
};
const fmt = (n) => (n == null ? '–' : String(Number(n)));
const host = (u) => { try { return new URL(u).hostname.replace(/^www\./, ''); } catch { return u; } };

/* ---------- Telefon hafızası ---------- */
function readFile(name, fallback) {
  const text = NATIVE ? NATIVE.read(name) : store.get('rafi-' + name);
  if (!text) return fallback;
  try { return JSON.parse(text); } catch { return fallback; }
}

function writeFile(name, data) {
  const text = JSON.stringify(data, null, 1);
  if (NATIVE) {
    const err = NATIVE.write(name, text);
    if (err) throw new Error(err);
  } else store.set('rafi-' + name, text);
}

// Dosyayı oku → değiştir → yaz (kontrol arka planda state.json'a yazarken listeyi bozmamak için hep tazesinden)
async function mutate(name, fallback, fn) {
  const next = fn(readFile(name, fallback));
  writeFile(name, next);
  return next;
}

const checking = () => !!(NATIVE && NATIVE.isChecking && NATIVE.isChecking());

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
        : r.st.latest == null ? `Okuduğun ${fmt(r.base)} · henüz okunamadı` : `Son ${fmt(r.st.latest)} · Okuduğun ${fmt(r.base)}`;
      if (r.st?.error) {
        const e = li.querySelector('.err-line');
        e.textContent = r.st.error;
        e.hidden = false;
      }
      const b = li.querySelector('.badge');
      if (r.pending) { b.textContent = '…'; b.classList.add('zero'); }
      else if (r.st?.error && !r.st.chapters?.length) { b.textContent = '!'; b.classList.add('warn'); }
      else if (r.unread) b.textContent = r.unread;
      else { b.textContent = '✓'; b.classList.add('zero'); }
      li.onclick = () => openSeries(r.url);
      return li;
    }),
  );
  $('#empty').hidden = list.length > 0 || filter === 'news';
  const total = rows.reduce((a, r) => a + r.unread, 0);
  document.title = total ? `(${total}) Manga Rafı` : 'Manga Rafı';
}

function load() {
  list = readFile('list.json', []);
  state = readFile('state.json', {});
  render();
  if (current && $('#dlgSeries').open) renderDetail(false);
  showLastRun();
  if (checking()) startPolling();
}

function showLastRun() {
  const el = $('#lastRun');
  const spin = checking();
  $('#btnCheck').classList.toggle('spin', spin);
  if (spin) { el.textContent = 'Kontrol ediliyor…'; return; }
  const [at] = (NATIVE ? NATIVE.lastCheck() : '').split('|');
  el.textContent = at ? `Son kontrol ${ago(at)}` : (list.length ? 'Henüz kontrol yapılmadı' : '');
}

// Kontrol sürerken 3 sn'de bir ekranı tazeler, seriler geldikçe görünür
function startPolling() {
  clearInterval(pollTimer);
  pollTimer = setInterval(() => {
    if (!checking()) clearInterval(pollTimer);
    list = readFile('list.json', []);
    state = readFile('state.json', {});
    render();
    if (current && $('#dlgSeries').open) renderDetail(false);
    showLastRun();
  }, 3000);
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
    });
    $('#dlgAdd').close();
    render();
    if (NATIVE) { NATIVE.checkNew(); toast('Eklendi, bölümler okunuyor…'); setTimeout(startPolling, 300); }
    else toast('Eklendi (tarayıcıda bölüm okunamaz, uygulamada çalışır)');
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
  });
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
    : `Son bölüm ${fmt(v.st.latest)} · Okuduğun ${fmt(v.base)}` + (v.st.error ? ` · ⚠️ ${v.st.error}` : '');

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
    list = await mutate('list.json', [], (l) => l.filter((i) => i.url !== url));
    await mutate('state.json', {}, (s) => { delete s[url]; return s; });
    state = readFile('state.json', {});
    $('#dlgSeries').close();
    render();
  } catch (e) { toast(e.message); }
};

/* ---------- Şimdi kontrol et ---------- */
$('#btnCheck').onclick = () => {
  if (!NATIVE) return toast('Kontrol sadece uygulamada çalışır');
  if (checking()) return toast('Kontrol zaten sürüyor');
  if (!list.length) return toast('Önce + ile bir seri ekle');
  NATIVE.checkNow();
  setTimeout(() => { showLastRun(); startPolling(); }, 300);
};

/* ---------- Ayarlar ---------- */
function openSettings() {
  updateBattery();
  $('#about').textContent = `${list.length} seri takipte · veriler bu telefonda saklanıyor`;
  if (!$('#dlgSettings').open) $('#dlgSettings').showModal();
}
$('#btnSettings').onclick = () => openSettings();

function updateBattery() {
  const free = !NATIVE || !NATIVE.batteryFree || NATIVE.batteryFree();
  $('#batteryState').textContent = free
    ? 'Açık · telefon yaklaşık 15 dakikada bir yeni bölüm arar.'
    : 'Pil tasarrufu kontrolleri geciktirebilir. Bildirimlerin zamanında gelmesi için izin ver.';
  $('#btnBattery').hidden = free;
}
$('#btnBattery').onclick = () => { NATIVE?.askBatteryFree(); setTimeout(updateBattery, 4000); };

$('#btnExport').onclick = () => {
  if (!list.length) return toast('Listen boş, yedeklenecek bir şey yok');
  if (NATIVE) NATIVE.exportBackup(); else toast('Yedek sadece uygulamada');
};
$('#btnImport').onclick = () => {
  if (list.length && !confirm('Yedekteki liste şimdiki listenin yerine geçecek. Devam edilsin mi?')) return;
  if (NATIVE) NATIVE.importBackup(); else toast('Yedek sadece uygulamada');
};
$('#btnGhImport').onclick = () => {
  const repo = $('#ghRepo').value.trim();
  if (!repo) return toast('Depo adını yaz (kullanıcı/depo)');
  if (list.length && !confirm('Alınan liste şimdiki listenin yerine geçecek. Devam edilsin mi?')) return;
  if (!NATIVE) return toast('Bu sadece uygulamada çalışır');
  NATIVE.importGithub(repo);
  toast('Alınıyor…');
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
  load();
  if (summary) toast(summary);
};
window.__nativeToast = (msg) => toast(msg);
window.__nativeImported = (n) => {
  $('#dlgSettings').close();
  load();
  toast(`${n} seri yüklendi, bölümler kontrol ediliyor…`);
  if (NATIVE) { NATIVE.checkNow(); setTimeout(startPolling, 300); }
};

document.querySelectorAll('[data-close]').forEach((b) => (b.onclick = () => b.closest('dialog').close()));
document.querySelectorAll('dialog').forEach((d) => d.addEventListener('click', (e) => { if (e.target === d) d.close(); }));

document.addEventListener('visibilitychange', () => { if (!document.hidden) load(); });


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

/* ---------- Başlat (en sonda: tüm tanımlar hazır olsun) ---------- */
load();
// İlk açılışta hiç kontrol yapılmadıysa başlat
if (NATIVE && list.length && !NATIVE.lastCheck().split('|')[0] && !checking()) { NATIVE.checkNow(); setTimeout(startPolling, 300); }
