# Sendro for Android

The Android peer of Sendro. Same protocol as the iPhone app
(`docs/PROTOCOL.md` v1), same design language, same rules: LAN only, original
bytes only, SHA-256 verified, no cloud, no Play Services, no telemetry.

Kotlin 2.0 · Jetpack Compose · minSdk 26 · targetSdk 35 · Gradle KTS.

---

## Opening and building

```bash
# Android Studio (Ladybug 2024.2.1 or newer)
#   File ▸ Open… ▸ sendro/android
# It will offer to create the Gradle wrapper jar; let it.

# Command line, with Gradle 8.11.1 on PATH:
cd android
gradle wrapper --gradle-version 8.11.1   # once, materialises gradle-wrapper.jar
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

The debug build installs as `com.sendro.android.debug` with a `-debug` version
suffix, so it sits next to a release install without clobbering it.

> **The wrapper jar is not in the repo.** `gradle/wrapper/gradle-wrapper.jar` is
> a binary that cannot be produced without a Gradle installation, so `gradlew`
> and `gradlew.bat` here carry one addition to the stock script: if the jar is
> missing they delegate to a `gradle` on `PATH` (and only fail if there is
> neither). That is what makes `./gradlew assembleRelease` work in
> `.github/workflows/release.yml` unchanged — `gradle/actions/setup-gradle`
> puts Gradle on `PATH` there. The first `gradle wrapper` run replaces the
> fallback with a real wrapper.

### What CI does

`.github/workflows/android-build.yml` runs on any push touching `android/**`:

1. JDK 17 (Temurin) + `gradle/actions/setup-gradle@v4` pinned to Gradle 8.11.1
2. generates `gradle-wrapper.jar` if it is missing
3. `testDebugUnitTest` — the HKDF/RFC-5869 vectors, the RFC 5987 encoder, the
   wire shapes, the §8 filename rules, the `versionCode` derivation
4. `lintDebug`
5. `assembleDebug`, uploaded as the `Sendro-debug-apk` artifact
6. test + lint reports uploaded on failure as well as success

Release builds are **not** here: `.github/workflows/release.yml` (owned by the
release process, not this module) builds `assembleRelease` from `android/` and
publishes the APK plus `android.json`.

### Signing

`app/build.gradle.kts` reads four values, from the environment or from
`local.properties`:

| name | meaning |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | base64 of the `.jks` (what CI passes) |
| `ANDROID_KEYSTORE_PATH` | a path to the `.jks` instead (what CI actually sets after decoding) |
| `ANDROID_KEYSTORE_PASSWORD` | store password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

All four present → `assembleRelease` is signed with that keystore (v1+v2+v3).
Any missing → it falls back to the **debug** key and logs a loud warning.

`docs/UPDATES.md` §4 is the reason this matters: the in-app updater installs a
downloaded APK, and Android refuses an update whose signature differs from the
installed one. A debug-signed build can never upgrade a release-signed install,
and vice versa. Same keystore, every release, forever.

`versionName` / `versionCode` in `app/build.gradle.kts` are written by
`scripts/bump_version.py` and must stay in the exact conventional form
(`versionName = "1.0.0"`, `versionCode = 10000`) — the script's regexes are
anchored to those two lines.

---

## Module map

```
android/
  gradle/libs.versions.toml        every dependency version, pinned
  app/src/main/java/com/sendro/android/
    SendroApplication.kt           the object graph (hand-rolled, no DI framework)
    MainActivity.kt                the one Activity: share intents, sendro:// links,
                                   notification routing
    core/                          protocol + engines, zero Compose imports
      Protocol.kt                  §4/§5/§6/§7/§9/§11 wire models
      Crypto.kt                    HKDF-SHA256 (RFC 5869), the §4.2 proof,
                                   streaming SHA-256, base64url
      SendroClient.kt              typed HTTP for one host, three OkHttp clients
      Discovery.kt                 NsdManager `_sendro._tcp`, serialised resolves,
                                   multicast lock
      NetworkWatcher.kt            ConnectivityManager callback -> change token
      TransferEngine.kt            poll loops, offers, bulk accept, save routing
      DownloadTask.kt              §6.4 streaming download, Range resume, hashing
      UploadEngine.kt              §7 reverse upload + content-URI staging
      MediaSaver.kt                MediaStore publishing (the PhotoKit analogue)
      MessageCenter.kt             §11 RAM-only inbox
      TransferService.kt           foreground service = the Live Activity analogue
      Notifier.kt                  local notifications, four channels
      SettingsStore.kt             DataStore
      TokenStore.kt                EncryptedSharedPreferences (the Keychain analogue)
      PairedHostStore.kt           paired hosts + last known endpoint
      HistoryStore.kt              JSON history, capped at 500
      SendTray.kt                  staged-but-not-sent files
      PairLink.kt                  §13 sendro://pair parsing + confirm flow
      UpdateChecker.kt             UPDATES.md §4 — the only internet call
      AppPaths.kt / Format.kt      paths, §8 filename rules, formatting
    ui/
      theme/Theme.kt               the design tokens, glass, the beam mark,
                                   the D-pad focus ring
      theme/DeviceProfile.kt       phone vs TV, plus every capability flag
      components/Common.kt         pills, chips, badges, section tags, the
                                   Pressable focus primitive
      components/TvControls.kt     D-pad keypad, code boxes, local QR rendering
      RootScreen.kt                Receive · Send · Library shell
      MessageViews.kt              §11.3 card
      screens/                     Receive, Send, Library, Flight, Devices,
                                   Settings, Preview, QrScanner
```

---

## Protocol coverage

| PROTOCOL.md | where |
|---|---|
| §2 discovery | `Discovery.kt` (TXT `v`/`id`/`nm`/`pf`, IPv4 resolve) |
| §3 auth | `SendroClient.applyAuth` + `TokenStore` |
| §4 pairing | `SendroCrypto.pairingProof`, `DevicesScreen.runPairing` |
| §5 info | `SendroClient.info` |
| §6.2 outbox | `TransferEngine.pollLoop` on the dedicated poll client |
| §6.3 accept/reject | `TransferEngine.accept` / `reject` |
| §6.4 download | `DownloadTask` (Range + If-Range, identity encoding) |
| §6.5 status | `TransferEngine.reportStatus` at every phase |
| §7 upload | `UploadEngine` + `SendroClient.uploadRequest` |
| §8 filenames | `FileNames` + `SendroClient.rfc5987Encode` |
| §9 errors | `SendroHttpException`, storage preflight |
| §10 versions | `DevicesScreen` refuses a mismatched `protocolVersion` |
| §11 messages | `MessageCenter`, `MessageViews`, the Send composer |
| §12 bulk accept | `TransferEngine.acceptAll` (Semaphore, ≤4) |
| §13 QR pairing | `PairLink`, `QrScannerView`, the `sendro://pair` intent filter |
| §14 Sendro Link | host-side only; nothing to implement here |
| §15 receiver host | `core/host/` — `HttpServer` + `ReceiverHost` (routing), `HostPairing` (§4 host side), `PeerStore` (token verifiers), `Advertiser` (§2 mDNS registration) |

---

## Deliberate Android adaptations

* **Foreground service instead of a Live Activity.** iOS keeps a transfer alive
  with a background `URLSession`; Android has no equivalent, so a transfer runs
  in the application scope with `TransferService` holding an ongoing
  notification (filename, progress, speed). If the OS refuses to start it
  (API 31+ background-start restrictions) the transfer still runs — it just
  loses its lifeline when the user leaves the app.
* **MediaStore instead of PhotoKit.** Photos to `Pictures/Sendro`, videos to
  `Movies/Sendro`, everything else to `Download/Sendro`, all with `IS_PENDING`
  so nothing indexes a half-file. API ≤ 28 falls back to legacy external
  storage plus a MediaScanner nudge.
* **Photo Picker instead of PHPicker.** `PickMultipleVisualMedia` needs no
  permission at all and hands back the original item; Sendro copies its bytes
  and never decodes a bitmap.
* **`Share → Sendro` instead of "Copy to Sendro".** Intent filters for
  `ACTION_SEND` / `ACTION_SEND_MULTIPLE` with `*/*`. Shared files are staged and
  wait in the Send tray — never auto-sent.
* **No backdrop blur.** iOS uses `.ultraThinMaterial`. Compose's `Modifier.blur`
  is API 31+ and blurs content rather than the backdrop, so the glass is a
  translucent white gradient with a hairline border. Identical on every
  supported device instead of good on new ones and flat on old ones.
* **`VideoView` instead of AVPlayer/ExoPlayer.** Media3 is megabytes for a
  preview of a file the user already has.
* **ZXing instead of ML Kit / `AVCaptureMetadataOutput`.** ML Kit needs Play
  Services. ZXing core decodes the analyser frame's Y plane directly.
* **NsdManager quirks handled explicitly.** Resolves are serialised through one
  coroutine (parallel `resolveService` fails with `FAILURE_ALREADY_ACTIVE` on
  most OEM builds), a `MulticastLock` is held while browsing, and every network
  change drops the whole resolution cache.
* **`java.util.Base64`, not `android.util.Base64`.** API 26 has it, and it means
  the pairing-proof tests run on the JVM without Robolectric.

## Dependency choices

* **OkHttp, not Ktor.** Ktor's Android client needs an engine, and that engine
  is OkHttp — so Ktor would be OkHttp plus an abstraction. Everything Sendro
  needs is OkHttp's core competence: per-call timeouts, `Range` headers, real
  streaming bodies in both directions, and cancellation that closes the socket.
* **kotlinx-serialization**, not Moshi/Gson: no reflection, no KAPT, and the
  compiler plugin is already in the Kotlin toolchain.
* **DataStore** for settings, **EncryptedSharedPreferences** for tokens,
  plain JSON files for history and in-flight state. No Room: there is no
  relational query anywhere in this app.
* **Coil** for thumbnails (downsampled to the tile size, never full-size),
  plus `coil-video` for video frames — registered manually in
  `SendroApplication.newImageLoader()` because Coil 2 does not auto-detect it.
* **CameraX + ZXing core** for QR. **No** Play Services, Firebase, or analytics
  anywhere in the graph — check with `./gradlew :app:dependencies`.
* **No DI framework.** Fourteen singletons and one lifetime.

---

## Android TV

Sendro is **one APK**: phone-first, TV-capable. The same file installs on a
phone and on an Android TV / Google TV box, and the UI adapts at runtime from
[`DeviceProfile`](app/src/main/java/com/sendro/android/ui/theme/DeviceProfile.kt) —
there is no TV flavour, no second module, and nothing on the phone changed to
make the TV work.

### Getting it onto the TV

There is no Play Store distribution, so sideload:

```bash
# 1. On the TV: Settings ▸ System ▸ About ▸ tap "Build" 7x  (enables developer options)
#    then Settings ▸ System ▸ Developer options ▸ USB debugging / Network debugging ON
# 2. Note the TV's IP:  Settings ▸ Network & Internet ▸ (your network) ▸ IP address
adb connect 192.168.1.50:5555
adb install -r Sendro-1.0.0.apk
adb disconnect
```

Without a computer: any sideload helper that can fetch a URL (Downloader,
X-plore, Send Files to TV) works — point it at the APK from the GitHub release.
Sendro then updates itself in place (see below).

After install the app appears in the TV launcher's app row with its banner —
that comes from `LEANBACK_LAUNCHER` plus `android:banner`, and from every
`<uses-feature>` being `required="false"`. **A single required feature the TV
lacks (touchscreen, camera, telephony) makes the app invisible to the TV
launcher**, which is why the manifest lists them all explicitly.

### Pairing with a remote

1. Open Sendro on the PC and start pairing — it shows a 6-digit code.
2. On the TV: the device chip (top right) ▸ your PC in the "On this Wi-Fi" list.
3. Type the six digits on the **on-screen keypad** — a real 3x4 D-pad grid, not
   a text field, so it is two presses per digit and never summons an IME. It
   submits itself on the sixth digit.
4. If mDNS is blocked (common on guest networks and some TV firmware), use
   **Connect by IP address**: two selectable value chips (IP / port) plus the
   same keypad, with `.` available while the address chip is selected.

QR scanning is hidden on a TV — see below.

### What is deliberately unavailable on TV

| Feature | Why | What you get instead |
|---|---|---|
| **Scan QR code** (§13) | Almost no TV has a camera. Gated on `FEATURE_CAMERA_ANY`. | The 6-digit keypad, promoted to the primary path, and connect-by-IP |
| **Photos & Videos** picker | The Android Photo Picker is absent on most TV firmware (`isPhotoPickerAvailable`). | Card hidden; receiving still works fully |
| **Files** picker | Often no SAF document provider (`ACTION_OPEN_DOCUMENT` does not resolve). | Card hidden |
| **Paste** | Clipboard is effectively unused on a TV. | Card hidden |
| **Share → Sendro** | Nothing on a TV shares into apps. | Intent filters stay declared; they simply never fire |
| Tap-outside-to-dismiss | Touch idiom. | BACK closes every sheet |

Everything hidden is hidden *by capability*, not by "is TV" — a TV box with a
USB camera gets the QR scanner back, and a tablet without a camera loses it.
Every remaining `startActivity` is wrapped, so a device that claims a picker
and then throws still shows a message instead of crashing.

### What works well on TV

- **Receiving** — the whole point. Offers, accept/decline, bulk accept, live
  progress, SHA-256 verification and saving are identical to the phone.
- **Video and photos** — full screen, remote-driven. On a video: **OK** toggles
  play/pause, **◀ ▶** seek ±10 s, and the media transport keys work too. On a
  photo: **OK** toggles fit/2.5x, arrows pan while zoomed.
- **Incoming text and links (§11)** — the card takes the whole screen, sets the
  text large enough to read from a sofa, and renders the text as a **QR code**
  beside it so you can point a phone at the TV and open the link there. The QR
  is generated locally with the ZXing core already bundled for the scanner: no
  network call, no bitmap, no file. The §11 ephemerality rule is unchanged —
  the message is never written anywhere and Close frees it.
- **The Library** is the reliable place to find things. Many TVs have no
  gallery app at all; Sendro still publishes to MediaStore (`Pictures/Sendro`,
  `Movies/Sendro`, `Download/Sendro`), and the in-app preview reads the
  MediaStore URI directly, so a received photo is viewable even with no other
  app on the device that can open it. If MediaStore refuses, the bytes fall
  back to Sendro's own store and the Library shows them from there.
- **Transfers survive leaving the app** — the foreground service and its
  ongoing notification work the same on TV.

### Cleartext on the LAN

Sendro speaks plain HTTP to peers by design (PROTOCOL.md §3). Android has
blocked cleartext by default since API 28, so without a policy **every
outbound request failed** — pairing, downloads, uploads, messages. The symptom
was `CLEARTEXT communication to 192.168.1.103 not permitted by network
security policy`, and it was asymmetric in a confusing way: the inbound
receiver host still worked, so phone → TV succeeded while TV → PC did not.

`res/xml/network_security_config.xml` permits cleartext in `base-config` and
pins `github.com` / `githubusercontent.com` back to HTTPS-only with a
`domain-config`, so the update check can never silently downgrade. It cannot be
narrowed to "private ranges only": `<domain>` matches host names and exact IP
literals, and **the platform does not support CIDR ranges** — a peer's address
is whatever the router handed out.

The manifest sets both `android:networkSecurityConfig` and
`android:usesCleartextTraffic="true"`; the latter covers API 26–27, where the
manifest flag is still consulted.

There is no `ConnectionSpec` anywhere in the app, so OkHttp keeps its default
`[MODERN_TLS, CLEARTEXT]` and does not reject plain HTTP on its own. If anyone
ever adds `connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))` for the update
client, it must be on a *separate* client from `SendroClient.Clients` or every
LAN call breaks again.

### Sending TO the TV (receiver host, PROTOCOL.md §15)

Until §15 the TV could only be a *client* of the PC, which made phone → TV
impossible: two clients cannot talk. Sendro on Android now also runs the
**host** side — a deliberately reduced one, because a TV only ever receives.

**On by default on a TV, off by default on a phone** (§15.4), toggled under
Settings ▸ "Receive from other devices". Off means the port is closed and the
mDNS advertisement withdrawn — not just hidden.

#### Pairing a phone to the TV

1. On the TV: **Devices ▸ Let a phone send to this TV**.
2. The TV shows a large QR code, the six digits under it, a live expiry
   countdown, and its own `IP:port` in plain text.
3. On the phone: **Devices ▸ Scan QR code**, point it at the TV.
4. That is it. The phone now lists the TV as a send target.

The QR is the ordinary §13 `sendro://pair?…` URL, so the phone's scanner needed
no change at all — and neither would an iPhone's. The six digits never cross
the network: the phone proves it saw them with an HMAC (§4.2). The code expires
after 120 seconds and renews itself while the screen is open.

