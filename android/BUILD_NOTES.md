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

---

# CI round 2 — `compileDebugKotlin` reached, 6 errors in 3 files

Round 2 got past Gradle and into the Kotlin frontend. It reported **exactly 6
errors in 3 files**, all of which were fixed before this sweep:

| # | File | Error | Cause | Fix |
|---|---|---|---|---|
| 1–3 | `core/MediaSaver.kt:525-526` | "Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type `java.io.File?`" ×2 + an argument-type mismatch at the same spot | `finalFile` / `partialFile` are **member** `val`s (`private val finalFile: File?`) and the use site was inside the `runCatching { }` lambda in `PendingSave.commit()`. A member property does not smart-cast inside a lambda. | Bind to locals in the `when` branch (`val target: File = finalFile`, `val partial: File = partialFile`) and use those. |
| 4 | `core/TransferEngine.kt:872` | "No value passed for parameter `mediaUri`" at the `is SaveResult.Files ->` call of `finishCompleted(...)` | `mediaUri` was added to `finishCompleted` later; one of the five call sites was never updated. | `mediaUri = null`. |
| 5–6 | `ui/theme/Theme.kt:476,485` | "Type `androidx.compose.runtime.State<Float>` has no method `getValue(...)`, so it cannot serve as a delegate" ×2 | Missing `import androidx.compose.runtime.getValue` for the two `val x by transition.animateFloat(...)` delegates in `PulseDot`. | Import added. |

## 1. The sweep — what was checked, and what it found

**Result: zero additional instances of any of the three classes, and zero
additional errors of the classes the compiler would hit next.** Every `.kt`
file in `app/src/main` and `app/src/test` (50 files, ~15 100 lines) was read in
full, plus a set of scripted cross-checks.

### Class A — Compose delegate imports (`getValue` / `setValue`)

Every `by` in the module was enumerated (not just the ones matching a producer
whitelist) and classified. **19 files contain a `by`; 12 of them are Compose
property delegates**, and every one of those files already imports what it
needs:

| File | delegates | `getValue` | `setValue` needed? |
|---|---|---|---|
| `ui/RootScreen.kt` | 3 `var` + 4 `collectAsStateWithLifecycle` | ✅ | ✅ present |
| `ui/components/Common.kt` | `collectIsPressedAsState`, `var focused`, `animateFloatAsState` | ✅ | ✅ present |
| `ui/theme/Theme.kt` | 2 × `transition.animateFloat` (both `val`) | ✅ **(this pass)** | not needed |
| `ui/screens/FlightScreen.kt` | 2 flow + `animateFloatAsState` (all `val`) | ✅ | not needed |
| `ui/screens/ReceiveScreen.kt` | 6 flow, 1 `var`, `transition.animateFloat` | ✅ | ✅ present |
| `ui/screens/SendScreen.kt` | 4 flow + 3 `var` | ✅ | ✅ present |
| `ui/screens/DevicesScreen.kt` | 3 flow + 9 `var` | ✅ | ✅ present |
| `ui/screens/SettingsScreen.kt` | 5 flow + 4 `var` (incl. the conditional `installError`) | ✅ | ✅ present |
| `ui/screens/LibraryScreen.kt` | 1 flow + 2 `var` | ✅ | ✅ present |
| `ui/screens/PreviewScreen.kt` | 3 × `mutableFloatStateOf`, 3 × `mutableStateOf` (all `var`) | ✅ | ✅ present |
| `ui/screens/ReceiverPairingScreen.kt` | 3 flow, `mutableLongStateOf`, 3 `var` | ✅ | ✅ present |
| `ui/screens/ReceivedActions.kt` | 2 `var` | ✅ | ✅ present |

Note that `mutableFloatStateOf` / `mutableLongStateOf` delegates use the *same*
`androidx.compose.runtime.getValue` / `setValue` operators as `State<T>`, so no
extra import is required for those.

The remaining seven `by` occurrences are not Compose delegates and need no
import: `by lazy` (`SendroApplication`, operator is in `kotlin`, auto-imported),
`by preferencesDataStore(...)` (`SettingsStore` — `ReadOnlyProperty` declares
`getValue` as a *member*), and the rest are the English word "by" inside KDoc
and UI copy.

### Class B — smart-cast failures

