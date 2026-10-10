// Siteye özel küçük ayarlar. Çoğu site için hiçbir şey gerekmez.
// Yeni site eklerken sadece farklı davranan bir şey varsa buraya yaz.
//
//   locked:  link metni buna uyuyorsa bölüm henüz açılmamış sayılır,
//            bildirimi açıldığı zaman gelir.
//   sitemap: seri sayfası bölümleri JavaScript ile yüklüyorsa,
//            bölümleri sitenin sitemap'inden oku.

const DEFAULT_LOCKED = /sonra\s+açılır|kilitli|erken\s+erişim|premium|coin\s*ile/i;

const RULES = {
  'golgebahcesi.com': { locked: DEFAULT_LOCKED },
  'mangawt.com': { sitemap: ['/sitemap-chapters.xml'] },
};

export function siteRules(hostname) {
  const host = hostname.replace(/^www\./, '');
  return { locked: DEFAULT_LOCKED, sitemap: null, ...(RULES[host] || {}) };
}
