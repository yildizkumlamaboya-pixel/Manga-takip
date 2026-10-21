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
    title: st?.title && st.title !== item.url ? st.title : (item.title || host(item.url)),
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
  const shelfOf = (r) => (r.shelf === 'done' || r.shelf === 'inbox' ? r.shelf : '');
  // Arama yazılıysa tüm raflarda arar; değilse seçili rafı gösterir
  const shown = q ? rows.filter((r) => r.title.toLocaleLowerCase('tr').includes(q) || r.url.includes(q))
    : filter === 'done' ? rows.filter((r) => shelfOf(r) === 'done')
    : filter === 'inbox' ? rows.filter((r) => shelfOf(r) === 'inbox')
    : rows.filter((r) => !shelfOf(r) && (filter !== 'unread' || r.unread > 0));
  $('#nDone').textContent = rows.filter((r) => shelfOf(r) === 'done').length || '';
  const nInbox = rows.filter((r) => shelfOf(r) === 'inbox').length;
  $('#nInbox').textContent = nInbox || '';
  $('#chipInbox').hidden = !nInbox && filter !== 'inbox';
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
        : r.shelf === 'inbox' ? `${r.from ? r.from + ' önerdi · ' : ''}` + (r.st.latest == null ? 'henüz okunamadı' : `${fmt(r.st.latest)} bölüm`)
        : r.st.latest == null ? `Okuduğun ${fmt(r.base)} · henüz okunamadı` : `Son ${fmt(r.st.latest)} · Okuduğun ${fmt(r.base)}`;

      const b = li.querySelector('.badge');
      if (r.pending) { b.textContent = '…'; b.classList.add('zero'); }
      else if (r.st?.error && !phoneFresh(r.st) && !r.st.chapters?.length) { b.textContent = '!'; b.classList.add('warn'); }
      else if (r.shelf === 'inbox') { b.textContent = '📥'; b.classList.add('zero'); }
      else if (r.unread) b.textContent = r.unread;
      else { b.textContent = '✓'; b.classList.add('zero'); }
      li.onclick = () => openSeries(r.url);
      return li;
    }),
  );
  $('#empty').hidden = list.length > 0 || filter === 'news';
  const none = $('#none');
  none.hidden = !(list.length > 0 && filter !== 'news' && shown.length === 0);
  none.textContent = q ? 'Aramana uyan seri yok.' : filter === 'done' ? 'Bitirdiğin seriler burada durur. Seri sayfasından "Bitti" seç.' : filter === 'unread' ? 'Yeni bölümü olan seri yok, güncelsin ✓'
    : filter === 'inbox' ? 'Arkadaşından liste eklediğinde seriler burada durur.' : '';
  const total = rows.filter((r) => r.shelf !== 'inbox').reduce((a, r) => a + r.unread, 0);
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
    if (current && $('#dlgSeries').open) renderDetail(false);
    showLastRun();
    if (list.some((i) => !state[i.url])) startPolling();
    // Kaldırılan "Bıraktım" rafındaki seriler Okuyorum'a döner
    if (list.some((i) => i.shelf === 'dropped')) {
      mutate('list.json', [], (x) => { x.forEach((i) => { if (i.shelf === 'dropped') delete i.shelf; }); return x; }, 'Raf düzeltme')
        .then((x) => { list = x; render(); }).catch(() => {});
    }
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
  renderOffline(v);
  const inbox = item.shelf === 'inbox';
  $('#dInbox').hidden = !inbox;
  $('#dShelf').hidden = inbox;
  if (inbox) $('#dInboxFrom').textContent = (item.from ? `${item.from} önerdi` : 'Arkadaştan geldi') + ' · bildirim gelmez';
  document.querySelectorAll('#dShelf button').forEach((b) => b.classList.toggle('active', b.dataset.shelf === (item.shelf === 'done' ? 'done' : '')));
  $('#dStatus').textContent = v.pending
    ? 'Bölümler henüz okunmadı, kontrol bitince gelecek.'
    : `Son bölüm ${fmt(v.st.latest)} · Okuduğun ${fmt(v.base)}` + '';

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
  if (NATIVE && NATIVE.clearNotification) NATIVE.clearNotification(current);
  if (st?.latest != null) setRead(st.latest).catch((e) => toast(e.message));
};

