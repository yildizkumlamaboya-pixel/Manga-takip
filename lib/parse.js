// Seri sayfasından başlık, kapak ve bölüm listesini çıkaran genel okuyucu.
// Siteye özel kod yazmadan çalışır: seri adresiyle başlayan ve içinde
// numara geçen linkleri bölüm sayar. Yeni bir site çoğu zaman ek ayar
// gerektirmez; gerekirse lib/sites.js içine kural eklenir.

import { siteRules } from './sites.js';

const SKIP_SEGMENT = /(^|\/)(page|sayfa|feed|comments?|yorum(lar)?|amp|reviews?|incelemeler?)(\/|$)/i;

export function decodeEntities(s = '') {
  return s
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#0?39;|&apos;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&nbsp;/g, ' ')
    .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)));
}

function stripTags(s) {
  return decodeEntities(s.replace(/<[^>]*>/g, ' ')).replace(/\s+/g, ' ').trim();
}

function meta(html, prop) {
  const re1 = new RegExp(`<meta[^>]+(?:property|name)=["']${prop}["'][^>]*content=["']([^"']*)["']`, 'i');
  const re2 = new RegExp(`<meta[^>]+content=["']([^"']*)["'][^>]*(?:property|name)=["']${prop}["']`, 'i');
  const m = html.match(re1) || html.match(re2);
  return m ? decodeEntities(m[1]).trim() : null;
}

// "Büyü İmparatoru - Gölge Bahçesi" -> "Büyü İmparatoru"
export function cleanTitle(raw, siteName) {
  if (!raw) return null;
  let t = raw;
  if (siteName) t = t.replace(new RegExp(`\\s*[-–—|]\\s*${siteName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}.*$`, 'i'), '');
  t = t.replace(/\s*[-–—|]\s*(Türkçe\s+)?(Manga|Webtoon|Manhwa)\s+Oku.*$/i, '');
  t = t.replace(/\s*[-–—|]\s*$/, '');
  t = t.replace(/\s+(Türkçe\s+)?Oku\s*$/i, '');
  return t.trim() || raw.trim();
}

// URL'nin seri adresinden sonraki kısmından bölüm numarası çıkarır.
// "920-bolum-oku" -> 920, "bolum/chapter-134.1" -> 134.1,
// "889-1-bolum-oku" -> 889.1, "chapter/104.2" -> 104.2
export function numFromPath(rest) {
  const last = rest.split('/').filter(Boolean).pop() || '';
  const m = last.match(/(\d+)(?:[.\-_](\d+))?/);
  if (!m) return null;
  return Number(m[2] ? `${m[1]}.${m[2]}` : m[1]);
}

export function numFromText(text) {
  const m = text.match(/(?:bölüm|bolum|chapter|ch\.?|ep\.?)\s*(\d+(?:[.,]\d+)?)/i);
  return m ? Number(m[1].replace(',', '.')) : null;
}

// Sayfada seri adıyla etiketlenmiş resim (asıl gösterilen kapak).
export function imageWithAlt(html, title, base) {
  if (!title || title.length < 3) return null;
  const want = title.toLocaleLowerCase('tr');
  for (const m of html.matchAll(/<img\b[^>]*>/gi)) {
    const tag = m[0];
    const alt = decodeEntities((tag.match(/\balt\s*=\s*["']([^"']*)["']/i) || [])[1] || '').trim().toLocaleLowerCase('tr');
    if (!alt || !alt.startsWith(want)) continue;
    const src = (tag.match(/\b(?:data-src|src)\s*=\s*["']([^"']+)["']/i) || [])[1];
    if (!src || src.startsWith('data:')) continue;
    try { return new URL(decodeEntities(src), base).href; } catch {}
  }
  return null;
}

export function seriesPath(url) {
  const u = new URL(url);
  return u.pathname.replace(/\/+$/, '');
}

export function parseSeriesPage(html, pageUrl) {
  const base = new URL(pageUrl);
  const rules = siteRules(base.hostname);
  const prefix = seriesPath(pageUrl);
  const siteName = meta(html, 'og:site_name');

  // Sayfanın paylaşım bilgisi başka bir adrese aitse (ör. içerik JavaScript ile
  // yükleniyor ve ana sayfanın bilgileri duruyor) başlık ve kapağa güvenme.
  const ogUrl = meta(html, 'og:url') || (html.match(/<link[^>]+rel=["']canonical["'][^>]*href=["']([^"']+)["']/i) || [])[1];
  let generic = false;
  if (ogUrl) {
    try {
      const p = new URL(decodeEntities(ogUrl), base).pathname.replace(/\/+$/, '');
      generic = !p.startsWith(prefix);
    } catch {}
  }

  const title = generic ? null :
    cleanTitle(meta(html, 'og:title'), siteName) ||
    cleanTitle(stripTags((html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/i) || [])[1] || ''), siteName) ||
    cleanTitle(stripTags((html.match(/<title>([\s\S]*?)<\/title>/i) || [])[1] || ''), siteName);

  const cover = generic ? null : (imageWithAlt(html, title, base) || meta(html, 'og:image') || meta(html, 'twitter:image'));

  const byNum = new Map();
  const re = /<a\b[^>]*?href\s*=\s*["']([^"'#]+)[^"']*["'][^>]*>([\s\S]*?)<\/a>/gi;
  let m;
  while ((m = re.exec(html))) {
    let abs;
    try { abs = new URL(decodeEntities(m[1]), base); } catch { continue; }
    if (abs.hostname !== base.hostname) continue;
    if (abs.search) continue;
    const path = abs.pathname.replace(/\/+$/, '');
    if (!path.startsWith(prefix + '/')) continue;
    const rest = path.slice(prefix.length + 1);
    if (!rest || SKIP_SEGMENT.test(rest)) continue;

    const text = stripTags(m[2]);
    if (rules.locked && rules.locked.test(text)) continue;

    const num = numFromPath(rest) ?? numFromText(text);
    if (num == null || Number.isNaN(num)) continue;

    if (!byNum.has(num)) {
      byNum.set(num, { num, url: abs.href, label: text.slice(0, 120) || `Bölüm ${num}` });
    }
  }

  const chapters = [...byNum.values()].sort((a, b) => b.num - a.num);
  return { title, cover, chapters };
}

// Sitemap'ten bölüm linkleri (sayfası JavaScript ile yüklenen siteler için).
export function chaptersFromSitemap(xml, pageUrl) {
  const base = new URL(pageUrl);
  const prefix = seriesPath(pageUrl);
  const out = new Map();
  const re = /<loc>\s*([^<\s]+)\s*<\/loc>/gi;
  let m;
  while ((m = re.exec(xml))) {
    let u;
    try { u = new URL(decodeEntities(m[1])); } catch { continue; }
    if (u.hostname !== base.hostname) continue;
    const path = u.pathname.replace(/\/+$/, '');
    if (!path.startsWith(prefix + '/')) continue;
    const rest = path.slice(prefix.length + 1);
    if (!rest || SKIP_SEGMENT.test(rest)) continue;
    const num = numFromPath(rest);
    if (num == null) continue;
    if (!out.has(num)) out.set(num, { num, url: u.href, label: `Bölüm ${num}` });
  }
  return [...out.values()].sort((a, b) => b.num - a.num);
}

export function sitemapChildren(xml) {
  if (!/<sitemapindex/i.test(xml)) return [];
  const locs = [...xml.matchAll(/<loc>\s*([^<\s]+)\s*<\/loc>/gi)].map((x) => decodeEntities(x[1]));
  return locs;
}