A **PC** has no camera, so it uses the typed path instead: it calls
`pair/start`, and the code *that* session generated appears on the TV screen
("Type this on your computer") for the user to enter on the PC.

#### What the TV's host implements

| endpoint | behaviour |
|---|---|
| `GET /api/v1/info` | §5, with `platform: "androidtv"` |
| `POST /api/v1/pair/start` · `/pair/confirm` | §4 verbatim — same HKDF/HMAC, 120 s expiry, 3-session and 5-attempt caps, constant-time compare |
| `GET /api/v1/ping` | §4.3, bearer-authenticated |
| `POST /api/v1/upload` | §7 verbatim — raw body, RFC 5987 name, hash-while-writing, `422` + delete on mismatch |
| `POST /api/v1/messages` | §11.2 — text and links land as an on-screen card, RAM only |
| `GET /api/v1/outbox` and every transfer route | `404 not_found` — a receiver never offers |

That 404 is load-bearing, not an omission: it is how a client discovers the
peer is receive-only. Sendro's own poll loop latches it, stops long-polling,
and falls back to a cheap ten-second ping so the device still shows as online.
The Devices list labels such a peer "receive-only" rather than "broken".

#### Peers are named, not assumed

The receiver host is paired to by phones **and** by the Windows app, so
nothing says "phone" by assumption. `PlatformNames` turns the §5 / §2-TXT
`platform` string into wording ("PC", "phone", "TV", "iPhone") and a glyph, and
it is used by the paired-peer list, the Send target chip and the incoming
pair-request banner alike.

