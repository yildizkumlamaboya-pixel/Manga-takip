// Bir seri adresini indirip bölümlerini döndürür.
// Bazı siteler GitHub gibi sunuculardan gelen istekleri engelliyor. Bu yüzden
// sırayla üç yol denenir:
//   1) normal istek
//   2) tam tarayıcı başlıklarıyla istek
//   3) Jina Reader üzerinden (sayfayı kendi sunucularından alıp HTML olarak verir)
import { parseSeriesPage, chaptersFromSitemap, sitemapChildren } from './parse.js';
import { siteRules } from './sites.js';

const UA =
  'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36';

const BASIC = {
  'User-Agent': UA,
  'Accept-Language': 'tr-TR,tr;q=0.9,en;q=0.6',
  Accept: 'text/html,application/xml;q=0.9,*/*;q=0.8',
};

const BROWSER = {
  ...BASIC,
  Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
  'Accept-Encoding': 'gzip, deflate, br',
  'Cache-Control': 'no-cache',
  Pragma: 'no-cache',
  'Sec-Ch-Ua': '"Chromium";v="129", "Google Chrome";v="129", "Not=A?Brand";v="8"',
  'Sec-Ch-Ua-Mobile': '?1',
  'Sec-Ch-Ua-Platform': '"Android"',
  'Sec-Fetch-Dest': 'document',
  'Sec-Fetch-Mode': 'navigate',
  'Sec-Fetch-Site': 'none',
  'Sec-Fetch-User': '?1',
  'Upgrade-Insecure-Requests': '1',
};

class FetchError extends Error {}

function isChallenge(body) {
  return /<title>\s*(Just a moment|Attention Required|Bir dakika)/i.test(body) || /cf_chl_opt|cf-chl-widget/i.test(body);
}

function looksBlocked(res, body) {
  return isChallenge(body) || res.status === 403 || res.status === 429 || res.status === 503;
}

async function attempt(url, headers, timeoutMs) {
  const res = await fetch(url, { headers, redirect: 'follow', signal: AbortSignal.timeout(timeoutMs) });
  const body = await res.text();
  // Normal sayfalarda da Cloudflare betiği (challenge-platform) bulunabilir;
  // gerçek engel sayfası "Just a moment" başlığı ya da cf_chl_opt içerir.
  if (res.ok && !isChallenge(body)) return body;
  const e = new FetchError(looksBlocked(res, body) ? `Site bot korumasına takıldı (${res.status})` : `Site ${res.status} döndü`);
  e.blocked = looksBlocked(res, body);
  throw e;
}