Every nullable **member** property in the module was enumerated (36 of them)
and every use site inspected. The MediaSaver pair was the only place where a
member property was dereferenced after a null check at all; everything else
already goes through `?.` / `?.let { }` / a local binding:

- `Discovery.listener?.let { current -> … }`, `Discovery.multicastLock?.let { lock -> … }`
- `Advertiser.listener?.let { … it … }`, `Advertiser.multicastLock?.let { lock -> … }`
- `HttpServer.serverSocket?.close()`, `ReceiverHost.server?.stop()` / `server?.isRunning == true`
- `UploadEngine.currentJob?.cancel()` (and `currentId` is only *compared*, never dereferenced)
- `UpdateChecker.job` → `val running = job; if (running?.isActive == true) running.cancel()`
- `PendingSave.abort()` → `uri?.let { }`, `partialFile?.let { }`
- `NetworkWatcher.lastSignature` — only compared, never dereferenced
- `PendingSave.commit()`'s `uri != null ->` branch — a member-`val` smart cast, but **outside** any lambda, which is exactly why the compiler accepted it while rejecting lines 525-526. That asymmetry is the evidence for the rule stated above; the branch is left as it is.

Nullable **local** vars behind Compose state delegates (`hint`, `status`,
`composerText`, `hudText`, `installError`, `justPaired`, `player`,
`sendTargetId`) can never smart-cast at all (delegated properties), and every
one of them is already read through `?.let { }` or bound to a local
(`val current = sendTargetId`, `val view = player ?: return`).

Smart casts of **local `val`s and function parameters** *do* propagate into
lambdas (they are stable values), so these are correct and were deliberately
left alone — but they are the first places to look if round 3 produces a
smart-cast diagnostic anyway:

| Site | value | used inside |
|---|---|---|
| `TransferEngine.resolveSaveChoice:426-437` | `file: File?` | `scope.launch { }` |
| `ReceiverPairingScreen:146-149` | `qrSession`, `running` | `remember(…) { }` |
| `MessageViews.MessageOverlay:77-104` | `newest` | `AnimatedVisibility` content + `onDismiss` |
| `PreviewScreen.VideoPlayer:377-453` | `uri` | `AndroidView(factory = { … })` |
| `DevicesScreen:111-113` | `initialLink` (parameter) | `LaunchedEffect { }` |
| `ReceivedActions:163-177` | `apkInfo` | `buildString { }` |

### Class C — call sites vs declarations

Every project-declared function, composable and data-class constructor was
cross-checked against every call site, by hand during the full read and with a
script that validates **both** directions the round-1 script missed:
argument *names* **and** coverage of every parameter that has no default (the
`finishCompleted` miss was the latter, which is why round 1's "zero mismatches"
was wrong).

Verified explicitly, in full:

- `finishCompleted(offer, hostId, savedTo, localName, mediaUri)` — all **five**
  call sites now pass five arguments (2 named at :846 and :871, 3 fully
  positional at :899/:901/:905).
- `MediaSaver.beginSave(displayName, declaredMimeType, useAlbum)` — 1 call site.
  `PendingSave(saver, displayName, uri, finalFile, partialFile, output)` — 2 call
  sites, both fully named.
- `HistoryStore.add(...)` — 6 call sites (TransferEngine ×4, UploadEngine ×1,
  ReceiverHost ×2), every one uses names drawn from the declared set.
- `Notifier.notifyIncomingOffers / notifyTransferFinished / notifyTransferFailed /
  notifyMessage / notifyPairingRequest / notifyPaired / buildProgressNotification`
  — every call matches.
- `TransferEngine(...)` 11 params / `UploadEngine(...)` 7 / `ReceiverHost(...)` 11
  — the `SendroApplication.onCreate` graph passes every one by name.
- `SaveResult.Gallery/Files/Failed/NeedsStoragePermission`, `PreviewRequest(...)`
  + `PreviewRequest.of(app, entry)`, `PairedHost(...)`, `DiscoveredHost(...)`,
  `Peer(...)`, `HostPairing.Session(...)`, `UploadItem(...)`, `HistoryEntry(...)`,
  `StatusReport(...)`, `PairStartRequest/PairConfirmRequest(...)`,
  `HttpRequest/HttpResponse/BodyStream(...)`, `DeviceProfile(...)`,
  `SendroSettings(...)`, `NetworkState(...)`, `ApkInfo(...)` — all clean.