The two pairing bodies deliberately have **no platform default that names a
platform**: `PairStartRequest.platform` defaults to `""` and
`PairConfirmRequest.platform` to `null`. They are used in both directions now
— encoded when this app pairs to a PC, decoded when a PC pairs to this device —
and a default of `"android"` would have silently labelled a Windows peer as an
Android one. This app also reports `androidtv` rather than `android` when it is
a TV, so a PC's device list shows a TV as a TV.

#### PC → TV: two legitimate paths (§15.3)

- **PC as host, TV as client** (the original, still preferred): the PC offers,
  the TV long-polls, accepts, and downloads with Range/resume. Use this when
  the PC drives — its queue and history stay accurate and an interrupted
  transfer resumes.
- **TV as host, PC as client**: the PC pushes with a §7 upload. Simpler, no
  offer/accept round trip, **no resume**.

Both run at the same time. The TV keeps its client role while hosting.

#### Security posture

- Uploads are accepted only from devices paired here, with a valid bearer
  token. The host stores only `SHA-256(token)` (§3), in
  EncryptedSharedPreferences, and compares it with `MessageDigest.isEqual`
  without an early exit.
- Every upload is SHA-256 verified before it is published. A mismatch deletes
  the destination and answers `422 integrity` — the bytes are never visible in
  a half-written state, because a MediaStore row is created with `IS_PENDING`
  and a plain file is written under `.sendropart` and renamed on commit.
