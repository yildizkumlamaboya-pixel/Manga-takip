// iOS arka plan kontrolü: sayfaları Swift indirir, ayrıştırma burada (Android ve GitHub ile aynı kodla) yapılır.
//   window.__ck = { mode: 'check' | 'page', onlyMissing, list, state, url, html }
import { parseSeriesPage, chaptersFromSitemap, sitemapChildren } from './parse.js';
import { mergeChapters, fillGaps } from './chapters.js';
import { siteRules } from './sites.js';

const C = window.__ck || {};
const NEWS_KEEP = 30;
let seq = 0;
const waits = {};
const post = (o) => window.webkit.messageHandlers.ck.postMessage(o);
window.__ckDone = (id, ok, body) => {
  const w = waits[id];
  delete waits[id];
  if (w) (ok ? w.res(body) : w.rej(new Error(body)));
};
const get = (url, browser = false) => new Promise((res, rej) => {
  const id = ++seq;
  waits[id] = { res, rej };
  post({ cmd: 'fetch', id, url, browser });
});

const titleFromSlug = (url) => new URL(url).pathname.replace(/\/+$/, '').split('/').pop()
  .split('-').filter(Boolean).map((w) => w.charAt(0).toLocaleUpperCase('tr') + w.slice(1)).join(' ');
const nowIso = () => new Date().toISOString();

const smCache = new Map();
const cached = (u) => { if (!smCache.has(u)) smCache.set(u, get(u)); return smCache.get(u); };

async function sitemapChapters(pageUrl, paths) {
  const origin = new URL(pageUrl).origin;
  const urls = [...new Set([...(paths || []).map((p) => origin + p), origin + '/sitemap.xml'])];
  for (const sm of urls) {
    try {
      const xml = await cached(sm);
      const all = sitemapChildren(xml);
      const named = all.filter((c) => /chapter|bolum|bölüm/i.test(c));
      const children = (named.length ? named : all.filter((c) => !/categor|kategori|tag|etiket|page|sayfa|post|author/i.test(c))).slice(0, 25);
      for (const c of children) {
        try {
          const found = chaptersFromSitemap(await cached(c), pageUrl);
          if (found.length) return found;
        } catch {}
      }
      const found = chaptersFromSitemap(xml, pageUrl);
      if (found.length) return found;
    } catch {}
  }
  return [];
}

async function fetchPage(url) {
  try {
    try {
      return await get(url);
    } catch (e) {
      // Bazı siteler adresin sonunda "/" yoksa 404 veriyor
      if (!/\b404\b/.test(e.message) || url.endsWith('/')) throw e;
      return await get(url + '/');
    }
  } catch (e) {
    // Bot koruması: sayfayı görünmez tarayıcıda aç
    if (!/403|503|429|doğrulama/.test(e.message)) throw e;
    return await get(url, true);
  }
}

function buildEntry(url, prev, s) {
  const chapters = fillGaps(mergeChapters(s.chapters, prev?.chapters || []));
  const top = chapters[0];
  return {
    title: s.title || (prev?.title && prev.title !== url ? prev.title : titleFromSlug(url)),
    cover: s.cover || prev?.cover || null,
    latest: top.num,
    first: prev?.first ?? top.num,
    chapters: chapters.map((c) => [c.num, c.url]),
    error: null,
    errorSince: null,
    checkedAt: nowIso(),
  };
}

async function readSeries(url, prev) {
  const rules = siteRules(new URL(url).hostname);
  let s;
  try {
    s = parseSeriesPage(await fetchPage(url), url);
  } catch (e) {
    const sm = await sitemapChapters(url, rules.sitemap);
    if (!sm.length) throw e;
    s = { title: null, cover: null, chapters: sm };
  }
  if (!s.chapters.length || rules.sitemap) {
    const sm = await sitemapChapters(url, rules.sitemap);
    if (sm.length) s.chapters = sm;
  }
  if (!s.chapters.length) throw new Error('bölüm bulunamadı');
  return buildEntry(url, prev, s);
}

/** Kaydı yazar, yeni bölümleri kayda ekler ve bildirim ister. Yeni bölüm sayısını döndürür. */
function save(item, prev, entry) {
  let fresh = [];
  if (entry.error == null && prev && prev.latest != null) fresh = entry.chapters.filter(([n]) => n > prev.latest);
  let log = prev?.news || [];
  if (fresh.length) log = [...fresh.map(([n]) => [n, nowIso()]), ...log].slice(0, NEWS_KEEP);
  if (log.length) entry.news = log;
  post({ cmd: 'entry', url: item.url, entry: JSON.stringify(entry) });
  const quietShelf = item.shelf === 'inbox' || item.shelf === 'dropped';
  if (fresh.length && !quietShelf) {
    const latest = entry.latest;
    const oldest = Math.min(...fresh.map(([n]) => n));
    const fmt = (n) => String(Number(n));
    post({
      cmd: 'notify', url: item.url, title: entry.title,
      body: fresh.length === 1 ? `Bölüm ${fmt(latest)} çıktı` : `${fresh.length} yeni bölüm (${fmt(oldest)}–${fmt(latest)})`,
      num: oldest,
    });
  }
  return fresh.length;
}

function errorEntry(url, prev, e) {
  const x = prev ? { ...prev } : { title: titleFromSlug(url), cover: null, latest: null, first: null, chapters: [] };
  x.error = e.message || String(e);
  if (!prev || !prev.errorSince) x.errorSince = nowIso();
  return x;
}

async function runCheck() {
  const list = C.list || [];
  const state = C.state || {};
  const items = list.filter((i) => i.url && (!C.onlyMissing || !state[i.url]));
  if (!items.length) return C.onlyMissing ? '' : 'Takip listesi boş';
  let ok = 0, fail = 0, fresh = 0, i = 0;
  async function worker() {
    while (i < items.length) {
      const item = items[i++];
      const prev = state[item.url];
      try {
        const entry = await readSeries(item.url, prev);
        fresh += save(item, prev, entry);
        ok++;
      } catch (e) {
        fail++;
        post({ cmd: 'entry', url: item.url, entry: JSON.stringify(errorEntry(item.url, prev, e)) });
      }
    }
  }
  await Promise.all([worker(), worker(), worker()]);
  return `Kontrol edildi: ${ok}` + (fail ? `, okunamayan: ${fail}` : '') + (fresh ? `, yeni bölüm: ${fresh}` : '');
}

async function runPage() {
  const s = parseSeriesPage(C.html || '', C.url);
  if (!s.chapters.length) return '0';
  const item = (C.list || []).find((i) => i.url === C.url) || { url: C.url };
  const prev = (C.state || {})[C.url];
  const entry = buildEntry(C.url, prev, s);
  save(item, prev, entry);
  return String(entry.chapters.length);
}

(C.mode === 'page' ? runPage() : runCheck())
  .then((summary) => post({ cmd: 'done', summary }))
  .catch((e) => post({ cmd: 'done', summary: 'Kontrol hatası: ' + e.message }));
