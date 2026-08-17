# Sendro Android — build notes

Written during the "no compiler available" audit before the second CI run. The
first run died on `app/build.gradle.kts` itself, so **not one line of Kotlin had
ever been compiled**; everything below was found by reading, grepping and
cross-checking call sites against declarations.

---

## 1. Fixed in this pass

### Gradle scripts

| What | Where | Why it would have failed |
|---|---|---|
| `import java.util.Base64` + `Base64.getMimeDecoder()` | `app/build.gradle.kts:53` | (already fixed before this pass) inside a Kotlin DSL script `java` resolves to `JavaPluginExtension`, so `java.util.*` is unresolvable. |
| Added `import java.io.File` | `app/build.gradle.kts:6` | `File` is used as a type and a constructor (`File?`, `File(path)`, `File(layout.buildDirectory…)`). `java.io.File` is **not** one of Gradle's implicit Kotlin DSL imports (that list is Gradle packages + Kotlin defaults). An explicit import is free insurance: if it were implicit, the import is merely redundant. |
| Module-wide Compose opt-ins | `app/build.gradle.kts` `kotlinOptions` | Added `-opt-in=androidx.compose.foundation.ExperimentalFoundationApi`, `…material3.ExperimentalMaterial3Api`, `…ui.ExperimentalComposeUiApi`. A *missing* opt-in is a hard error; an unnecessary one is a warning. This covers every experimental Compose API in the module at once instead of hoping each call site guessed right. |

Verified and left alone: `layout.buildDirectory.get().asFile` (correct Gradle
8.x shape), `signingConfigs.create("release") { … }` / `signingConfigs.getByName(…)`
(the `android {}` block in KTS is `BaseAppModuleExtension`, whose
`signingConfigs` is invariant, so this is the canonical snippet),
`packaging { resources { excludes += … } }`, `lint { }`,
`testOptions { unitTests.isReturnDefaultValues }`, `kotlinOptions`
(deprecated in AGP 8.7, not removed).

### Version catalog

Every single `libs.*` accessor in `build.gradle.kts` and `app/build.gradle.kts`
was machine-checked against `gradle/libs.versions.toml`: **31 library
accessors + 4 plugin accessors, all resolve**, and every `version.ref` points at
a declared version. Compatibility triangle re-checked: AGP 8.7.3 ↔ Gradle 8.11.1
(needs ≥ 8.9) ↔ Kotlin 2.0.21 ↔ `org.jetbrains.kotlin.plugin.compose` 2.0.21 ↔
Compose BOM 2024.12.01 (ui 1.7.6 / material3 1.3.1) ↔ compileSdk 35 (needs AGP
≥ 8.6). No change needed.

Two entries are declared but never referenced: the `androidx-navigation-compose`
library and the `ksp` version. Both are valid published coordinates and an
unused catalog entry is never resolved, so they cannot break the build; left in
place deliberately.

### Kotlin sources

1. **`ui/screens/FlightScreen.kt` — missing `import androidx.compose.foundation.focusGroup`.**
   `Modifier.focusGroup()` is used four times (lines ~219/250/280/304).
   Unresolved reference → hard error. Import added.