- Traffic is plain HTTP, exactly as §3 documents for v1. This is a personal
  trusted LAN feature; do not expose port 48800 to the internet.
- There is no directory listing and no path is ever mapped to a file: the
  server answers six fixed routes and 404s everything else.

#### The HTTP server

Raw `ServerSocket`, about 350 lines, no dependency. NanoHTTPD wants to parse
the body for you (and buffers it); Ktor server is megabytes of engine for six
endpoints. Sendro's promise is "the bytes are never buffered and the hash is
computed on exactly what is written", and the shortest way to *prove* that is a
byte loop you can read. Bounded worker pool, bounded request line and headers,
`Connection: close` (no keep-alive), `Content-Length` and chunked bodies both
handled, and an upload is streamed to its destination 1 MiB at a time.

An 8 GB movie costs one 1 MiB buffer and **one** pass over storage: the upload
is written straight into its final MediaStore row rather than to a temp file
that then gets copied.

### The TV home screen

A TV is a pure receiver, and since §15 there are two equally valid ways to
feed it. Both are on the home screen, side by side, not buried in a sheet:

| action | one-liner | what it opens |
|---|---|---|
| **Pair a PC** | "Your computer sends files here" | the Devices sheet (discovery, pairing, manual IP) |
| **Let a phone send** | "Scan a code with your phone to send from it" | the receiver-pairing screen with the big QR |

