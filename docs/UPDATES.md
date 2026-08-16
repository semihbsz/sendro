# Sendro update channel (Windows + Android)

One release action, both platforms updated. No server, no cloud account:
the manifests and installers are published as **GitHub Release assets**, and
each app checks them over HTTPS. This is the only place Sendro touches the
internet — transfers stay LAN-only, always.

## 1. Source of truth

`release/release.json` in the repo, edited by the person cutting a release:

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

- `version` — semver, must be strictly greater than the previous release.
- `notes` / `notesTr` — shown in the update card (English + Turkish).
- `minSupported` — versions below this see "update required" instead of
  "update available"; use sparingly (protocol breaks only).
- `mandatory` — when true the card cannot be dismissed for the session.

The release workflow derives everything else. The version in
`desktop/src-tauri/tauri.conf.json`, `desktop/package.json`,
`core/Cargo.toml`, `android/app/build.gradle.kts` and `ios/project.yml`
must equal `version` — CI fails the release if they drift.

## 2. Published assets (per GitHub Release, tag `v<version>`)

| asset | consumer |
|---|---|
| `latest.json` | Windows (Tauri updater format, signed) |
| `Sendro_<version>_x64-setup.exe` + `.sig` | Windows installer + minisign signature |
| `android.json` | Android in-app updater |
| `Sendro-<version>.apk` | Android install |
| `Sendro-unsigned.ipa` | iOS sideload (no auto-update — see §6) |

Stable URLs (never change between releases):

```
https://github.com/<owner>/sendro/releases/latest/download/latest.json
https://github.com/<owner>/sendro/releases/latest/download/android.json
```

## 3. Windows — Tauri updater

`latest.json` is exactly the shape `tauri-plugin-updater` expects:

```json
{
  "version": "1.2.0",
  "notes": "…",
  "pub_date": "2026-08-15T12:00:00Z",
  "platforms": {
    "windows-x86_64": {
      "signature": "<contents of the .sig file>",
      "url": "https://github.com/<owner>/sendro/releases/download/v1.2.0/Sendro_1.2.0_x64-setup.exe"
    }
  }
}
```

**Signing is mandatory.** The updater refuses any payload whose minisign
signature does not verify against the public key baked into
`tauri.conf.json`. The private key lives only in GitHub Secrets
(`TAURI_SIGNING_PRIVATE_KEY`, `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`) — never
in the repo. Losing it means users cannot be updated automatically any more,
so it is also kept offline by the maintainer.

Behaviour in the app:
- Check on launch (after a 10 s grace period so startup stays fast) and
  every 6 h while running, plus a manual "Check for updates" in Settings.
- A found update shows a dismissible card: version, date, notes (Turkish
  when the UI is Turkish), **Update now** and **Later**.
- **Update now** downloads with a real progress bar, verifies the
  signature, installs, and relaunches. Transfers in flight block the
  install: the card says "3 transfers running — finish or pause first".
- Failures never brick the app: on any error it stays on the current
  version and shows the reason plus a link to the release page.
- Setting: "Check for updates automatically" (default on). Off means only
  the manual button checks — nothing is ever downloaded silently.

## 4. Android — self-update via APK

Play Store is not involved (the app is sideloaded), so the app updates
itself the way other sideloaded Android apps do:

`android.json`:
```json
{
  "version": "1.2.0",
  "versionCode": 10200,
  "pubDate": "2026-08-15T12:00:00Z",
  "notes": "…",
  "notesTr": "…",
  "minSupported": "1.0.0",
  "mandatory": false,
  "apkUrl": "https://github.com/<owner>/sendro/releases/download/v1.2.0/Sendro-1.2.0.apk",
  "apkSha256": "<lowercase hex>",
  "apkSizeBytes": 12345678
}
```

Flow: compare `versionCode` → download the APK to app-scoped storage with
progress → **verify SHA-256 before installing** (same byte-for-byte ethic as
transfers) → hand it to the system installer via
`Intent.ACTION_VIEW` + `FileProvider` (or `PackageInstaller`). Android shows
its own install prompt; the user must grant "install unknown apps" once.

The APK must be signed with the **same keystore** every release or Android
refuses the update. The keystore lives in GitHub Secrets
(`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`); a debug-signed build cannot upgrade a
release-signed one, and vice versa.

Same UI contract as Windows: dismissible card, notes, progress, "Later",
auto-check toggle, mandatory/minSupported handling.

## 5. Release procedure (the "one panel")

```
1. edit release/release.json  (version + notes)
2. python3 scripts/bump_version.py     # syncs every manifest, fails loudly on drift
3. git commit && git tag v1.2.0 && git push --tags
```

`.github/workflows/release.yml` then, in one run:
- builds the Windows bundle (signed with the minisign key),
- builds the Android release APK (signed with the keystore),
- generates `latest.json` and `android.json` from `release/release.json`,
- creates the GitHub Release `v1.2.0` and uploads every asset.

Both apps see the new version within minutes; nothing else to operate.

## 6. iOS

No auto-update: sideloaded IPAs cannot self-install, and the App Store is
not in play. The iOS app only *shows* "A newer Sendro is available
(1.2.0)" with a short line telling the user to re-sideload, using the same
`android.json`-style check against `latest.json`'s `version`. Whenever the
free-signing certificate is refreshed (weekly), installing the newest IPA
is the update.

## 7. Privacy

- Update checks send nothing but a plain HTTPS GET for a static file — no
  identifiers, no telemetry, no analytics.
- The check is skipped entirely when auto-check is off.
- Nothing about the user's transfers, devices, or files ever leaves the LAN.
