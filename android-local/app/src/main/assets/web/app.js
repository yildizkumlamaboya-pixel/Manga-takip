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
  const recent = recentMap();
  // Son okunanlar üstte (en yeni en üstte), hiç okunmamışlar altta yeni bölüm sayısına göre
  const rows = list.map(view).sort((a, b) =>
    (recent[b.url] || '').localeCompare(recent[a.url] || '') || b.unread - a.unread || a.title.localeCompare(b.title, 'tr'));
  const q = searchText();
  const shelfOf = (r) => (r.shelf === 'done' ? 'done' : '');
  // Arama yazılıysa tüm raflarda arar; değilse seçili rafı gösterir
  const shown = q ? rows.filter((r) => r.title.toLocaleLowerCase('tr').includes(q) || r.url.includes(q))
    : filter === 'done' ? rows.filter((r) => shelfOf(r) === 'done')
    : rows.filter((r) => !shelfOf(r) && (filter !== 'unread' || r.unread > 0));
  $('#nDone').textContent = rows.filter((r) => shelfOf(r) === 'done').length || '';
  $('#search').hidden = list.length < 6 && !q;
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
        e.textContent = r.st.phoneError || r.st.error;
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
  const none = $('#none');
  none.hidden = !(list.length > 0 && filter !== 'news' && shown.length === 0);
  none.textContent = q ? 'Aramana uyan seri yok.' : filter === 'done' ? 'Bitirdiğin seriler burada durur. Seri sayfasından "Bitti" seç.' : filter === 'unread' ? 'Yeni bölümü olan seri yok, güncelsin ✓' : '';
  const total = rows.reduce((a, r) => a + r.unread, 0);
  document.title = total ? `(${total}) Manga Rafı` : 'Manga Rafı';
}

