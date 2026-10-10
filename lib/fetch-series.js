// Bir seri adresini indirip bölümlerini döndürür.
import { parseSeriesPage, chaptersFromSitemap, sitemapChildren } from './parse.js';
import { siteRules } from './sites.js';

const UA =
  'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36';

export async function getText(url, timeoutMs = 15000) {
  const res = await fetch(url, {
    headers: { 'User-Agent': UA, 'Accept-Language': 'tr-TR,tr;q=0.9,en;q=0.6', Accept: 'text/html,application/xml;q=0.9,*/*;q=0.8' },
    redirect: 'follow',
    signal: AbortSignal.timeout(timeoutMs),
  });
  const body = await res.text();
  if (!res.ok) {
    const cf = res.headers.get('server')?.includes('cloudflare') || /cf-chl|challenge-platform/i.test(body);
    throw new Error(cf ? `Site bot korumasına takıldı (${res.status})` : `Site ${res.status} döndü`);
  }
  return body;
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
      if (!sitemapCache.has(sm)) sitemapCache.set(sm, getText(sm, 20000));
      const xml = await sitemapCache.get(sm);
      const children = sitemapChildren(xml).filter((c) => /chapter|bolum|bölüm/i.test(c));
      for (const c of children) {
        if (!sitemapCache.has(c)) sitemapCache.set(c, getText(c, 20000));
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

export async function fetchSeries(url) {
  const html = await getText(url);
  const data = parseSeriesPage(html, url);
  const rules = siteRules(new URL(url).hostname);
  if (data.chapters.length === 0 || rules.sitemap) {
    const fromSitemap = await sitemapChapters(url, rules.sitemap);
    if (fromSitemap.length) data.chapters = fromSitemap;
  }
  if (data.chapters.length === 0) {
    throw new Error('Bu sayfada bölüm bulunamadı. Seri ana sayfasının linkini yapıştırdığından emin ol.');
  }
  return data;
}
