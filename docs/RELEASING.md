# Sendro — sürüm çıkarma / Releasing

> **Sürüm çıkarmak için üç komut** (kurulumu bir kez yaptıktan sonra):
>
> ```bash
> # 1) release/release.json içindeki "version" ve notları düzenle
> python3 scripts/bump_version.py
> git commit -am "release 1.2.0" && git tag v1.2.0 && git push && git push --tags
> ```
>
> Gerisini GitHub Actions yapar: Windows kurulumunu imzalar, (varsa) Android
> APK'sını imzalar, `latest.json` ve `android.json` dosyalarını üretir ve
> GitHub Release'i tüm dosyalarla birlikte yayınlar. Uygulamalar birkaç dakika
> içinde yeni sürümü görür.

This document is the **one-time setup** a maintainer does before the first
signed release, then the routine after that. It assumes no prior experience
with code signing. Follow it top to bottom; every step says exactly what to
type and what to paste where.

The design behind all of this is `docs/UPDATES.md`. This file is the how-to.

---

## What you are setting up

| thing | why it exists | where it lives |
|---|---|---|
| **Tauri updater key pair** | Windows refuses to install an update whose signature does not match. Without it there are no auto-updates. | public half: `desktop/src-tauri/tauri.conf.json`. private half: GitHub Secrets + your own offline backup. |
| **Android keystore** | Android refuses to upgrade an app signed by a different key. Lose it and users must uninstall/reinstall. | GitHub Secrets + your own offline backup. |
| `release/release.json` | The single source of truth for the version number and the release notes. | the repo. |

**Back up both private keys somewhere outside GitHub.** A password manager, an
encrypted USB stick — anywhere you will still have it in two years. Losing the
updater key means no Windows user can ever be auto-updated again; losing the
keystore means every Android user has to uninstall and reinstall by hand.

---

## Step 1 — Check the GitHub account name

Everything is published as GitHub Release assets on **`semihbsz/sendro`**. If
your repository lives somewhere else, change the owner in these three places:

1. `desktop/src-tauri/tauri.conf.json` → `plugins.updater.endpoints`
2. `desktop/src/updates.tsx` → `RELEASES_URL`
3. nothing in the workflow — `.github/workflows/release.yml` reads the owner
   from `$GITHUB_REPOSITORY` at run time.

The endpoint URL is deliberately the `/releases/latest/download/...` form, so
it never changes between releases.

---

## Step 2 — Generate the Windows update signing key (once)

In a terminal, in the `desktop/` folder:

```bash
cd desktop
npm install
npm run tauri signer generate -- -w ~/.tauri/sendro.key
```

It asks for a password. **Use one, and write it down** — you will paste it into
GitHub in step 3.

The command prints two things and writes two files:

- `~/.tauri/sendro.key` — the **private** key. Never commit this. Never paste
  it anywhere except the GitHub secret in step 3.
- `~/.tauri/sendro.key.pub` — the **public** key. This one is meant to be
  public and goes in the repo.

Now put the public key into the app:

```bash
cat ~/.tauri/sendro.key.pub
```

Copy that whole one-line string and paste it into
`desktop/src-tauri/tauri.conf.json`, replacing the placeholder:

```jsonc
"plugins": {
  "updater": {
    "endpoints": ["https://github.com/semihbsz/sendro/releases/latest/download/latest.json"],
    "pubkey": "REPLACE_WITH_UPDATER_PUBLIC_KEY",   // <- paste here
    "windows": { "installMode": "passive" }
  }
}
```

Commit that change.

> **Until you do this, the app behaves correctly but does not auto-update.**
> Sendro detects the placeholder at startup, does not register the updater
> plugin at all, and Settings → Updates says *"Updates are not configured in
> this build"* with a link to the release page. It never crashes and never
> offers a button that can only fail. The release workflow refuses to run
> while the placeholder is still there, so you cannot ship a half-configured
> build by accident.

---

## Step 3 — Paste the two Tauri secrets into GitHub

Go to **GitHub → your repo → Settings → Secrets and variables → Actions → New
repository secret**, and add these two:

| secret name | value |
|---|---|
| `TAURI_SIGNING_PRIVATE_KEY` | the **entire contents** of `~/.tauri/sendro.key` — run `cat ~/.tauri/sendro.key` and copy the whole line (it is one long base64 string) |
| `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` | the password you typed in step 2 |

If you used no password, still create the secret and leave it empty.

---

## Step 4 — Create the Android keystore and paste its four secrets

Skip this until the Android app exists in `android/`. The release workflow
detects `android/` automatically and simply builds Windows only until then.

Create the keystore (once, ever):

```bash
keytool -genkeypair -v \
  -keystore sendro-release.jks \
  -alias sendro \
  -keyalg RSA -keysize 4096 -validity 10000
```

It asks for a keystore password, your name/organisation (anything sensible),
and then a key password. Write all of it down.

Turn the file into text so it can live in a secret:

```bash
base64 -w0 sendro-release.jks > sendro-release.jks.base64   # Linux
base64 -i sendro-release.jks -o sendro-release.jks.base64   # macOS
```

Then add these four repository secrets:

| secret name | value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | the whole contents of `sendro-release.jks.base64` |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | `sendro` (or whatever `-alias` you used) |
| `ANDROID_KEY_PASSWORD` | the key password |