// Jina'nın düz metin (markdown) çıktısını okuyucunun anladığı basit HTML'e çevirir.
export function markdownToHtml(md) {
  const title = (md.match(/^Title:\s*(.+)$/m) || [])[1] || '';
  const esc = (t) => t.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
  const links = [...md.matchAll(/\[([^\]]*)\]\((https?:\/\/[^)\s]+)(?:\s+"[^"]*")?\)/g)]
    .map((m) => `<a href="${esc(m[2])}">${esc(m[1])}</a>`)
    .join('\n');
  return `<html><head><meta property="og:title" content="${esc(title)}"></head><body>${links}</body></html>`;
}

async function jina(url, format, timeoutMs) {
  const headers = { 'X-Return-Format': format, 'X-No-Cache': 'true' };
  if (process.env.JINA_API_KEY) headers.Authorization = `Bearer ${process.env.JINA_API_KEY}`;
  const res = await fetch('https://r.jina.ai/' + url, { headers, signal: AbortSignal.timeout(timeoutMs) });
  const body = await res.text();
  if (!res.ok) throw new FetchError(`Okuma servisi ${res.status} döndü`);
  if (isChallenge(body)) throw new FetchError('Okuma servisi de bot korumasına takıldı');
  return body;
}

// Jina'dan iki biçimde dener: önce HTML, bölüm çıkmazsa markdown.
async function viaJina(url, timeoutMs) {
  const html = await jina(url, 'html', timeoutMs);
  if (parseSeriesPage(html, url).chapters.length) return html;
  const md = await jina(url, 'markdown', timeoutMs);
  const conv = markdownToHtml(md);
  if (parseSeriesPage(conv, url).chapters.length) return conv;
  const head = (md.match(/^Title:\s*(.+)$/m) || [])[1] || md.replace(/\s+/g, ' ').slice(0, 80);
  throw new FetchError(`Okuma servisi sayfayı aldı ama bölüm yok. Gelen sayfa: "${head.slice(0, 80)}"`);
}

// Sayfayı alır; hangi yolla alındığını da döndürür.
export async function getPage(url, timeoutMs = 20000) {
  let first;
  try {
    return { body: await attempt(url, BASIC, timeoutMs), via: 'direct' };
  } catch (e) {
    first = e;
    if (!e.blocked && !(e.name === 'TimeoutError' || e.cause)) throw e;
  }
  try {
    return { body: await attempt(url, BROWSER, timeoutMs), via: 'browser' };
  } catch {}
  try {
    return { body: await viaJina(url, Math.max(timeoutMs, 30000)), via: 'jina' };
  } catch (e) {
    throw new FetchError(`${first.message}; ${e.message}`);
  }
}

export async function getText(url, timeoutMs) {
  return (await getPage(url, timeoutMs)).body;
}

// Aynı kontrol turunda aynı sitemap'i tekrar indirmemek için.
const sitemapCache = new Map();

async function sitemapChapters(pageUrl, paths) {
  const origin = new URL(pageUrl).origin;
  const tried = [];
  const urls = [...(paths || []).map((p) => origin + p), origin + '/sitemap.xml'];
  for (const sm of urls) {
    if (tried.includes(sm)) continue;
    tried.push(sm);
    try {
      if (!sitemapCache.has(sm)) sitemapCache.set(sm, attempt(sm, BASIC, 20000));
      const xml = await sitemapCache.get(sm);
      const all = sitemapChildren(xml);
      const named = all.filter((c) => /chapter|bolum|bölüm/i.test(c));
      // Adında bölüm geçen alt dosya yoksa (ör. manga1.xml…) hepsine bak
      const children = (named.length ? named : all.filter((c) => !/categor|kategori|tag|etiket|page|sayfa|post|author/i.test(c))).slice(0, 25);
      for (const c of children) {
        if (!sitemapCache.has(c)) sitemapCache.set(c, attempt(c, BASIC, 20000));
        const found = chaptersFromSitemap(await sitemapCache.get(c), pageUrl);
        if (found.length) return found;
      }
      const found = chaptersFromSitemap(xml, pageUrl);
      if (found.length) return found;
    } catch {
      /* sıradaki sitemap'i dene */
    }
  }
  return [];
}

export function clearCache() {
  sitemapCache.clear();
}

function titleFromSlug(url) {
  const slug = new URL(url).pathname.replace(/\/+$/, '').split('/').pop() || url;
  return slug.split('-').map((w) => w.charAt(0).toLocaleUpperCase('tr') + w.slice(1)).join(' ');
}

export async function fetchSeries(url) {
  const rules = siteRules(new URL(url).hostname);
  let page;
  try {
    page = await getPage(url);
  } catch (e) {
    // Seri sayfası engellendiyse bölümleri sitemap'ten okumayı dene
    const fromSitemap = await sitemapChapters(url, rules.sitemap);
    if (fromSitemap.length) return { title: titleFromSlug(url), cover: null, chapters: fromSitemap, via: 'sitemap' };
    throw e;
  }
  const data = parseSeriesPage(page.body, url);
  data.via = page.via;
  if (data.chapters.length === 0 || rules.sitemap) {
    const fromSitemap = await sitemapChapters(url, rules.sitemap);
    if (fromSitemap.length) data.chapters = fromSitemap;
  }
  if (data.chapters.length === 0) {
    throw new Error(
      page.via === 'jina'
        ? 'Sayfa okundu ama bölüm bulunamadı (okuma servisi üzerinden).'
        : 'Bu sayfada bölüm bulunamadı. Seri ana sayfasının linkini yapıştırdığından emin ol.',
    );
  }
  return data;
}