2. **`ui/screens/DevicesScreen.kt` — same missing `focusGroup` import** (three
   uses). Import added.
   *(All ten other files that call `focusGroup()` were checked and already
   import it; `PreviewScreen`'s two `.focusable()` calls are imported too.)*
3. **`core/TransferEngine.kt` — the outbox long-poll body had been truncated.**
   Line 526 read literally:
   ```kotlin
   val response = client.outboxLongPoll(POLL_WAIT_SECONDS)            } catch (e: CancellationException) {
   ```
   That *compiles* (an unused local is only a warning), which makes it worse
   than a syntax error: the client would have polled forever and thrown every
   answer away — no offers, no messages, no "online" after a successful poll,
   and `handleOffer` / `resumeInterrupted` / `notifyIncomingOffers` would have
   been dead code. Restored: mark online + clear backoff on any 200, feed
   `response.offers` through `handleOffer` (counting genuinely new ones for one
   grouped notification), push `response.messages` into the RAM inbox and post
   the §11 sender-only notification, then `resumeInterrupted(hostId)`.

---

## 2. Checked and found clean (so the next failure is not re-investigated)

Machine checks (scripts run over all 50 `.kt` files):

- **Brace / paren / string balance** with a Kotlin-aware lexer (handles `"""`,
  `${…}` templates, char literals, both comment forms): all 50 files balanced.
  The TransferEngine truncation was the only lost-code site.
- **Unresolved capitalized identifiers**: every type/companion referenced is
  imported, declared in the same file, or in the same package. Zero hits.
- **Named arguments at call sites** vs. the parameter lists of every project
  function, composable and data-class constructor: zero real mismatches.
- **Redeclaration**: no duplicate non-private top-level declaration inside a
  package (all the same-named helpers — `Header`, `check`, `sendroFieldColors`,
  … — are `private`, i.e. file-scoped).
- **`kotlinx.serialization`**: every file using `SendroJson.decodeFromString` /
  `encodeToString` imports the reified extension from `kotlinx.serialization`.
- **`@Composable` discipline**: no composable-only API (`Sendro.sans/mono`,
  the `@Composable` colour getters, `glassCard/glassRow`, `LocalDeviceProfile`,
  `remember`, `screenPadding`) is called from a non-composable function. The
  local funs inside composables (`submitIfComplete`, `check`, `togglePlay`,
  `stageAndQueue`) call nothing composable.
- **Exhaustive `when`**: every `when` over `TransferPhase`, `UploadPhase`,
  `SaveResult`, `UpdateState`, `ReceiverHost.State`, `DevicesPane`, `PairStage`,
  `ReceivedAction`, `DownloadTask.Outcome`, `UploadEngine.Outcome`,
  `HostPairing.ConfirmResult`, `SaveMediaMode`, `ApkInstaller.Kind`,
  `MediaKind?`, `Discovery.Status`, `NotificationRoute`, `AppSurface` covers
  every branch (they are all used as expressions or as statements over sealed
  types, both of which are errors when non-exhaustive).
- **Resources**: the only `R.*` references in Kotlin are
  `R.drawable.ic_stat_sendro` and the four `R.string.notification_channel_*` —
  all present. Every XML under `res/` plus the manifest parses.
- **Manifest ↔ code**: `.MainActivity` and `.core.TransferService` exist at
  those names; `android:exported` is set on the one activity that has intent
  filters; the FileProvider authority `${applicationId}.fileprovider` matches
  `"${context.packageName}.fileprovider"` in `ApkInstaller` and
  `PreviewScreen.playableUri` (including the debug `.debug` suffix); every
  permission the code requests is declared (INTERNET, ACCESS_NETWORK_STATE,
  ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE, FOREGROUND_SERVICE +
  FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS, CAMERA,
  REQUEST_INSTALL_PACKAGES, WRITE_EXTERNAL_STORAGE ≤ 28); the API-34 rule is
  satisfied on both sides — `foregroundServiceType="dataSync"` in the manifest
  **and** `FOREGROUND_SERVICE_TYPE_DATA_SYNC` in `ServiceCompat.startForeground`;
  `file_paths.xml` covers exactly the four directories `AppPaths` writes to.
- **Material icons**: only `Close`, `KeyboardArrowRight`, `Refresh`, `Settings`,
  `Warning` are used — all five are in `material-icons-core` (no
  `material-icons-extended` dependency needed).

---

## 3. Where I am least confident (triage these first if CI fails again)

1. **`Modifier.focusGroup()`** — stable since Compose Foundation 1.4 as far as I
   know, but this is the API I'd re-check first. If it still needs an opt-in in
   1.7.6, the new module-wide `-opt-in=…ExperimentalFoundationApi` already
   covers it; if the *error* is "unresolved reference", it moved package.
   (`androidx.compose.foundation.focusGroup`, used in 11 files.)
2. **`androidx.lifecycle.compose.LocalLifecycleOwner`** in
   `ui/screens/QrScanner.kt` — this is the lifecycle-2.8 home for it. If CI says
   unresolved, switch to `androidx.compose.ui.platform.LocalLifecycleOwner`
   (deprecated in newer compose-ui but present in 1.7.6). One-line change.
3. **Coil 2.7 package paths** in `SendroApplication` —
   `coil.ImageLoaderFactory`, `coil.decode.VideoFrameDecoder.Factory` (from
   `coil-video`), `ImageLoader.Builder.respectCacheHeaders`. If `coil-video`'s
   decoder moved, the fix is to drop `.components { add(...) }` entirely; video
   thumbnails degrade to the hatched badge and nothing else breaks.
4. **`androidx.security:security-crypto:1.1.0-alpha06`** — `SecurePrefs` uses
   the 5-argument `EncryptedSharedPreferences.create(context, name, MasterKey,
   keyScheme, valueScheme)` overload, which is the alpha API (the 1.0.0 API took
   a `String` master-key alias first). If it fails, it fails in exactly one file
   and the fallback path already exists.
5. **`NsdServiceInfo.hostAddresses`** (API 34) in `Discovery.pickIpv4` — guarded
   by an SDK_INT check, compiled against compileSdk 35, so it should resolve;
   `resolveService` is deprecated-not-removed and carries `@Suppress`.
6. **`ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)`**
   in `ui/theme/DeviceProfile.kt` — the `Context` overload (activity 1.9.x). The
   no-arg one is deprecated; if the call is ambiguous, keep the `Context` form.
7. **`AndroidView(..., onRelease = …)`** in `PreviewScreen`/`QrScanner`, and
   `ServiceCompat.startForeground(this, id, notification, type)` — both are
   long-standing signatures, but they are the kind of thing that shifts.
8. **Lint, not compilation**: `MediaSaver.beginSave` calls `@RequiresApi(Q)`
   helpers behind a plain `SDK_INT` guard, and `Notifier.post` posts behind
   `canPost()`. `abortOnError = false` is set, so lint cannot gate the build —
   but a NewApi warning there is expected, not a regression.

## 4. Deliberately left alone

- **Unused catalog entries** (`androidx-navigation-compose`, `ksp` version) —
  valid coordinates, never resolved, removing them is churn with no upside.
- **`kotlinOptions { }`** instead of the newer `compilerOptions { }` DSL — the
  old block still works in AGP 8.7 and is the shape every AGP-8 example uses;
  swapping it in an already-broken build would add a variable.
- **`java.util.Base64` fully qualified inside `core/Crypto.kt`** — that is a
  normal Kotlin source file, not a build script, so `java.util` resolves
  correctly there. The Base64Url object stays on the JDK implementation
  deliberately so the pairing tests run without Robolectric.
- **`PlaceholderTile`, `LibraryFooterActions`, `canOpen`, `openExternally`,
  `Sendro.cardShape/rowShape/pillShape`, `SendTray.Item.id`** and similar
  currently-unreferenced declarations — unused-symbol warnings only.
