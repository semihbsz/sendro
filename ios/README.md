# Sendro for iPhone — build notes & deliberate trade-offs

Everything here exists to keep the app installable with **free personal-team
signing** (a plain Apple ID + Sideloadly/AltStore). Anything that would need a
paid Apple Developer Program membership is called out below with what you'd
gain by paying for it.

## Targets

| target | type | deployment target | why |
|---|---|---|---|
| `Sendro` | application | iOS 16.0 | the app |
| `SendroActivity` | app extension (WidgetKit) | iOS 16.1 | Live Activity only |

`Shared/SendroActivityAttributes.swift` is compiled into **both** targets —
ActivityKit requires the `ActivityAttributes` type to be identical on each
side. That is plain shared source: no App Group, no entitlement.

An embedded extension may require a newer OS than its container app, so the
16.1 floor on `SendroActivity` does not raise the app's own 16.0 floor. On
iOS 16.0 the extension simply never runs, and every ActivityKit call in the
app is behind `#available(iOS 16.1, *)`.

Generate and build exactly like before:

```sh
cd ios
xcodegen generate
xcodebuild -project Sendro.xcodeproj -scheme Sendro -configuration Release \
  -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
```

### If you change the bundle id

An app extension's bundle id **must** be prefixed by the containing app's.
`com.sendro.ios` → `com.sendro.ios.SendroActivity`. Sideloading tools that
rewrite the app's bundle id normally rewrite embedded extension ids to match;
if a sideload ever fails with a "bundle identifier mismatch" or provisioning
error mentioning `SendroActivity`, delete the `SendroActivity` target (and the
`dependencies:` entry in `project.yml`) and everything else keeps working —
the app already behaves identically when no Live Activity is available.

## Why there is no Share Extension

The user wants Sendro in the iOS share sheet. There are two ways to get there:

1. **Share Extension** (`.appex` with `NSExtensionPointIdentifier =
   com.apple.share-services`). It runs in its own process and has to hand the
   picked files to the app through an **App Group** container
   (`group.com.sendro…`). App Groups are a *paid-account* entitlement — they
   are not available to free personal-team signing. Adding one breaks the
   sideload for anyone without a paid membership.

2. **Document types** (`CFBundleDocumentTypes` in `Sendro/Info.plist`) — what
   this app does. Declaring `public.image` / `public.movie` / `public.item`
   puts Sendro in the share sheet's **"Copy to Sendro"** / Open-In list with
   **zero entitlements**. iOS copies the file into `Documents/Inbox/` and
   calls the app's URL handler; `SendTray` moves it into the send staging
   area and the Send tab shows it queued and ready. Multiple files arrive as
   several `openURL` calls in a row and all of them queue.

Trade-off: option 2 switches to the app instead of showing a compact sheet
UI, and it lands under "Copy to…" rather than at the top of the share row.
**With a paid Apple Developer account** you can add the App Group + Share
Extension and get the richer in-sheet flow; nothing else in the app would
have to change.

`LSHandlerRank` is `Default` for images/movies and `Alternate` for
`public.item` — deliberately not `Owner`, which would tell iOS that Sendro
*owns* those formats and let it outrank Photos/Files as the default app for
them. Sendro is a transport, not a viewer of record.

## Other entitlement-free choices

- **Notifications**: `UNUserNotificationCenter` local notifications only — no
  push, no APNs entitlement, no server. Authorization is requested after the
  first successful pairing, not at launch. They are best-effort: iOS suspends
  a backgrounded app, and Sendro's outbox long-poll only runs while the app is
  alive, so a notification fires for things that arrive while Sendro is
  running or briefly backgrounded. The UI never promises more.
- **Live Activities**: ActivityKit ships state to the widget itself, so no App
  Group is needed. Started only for transfers ≥ 200 MB, updated ≤ 1×/s.
- **QR pairing** (PROTOCOL.md §13): `AVCaptureMetadataOutput` in-app, plus the
  `sendro` URL scheme so the system Camera app can open the same flow. A
  `sendro://pair` URL is only ever accepted from the in-app scanner or from an
  OS URL open, and always shows a confirmation screen naming the PC first.
- **No third-party dependencies**, anywhere.

## Hotspot / no-router use

The transfer path is LAN-only and never needs internet, so a hotspot is a
perfectly good network:

1. **PC's Mobile Hotspot**, iPhone joins it — discovery normally works.
2. **iPhone Personal Hotspot**, PC joins it — Bonjour is unreliable here, so
   use **Scan QR** or **Connect by IP** with the address the Sendro window on
   the PC shows (usually `172.20.10.x`).

`NetworkWatcher` (one shared `NWPathMonitor`) publishes a change token on every
meaningful path change; the app restarts discovery and re-pings every paired
host, so a manually entered or QR-scanned address is re-probed after a network
switch instead of being cached as dead.
