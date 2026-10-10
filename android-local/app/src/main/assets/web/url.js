// Kullanıcının yapıştırdığı linki seri ana sayfası linkine çevirir.
// Bölüm linki yapıştırılsa bile seriyi bulur.
export function normalizeUrl(raw) {
  let u;
  try { u = new URL(String(raw || '').trim()); } catch { throw new Error('Geçerli bir link değil'); }
  if (!/^https?:$/.test(u.protocol)) throw new Error('Link http(s) ile başlamalı');
  u.hash = '';
  u.search = '';
  u.pathname = u.pathname
    .replace(/\/(bolum|chapter)\/[^/]+\/?$/i, '')          // /bolum/chapter-66, /chapter/104.2
    .replace(/\/(bolum|chapter)[-_][^/]+\/?$/i, '')        // /bolum-915/, /bolum-22
    .replace(/\/\d+(?:-\d+)*-[^/]*bolum[^/]*\/?$/i, '')    // /920-bolum-oku
    .replace(/\/+$/, '');
  return u.href;
}
