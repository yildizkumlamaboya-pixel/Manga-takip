// Gerçek sitelerde gözlenen link yapılarını taklit eden küçük örneklerle test.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseSeriesPage, chaptersFromSitemap, sitemapChildren, numFromPath, cleanTitle } from '../lib/parse.js';

const page = (ogTitle, site, links, extra = '') => `<!doctype html><html><head>
<meta property="og:title" content="${ogTitle}"><meta property="og:site_name" content="${site}">
<meta property="og:image" content="https://cdn.example/cover.webp"></head><body>
<a href="/">Ana Sayfa</a>${extra}${links}</body></html>`;

test('uzaymanga: /manga/seri/920-bolum-oku ve ara bölümler', () => {
  const html = page('Seri Adı - Türkçe Manga Oku |', '',
    `<a href="https://uzaymanga.com/manga/seri-adi/2-bolum-oku">İlk Bölüm Bölüm 2</a>
     <a href="https://uzaymanga.com/manga/seri-adi/920-bolum-oku">En Son Bölüm Bölüm 920</a>
     <a href="https://uzaymanga.com/manga/seri-adi/920-bolum-oku">920 Seri Adı 920. Bölüm 10.10.2026</a>
     <a href="https://uzaymanga.com/manga/seri-adi/889-1-bolum-oku">889.1</a>
     <a href="https://uzaymanga.com/manga/seri-adi/866-001-fanart-bolum-oku">866.001 FanArt</a>
     <a href="https://uzaymanga.com/manga?category?=buyu">Büyü</a>`);
  const r = parseSeriesPage(html, 'https://uzaymanga.com/manga/seri-adi');
  assert.equal(r.title, 'Seri Adı');
  assert.equal(r.chapters[0].num, 920);
  assert.equal(r.chapters[0].url, 'https://uzaymanga.com/manga/seri-adi/920-bolum-oku');
  assert.deepEqual(r.chapters.map((c) => c.num), [920, 889.1, 866.001, 2]);
});

test('golgebahcesi: bolum/chapter-N ve kilitli bölümler atlanır', () => {
  const html = page('Seri - Gölge Bahçesi', 'Gölge Bahçesi',
    `<a href="https://golgebahcesi.com/manga/seri/bolum/chapter-66">Bölüm 66 - Final6g 15s sonra açılır</a>
     <a href="https://golgebahcesi.com/manga/seri/bolum/chapter-65">Bölüm 65 17 saat önce</a>
     <a href="https://golgebahcesi.com/manga/seri/bolum/chapter-134.1">Bölüm 134.1</a>
     <a href="https://golgebahcesi.com/manga/baska-seri">Benzer</a>`);
  const r = parseSeriesPage(html, 'https://golgebahcesi.com/manga/seri');
  assert.equal(r.title, 'Seri');
  assert.deepEqual(r.chapters.map((c) => c.num), [134.1, 65]);
});

test('ragnarscans: sondaki / ve sayfalama linkleri karışmaz', () => {
  const html = page('Seri - Magic Emperor - Ragnar Scans', 'Ragnar Scans',
    `<a href="https://ragnarscans.net/manga/seri/bolum-1/">From Start</a>
     <a href="https://ragnarscans.net/manga/seri/bolum-915/">Seri – Bölüm 915 1 hafta</a>
     <a href="https://ragnarscans.net/manga/seri/bolum-914/">Seri – Bölüm 914</a>
     <a href="https://ragnarscans.net/manga/seri/bolum/page/39/#chapter-list">39</a>
     <a href="https://ragnarscans.net/manga/seri/?rev=1#chapter-list">rev</a>`);
  const r = parseSeriesPage(html, 'https://ragnarscans.net/manga/seri/');
  assert.equal(r.title, 'Seri - Magic Emperor');
  assert.deepEqual(r.chapters.map((c) => c.num), [915, 914, 1]);
});

test('okutoon: kök dizinde seri, /seri/bolum-N', () => {
  const html = page('Magic Emperor — OkuToon', 'OkuToon',
    `<a href="https://okutoon.com/magic-emperor/bolum-919">919 Bölüm 919 10 Ekim 2026</a>
     <a href="https://okutoon.com/magic-emperor/bolum-918">918 Bölüm 918</a>
     <a href="https://okutoon.com/magic-emperor/bolum-0">0</a>
     <a href="https://okutoon.com/tur/aksiyon">Aksiyon</a>`);
  const r = parseSeriesPage(html, 'https://okutoon.com/magic-emperor');
  assert.equal(r.title, 'Magic Emperor');
  assert.equal(r.cover, 'https://cdn.example/cover.webp');
  assert.deepEqual(r.chapters.map((c) => c.num), [919, 918, 0]);
});

