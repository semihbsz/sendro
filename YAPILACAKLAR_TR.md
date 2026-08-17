# Sendro — Şu Andan İtibaren Adım Adım (Türkçe)

Durum: son iki güncelleme (otomatik güncelleme + Android/TV) bilgisayarına
indi ama henüz GitHub'a gitmedi, imzalama ayarları da yapılmadı.

**Önemli:** İmzalama ayarı yalnızca *yayınlamak* ve *otomatik güncelleme*
içindir. Uygulamaları denemek için gerekli değil. O yüzden sıra şöyle:
önce kod yüklensin ve derlensin, imzalama en sonda.

---

## AŞAMA 1 — Kodu bilgisayarına al ve GitHub'a gönder

1. Sana gönderdiğim son `sendro.zip`'i indir.
2. İçindeki `sendro` klasörünün **içeriğini** `S:\sendro` üzerine çıkar
   ("Dosyaları değiştir / Replace" de).
3. PowerShell'de:

```powershell
cd S:\sendro
git add -A
git commit -m "Otomatik guncelleme, Android ve Android TV uygulamasi"
git pull origin main --no-edit
git push
```

> `git pull` sırasında çakışma çıkarsa (genelde `.github/workflows/`
> içindeki bir dosyada olur):
> ```powershell
> git checkout --ours .github/workflows/ios-build.yml
> git add -A
> git commit --no-edit
> git push
> ```

## AŞAMA 2 — GitHub derlemelerine bak (burada hata bekliyoruz)

Depoda **Actions** sekmesini aç. Dört iş var:

| İş | Ne yapar | Beklenti |
|---|---|---|
| Core engine tests | Rust testleri | geçmeli (84 test) |
| Build Windows App | Windows kurulumu | ilk denemede hata verebilir |
| iOS Build | IPA üretir | genelde geçer |
| Android Build | APK üretir | **ilk denemede hata vermesi normal** |

Android ve Windows hiç derlenmemiş kod içeriyor (bende Android SDK ve
Windows yok). **Hata çıkarsa panik yok:** kırmızı işe tıkla, hatanın
yazdığı yeri kopyala, bana yapıştır — düzeltip yeni zip gönderirim, sen
tekrar Aşama 1'i uygularsın. Bu turu 1-2 kez tekrarlamak normaldir.

## AŞAMA 3 — Windows uygulamasını çalıştır (imzasız, hemen)

```powershell
cd S:\sendro\desktop
npm install
npm run tauri dev
```

Bu haliyle her şey çalışır. Ayarlar'da "Güncellemeler bu derlemede
yapılandırılmadı" yazacak — normal, Aşama 5'te düzelecek.

## AŞAMA 4 — Android / Android TV uygulamasını yükle (deneme sürümü)

Actions → **Android Build** işi yeşil olunca, en alttaki **Artifacts**
bölümünden APK'yı indir, zip'ten çıkar.

**Telefona:** APK'yı telefona at (Sendro ile de gönderebilirsin!), dosya
yöneticisinden tıkla, "bilinmeyen kaynaklara izin ver" deyip kur.

**TV'ye:** TV'de Ayarlar → Cihaz Tercihleri → Hakkında → "Yapı" üzerine
7 kez bas (geliştirici modu), sonra Geliştirici Seçenekleri → **USB hata
ayıklama** ve **Ağ üzerinden hata ayıklama** aç. TV'nin IP'sini not al.
Bilgisayarda (platform-tools klasöründe):

```powershell
adb connect TV_IP:5555
adb install -r Sendro-debug.apk
```

> Not: Bu deneme APK'sı "debug" imzalıdır. Aşama 5'teki gerçek keystore ile
> imzalanmış sürüme geçerken **önce eskisini kaldırman** gerekir (Android
> farklı imzalı güncellemeyi reddeder). Bu yüzden TV/telefon kurulumunu
> kalıcı yapacaksan Aşama 5'i önce bitirmek daha rahat.

## AŞAMA 5 — İmzalama ayarları (bir kerelik, yayın için)

### 5a. Windows güncelleme anahtarı

