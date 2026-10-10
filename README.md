# Manga Takip

Farklı sitelerden okuduğun mangaları tek listede toplar. Her 30 dakikada bir seri sayfalarını kontrol eder, yeni bölüm gelince telefonuna bildirim yollar. Bildirime dokununca bölüm kendi sitesinde açılır.

**Desteklenen siteler:** uzaymanga.com, golgebahcesi.com, mangawt.com, ragnarscans.net, okutoon.com. Okuyucu siteye özel değil, link yapısına bakar. Yeni sitelerin çoğu ek kod gerekmeden çalışır.

## Nasıl çalışır

Ayrı bir sunucu yok, her şey GitHub'da:

| Parça | Nerede |
|---|---|
| Telefondaki uygulama | GitHub Pages (`public/`) |
| 30 dakikada bir kontrol + bildirim | GitHub Actions (`.github/workflows/check.yml`, `scripts/check.mjs`) |
| Veriler | `data` dalında üç JSON dosyası |

`data` dalındaki dosyalar:
- `list.json`: takip ettiğin seriler ve kaldığın bölüm. Uygulama yazar.
- `state.json`: başlık, kapak ve bölüm listesi. Kontrol işi yazar.
- `subs.json`: bildirim alacak cihazlar. Uygulama yazar.

Uygulama GitHub'a, telefonda saklanan bir **erişim anahtarı** ile okuyup yazar.

## Kurulum

1. **Repo'yu herkese açık yap.** Settings → General → Danger Zone → Change visibility → Public. Ücretsiz hesapta GitHub Pages sadece açık repolarda çalışır, Actions dakikaları da açık repolarda sınırsızdır.
2. **Pages'i aç.** Settings → Pages → Source: **GitHub Actions**. Adres `https://yildizkumlamaboya-pixel.github.io/Manga-takip/` olur.
3. **Bildirim gizli anahtarı.** Settings → Secrets and variables → Actions → **New repository secret**: ad `VAPID_PRIVATE_KEY`, değer sana ayrıca verilen anahtar. (Açık anahtar zaten `public/config.js` içinde.)
4. **Telefonda.** Android'de Chrome ile Pages adresini aç → menü (⋮) → **Ana ekrana ekle**. Uygulamayı aç, Ayarlar'daki adımlarla bir GitHub erişim anahtarı oluşturup yapıştır, sonra bildirimleri aç.

## Kullanım
- **+** ile seri linkini yapıştır (bölüm linki de olur). Bölümler yaklaşık 1 dakika içinde gelir.
- Turuncu sayı okunmamış bölüm sayısıdır, ✓ güncelsin demektir.
- Seriye dokununca bölüm listesi açılır. Bir bölüme dokununca sitesinde açılır ve okundu sayılır.
- Üstteki ↻ butonu kontrolü hemen başlatır.

## Yeni site eklemek
Çoğu zaman linki yapıştırmak yeterli. Bilgisayarda denemek için:
```
npm run try -- https://yenisite.com/manga/seri-adi
```
Bölüm bulunamazsa `lib/sites.js` içine sitemap yolu (`sitemap`) ya da kilitli bölüm ifadesi (`locked`) eklenir.

## Notlar
- Uygulama bölümleri kopyalamaz, sadece numaralarını ve linklerini tutar. Okuma her zaman kaynağın kendi sitesinde olur.
- Repo açık olduğu için takip listen (`data` dalı) herkese görünür.
- GitHub'ın zamanlanmış işleri yoğunlukta birkaç dakika gecikebilir.
- Bir site bot korumasıyla GitHub sunucularını engellerse o seri için kontrol çalışmaz, kartında hata yazar.