test('mangawt: sitemap index ve bölüm sitemap\'i', () => {
  const idx = `<sitemapindex><sitemap><loc>https://mangawt.com/sitemap-mangas.xml</loc></sitemap>
    <sitemap><loc>https://mangawt.com/sitemap-chapters.xml</loc></sitemap></sitemapindex>`;
  assert.equal(sitemapChildren(idx).length, 2);
  const xml = `<urlset>
    <url><loc>https://mangawt.com/manga/cehennem-modu/chapter/104.2</loc></url>
    <url><loc>https://mangawt.com/manga/cehennem-modu/chapter/104</loc></url>
    <url><loc>https://mangawt.com/manga/cehennem-modu-2/chapter/500</loc></url>
    <url><loc>https://mangawt.com/manga/bir-dagda-baslamak/chapter/852</loc></url></urlset>`;
  const ch = chaptersFromSitemap(xml, 'https://mangawt.com/manga/cehennem-modu');
  assert.deepEqual(ch.map((c) => c.num), [104.2, 104]);
});

test('yardımcılar', () => {
  assert.equal(numFromPath('bolum/chapter-134.1'), 134.1);
  assert.equal(numFromPath('920-bolum-oku'), 920);
  assert.equal(numFromPath('chapter/abc'), null);
  assert.equal(cleanTitle('Büyü İmparatoru - Gölge Bahçesi', 'Gölge Bahçesi'), 'Büyü İmparatoru');
});

import { normalizeUrl } from '../lib/url.js';
test('bölüm linki yapıştırılınca seri linkine çevrilir', () => {
  assert.equal(normalizeUrl('https://uzaymanga.com/manga/efsanevi-buyu-imparatoru/920-bolum-oku'), 'https://uzaymanga.com/manga/efsanevi-buyu-imparatoru');
  assert.equal(normalizeUrl('https://uzaymanga.com/manga/x/889-1-bolum-oku'), 'https://uzaymanga.com/manga/x');
  assert.equal(normalizeUrl('https://golgebahcesi.com/manga/buyu-imparatoru/bolum/chapter-920'), 'https://golgebahcesi.com/manga/buyu-imparatoru');
  assert.equal(normalizeUrl('https://ragnarscans.net/manga/buyu-imparatoru/bolum-915/'), 'https://ragnarscans.net/manga/buyu-imparatoru');
  assert.equal(normalizeUrl('https://okutoon.com/magic-emperor/bolum-919'), 'https://okutoon.com/magic-emperor');
  assert.equal(normalizeUrl('https://mangawt.com/manga/cehennem-modu/chapter/104.2'), 'https://mangawt.com/manga/cehennem-modu');
  assert.equal(normalizeUrl('https://ragnarscans.net/manga/buyu-imparatoru/'), 'https://ragnarscans.net/manga/buyu-imparatoru');
  assert.throws(() => normalizeUrl('merhaba'));
});

import { mergeChapters, fillGaps } from '../lib/chapters.js';
test('sayfada olmayan ara bölümler kalıptan tamamlanır', () => {
  const base = 'https://ornek.site/manga/seri/bolum-';
  const page = [315, 300, 299, 292, 1].map((n) => ({ num: n, url: `${base}${n}/` }));
  const full = fillGaps(page);
  assert.equal(full.length, 315);
  assert.equal(full.find((c) => c.num === 115).url, `${base}115/`);
  // kesirli bölümler kalıba karışmaz, boşluk doldurmayı bozmaz
  const uzay = [{ num: 921, url: 'https://u.com/manga/s/921-bolum-oku' }, { num: 889.1, url: 'https://u.com/manga/s/889-1-bolum-oku' }, { num: 880, url: 'https://u.com/manga/s/880-bolum-oku' }];
  const f2 = fillGaps(uzay);
  assert.equal(f2.find((c) => c.num === 900).url, 'https://u.com/manga/s/900-bolum-oku');
  assert.ok(f2.some((c) => c.num === 889.1));
  // kalıp yoksa dokunmaz
  const odd = [{ num: 5, url: 'https://x.com/s/abc' }, { num: 1, url: 'https://x.com/s/def' }];
  assert.equal(fillGaps(odd).length, 2);
});
test('eski bölümler unutulmaz', () => {
  const m = mergeChapters([{ num: 316, url: 'u316' }, { num: 315, url: 'u315-yeni' }], [[315, 'u315'], [200, 'u200']]);
  assert.deepEqual(m.map((c) => [c.num, c.url]), [[316, 'u316'], [315, 'u315-yeni'], [200, 'u200']]);
});