function load() {
  list = readFile('list.json', []);
  // Kaldırılan "Bıraktım" rafındaki seriler Okuyorum'a döner
  if (list.some((i) => i.shelf === 'dropped')) {
    list.forEach((i) => { if (i.shelf === 'dropped') delete i.shelf; });
    try { writeFile('list.json', list); } catch {}
  }
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
  renderOffline(v);
  document.querySelectorAll('#dShelf button').forEach((b) => b.classList.toggle('active', b.dataset.shelf === (item.shelf === 'done' ? 'done' : '')));
  $('#dStatus').textContent = v.pending
    ? 'Bölümler henüz okunmadı, kontrol bitince gelecek.'
    : `Son bölüm ${fmt(v.st.latest)} · Okuduğun ${fmt(v.base)}` + (v.st.error ? ` · ⚠️ ${v.st.phoneError || v.st.error}` : '');

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
  const cur = v.base == null ? null : chapters.find(([n]) => n === v.base);
  const firstCh = chapters.length ? chapters[chapters.length - 1] : null;
  const blocked = !!v.st?.error && !chapters.length;
  $('#dSiteHelp').hidden = !(blocked && NATIVE && NATIVE.openSite);
  $('#dResume').hidden = blocked;
  $('#dRestart').hidden = !firstCh || v.pending;
  // Okuyucunun kaydettiği satır: bölüm yarım kaldıysa ana düğme oraya döner
  let pos = null;
  try { pos = JSON.parse((NATIVE && NATIVE.getPosition && NATIVE.getPosition(v.url)) || 'null'); } catch {}
  const half = cur && pos && pos.num === cur[0] && pos.p < 0.95 && pos.y >= 300 ? Math.round(pos.p * 100) : null;
  $('#dResumeInfo').textContent = v.pending ? 'Bölümler okunuyor…'
    : v.base == null ? 'Henüz okumaya başlamadın'
    : `Son okuduğun: Bölüm ${fmt(v.base)}` + (half != null ? ` (%${half})` : '') + (v.unread ? ` · ${v.unread} yeni bölüm` : ' · güncelsin');
  const ab = $('#dAgain');
  const nb = $('#dNext');
  if (half != null) {
    // Yarım bölüm: ana düğme kaldığın satıra, yandaki sıradakine
    nb.href = cur[1];
    nb.textContent = `Kaldığın yerden: ${fmt(cur[0])} ›`;
    nb.removeAttribute('aria-disabled');
    nb.onclick = (e) => openChapter(e, cur[0]);
    ab.hidden = !next;
    if (next) {
      ab.href = next[1];
      ab.textContent = `Sonraki: ${fmt(next[0])}`;
      ab.onclick = (e) => openChapter(e, next[0]);
    }
    return;
  }
  ab.hidden = !cur;
  if (cur) {
    ab.href = cur[1];
    ab.textContent = `Tekrar aç: ${fmt(cur[0])}`;
    ab.onclick = (e) => openChapter(e, cur[0]);
  }
  if (next) {
    nb.href = next[1];
    nb.textContent = `Devam et: ${fmt(next[0])} ›`;
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
  touchRecent(current);
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
  if (!(await ask(`"${view(item).title}" takipten çıkarılsın mı?`))) return;
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
$('#btnImport').onclick = async () => {
  if (list.length && !(await ask('Yedekteki liste şimdiki listenin yerine geçecek. Devam edilsin mi?'))) return;
  if (NATIVE) NATIVE.importBackup(); else toast('Yedek sadece uygulamada');
};
$('#btnGhImport').onclick = async () => {
  const repo = $('#ghRepo').value.trim();
  if (!repo) return toast('Depo adını yaz (kullanıcı/depo)');
  if (list.length && !(await ask('Alınan liste şimdiki listenin yerine geçecek. Devam edilsin mi?'))) return;
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

/* ---------- Son okunanlar ---------- */
// Hangi seriyi ne zaman okuduğun bu cihazda tutulur; ana sayfa buna göre sıralanır.
const RECENT_KEY = 'rafi-recent';
function recentMap() {
  try { return JSON.parse(store.get(RECENT_KEY) || '{}') || {}; } catch { return {}; }
}
function touchRecent(url) {
  if (!url) return;
  const m = recentMap();
  m[url] = new Date().toISOString();
  store.set(RECENT_KEY, JSON.stringify(m));
}

/* ---------- Arama ve raflar ---------- */
function searchText() { return ($('#search').value || '').trim().toLocaleLowerCase('tr'); }
$('#search').addEventListener('input', () => render());

const SHELF_NAMES = { '': 'Okuyorum', done: 'Bitti' };
document.querySelectorAll('#dShelf button').forEach((b) => (b.onclick = async () => {
  const url = current;
  const shelf = b.dataset.shelf || null;
  try {
    list = await mutate('list.json', [], (l) => {
      const it = l.find((i) => i.url === url);
      if (it) { if (shelf) it.shelf = shelf; else delete it.shelf; }
      return l;
    }, `Raf: ${SHELF_NAMES[shelf || '']}`);
    render();
    renderDetail(false);
    toast(`${SHELF_NAMES[shelf || '']} rafına taşındı`);
  } catch (e) { toast(e.message); }
}));

/* ---------- Sessiz saatler (uygulama ayarı) ---------- */
(function setupQuiet() {
  const box = $('#quietBox');
  if (!box || !NATIVE || !NATIVE.getSetting) return;
  box.hidden = false;
  const from = $('#quietFrom'), to = $('#quietTo'), on = $('#quietOn');
  for (const sel of [from, to]) {
    for (let h = 0; h < 24; h++) sel.append(new Option(String(h).padStart(2, '0') + ':00', h));
  }
  on.checked = NATIVE.getSetting('quiet_on') === 'true';
  from.value = NATIVE.getSetting('quiet_from') || '0';
  to.value = NATIVE.getSetting('quiet_to') || '8';
  const sync = () => { from.disabled = to.disabled = !on.checked; };
  sync();
  on.onchange = () => { NATIVE.setSetting('quiet_on', String(on.checked)); sync(); toast(on.checked ? 'Sessiz saatler açık' : 'Sessiz saatler kapalı'); };
  from.onchange = () => NATIVE.setSetting('quiet_from', from.value);
  to.onchange = () => NATIVE.setSetting('quiet_to', to.value);
})();

/* ---------- Seriye baştan başla ---------- */
// Kaldığın yeri ilk bölüme çeker ve ilk bölümü açar.
$('#dRestart').onclick = async () => {
  const url = current;
  const v = view(list.find((i) => i.url === url));
  const chapters = v.st?.chapters || [];
  if (!chapters.length) return;
  const [firstNum, firstUrl] = chapters[chapters.length - 1];
  if (v.base != null && v.base > firstNum
    && !(await ask(`Kaldığın yer (Bölüm ${fmt(v.base)}) sıfırlanacak ve ilk bölümden (Bölüm ${fmt(firstNum)}) başlayacaksın. Devam edilsin mi?`))) return;
  try {
    list = await mutate('list.json', [], (l) => {
      const it = l.find((i) => i.url === url);
      if (it) it.lastRead = firstNum;
      return l;
    }, 'Baştan başla');
    touchRecent(url);
    render();
    renderDetail(false);
    if (NATIVE && NATIVE.openReader) NATIVE.openReader(url, v.title, JSON.stringify(chapters), firstNum);
    else window.open(firstUrl, '_blank', 'noopener');
  } catch (e) { toast(e.message); }
};

/* ---------- Onay penceresi ---------- */
// Uygulamanın içindeki tarayıcı confirm() pencerelerini göstermediği için kendi penceremiz.
function ask(message, okText = 'Evet') {
  return new Promise((resolve) => {
    const d = document.createElement('dialog');
    d.className = 'sheet';
    d.innerHTML = '<p class="ask-msg"></p><div class="row"><button type="button" class="ghost">Vazgeç</button><button type="button" class="primary"></button></div>';
    d.querySelector('.ask-msg').textContent = message;
    const [no, yes] = d.querySelectorAll('button');
    yes.textContent = okText;
    let answered = false;
    const done = (v) => { if (answered) return; answered = true; d.close(); d.remove(); resolve(v); };
    no.onclick = () => done(false);
    yes.onclick = () => done(true);
    d.addEventListener('close', () => done(false));
    document.body.append(d);
    d.showModal();
  });
}

/* ---------- Bot korumalı site: sayfayı uygulamada açıp bölümleri al ---------- */
$('#dOpenSite').onclick = () => {
  const v = view(list.find((i) => i.url === current));
  touchRecent(current);
  NATIVE.openSite(current, v.title);
};

/* ---------- Çevrimdışı okuma (manga başına) ---------- */
let offTimer = null;
const mb = (b) => (b / 1048576 < 10 ? (b / 1048576).toFixed(1) : Math.round(b / 1048576)) + ' MB';

function offSummary(url) {
  try { return JSON.parse(NATIVE.getOffline(url) || '{}'); } catch { return {}; }
}

function renderOffline(v) {
  const box = $('#dOffline');
  if (!NATIVE || !NATIVE.getOffline) { box.hidden = true; return; }
  box.hidden = false;
  const o = offSummary(v.url);
  const sel = $('#dOffN');
  if (![...sel.options].some((x) => +x.value === (o.n || 0))) sel.append(new Option(`Sonraki ${o.n} bölüm`, o.n));
  sel.value = String(o.n || 0);
  const nums = o.nums || [];
  $('#dOffInfo').textContent = !o.n && !nums.length ? 'Açarsan kaldığın yerden sonraki bölümler telefona kaydedilir, internetsiz okursun. Okuduğun bölüm 7 gün sonra silinir.'
    : `${nums.length} bölüm telefonda hazır` + (nums.length ? ` (${fmt(nums[0])}–${fmt(nums[nums.length - 1])}) · ${mb(o.size || 0)}` : '')
      + (o.busy ? ' · indiriliyor…' : '') + ' · okuduktan 7 gün sonra silinir';
  // Bölüm listesinde kayıtlı olanları işaretle
  const set = new Set(nums.map(Number));
  setTimeout(() => document.querySelectorAll('#dChapters li').forEach((li) => li.classList.toggle('off', set.has(Number(li.dataset.num)))), 0);
  clearTimeout(offTimer);
  if (o.busy && $('#dlgSeries').open) offTimer = setTimeout(() => { if (current === v.url) renderOffline(view(list.find((i) => i.url === v.url))); }, 3000);
}

$('#dOffN').onchange = () => {
  const v = view(list.find((i) => i.url === current));
  const n = Number($('#dOffN').value);
  NATIVE.setOffline(v.url, n, JSON.stringify(v.st?.chapters || []), v.base ?? -1);
  toast(n >= 9999 ? 'Okumadığın tüm bölümler indirilecek' : n ? `Sonraki ${n} bölüm indirilecek` + (NATIVE.getSetting && NATIVE.getSetting('offline_wifi') === 'true' ? ' (Wi-Fi\'da)' : '') : 'Çevrimdışı okuma kapatıldı, bu mangadaki kopyalar silindi');
  setTimeout(() => renderOffline(v), 600);
};

(function setupNet() {
  const box = $('#netBox');
  if (!box || !NATIVE || !NATIVE.getOffline) return;
  box.hidden = false;
  const dns = $('#dnsOn'), wifi = $('#offWifi');
  const refresh = () => { $('#offTotal').textContent = 'Çevrimdışı kopyalar: ' + mb(NATIVE.offlineTotal() || 0); };
  dns.checked = NATIVE.getSetting('dns_on') === 'true';
  wifi.checked = NATIVE.getSetting('offline_wifi') === 'true';
  dns.onchange = () => { NATIVE.setSetting('dns_on', String(dns.checked)); toast(dns.checked ? 'Güvenli DNS açık' : 'Güvenli DNS kapalı'); };
  wifi.onchange = () => NATIVE.setSetting('offline_wifi', String(wifi.checked));
  $('#offClear').onclick = async () => {
    if (!(await ask('Telefondaki tüm çevrimdışı bölüm kopyaları silinsin mi?', 'Sil'))) return;
    NATIVE.clearOffline();
    setTimeout(refresh, 800);
    toast('Kopyalar silindi');
  };
  $('#btnSettings').addEventListener('click', refresh);
  refresh();
})();