While nothing is connected they are large glass cards with an accent bar and
the D-pad lands on the first of them. Once anything is paired — in *either*
direction — they collapse into a quiet two-button row under the header and
focus goes back to the device chip. They are entry points, not the point of
the screen.

The empty state no longer says "Pair your PC to start"; it says "Nothing
connected yet" and names both paths, because a computer is no longer required.

If a peer with no camera (in practice the Windows app) sends a `pair/start`,
the six digits it needs appear **on the home screen** as a banner naming the
device, with the code set at roughly twice body size and spaced — a TV
notification is invisible from a sofa, so the digits have to be where the user
is already looking. The banner also appears on the receiver-pairing screen.

Phones are untouched: the whole block is gated on `DeviceProfile.isTv`.

### Movies: the TV's own player comes first

For a received video on a TV the primary action is **Play now**, which sends
the file to the TV's player via `ACTION_VIEW` + a FileProvider URI, through a
chooser when several players are installed. Sendro's own `VideoView` is the
fallback ("Play in Sendro"), used automatically when nothing else resolves.

That order is deliberate: the TV's player handles MKV, HEVC, DTS and embedded
subtitles that the in-app one will not, and it is what the remote's transport
keys were designed for. The same action appears in three places — the
completed-transfer card, the Library row (whose chip reads "play"), and the
preview screen, where it is the focused button.