Keep `sendro-release.jks` itself offline and out of the repo. Delete the
`.base64` file once it is pasted.

### Android contract

The Gradle build in `android/` must read the keystore from these environment
variables — the workflow sets them and nothing else:

```
ANDROID_KEYSTORE_PATH        absolute path to the .jks the workflow wrote
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

and `./gradlew assembleRelease` must leave a signed APK under
`android/**/outputs/apk/release/`. `versionName` / `versionCode` are written
by `scripts/bump_version.py`; do not hand-edit them.

---

## Step 5 — Cut a release

1. Edit `release/release.json`:

   ```json
   {
     "version": "1.2.0",
     "pubDate": "2026-08-15T12:00:00Z",
     "notes": "Sendro Link, QR pairing, in-app preview.",
     "notesTr": "Sendro Link, QR ile eşleştirme, uygulama içi önizleme.",
     "minSupported": "1.0.0",
     "mandatory": false
   }
   ```

   - `version` must be **strictly greater** than the last release.
   - `notesTr` is what the Turkish UI shows in the update card; `notes` is the
     fallback.
   - `minSupported` turns "update available" into "update required" for older
     builds. Use it only for protocol breaks.
   - `mandatory: true` makes the card undismissable for the session.

2. Sync every manifest and check it:

   ```bash
   python3 scripts/bump_version.py
   python3 scripts/bump_version.py --check   # what CI runs; must print "everything matches"
   ```

   This writes the version into `desktop/package.json`,
   `desktop/src-tauri/tauri.conf.json`, `desktop/src-tauri/Cargo.toml`,
   `core/Cargo.toml`, `ios/project.yml` (`MARKETING_VERSION` only) and — when
   it exists — `android/app/build.gradle.kts` (`versionName` plus a derived
   `versionCode`, e.g. 1.2.0 → 10200).

3. Commit, tag, push:

   ```bash
   git commit -am "release 1.2.0"
   git tag v1.2.0
   git push && git push --tags
   ```

The `Release` workflow starts on the tag. Watch it under **Actions**. When it
is green there is a GitHub Release `v1.2.0` carrying:

| asset | consumer |
|---|---|
| `latest.json` | the Windows updater |
| `Sendro_1.2.0_x64-setup.exe` + `.sig` | Windows install / update |
| `android.json` | the Android in-app updater |
| `Sendro-1.2.0.apk` | Android sideload |

Windows users see the update card within 6 hours, or immediately if they
restart or press **Check now**.

---

## What the workflow refuses to do

It fails **before building anything** when:

- `scripts/bump_version.py --check` reports drift (a manifest disagrees with
  `release/release.json`);
- the tag does not match `release/release.json` — `v1.2.0` vs `1.2.0`;
- `plugins.updater.pubkey` is still the placeholder;
- `TAURI_SIGNING_PRIVATE_KEY` is missing;
- (Android only) `ANDROID_KEYSTORE_BASE64` is missing.

It also fails if the bundler did not produce a `.sig` next to the installer —
an unsigned installer would be rejected by every user's app anyway, so it is
never published.

---

## Why `createUpdaterArtifacts` is `false` in the repo

Turning it on makes signing **mandatory** for every `tauri build`, including
the plain push CI in `.github/workflows/windows-build.yml`, which has no
signing key and should not have one. So the committed config leaves it off and
the release workflow switches it on for its own build, by deep-merging a
one-key override file into the config:

```bash
echo '{"bundle":{"createUpdaterArtifacts":true}}' > /tmp/updater-on.json
cd desktop
TAURI_SIGNING_PRIVATE_KEY="$(cat ~/.tauri/sendro.key)" \
TAURI_SIGNING_PRIVATE_KEY_PASSWORD='…' \
  npm run tauri build -- --config /tmp/updater-on.json
```

That is exactly what CI runs, so you can reproduce a release build locally.
The signed installer and its `.sig` land in
`desktop/src-tauri/target/release/bundle/nsis/`.

---

## Rotating or losing a key

- **Updater key lost:** generate a new pair, replace `pubkey`, replace the
  secret, ship a new release. Everyone already running Sendro must install
  that release **by hand** once — their app cannot verify anything signed with
  the new key until it is running the build that carries the new public key.
- **Android keystore lost:** there is no recovery. Users must uninstall and
  reinstall. This is why it is backed up offline.

---

## Troubleshooting

**"Updates are not configured" in Settings.**
The build was made with the placeholder pubkey (or no endpoint). Do step 2 and
rebuild. This is expected on any build made before the first signed release.

**The card says the signature did not match.**
The installer on the release was signed with a different private key than the
public key inside the running app. That happens after a key rotation, or when
`TAURI_SIGNING_PRIVATE_KEY` was replaced without updating `pubkey`. The app
stays on its current version; users install from the release page once.

**The update check says it could not reach GitHub.**
Offline, or a firewall blocking `github.com`. Nothing is retried in a loop —
the next scheduled check is 6 hours away, and **Check now** works any time.

**The release ran but there is no `android.json`.**
`android/` was not in the tree (or has no `gradlew`), so the Android job was
skipped. That is the intended behaviour until the Android app lands.

**MSI vs setup.exe.**
Only the NSIS `…_x64-setup.exe` is published and only that is what the
updater installs. `windows-build.yml` still produces an `.msi` as a CI
artifact for testing; do not hand it to users, or their in-app update will
install a second copy alongside it.
