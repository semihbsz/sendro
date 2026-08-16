# Sendro

**Send it. Original. Local. Private.**

Sendro moves your files from your Windows PC to your iPhone over your own Wi-Fi — byte for byte, verified with SHA-256, with zero cloud in between. No compression, no re-encoding, no upload to anyone's server. The 8 GB ProRes-adjacent MOV you exported is the exact same 8 GB MOV in your Photos library, provably.

It exists because AirDrop needs a Mac, cloud drives recompress or crawl, and cables plus iTunes are from another decade. Sendro is a small HTTP server on your PC and a small SwiftUI app on your phone, speaking a documented protocol on your LAN and nothing else.

## Who it's for

Solo videographers, photographers, and anyone on Windows who shoots or edits for a phone-first world: export from Premiere, and the file is on your iPhone — original quality, ready to post — before you've picked a caption. No Mac required at any point, including for building and installing the iPhone app.

## Features

- **Byte-for-byte fidelity** — files are streamed raw (`Content-Encoding: identity`). No transformation, ever.
- **SHA-256 verified** — the PC hashes each file before offering it; the iPhone re-hashes what it received and only reports success when the hashes match. A mismatch is surfaced, not silently ignored.
- **LAN-only, no cloud** — transfers never touch the internet. Airplane-mode-router tested (see the acceptance tests).
- **Bonjour discovery** — the PC advertises `_sendro._tcp.` via mDNS; the iPhone finds it by name. Manual IP:port connect as a fallback.
- **6-digit pairing** — a code shown on the PC, typed on the phone, proven with an HMAC so the code itself never crosses the wire. Pairing yields a per-device bearer token.
- **Resumable transfers** — HTTP Range downloads with `If-Range` hash guarding. Kill the Wi-Fi mid-transfer; it picks up where it left off and still verifies the full hash.
- **Watch folders** — point Sendro at your Premiere export folder; new files are detected and offered (or auto-sent) to your phone.
- **Photos import** — videos and images land directly in your iPhone Photos library via PhotoKit (with your permission); everything else goes to Files.
- **System tray** — Sendro lives quietly in the Windows tray, with pause/resume and quick access.
- **Reverse direction (v1-lite)** — send a file from iPhone back to a folder on the PC, hash-verified on write.

## Architecture

```
        Your Wi-Fi router (LAN only — no internet required for transfers)
                                   │
   ┌───────────────────────────────┴──────────────────────────────┐
   │                                                              │
┌──┴─────────────────────────────┐              ┌─────────────────┴─────────────┐
│  Windows PC — "Host"           │              │  iPhone — "Client"            │
│                                │              │                               │
│  ┌──────────────────────────┐  │   mDNS       │  ┌─────────────────────────┐  │
│  │ desktop/  (Tauri 2)      │  │◄─────────────┤  │ ios/  (SwiftUI)         │  │
│  │  React/Vite UI + tray    │  │  discovery   │  │  Bonjour browser        │  │
│  └───────────┬──────────────┘  │              │  │  pairing UI             │  │
│              │ Tauri commands  │   HTTP/1.1   │  │  download + resume      │  │
│  ┌───────────┴──────────────┐  │  (port 48800)│  │  SHA-256 verify         │  │
│  │ core/  (sendro-core)     │  │◄─────────────┤  │  PhotoKit import        │  │
│  │  HTTP server, mDNS ads,  │  │  pair, poll, │  └─────────────────────────┘  │
│  │  hashing, offers/outbox, │  │  range GET,  │                               │
│  │  watch folders, history  │  │  status POST │  Keychain: deviceToken        │
│  └──────────────────────────┘  │              │                               │
└────────────────────────────────┘              └───────────────────────────────┘

Flow: hash file → offer to paired device → iPhone long-polls outbox →
accept → ranged download → re-hash → verified → save to Photos/Files →
status reported back to PC.
```

The iPhone always initiates the TCP connections (it plays nicest with iOS networking), but semantically the PC *pushes*: you pick files on Windows, the phone receives offers.

## Repository layout

```
sendro/
├── core/                 Rust engine crate (sendro-core): HTTP server, mDNS,
│                         hashing, transfer state machine, watch folders.
│                         No Tauri dependency — testable standalone.
├── desktop/              Windows app: Tauri 2 shell (src-tauri/) wrapping
│                         sendro-core + a React/Vite frontend and tray.
├── ios/                  SwiftUI iPhone app + XcodeGen project.yml
│                         (the Xcode project is generated, not committed).
├── .github/workflows/    ios-build.yml  — builds an unsigned IPA on a macOS
│                                          runner (no Mac needed at home).
│                         windows-build.yml — builds the Tauri installer.
│                         release.yml    — one tag, both platforms: signed
│                                          bundles + update manifests.
├── branding/             Icon system and brand assets.
├── release/              release.json — the one place a version number and
│                         its release notes are written by hand.
├── docs/                 You are here. PROTOCOL.md is the wire-format
│                         source of truth; CORE_API.md pins the Rust seam.
└── scripts/              Helper scripts.
```

## Quickstart

1. **Windows side** — build and run the desktop app: [docs/WINDOWS_SETUP.md](docs/WINDOWS_SETUP.md)
2. **iPhone side** — build the IPA in the cloud and sideload it with no Mac and a free Apple ID: [docs/IOS_BUILD_AND_SIDELOAD.md](docs/IOS_BUILD_AND_SIDELOAD.md)
3. Pair (6-digit code), send a file, watch it verify.

When something doesn't connect: [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md). For hacking on Sendro: [docs/DEV_WORKFLOW.md](docs/DEV_WORKFLOW.md). To cut a release (and to set up update signing the first time): [docs/RELEASING.md](docs/RELEASING.md). For the honest security story: [docs/SECURITY.md](docs/SECURITY.md). To validate a build end-to-end: [docs/ACCEPTANCE_TESTS.md](docs/ACCEPTANCE_TESTS.md).

## Honest limitations

- **No TLS in v1.** Traffic is plain HTTP on your LAN. Pairing codes and tokens are protected by design (the code never crosses the wire; tokens are random and hashed at rest), but the *file bytes* are visible to a device already on your Wi-Fi. Deliberate trade-off for a personal, trusted home/studio network — read [docs/SECURITY.md](docs/SECURITY.md) before using it anywhere else. v2 plans pinned self-signed TLS.
- **Free Apple ID sideloading expires every 7 days.** The app must be re-signed (about 2 minutes with Sideloadly over USB). Your data and pairing persist. A paid Apple Developer account ($99/yr) extends this to 1 year. Details in [docs/IOS_BUILD_AND_SIDELOAD.md](docs/IOS_BUILD_AND_SIDELOAD.md).
- **Wi-Fi speed is your ceiling.** Realistically 20–60 MB/s on good Wi-Fi 5/6 at 5 GHz. A 10 GB file is minutes, not seconds. There is no USB transfer mode by design.
- **Windows → iPhone is the primary direction.** iPhone → PC exists but is deliberately minimal in v1 (single-file uploads).
- **One PC, one phone is the tested happy path.** Multiple paired devices work at the protocol level but aren't the focus.
- **No internet features at all** — no relay, no remote access, no accounts. If the two devices aren't on the same LAN, Sendro does nothing. That's the point.

## License

Sendro is provided for **personal use**. It's built for one person's PC and one person's phone on one person's Wi-Fi. See `LICENSE` for terms.