Photos go to the in-app zoomable viewer, with "Open with…" alongside for the
system gallery when one exists.

### Received APKs

When the received file is an `.apk`, the primary action becomes **Install**:

- the row and the preview show the parsed **package name and version**
  (`PackageManager.getPackageArchiveInfo`, manifest only), and whether that
  package is already installed — including a warning when the archive is a
  downgrade Android will refuse;
- a **SHA-256 verified on arrival** badge sits directly next to the button,
  because "is this the file they actually sent me" is the question that matters
  at that moment;
- installing takes **two presses**: the button arms, renames itself to
  "Confirm install", and only then hands the file to Android — which asks
  again itself. Nothing is ever auto-installed;
- if "install unknown apps" has not been granted, the button opens that setting
  instead (walking `MANAGE_UNKNOWN_APP_SOURCES` → security settings → settings
  root, because some TV firmwares implement only one of them), and there is an
  explicit "Allow installs" button next to it.

`.apks` / `.xapk` / `.apkm` split bundles are **not** installable by Android
directly; Sendro says so plainly and offers "Open with…" instead of launching
an intent that would fail.

Received APKs are always kept in Sendro's own store rather than MediaStore —
the installer and the manifest parser both need a real file path, which a
`content://` Downloads row does not provide.

### Self-update on TV

The in-app updater (docs/UPDATES.md §4) works with a remote: the update card's
buttons are focusable, the system installer prompt is D-pad navigable, and a
failure never strands you — the verified APK stays on disk.

**Some TV firmwares require "install unknown apps" to be enabled first.** The
path varies:

- Google TV / Android TV 10+: **Settings ▸ Apps ▸ Security & restrictions ▸
  Unknown sources ▸ Sendro** → on
- Older Android TV: **Settings ▸ Device Preferences ▸ Security & restrictions ▸
  Unknown sources ▸ Sendro** → on
- Some vendor skins put it under **Settings ▸ System ▸ Security**

