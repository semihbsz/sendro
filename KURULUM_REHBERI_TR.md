# Sendro — Sıfırdan Kurulum Rehberi (Türkçe)

Bu rehber seni en baştan alır: Windows'a kurulumdan, iPhone'una uygulamayı
yükleyip ilk dosyayı göndermene kadar her adım burada. Hiçbir adımda Mac
gerekmez.

Toplam süre tahmini: ilk kurulum ~1,5–2 saat (çoğu bekleme), sonraki
kullanımlar anlık. 7 günde bir 2 dakikalık yeniden imzalama gerekir
(bkz. Bölüm 8 — bu Apple'ın ücretsiz hesap kısıtı, Sendro'nun değil).

---

## BÖLÜM 1 — Windows'a geliştirme araçlarını kur (bir kerelik)

Sendro'nun Windows uygulamasını kendi bilgisayarında derleyeceksin.
Şunları sırayla kur:

1. **Rust** — https://rustup.rs adresine git, `rustup-init.exe` indir,
   çalıştır, varsayılan seçeneklerle (Enter'a basarak) kur.
2. **Node.js LTS** — https://nodejs.org adresinden LTS sürümünü indir, kur.
3. **Visual Studio Build Tools** —
   https://visualstudio.microsoft.com/visual-cpp-build-tools/ adresinden
   indir. Kurulumda **"Desktop development with C++"** iş yükünü işaretle.
   (Rust'ın Windows'ta derleme yapabilmesi için şart.)
4. **Git** — https://git-scm.com/download/win adresinden indir, varsayılan
   ayarlarla kur.
5. **WebView2** — Windows 11'de zaten yüklü. Windows 10 kullanıyorsan
   https://developer.microsoft.com/microsoft-edge/webview2/ adresinden
   "Evergreen Bootstrapper" indir ve kur.

Kurulumlar bitince **yeni bir PowerShell penceresi** aç ve doğrula:

```powershell
rustc --version
node --version
git --version
```

Üçü de sürüm numarası basıyorsa hazırsın.

## BÖLÜM 2 — Sendro'yu bilgisayarına aç ve çalıştır

1. Sana gönderdiğim `sendro.zip` dosyasını indir ve bir klasöre çıkar,
   örneğin: `C:\Projeler\sendro`
2. PowerShell'de:

```powershell
cd C:\Projeler\sendro\desktop
npm install
npm run tauri dev
```

3. **İlk derleme uzun sürer (10–25 dakika)** — Rust yüzlerce paketi bir
   kez derler, sonraki açılışlar saniyeler sürer. Bekle.
4. Sendro penceresi açıldığında **Windows Güvenlik Duvarı** izin soracak:
   **"Özel ağlar" (Private networks) kutusunu işaretleyip İzin Ver** de.
   Bu adım kritik — izin vermezsen iPhone bilgisayarı göremez.
5. Sendro'nun Ayarlar (Settings) sayfasında yerel IP adresini ve portu
   göreceksin (örn. `192.168.1.34:48800`). Bunu aklında tut, ileride
   lazım olabilir.

İstersen kalıcı kurulum dosyası da üretebilirsin (isteğe bağlı):
`npm run tauri build` → `desktop\src-tauri\target\release\bundle\`
klasöründe `.msi` kurulum dosyası oluşur.

## BÖLÜM 3 — Projeyi GitHub'a yükle

iPhone uygulaması Apple araçlarıyla derlenmek zorunda ve bu araçlar sadece
Mac'te çalışır. Mac'in yok — sorun değil: GitHub'ın bulutundaki Mac'leri
bedava kullanacağız.

1. https://github.com adresinde hesap aç (yoksa).
2. Sağ üstten **New repository** → isim: `sendro` →
   **Public** seç → **Create repository**.
   - Neden Public? GitHub Actions, public depolarda **tamamen ücretsiz ve
     sınırsız**. Private depoda da çalışır ama macOS dakikaları 10 kat
     sayılır ve aylık ücretsiz limiti hızlı tüketir. Kodunda gizli bir şey
     yok (şifre/token içermiyor), Public güvenle kullanılabilir.
3. PowerShell'de proje köküne dön ve yükle:

```powershell
cd C:\Projeler\sendro
git init
git add .
git commit -m "Sendro v1"
git branch -M main
git remote add origin https://github.com/KULLANICI_ADIN/sendro.git
git push -u origin main
```

(`KULLANICI_ADIN` yerine kendi GitHub kullanıcı adını yaz. İlk push'ta
GitHub tarayıcı üzerinden giriş yapmanı isteyecek.)

## BÖLÜM 4 — GitHub'da iPhone uygulamasını derlet

1. Tarayıcıda deposuna git → üstteki **Actions** sekmesi.
2. Push yaptığın anda **"iOS Build"** işi otomatik başlamış olmalı.
   Başlamadıysa: soldan **iOS Build** → sağda **Run workflow** → **Run**.
3. İş yeşil tik alana kadar bekle (~5–10 dakika).
4. Biten işin sayfasına tıkla → en altta **Artifacts** bölümünde
   **`Sendro-unsigned.ipa`** göreceksin → indir.
5. İndirilen dosya bir `.zip`'tir — **içinden `Sendro-unsigned.ipa`
   dosyasını çıkar.** (GitHub artifact'ları hep zip'ler.)

## BÖLÜM 5 — iPhone'a yükle (Sideloadly ile, Windows'tan)

1. **iTunes kur** — https://www.apple.com/itunes/ üzerinden (Microsoft
   Store sürümü yerine Apple'ın sitesindeki klasik kurulum tercih edilir;
   Sideloadly'nin iPhone'la konuşması için Apple sürücüleri gerekiyor).
2. **Sideloadly kur** — https://sideloadly.io adresinden Windows sürümünü
   indir ve kur.
3. iPhone'unu **USB kablosuyla** bilgisayara bağla. Telefonda
   **"Bu Bilgisayara Güven"** çıkarsa Güven de, şifreni gir.
   (Kablo sadece yükleme ve 7 günlük yenileme için; dosya transferleri
   tamamen kablosuz.)
4. Sideloadly'yi aç:
   - Üstte iPhone'unun adı görünmeli.
   - **IPA** alanına Bölüm 4'te çıkardığın `Sendro-unsigned.ipa`
     dosyasını sürükle.
   - **Apple ID** alanına kendi Apple kimliğini (e-posta) yaz.
   - **Start**'a bas. Şifreni sorar; iki adımlı doğrulama açıksa telefona
     gelen kodu girersin.
5. 1–2 dakika içinde Sendro iPhone ana ekranında belirir.

> Not: Apple ID şifreni Sideloadly'ye girmek istemezsen
> https://account.apple.com → Oturum Açma ve Güvenlik →
> **Uygulamaya Özel Şifreler** bölümünden tek kullanımlık şifre üretip
> onu kullanabilirsin.

## BÖLÜM 6 — iPhone'da ilk açılış ayarları

Sırasıyla, hepsi gerekli:

1. **Sertifikaya güven:** Ayarlar → Genel → VPN ve Aygıt Yönetimi →
   Apple ID'nin göründüğü "Geliştirici Uygulaması" satırı → **Güven**.
2. **Geliştirici Modu:** Ayarlar → Gizlilik ve Güvenlik → en altta
   **Geliştirici Modu** → aç → telefon yeniden başlar → açılışta onayla.
   (Bu ayar sadece iOS 16 ve üzeri, sideload edilen uygulamalar için
   Apple'ın şartı.)
3. **Sendro'yu aç.** İlk açılışta:
   - **"Yerel Ağ" izni** soracak → **İzin Ver**. (Bilgisayarını Wi-Fi'da
     bulabilmesi bunun sayesinde. Yanlışlıkla reddettiysen: Ayarlar →
     Gizlilik ve Güvenlik → Yerel Ağ → Sendro'yu aç.)
   - İlk medya kaydında **Fotoğraflar izni** soracak → **İzin Ver**.

## BÖLÜM 7 — Eşleştir ve ilk dosyanı gönder

1. Bilgisayarda Sendro açık olsun; iPhone ve PC **aynı Wi-Fi ağında**
   olsun.
2. iPhone'da Sendro → **Devices** sekmesi → bilgisayarın (örn. "SEMIH-PC")
   listede belirir → dokun.
3. **Bilgisayar ekranında 6 haneli kod** belirir → bu kodu telefona gir.
   Artık eşleştiler; bu işlem bir kereliktir.
4. Windows'ta Sendro → Ana sayfadaki büyük alana bir **JPG sürükle** →
   cihaz olarak iPhone'unu seç.
5. iPhone'da bildirim kartı çıkar → **Accept**.
6. Transfer biter → Sendro dosyanın SHA-256 özetini doğrular ("Verified")
   → fotoğraf **Apple Fotoğraflar'a, "Sendro" albümüne** düşer. Bit bit
   aynı dosya — sıkıştırma yok, kalite kaybı yok.
7. Sonra bir video (MOV/MP4) ile dene, sonra istersen çok gigabaytlık bir
   export ile.

### Watch Folder kurmak (imza özellik)

Windows'ta Sendro → **Watch Folders** → **Add Folder** → örn.
`D:\Exports\Instagram` seç → **Automatically Send** açık + hedef olarak
iPhone'unu seç. iPhone tarafında Ayarlar → **Auto Accept From Trusted
Devices**'ı aç. Artık Premiere export'u bitirdiği anda dosya kendiliğinden
telefonunun Fotoğraflar'ına düşer. Kablo yok, bulut yok.

## BÖLÜM 8 — 7 günlük yenileme (önemli, dürüst uyarı)

Ücretsiz Apple hesabıyla imzalanan uygulamalar **7 gün sonra açılmaz
olur.** Verilerin (eşleştirme, geçmiş) silinmez; sadece imza süresi dolar.

**Yenilemek:** iPhone'u USB ile bağla → Sideloadly'yi aç → aynı IPA'yı
tekrar **Start** ile yükle. ~2 dakika sürer, üzerine kurulur, her şey
kaldığı yerden devam eder. Telefona hatırlatıcı kurmanı öneririm
(her 6 günde bir).

Bundan kurtulmanın tek yolu yılda 99 $ olan Apple Developer üyeliği
(imza 1 yıl geçerli olur). Zorunlu değil; ücretsiz yol çalışır.

Diğer ücretsiz hesap sınırları: aynı anda en fazla 3 sideload uygulama,
haftada 10 uygulama kimliği.

## BÖLÜM 9 — Sorun çıkarsa

En sık üç sorun:

1. **iPhone bilgisayarı görmüyor** → İkisi de aynı Wi-Fi'da mı? Windows
   güvenlik duvarında Sendro'ya "Özel ağ" izni verdin mi? Ağ profili
   "Genel" (Public) ise "Özel"e (Private) çevir: Ayarlar → Ağ ve İnternet
   → Wi-Fi → ağına tıkla → Özel. Router'da "AP/Client Isolation" kapalı
   olmalı. Hâlâ olmuyorsa iPhone'da **Manuel Bağlan** ile Windows
   Sendro'sunun Ayarlar sayfasındaki IP:port'u gir.
2. **Uygulama açılmıyor (7 gün geçti)** → Bölüm 8, yeniden imzala.
3. **Transfer yarıda kaldı** → Yeniden dene; Sendro kaldığı bayttan devam
   eder, baştan başlamaz. Büyük transferlerde PC'nin uyku moduna
   geçmediğinden emin ol.

Daha fazlası: `docs/TROUBLESHOOTING.md` (İngilizce ama madde madde).

## Güncelleme akışı (ileride)

Kodda bir şey değişirse: `git add . && git commit -m "..." && git push`
→ GitHub yeni IPA üretir → indir → Sideloadly ile yükle. Windows tarafı
için sadece `npm run tauri dev/build` yeterli, GitHub gerekmez.