- Every Composable in `components/` (`Pressable`, `AccentPill`, `GhostPill`,
  `SectionTag`, `SectionHeader`, `NoticeCard`, `FileBadge`, `StatusChip`,
  `ThinProgress`, `TopInsetSpacer`, `screenPadding`, `RequestInitialFocus`,
  `DpadKeypad`, `CodeBoxes`, `QrCode`, `QrUnavailable`) and every screen-private
  helper (`Header`, `BulkBar`, `OfferCard`, `TrayCard`, `ActionGrid`,
  `ActionCard`, `TargetPicker`, `UploadRow`, `TextComposer`, `FilterBar`,
  `LibraryRow`, `ToggleRow`, `ChoiceRow`, `DiagnosticLine`, `ValueChip`,
  `PlainField`, `StageLine`, `HotspotStep`, `InFlightBody`, `FinishedBody`,
  `ProgressRing`, `PhaseRail`, `Metric`, `UnpairPeerButton`,
  `ReceiverStatusLine`, `HistoryThumbnail`, `ReceivedActionRow`) — arity, names
  and required-parameter coverage all check out.
- All 266 `import com.sendro.android.…` statements resolve to a real declaration
  in the named package (scripted).
- Every `app.<member>` reference in the UI (19 distinct) exists on
  `SendroApplication`.

### The classes the compiler would have hit next

- **Exhaustive `when`** — all 57 subject-`when`s re-checked. Every one over a
  project enum or sealed type is exhaustive: `TransferPhase` (8 subtypes, 4
  sites), `UploadPhase` (5, 3 sites), `SaveResult` (4, 3 sites),
  `UpdateState` (7), `ReceiverHost.State` (3), `DevicesPane` (6, 2 sites),
  `PairStage` (5, 2 sites), `ReceivedAction` (5, 2 sites),
  `DownloadTask.Outcome` (3), `UploadEngine.Outcome` (3),
  `HostPairing.ConfirmResult` (4), `SaveMediaMode` (3), `ApkInstaller.Kind` (3),
  `MediaKind`/`MediaKind?` (6 sites), `Discovery.Status` (2),
  `NotificationRoute` (1), `AppSurface` (1), `SendroTab` (1),
  `LibraryFilter` (2), `Overlay` (1).
- **suspend / non-suspend** — every suspend call is inside a `suspend fun`, a
  `scope.launch { }` / `LaunchedEffect { }`, or an *inline* lambda
  (`runCatching`, `use`, `forEach`, `withPermit`, `let`) nested in one. The
  local funs declared inside composables (`stageAndQueue`, `submitIfComplete`,
  `check`, `seekBy`, `togglePlay`) are all non-suspend and only *start*
  coroutines.
- **`@Composable` from a non-composable lambda** — scripted: every use of
  `Sendro.sans/mono` and the three `@Composable` colour getters
  (`textSecondary/textTertiary/textFaint`, 239 uses) sits in a `@Composable`
  function body, in a `@Composable` default-parameter expression, or in an
  *inline* lambda (`forEach`, `let`, `repeat`, `item { }` — whose content
  parameter is itself `@Composable`). **No** `DrawScope` block uses one: every
  `Canvas` / `drawBehind` / `drawWithContent` in the module uses only the plain
  `val` colours (`Sendro.iris`, `teal`, `textBase`, `Color.White`) — which is
  also why `Modifier.sendroFocusRing` can be, and is, non-composable.
  `phaseColor`, `platformLabel`, `rowActionLabel` and `savedToLabel` are
  non-composable helpers and touch only plain `val`s.
- **`override` signatures** — re-checked against the SDK/library shapes:
  OkHttp `Callback` (`onFailure(Call, IOException)` / `onResponse(Call, Response)`),
  `RequestBody` (`contentType`/`contentLength`/`writeTo(BufferedSink)`),
  `NsdManager.DiscoveryListener` (all 6), `NsdManager.ResolveListener` (2),
  `NsdManager.RegistrationListener` (4),
  `ConnectivityManager.NetworkCallback` (`onAvailable`/`onLost`/
  `onCapabilitiesChanged`/`onUnavailable`), `Service`, `Application`,
  `coil.ImageLoaderFactory.newImageLoader`, `ComponentActivity.onCreate` /
  `onNewIntent(Intent)` (public + platform-typed parameter in
  androidx.activity 1.9.x, so the non-null override is legal),
  `DefaultLifecycleObserver.onStart/onStop(LifecycleOwner)`,
  `InputStream.read()` / `read(ByteArray, Int, Int)`.
