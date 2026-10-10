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

// Tam sayı bölümler arasındaki boşlukları doldurur. Her boşluk, hemen
// altındaki bölümün link kalıbıyla doldurulur (site zamanla link biçimini
// değiştirmiş olabilir: "bolum-292/" -> "bolum-315-0/").
export function fillGaps(chapters, maxFill = 3000) {
  const ints = chapters.filter((c) => Number.isInteger(c.num)).sort((a, b) => a.num - b.num);
  if (ints.length < 2) return chapters;

  // En az iki bölüm aynı kalıbı paylaşmıyorsa linkler tahmin edilemez
  const counts = new Map();
  const tpl = new Map();
  for (const c of ints) {
    const t = templateOf(c);
    tpl.set(c.num, t);
    if (t) counts.set(t, (counts.get(t) || 0) + 1);
  }
  if (![...counts.values()].some((n) => n >= 2)) return chapters;

  const have = new Set(chapters.map((c) => c.num));
  const added = [];
  for (let i = 0; i < ints.length - 1 && added.length < maxFill; i++) {
    const lo = ints[i];
    const hi = ints[i + 1];
    const t = tpl.get(lo.num) || tpl.get(hi.num);
    if (!t || (counts.get(t) || 0) < 2 && tpl.get(lo.num) !== tpl.get(hi.num)) continue;
    for (let n = lo.num + 1; n < hi.num && added.length < maxFill; n++) {
      if (!have.has(n)) added.push({ num: n, url: t.replace('{n}', String(n)) });
    }
  }
  if (!added.length) return chapters;
  return [...chapters, ...added].sort((a, b) => b.num - a.num);
}