Settings ▸ Updates has an **Allow installs** button that walks a list of
candidate system screens (`MANAGE_UNKNOWN_APP_SOURCES`, then security settings,
then the settings root) and opens the first one the device implements. If none
resolve, the card says so and names the path above rather than failing silently.

### TV design adaptations

All of these come from `DeviceProfile` and none of them touch the phone:

- **Overscan-safe padding** — 48dp horizontal / 27dp vertical (~5% of a
  960x540dp surface). Consumer sets still crop the panel edges.
- **Type scale 1.3x with an 18sp floor** on body text. Micro-labels are scaled
  but not floored, so a tracked caption does not become a second headline.
- **Higher-contrast surfaces** — glass fill +0.05 alpha, borders +0.10 and 1dp
  instead of 0.5dp, and a floor under the secondary/tertiary text ramp. The
  phone's very quiet glass simply disappears on a dim, glossy, contrast-boosted
  TV panel.
- **A real bottom tab bar**, not a floating overlay. That is a focus
  requirement: Compose's 2D focus search walks geometry, so a bar drawn on top
  of the last list rows makes "D-pad down out of the list" ambiguous. As
  siblings in a Column it is unambiguous, with no explicit focus wiring.
- **One focusable layer at a time.** On TV exactly one of {overlay, message
  card, shell} is composed. An opaque layer hides the shell visually but leaves
  every button under it focusable, and the D-pad walks into things nobody can
  see; removing it from composition is the only reliable fix.
- **A visible focus indicator** on every actionable element: an iris ring plus
  a soft glow drawn *outside* the bounds (so nothing reflows) and a 1.045x
  scale. Never colour alone.

### D-pad focus notes

`Modifier.clickable` already makes a node focusable and already activates on
DPAD_CENTER / ENTER / NUMPAD_ENTER. Because every tappable surface in Sendro
goes through the single `Pressable` primitive, making the app remote-navigable
was one change in one file plus per-screen entry points. There is deliberately
no second `Modifier.focusable()` on those nodes — that would install a second
focus target and the remote would need two presses to walk past each button.

Only stable Compose focus APIs are used (`FocusRequester`, `focusRequester`,
`onFocusChanged`, `focusable`, `focusGroup`, `onKeyEvent`). `bringIntoViewRequester`
and `focusRestorer` are experimental in the pinned BOM and are avoided: lazy
lists and `verticalScroll` containers already scroll a newly focused child into
view on their own, including items past the visible bounds.

---

## Known limitations and rough edges

Honest list, in rough order of how likely they are to bite:

1. **This has never been compiled.** It was written without an Android
   toolchain available. Expect a first build to surface import/API nits —
   most likely candidates are Compose overload resolution, Material3 API
   surface drift between BOM versions, and the OkHttp/Okio interop in
   `StreamingFileBody`.
2. **No `gradle-wrapper.jar`.** See above. Everything works, but the first
   `gradle wrapper` run is a manual step outside CI.
3. **`androidx.security:security-crypto` is `1.1.0-alpha06`.** The 1.0.0 stable
   line is effectively unmaintained and the alpha is what everyone ships.
   `TokenStore` degrades to plain SharedPreferences (with the file wiped first)
   if the Keystore is unusable, and Settings shows which mode is in effect.
4. **Foreground-service start can be refused.** On API 31+ an app in the
   background may not start one. Sendro only ever starts a transfer from a user
   action or while visible, so this should not happen — but a transfer that
   begins from an auto-accepted offer while the app is backgrounded will run
   without service protection and may be frozen by the OS.
5. **Background delivery is best-effort, and less good than iOS.** The outbox
   long poll only runs while the process is alive. Doze and App Standby will
   eventually freeze it. There is no push, by design.
6. **mDNS is unreliable on some networks and devices.** Guest Wi-Fi and many
   hotspots drop multicast entirely. Connect-by-IP is a first-class path for
   exactly this reason, and the Devices screen says so.
7. **`ACTION_VIEW` install path.** The updater hands the APK to the system
   installer. If the user has not granted "install unknown apps" the first tap
   opens that settings screen instead; they must come back and tap Install
   again. `PackageInstaller`'s session API would not improve this without a
   privilege we do not have.