```powershell
cd S:\sendro\desktop
npm run tauri signer generate -- -w $HOME\.tauri\sendro.key
```

- Bir şifre sorar, belirle ve **not al**.
- Komut iki dosya üretir: `sendro.key` (özel) ve `sendro.key.pub` (açık).
- `sendro.key.pub` dosyasını Not Defteri'yle aç, içindeki metni kopyala →
  `S:\sendro\desktop\src-tauri\tauri.conf.json` içindeki
  `"pubkey": "REPLACE_WITH_UPDATER_PUBLIC_KEY"` satırında tırnak arasına
  yapıştır, kaydet.
- GitHub → depon → **Settings** → **Secrets and variables** → **Actions** →
  **New repository secret**, iki tane ekle:
  - `TAURI_SIGNING_PRIVATE_KEY` → `sendro.key` dosyasının **tüm içeriği**
  - `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` → belirlediğin şifre

> `sendro.key` dosyasını yedekle (harici disk/şifre yöneticisi). Kaybedersen
> mevcut kullanıcılara bir daha otomatik güncelleme gönderemezsin.

### 5b. Android imza anahtarı (keystore)

```powershell
cd $HOME
keytool -genkeypair -v -keystore sendro.jks -alias sendro -keyalg RSA -keysize 2048 -validity 10000
```

(`keytool` bulunamazsa: Android Studio kuruluysa
`C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe` yolunu kullan.)

Sorulara cevap ver, bir şifre belirle. Sonra base64'e çevir:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\sendro.jks")) | Set-Clipboard
```

GitHub Secrets'a dört tane ekle:
- `ANDROID_KEYSTORE_BASE64` → panodaki uzun metin (Ctrl+V)
- `ANDROID_KEYSTORE_PASSWORD` → keystore şifresi
- `ANDROID_KEY_ALIAS` → `sendro`
- `ANDROID_KEY_PASSWORD` → anahtar şifresi (aynıysa aynısını yaz)

> Bu keystore'u da yedekle. Her sürümde **aynısı** kullanılmalı, yoksa
> Android güncellemeyi reddeder.

Ardından değişikliği gönder:

```powershell
cd S:\sendro
git add -A
git commit -m "Guncelleme acik anahtari"
git push
```

## AŞAMA 6 — İlk gerçek sürümü yayınla

```powershell
cd S:\sendro
# release/release.json içindeki version ve notları düzenle (1.0.0 kalabilir)
python scripts/bump_version.py
git add -A
git commit -m "Surum 1.0.0"
git push
git tag v1.0.0
git push --tags
```

Actions → **Release** işi çalışır ve şunları üretir:
- `Sendro_1.0.0_x64-setup.exe` (Windows kurulumu, imzalı)
- `Sendro-1.0.0.apk` (Android/TV, imzalı)
- `latest.json` + `android.json` (güncelleme manifestleri)

Depo sayfasında sağdaki **Releases** bölümünden indirilir. Bundan sonra:

- Windows kullanıcıları `.exe` ile kurar; yeni sürüm çıktığında uygulama
  içinde "güncelleme var" kartı görür ve tek tıkla günceller.
- Android/TV kullanıcıları APK'yı kurar; yeni sürümde uygulama içinden
  günceller.

## Sonraki güncellemeler (rutin)

```powershell
cd S:\sendro
# release/release.json → version'ı yükselt (örn. 1.1.0) + notları yaz
python scripts/bump_version.py
git add -A
git commit -m "Surum 1.1.0"
git push
git tag v1.1.0
git push --tags
```

Hepsi bu. Windows ve Android aynı anda yayınlanır.

## Sık sorunlar

- **"nothing to commit"** → zip'i yanlış klasöre çıkarmışsındır; `S:\sendro`
  üzerine çıkarman gerekiyor.
- **push reddedildi (rejected)** → önce `git pull origin main --no-edit`.
- **Release işi "pubkey placeholder" diye durdu** → Aşama 5a'daki açık
  anahtar yapıştırılmamış.
- **Android "app not installed"** → farklı imza. Eski sürümü kaldırıp
  yeniden kur.
