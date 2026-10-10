// GitHub Actions her 30 dakikada bunu çalıştırır.
//   node scripts/check.mjs <veri-klasörü>
// list.json'daki serileri kontrol eder, state.json'u günceller,
// yeni bölüm varsa subs.json'daki cihazlara bildirim yollar.
import { readFile, writeFile, appendFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fetchSeries, clearCache } from '../lib/fetch-series.js';

const dir = process.argv[2] || 'store';
const read = async (f, def) => {
  try { return JSON.parse(await readFile(join(dir, f), 'utf8')); } catch { return def; }
};
const write = (f, data) => writeFile(join(dir, f), JSON.stringify(data, null, 1) + '\n');

const list = await read('list.json', []);
const oldState = await read('state.json', {});
let subs = await read('subs.json', []);

async function pool(items, limit, fn) {
  let i = 0;
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (i < items.length) await fn(items[i++]);
  }));
}

clearCache();
const state = {};
const news = [];
const errors = [];

await pool(list, 4, async (item) => {
  const prev = oldState[item.url];
  try {
    const d = await fetchSeries(item.url);
    const top = d.chapters[0];
    state[item.url] = {
      title: d.title || prev?.title || item.url,
      cover: d.cover || prev?.cover || null,
      latest: top.num,
      first: prev?.first ?? top.num,          // eklendiği andaki son bölüm
      chapters: d.chapters.map((c) => [c.num, c.url]),
      error: null,
      errorSince: null,
      via: d.via,
    };
    if (prev && prev.latest != null) {
      const fresh = d.chapters.filter((c) => c.num > prev.latest);
      if (fresh.length) news.push({ url: item.url, title: state[item.url].title, fresh });
    }
  } catch (e) {
    errors.push({ url: item.url, error: e.message });
    state[item.url] = prev
      ? { ...prev, error: e.message, errorSince: prev.errorSince || new Date().toISOString() }
      : { title: item.url, cover: null, latest: null, first: null, chapters: [], error: e.message, errorSince: new Date().toISOString() };
  }
});

// Sadece gerçekten bir şey değiştiyse dosyayı yaz (gereksiz commit olmasın)
if (JSON.stringify(state) !== JSON.stringify(oldState)) await write('state.json', state);

// Bildirimler
let sent = 0;
if (news.length && subs.length && process.env.VAPID_PRIVATE_KEY) {
  const { default: webpush } = await import('web-push');
  webpush.setVapidDetails(
    process.env.VAPID_SUBJECT || 'mailto:manga-takip@example.com',
    process.env.VAPID_PUBLIC_KEY,
    process.env.VAPID_PRIVATE_KEY,
  );
  const dead = new Set();
  for (const n of news) {
    const newest = n.fresh[0];
    const oldest = n.fresh[n.fresh.length - 1];
    const body = n.fresh.length === 1
      ? `Bölüm ${newest.num} çıktı`
      : `${n.fresh.length} yeni bölüm (${oldest.num}–${newest.num})`;
    const payload = JSON.stringify({ title: n.title, body, url: oldest.url, tag: n.url });
    for (const s of subs) {
      try {
        await webpush.sendNotification(s, payload, { TTL: 86400 });
        sent++;
      } catch (e) {
        if (e.statusCode === 404 || e.statusCode === 410) dead.add(s.endpoint);
        else console.error('Bildirim hatası:', e.statusCode, e.body);
      }
    }
  }
  if (dead.size) {
    subs = subs.filter((s) => !dead.has(s.endpoint));
    await write('subs.json', subs);
  }
}

// Özet (Actions sayfasında görünür)
const lines = [
  `### Manga kontrolü`,
  `- Kontrol edilen: ${list.length} seri`,
  `- Yeni bölüm: ${news.map((n) => `${n.title} (${n.fresh.length})`).join(', ') || 'yok'}`,
  `- Gönderilen bildirim: ${sent}`,
  ...Object.entries(state).filter(([, v]) => v.via && v.via !== 'direct').map(([u, v]) => `- ℹ️ ${u}: ${v.via} yoluyla okundu`),
  ...errors.map((e) => `- ⚠️ ${e.url}: ${e.error}`),
];
console.log(lines.join('\n'));
if (process.env.GITHUB_STEP_SUMMARY) await appendFile(process.env.GITHUB_STEP_SUMMARY, lines.join('\n') + '\n');