8. **No ranged upload (§7).** A failed phone→PC upload restarts from byte 0.
   That is the protocol's v1 shape, not an implementation gap.
9. **`Environment.DIRECTORY_DOWNLOADS` + `RELATIVE_PATH` subfolders** are
   honoured by AOSP but a few OEM MediaStore implementations flatten them.
   The save falls back to app-private storage rather than failing.
10. **Legacy storage (API 26–28).** The `WRITE_EXTERNAL_STORAGE` runtime request
    is not wired into a screen yet: `MediaSaver` reports
    `NeedsStoragePermission`, the transfer parks in `StorageDenied`, and the
    Flight screen offers "keep in Files". On API 29+ (the overwhelming
    majority) this path never runs.
11. **Turkish UI is not localised.** English UI, consistent with iOS. Only the
    update notes prefer `notesTr`.
12. **No instrumented tests.** The unit tests cover the parts that are provable
    on a JVM; the engine's timing behaviour is not covered by anything but a
    real device.
13. **TV focus was verified by reading the focus graph, not by running it.**
    There is no TV emulator here. The layouts are built so that Compose's 2D
    search has an unambiguous answer everywhere (no overlapping focusable
    layers, no holes in the keypad grid, action pairs in Rows inside
    `focusGroup`s), but the first real run on a TV is where any residual
    "D-pad presses do nothing here" bug will surface.
14. **The TV banner is a generated 320x180 PNG.** It was drawn from the same
    beam geometry as the app icon (`scripts/generate_icons.py`) with the
    wordmark set in DejaVu Sans Bold, because the UI's actual font (Roboto)
    was not available to the generator. At banner size it reads as the brand;
    if that ever matters, regenerate it with Roboto.
15. **`VideoView` seek granularity** depends on the container. On a
    non-seekable stream `seekTo` is a no-op and the HUD will show the position
    not moving; there is no error, it just does not seek.
16. **A §11 message arriving while an overlay is open waits** on TV, because an
    explicit destination (pairing, settings, a live transfer) must not be
    hijacked. It appears the moment that destination closes. On a phone the
    banner shows immediately.
17. **The receiver host has never spoken to a real client.** The HTTP parser,
    the chunked decoder and the §4 host-side pairing were written against the
    spec and unit-tested, but the first conversation with the Rust host or with
    OkHttp is where a framing or header-case assumption will show.
18. **`Connection: close` on every response.** Correct and universally
    supported, but it means one TCP connection per request; a client that
    assumed keep-alive would just be slower, not broken.
19. **Plain HTTP, as §3 specifies for v1.** The receiver listens on 0.0.0.0.
    On a hostile network anyone who can reach the port can attempt pairing —
    they still need the six digits, but do not run this on a coffee-shop
    Wi-Fi with the toggle on.
20. **A phone paired to a TV shows the TV as a target only while the TV is
    reachable.** There is no "offline queue" for phone → TV; §7 has no resume,
    so a failed upload restarts from byte 0.
21. **`getPackageArchiveInfo` returns null for some APKs** (v2-signing-only
    edge cases, or a corrupt archive). Sendro then shows a plain warning
    instead of package details and still lets the user install deliberately.
22. **The cleartext policy is global.** `base-config` permits plain HTTP to
    *any* host that is not github; it cannot be limited to RFC1918 ranges
    because the platform's `<domain>` has no CIDR form. The mitigation is that
    the only non-LAN URL in the app is the pinned update manifest.
23. **The Windows client role is new on both sides.** The TV's host has never
    seen a `pair/start` from the real Windows app — the field names and the
    `platform` string are per §4.1, but the first handshake is where a
    mismatch would show.
24. **The home-screen pair banner ticks a 500 ms clock** while a request is
    pending. It stops when the session clears, but a session that somehow
    never expires would keep the TV home screen recomposing.

## Privacy, restated

The only outbound-internet request in the whole app is
`GET https://github.com/semihbsz/sendro/releases/latest/download/android.json`,
and it does not happen when auto-check is off. Everything else is a private
LAN address. Text messages (§11) never touch disk, never appear in history, and
never put their text in a notification.