$('#dDelete').onclick = async () => {
  const item = list.find((i) => i.url === current);
  if (!(await ask(`"${view(item).title}" takipten çıkarılsın mı?`))) return;
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
  $('#tokenStatus').textContent = token() ? 'Kayıtlı ✓' : 'Girilmedi, dokunup yapıştır';
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
    $('#pushRow').hidden = true; // uygulamada bildirimleri telefon gösteriyor
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
  if (!summary) return;
  // Telefonun özeti ("Telefondan okunan: 6, yeni bölüm: 2") sadeleştirilir
  const fresh = /yeni bölüm: (\d+)/.exec(summary);
  if (fresh) toast(`${fresh[1]} yeni bölüm`);
  else if (/hata|anahtarı yok/i.test(summary)) toast(summary);
  else toast('Kontrol tamamlandı');
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
const NEWS_CLEARED_KEY = 'mt-news-cleared';

function newsEntries() {
  const since = Date.now() - NEWS_DAYS * 86400000;
  const out = [];
  for (const item of list) {
    const st = state[item.url];
    if (!st?.news || item.shelf === 'inbox') continue;
    const v = view(item);
    const urls = new Map((st.chapters || []).map(([n, u]) => [n, u]));
    for (const [num, at] of st.news) {
      if (new Date(at).getTime() < since) continue;
      if (at <= (store.get('mt-news-cleared') || '')) continue; // "Tümünü okundu say" ile temizlendi
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
  // Düğmeler: okunmamış varsa "okundu say", liste doluysa "temizle"
  $('#newsActions').hidden = !entries.length;
  $('#newsAllRead').hidden = !entries.some((e) => !e.read);
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

$('#newsAllRead').onclick = async () => {
  // Bildirimdeki tüm bölümleri okundu say, listeyi ve telefondaki bildirimleri temizle
  const entries = newsEntries();
  const upTo = {};
  for (const e of entries) if (!e.read) upTo[e.item.url] = Math.max(upTo[e.item.url] ?? -Infinity, e.num);
  try {
    if (Object.keys(upTo).length) {
      list = await mutate('list.json', [], (l) => {
        for (const it of l) {
          const n = upTo[it.url];
          if (n != null && (it.lastRead == null || n > it.lastRead)) it.lastRead = n;
        }
        return l;
      }, 'Bildirimler okundu');
    }
  } catch (err) { toast(err.message); }
  store.set(NEWS_CLEARED_KEY, new Date().toISOString());
  if (NATIVE && NATIVE.clearNotifications) NATIVE.clearNotifications();
  toast('Tüm bildirimler okundu sayıldı');

  store.set(NEWS_SEEN_KEY, new Date().toISOString());
  render();
};

/* ---------- Son okunanlar ---------- */
// Hangi seriyi ne zaman okuduğun bu cihazda tutulur; ana sayfa buna göre sıralanır.
const RECENT_KEY = 'mt-recent';
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
  const sync = () => { $('#quietTimes').hidden = !on.checked; };
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
  $('#dOffInfo').textContent = !o.n && !nums.length ? 'Açarsan kaldığın yerden sonraki bölümler telefona kaydedilir, internetsiz okursun. Okuduğun bölümler 7 gün sonra silinir; son okuduğun 3 bölüm hep kalır.'
    : `${nums.length} bölüm telefonda hazır` + (nums.length ? ` (${fmt(nums[0])}–${fmt(nums[nums.length - 1])}) · ${mb(o.size || 0)}` : '')
      + (o.busy ? ' · indiriliyor…' : '') + ' · son okuduğun 3 bölüm hep kalır';
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
  const refresh = () => { $('#offTotal').textContent = mb(NATIVE.offlineTotal() || 0) + ' yer kaplıyor · ayrıntılar için dokun'; };
  dns.checked = NATIVE.getSetting('dns_on') === 'true';
  wifi.checked = NATIVE.getSetting('offline_wifi') === 'true';
  dns.onchange = () => { NATIVE.setSetting('dns_on', String(dns.checked)); toast(dns.checked ? 'Güvenli DNS açık' : 'Güvenli DNS kapalı'); };
  wifi.onchange = () => NATIVE.setSetting('offline_wifi', String(wifi.checked));
  $('#offClear').onclick = async () => {
    if (!(await ask('Telefondaki tüm çevrimdışı bölüm kopyaları silinsin mi?', 'Sil'))) return;
    NATIVE.clearOffline();
    setTimeout(() => { refresh(); renderOffDetails(); }, 800);
    toast('Kopyalar silindi');
  };
  $('#offDetails').onclick = () => { renderOffDetails(); $('#dlgOffline').showModal(); };
  $('#btnSettings').addEventListener('click', refresh);
  refresh();
})();

/* ---------- Pil izni (bildirimlerin gecikmemesi için) ---------- */
function updateBattery() {
  const box = $('#batteryBox');
  if (!NATIVE || !NATIVE.batteryFree) return;
  box.hidden = false;
  const free = NATIVE.batteryFree();
  $('#batteryState').textContent = free
    ? 'Açık · telefon yaklaşık 15 dakikada bir yeni bölüm arar.'
    : 'Pil tasarrufu kontrolleri saatlerce geciktirebilir. Bildirimler zamanında gelsin diye izin ver.';
  $('#btnBattery').hidden = free;
}
$('#btnBattery').onclick = () => { NATIVE.askBatteryFree(); setTimeout(updateBattery, 4000); };
$('#btnSettings').addEventListener('click', updateBattery);
document.addEventListener('visibilitychange', () => { if (!document.hidden) updateBattery(); });

/* ---------- Liste paylaş ---------- */
const NAME_KEY = 'mt-name';

function shareText() {
  const name = ($('#shareName').value || '').trim();
  const rows = list.filter((i) => i.shelf !== 'inbox').map(view)
    .sort((a, b) => a.title.localeCompare(b.title, 'tr'));
  const head = `📚 Manga listem (${rows.length} seri)` + (name ? ` — ${name}` : '');
  const how = 'Eklemek için: Manga Takip / Manga Rafı → Ayarlar → Liste ekle → bu mesajı yapıştır.';
  const body = rows.map((r, i) => `${i + 1}. ${r.title}\n${r.url}`).join('\n\n');
  return { text: `${head}\n${how}\n\n${body}`, count: rows.length };
}

function openShare() {
  $('#shareName').value = store.get(NAME_KEY) || '';
  const upd = () => {
    const t = shareText();
    $('#shareText').value = t.text;
    $('#shareInfo').textContent = `${t.count} seri paylaşılacak (Arkadaştan rafındakiler hariç). Kaldığın bölümler paylaşılmaz.`;
  };
  $('#shareName').oninput = () => { store.set(NAME_KEY, $('#shareName').value.trim()); upd(); };
  upd();
  $('#dlgShare').showModal();
}

async function copyText(t) {
  try { await navigator.clipboard.writeText(t); return true; } catch {}
  const ta = $('#shareText');
  ta.removeAttribute('readonly'); ta.select();
  const ok = document.execCommand && document.execCommand('copy');
  ta.setAttribute('readonly', '');
  return ok;
}

$('#btnShare').onclick = () => { $('#dlgSettings').close(); openShare(); };
$('#shareCopy').onclick = async () => toast((await copyText($('#shareText').value)) ? 'Kopyalandı, istediğin yere yapıştır' : 'Kopyalanamadı, metni basılı tutup kopyala');
$('#shareSend').onclick = async () => {
  const t = $('#shareText').value;
  if (NATIVE && NATIVE.share) return NATIVE.share(t);
  if (navigator.share) { try { await navigator.share({ text: t }); return; } catch {} }
  toast((await copyText(t)) ? 'Kopyalandı, istediğin yere yapıştır' : 'Metni basılı tutup kopyala');
};

/* ---------- Liste ekle ---------- */
let importItems = [];
let importDest = 'inbox';

function parseList(text) {
  let from = null;
  const m = /📚[^\n]*?(?:—|-)\s*([^\n(]+?)\s*$/m.exec(text);
  if (m) from = m[1].trim().slice(0, 40) || null;
  const lines = text.split(/\r?\n/);
  const out = [];
  const seen = new Set();
  let prev = '';
  for (const line of lines) {
    const urls = line.match(/https?:\/\/[^\s<>"')\]]+/g);
    if (!urls) { if (line.trim()) prev = line.trim(); continue; }
    for (const raw of urls) {
      let url;
      try { url = normalizeUrl(raw); } catch { continue; }
      if (seen.has(url)) continue;
      seen.add(url);
      const t = line.replace(raw, '').replace(/^\s*\d+[.)-]\s*/, '').trim() || prev.replace(/^\s*\d+[.)-]\s*/, '').trim();
      out.push({ url, title: t && !/^https?:/.test(t) && !t.startsWith('📚') && !t.startsWith('Eklemek için') ? t : host(url), have: list.some((i) => i.url === url) });
    }
    prev = '';
  }
  return { from, items: out };
}

function renderImport() {
  const ul = $('#importList');
  ul.replaceChildren(...importItems.map((it, i) => {
    const li = document.createElement('li');
    li.innerHTML = '<label><input type="checkbox"><span><b></b><small></small></span></label>';
    const cb = li.querySelector('input');
    cb.checked = !it.have && it.on !== false;
    cb.disabled = it.have;
    cb.onchange = () => { it.on = cb.checked; updateImportBtn(); };
    li.querySelector('b').textContent = it.title;
    li.querySelector('small').textContent = it.have ? 'zaten listende' : host(it.url);
    if (it.have) li.classList.add('have');
    return li;
  }));
  updateImportBtn();
}

function updateImportBtn() {
  const n = importItems.filter((it) => !it.have && it.on !== false).length;
  const btn = $('#importAdd');
  btn.disabled = n === 0;
  btn.textContent = n ? `${n} seriyi ekle` : 'Ekle';
}

function openImport() {
  importItems = [];
  $('#importText').value = '';
  $('#importPreview').hidden = true;
  updateImportBtn();
  $('#dlgImport').showModal();
}

$('#btnImportList').onclick = () => { $('#dlgSettings').close(); openImport(); };
$('#openImport').onclick = () => { $('#dlgAdd').close(); openImport(); };
$('#importRead').onclick = () => {
  const { from, items } = parseList($('#importText').value);
  importItems = items.map((it) => ({ ...it, from }));
  $('#importPreview').hidden = false;
  $('#importInfo').textContent = !items.length ? 'Metinde manga linki bulunamadı.'
    : `${from ? 'Gönderen: ' + from + ' · ' : ''}${items.length} seri bulundu` + (items.some((i) => i.have) ? `, ${items.filter((i) => i.have).length} tanesi zaten listende` : '') + '.';
  renderImport();
};
document.querySelectorAll('#importDest button').forEach((b) => (b.onclick = () => {
  importDest = b.dataset.dest;
  document.querySelectorAll('#importDest button').forEach((x) => x.classList.toggle('active', x === b));
  $('#importDestInfo').textContent = importDest === 'inbox'
    ? 'Arkadaştan rafındaki serilerden bildirim gelmez. Beğendiğini "Okumaya başla" ile Okuyorum\'a alırsın.'
    : 'Seriler doğrudan Okuyorum\'a eklenir ve yeni bölüm bildirimleri gelir.';
}));
$('#importAdd').onclick = async () => {
  const pick = importItems.filter((it) => !it.have && it.on !== false);
  if (!pick.length) return;
  const btn = $('#importAdd');
  btn.disabled = true;
  try {
    const now = new Date().toISOString();
    list = await mutate('list.json', [], (l) => {
      for (const it of pick) {
        if (l.some((i) => i.url === it.url)) continue;
        const entry = { url: it.url, lastRead: null, added: now };
        if (it.title && it.title !== host(it.url)) entry.title = it.title;
        if (importDest === 'inbox') { entry.shelf = 'inbox'; if (it.from) entry.from = it.from; }
        l.push(entry);
      }
      return l;
    }, `Liste eklendi (${pick.length})`);
    $('#dlgImport').close();
    setFilter(importDest === 'inbox' ? 'inbox' : 'all');
    toast(`${pick.length} seri eklendi, bölümler okunuyor…`);
    startImportCheck();
  } catch (e) { toast(e.message); btn.disabled = false; }
};

/* Arkadaştan rafından Okuyorum'a: ilk bölümden başlar */
$('#dStart').onclick = async () => {
  const url = current;
  const v = view(list.find((i) => i.url === url));
  const chapters = v.st?.chapters || [];
  const first = chapters.length ? chapters[chapters.length - 1][0] : null;
  try {
    list = await mutate('list.json', [], (l) => {
      const it = l.find((i) => i.url === url);
      if (it) { delete it.shelf; delete it.from; it.lastRead = first != null ? first - 1 : null; }
      return l;
    }, 'Okumaya başla');
    render();
    renderDetail(false);
    toast("Okuyorum'a taşındı, ilk bölümden başlayabilirsin");
  } catch (e) { toast(e.message); }
};

function startImportCheck() {
  render();
  if (NATIVE) NATIVE.checkNow();
  runCheck().then(startPolling).catch(() => {});
}

/* ---------- Kayıtlı bölümler ayrıntısı ---------- */
// 41,42,43,45 → "41–43, 45"
function ranges(nums) {
  const out = [];
  let start = null, prev = null;
  for (const n of nums) {
    if (start == null) { start = prev = n; continue; }
    if (n - prev <= 1 && Number.isInteger(n) === Number.isInteger(prev)) { prev = n; continue; }
    out.push(start === prev ? fmt(start) : `${fmt(start)}–${fmt(prev)}`);
    start = prev = n;
  }
  if (start != null) out.push(start === prev ? fmt(start) : `${fmt(start)}–${fmt(prev)}`);
  return out.join(', ');
}

function renderOffDetails() {
  if (!NATIVE || !NATIVE.offlineDetails) return;
  const urls = list.map((i) => i.url);
  let rows = [];
  try { rows = JSON.parse(NATIVE.offlineDetails(JSON.stringify(urls)) || '[]'); } catch {}
  const total = rows.reduce((a, r) => a + (r.size || 0), 0);
  const count = rows.reduce((a, r) => a + (r.nums ? r.nums.length : 0), 0);
  $('#offSum').textContent = rows.length ? `${count} bölüm · ${mb(total)} yer kaplıyor. Okuduğun bölümler 7 gün sonra silinir, son okuduğun 3 bölüm hep kalır.`
    : 'Telefonda kayıtlı bölüm yok. Bir mangada "Çevrimdışı okuma"yı açınca bölümler burada görünür.';
  $('#offClear').hidden = !rows.length;
  $('#offList').replaceChildren(...rows.map((r) => {
    const li = document.createElement('li');
    li.innerHTML = '<div class="cover"><span></span><img alt="" referrerpolicy="no-referrer"></div><div class="txt"><b></b><small></small><small class="nums"></small></div><button type="button" class="ghost small-btn">Sil</button>';
    const btn = li.querySelector('button');
    if (!r.url) {
      li.querySelector('.cover').remove();
      li.querySelector('b').textContent = 'Listende olmayan seriler';
      li.querySelector('small').textContent = `${mb(r.size)} · takipten çıkardığın serilerden kalan`;
      li.querySelector('.nums').remove();
      btn.onclick = () => { NATIVE.clearOfflineOthers(JSON.stringify(urls)); setTimeout(renderOffDetails, 500); };
      return li;
    }
    const v = view(list.find((i) => i.url === r.url));
    setCover(li.querySelector('.cover'), v.st?.cover, v.title);
    li.querySelector('b').textContent = v.title;
    li.querySelector('small').textContent = `${r.nums.length} bölüm · ${mb(r.size)}` + (r.busy ? ' · indiriliyor…' : '');
    li.querySelector('.nums').textContent = r.nums.length ? 'Bölüm ' + ranges(r.nums) : '';
    btn.onclick = async () => {
      if (!(await ask(`"${v.title}" için kayıtlı ${r.nums.length} bölüm silinsin mi?`, 'Sil'))) return;
      NATIVE.clearOfflineSeries(r.url);
      setTimeout(renderOffDetails, 500);
    };
    li.querySelector('.txt').onclick = () => { $('#dlgOffline').close(); $('#dlgSettings').close(); openSeries(r.url); };
    return li;
  }));
  const t = $('#offTotal');
  if (t) t.textContent = mb(total) + ' yer kaplıyor · ayrıntılar için dokun';
}

/* Bildirimleri temizle: okundu saymadan listeyi ve telefondaki bildirimleri boşaltır */
$('#newsClear').onclick = async () => {
  if (!(await ask('Bildirim listesi ve telefondaki bildirimler temizlensin mi? Okuduğun yer değişmez.', 'Temizle'))) return;
  store.set('mt-news-cleared', new Date().toISOString());
  store.set(NEWS_SEEN_KEY, new Date().toISOString());
  if (NATIVE && NATIVE.clearNotifications) NATIVE.clearNotifications();
  render();
  toast('Bildirimler temizlendi');
};

/* ---------- Okurken müzik ---------- */
(function setupMusic() {
  if (!NATIVE || !NATIVE.getSetting || !['true', 'false'].includes(NATIVE.getSetting('music_on'))) return;
  $('#musicBox').hidden = false;
  const on = $('#musicOn');
  on.checked = NATIVE.getSetting('music_on') === 'true';
  on.onchange = () => { NATIVE.setSetting('music_on', String(on.checked)); toast(on.checked ? 'Okurken müzik çalacak' : 'Müzik kapalı'); };
})();
