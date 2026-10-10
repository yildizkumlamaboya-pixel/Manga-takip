// Yeni bir siteyi eklemeden önce okunabiliyor mu diye dene:
//   npm run try -- https://site.com/manga/seri-adi
import { fetchSeries } from '../lib/fetch-series.js';
import { normalizeUrl } from '../lib/url.js';

const url = normalizeUrl(process.argv[2]);
try {
  const r = await fetchSeries(url);
  console.log('Seri   :', r.title);
  console.log('Kapak  :', r.cover);
  console.log('Bölüm  :', r.chapters.length, 'adet');
  for (const c of r.chapters.slice(0, 5)) console.log('  ', c.num, c.url);
} catch (e) {
  console.error('Okunamadı:', e.message);
  process.exit(1);
}
