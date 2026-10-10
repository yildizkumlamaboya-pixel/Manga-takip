// Bölüm listesini düzenleyen yardımcılar.

// Eski ve yeni listeyi birleştirir (yeni olan önceliklidir). Bazı siteler
// sayfada sadece son bölümleri gösterir; daha önce görülenler kaybolmasın.
export function mergeChapters(fresh, previous = []) {
  const byNum = new Map();
  for (const [num, url] of previous) byNum.set(num, url);
  for (const c of fresh) byNum.set(c.num, c.url);
  return [...byNum.entries()].map(([num, url]) => ({ num, url })).sort((a, b) => b.num - a.num);
}

// Linkteki bölüm numarasının yerini bulup kalıp çıkarır:
// ".../bolum-292/" -> ".../bolum-{n}/"
function templateOf(c) {
  if (!Number.isInteger(c.num)) return null;
  const u = new URL(c.url);
  const n = String(c.num);
  const re = new RegExp(`(^|[^0-9])${n}(?![0-9])`, 'g');
  let last = -1;
  let m;
  while ((m = re.exec(u.pathname))) last = m.index + m[1].length;
  if (last < 0) return null;
  return u.origin + u.pathname.slice(0, last) + '{n}' + u.pathname.slice(last + n.length);
}

// Tam sayı bölümler arasındaki boşlukları, linkler ortak bir kalıba
// uyuyorsa doldurur. Site 1. bölümü ve son 25 bölümü gösterip arayı
// atladığında "sıradaki bölüm" doğru çıksın diye.
export function fillGaps(chapters, maxFill = 3000) {
  const ints = chapters.filter((c) => Number.isInteger(c.num));
  if (ints.length < 2) return chapters;

  const counts = new Map();
  for (const c of ints) {
    const t = templateOf(c);
    if (t) counts.set(t, (counts.get(t) || 0) + 1);
  }
  let best = null;
  let bestCount = 0;
  for (const [t, n] of counts) if (n > bestCount) { best = t; bestCount = n; }
  if (!best || bestCount < 2 || bestCount < ints.length * 0.8) return chapters;

  const have = new Set(chapters.map((c) => c.num));
  const nums = ints.map((c) => c.num);
  const lo = Math.min(...nums);
  const hi = Math.max(...nums);
  const added = [];
  for (let n = lo + 1; n < hi && added.length < maxFill; n++) {
    if (!have.has(n)) added.push({ num: n, url: best.replace('{n}', String(n)) });
  }
  if (!added.length) return chapters;
  return [...chapters, ...added].sort((a, b) => b.num - a.num);
}