- **Renames / unresolved own symbols** — no duplicate non-private top-level
  declaration in any package; `TAG`, `QR_MAX_BYTES`, `ICON_CANVAS`,
  `ClipDescriptionSensitive` are all `private` (file-scoped), so the several
  same-named helpers cannot clash.
- **Brace / paren / string balance** re-run with a template-and-nested-string
  aware lexer (`"${x.ifBlank { "y" }}"` defeats a naive one): all 50 files
  balanced.

## 2. The premise this sweep started from was wrong — and that is the finding

The sweep was commissioned on the assumption that "the Kotlin compiler stops
after a batch of errors, so there are almost certainly more in the other ~47
files it never got to". **That is not how it works, and it matters for
estimating round 3.**

`compileDebugKotlin` runs the K2 *frontend* (FIR) over **every** source file in
the module, collects the diagnostics from all of them, and only then aborts
before the backend if any are errors. There is no per-file early exit and no
diagnostic cap. Unresolved references, type mismatches, smart-cast failures,
missing arguments, non-exhaustive `when`, wrong `override` signatures, suspend
misuse and the Compose plugin's `@Composable`-context checks (FIR checkers in
Kotlin 2.0) are *all* frontend diagnostics.

So "6 errors in 3 files" means: **those were all the frontend errors in all 50
files.** The other 47 files were analysed and found clean by the compiler
itself, which is exactly what this by-hand sweep independently reproduced.

## 3. What is still genuinely unverified going into round 3

1. **The IR/backend phase never ran.** Frontend-clean does not prove
   codegen-clean. The realistic backend-only failure is a *platform declaration
   clash* (two declarations with the same JVM signature). Checked by hand:
   `TransferService.notificationFor(…, ActiveTransfer, …)` vs
   `(…, UploadItem, …)`, `PreviewRequest.of(SendroApplication, HistoryEntry)`
   vs `of(File)`, `SendroCrypto.sha256Hex(File)` vs `sha256Hex(InputStream, …)`,
   `PairLink.parse(String)` vs `parse(Uri)` — all four overload pairs erase to
   distinct signatures. Nothing else in the module overloads a name.
2. **`compileDebugUnitTestKotlin` has never run.** It is a *separate* task from
   `compileDebugKotlin`, so the three files under `app/src/test` have not been
   compiled even once. They were read line by line here — `HostPairing(now = { now })`
   binds the test's own `private var now`; `PairConfirmRequest(pairingId,
   deviceId, proof)` and `PairStartRequest(deviceId, deviceName)` match their
   defaults; `assertEquals(Int, Int)` / `(null, Int?)` resolve to
   `assertEquals(Object, Object)` (Kotlin does not widen `Int` to `long`, so
   the primitive overload is simply inapplicable, not ambiguous);
   `proof!!.length` then `proof.contains(…)` relies on the `!!` smart cast,
   which is fine for a local `val` — but none of that has been near a compiler.
3. **The round-1 "least confident" API list is unchanged and still the right
   triage order** if round 3 fails on an unresolved reference:
   `Modifier.focusGroup()`, `androidx.lifecycle.compose.LocalLifecycleOwner`,
   Coil 2.7 paths, `EncryptedSharedPreferences.create` (alpha 5-arg overload),
   `NsdServiceInfo.hostAddresses`, `isPhotoPickerAvailable(Context)`,
   `AndroidView(onRelease = …)`, `ServiceCompat.startForeground(…, type)`.
   Two more from this pass, both verified as correct but version-sensitive:
   `Brush.radialGradient(colorStops = arrayOf(…))` — passing an array to a
   `vararg` in *named* form is legal (and is the recommended form) — and the
   `Icons.Filled` set, still only the five that ship in `material-icons-core`.
4. **Nothing after Kotlin has run either**: `dexBuilderDebug` / `mergeDex`,
   packaging, and `lint` (which cannot gate — `abortOnError = false`).

## 4. Changed in this pass

Nothing beyond the six fixes listed at the top. No speculative hardening was
applied: rewriting the six correct local-`val`-smart-cast-into-a-lambda sites
listed in §1 would be churn on code the compiler has already accepted, and
churn in a build that is one round from green is a way to introduce a seventh
error.
