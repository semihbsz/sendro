# Windows Setup

This guide takes you from a clean Windows 10/11 machine to a running Sendro desktop app — first in development mode, then as a proper installed app.

## 1. Prerequisites

Install these once, in this order.

### Rust toolchain

Sendro's engine (`core/`) and the Tauri shell (`desktop/src-tauri/`) are Rust.

1. Download `rustup-init.exe` from <https://rustup.rs> and run it.
2. Accept the default installation (stable toolchain, MSVC target).
3. Open a **new** terminal and verify:

```powershell
rustc --version
cargo --version
```

### Visual Studio Build Tools (C++ linker)

Rust on Windows needs the MSVC linker. If `rustup-init` didn't already prompt you to install it:

1. Download **Build Tools for Visual Studio** from <https://visualstudio.microsoft.com/downloads/> (under "Tools for Visual Studio").
2. In the installer, check the **"Desktop development with C++"** workload. The defaults inside it are fine.
3. Install (this is a few GB) and reboot if asked.

### Node.js LTS

The desktop UI is React/Vite, driven by npm.

1. Download the **LTS** installer from <https://nodejs.org> and run it with defaults.
2. Verify in a new terminal:

```powershell
node --version
npm --version
```

### WebView2 runtime

Tauri renders its UI in Microsoft's WebView2. **Windows 11 ships with it preinstalled — you almost certainly have it already.** On Windows 10, if the app window comes up blank or fails to launch, install the "Evergreen Runtime" from <https://developer.microsoft.com/microsoft-edge/webview2/>.

## 2. Clone and install

```powershell
git clone https://github.com/<you>/sendro.git
cd sendro\desktop
npm install
```

`npm install` pulls the frontend dependencies and the Tauri CLI. The Rust dependencies are fetched automatically on first build.

## 3. Run in development mode

From `sendro\desktop`:

```powershell
npm run tauri dev
```

First run compiles the entire Rust dependency tree — expect **5–15 minutes**. Subsequent runs are fast (seconds to rebuild your changes). The Sendro window opens with hot-reload for the React frontend.

### The firewall prompt — do not click through this blindly

The first time Sendro's server starts, Windows Defender Firewall shows:

> *Windows Defender Firewall has blocked some features of this app*

with checkboxes for **Private networks** and **Public networks**.

- **Check "Private networks" and click "Allow access."** This is the single most important click in the whole setup. Sendro is a server; if the firewall blocks it, your iPhone can connect to nothing and discovery silently finds nothing.
- You do not need (or want) "Public networks" — Sendro is for your home/studio Wi-Fi only.
- If you accidentally clicked **Cancel**: open **Windows Security → Firewall & network protection → Allow an app through firewall → Change settings**, find Sendro (or add it via **Allow another app…**), and tick **Private**.

**Also make sure your Wi-Fi network itself is set to "Private" profile:** **Settings → Network & internet → Wi-Fi → (your network) → Network profile type → Private**. Windows treats "Public" networks as hostile and blocks inbound connections regardless of the app rule. This is the #1 "iPhone can't find my PC" cause after router isolation — see [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## 4. Build the installer

From `sendro\desktop`:

```powershell
npm run tauri build
```

This produces a release build and a Windows installer under:

```
desktop\src-tauri\target\release\bundle\
```

(an `.msi` and/or NSIS `.exe` installer, depending on the bundle config). Run the installer; Sendro appears in the Start menu like any other app. The CI workflow `.github/workflows/windows-build.yml` runs the same command on GitHub's Windows runners if you'd rather download an installer artifact than build locally.

> Note: the installer is not code-signed (no paid certificate), so SmartScreen may show "Windows protected your PC" — click **More info → Run anyway**. It's your own build.

## 5. Where Sendro keeps its data

Settings and state live in your roaming app-data folder:

```
%APPDATA%\Sendro\
├── settings.json          device name, port, receive folder, tray options
├── trusted_devices.json   paired devices (token *hashes* only — see SECURITY.md)
└── history.json           transfer history
```

Paste `%APPDATA%\Sendro` into the Explorer address bar to jump there. Deleting this folder factory-resets Sendro (you'll need to re-pair your iPhone).

Files uploaded **from** the iPhone land in the **receive folder** configured in Settings (default: your Downloads folder).

## 6. Choosing the port

Sendro listens on TCP **48800** by default. If 48800 is taken it automatically tries 48801–48820 and advertises whichever it got — you normally never think about this.

You only need to change it (Settings → Advanced → Port) if some other software owns that range or a network policy demands a specific port. The currently active **IP addresses and port** are always displayed in Settings — that's exactly what you type into the iPhone's Manual Connect screen if discovery ever fails.

## 7. Tray behavior

- Closing the Sendro window **minimizes to the system tray** (bottom-right, near the clock) rather than quitting — the server keeps running so your phone stays connected. This is controlled by **Settings → Minimize to tray**.
- The tray icon's right-click menu offers: **Open Sendro**, **Pause transfers** (a global gate — offers stay queued, nothing moves until resumed), and **Quit** (actually stops the server).
- **Settings → Launch on startup** makes Sendro start with Windows, sitting in the tray. Recommended if you use watch folders — exports get offered to your phone without you ever opening the window.

## 8. Watch folder walkthrough: the Premiere export case

Goal: every video you export from Premiere Pro to `D:\Exports\Instagram` gets offered to your iPhone automatically.

1. In Sendro, go to **Watch Folders → Add folder**.
2. Browse to `D:\Exports\Instagram` (create the folder first if it doesn't exist).
3. **Target device:** select your paired iPhone.
4. **Auto-send:** your choice of two modes:
   - **Off (ask first)** — when a new file appears, Sendro pops a notification: *"BMW E36 Final.mp4 detected — Send / Ignore."* Nothing leaves your PC until you click Send.
   - **On** — the file is hashed and offered immediately, flagged `autoAccept`. On the iPhone, if **Auto Accept From Trusted Devices** is also enabled in the Sendro app's settings, the download starts without you touching the phone. Both switches must be on — auto-accept is opt-in on *each* side.
5. Make sure the folder rule is **Enabled** and Sendro is running (tray is fine).

Now in Premiere: **File → Export → Media**, set the output location to `D:\Exports\Instagram`, export. Sendro waits until Premiere finishes writing the file (it ignores files still growing), hashes it, and offers it. With both auto switches on, by the time you pick up your phone the video is verifying or already in Photos — full original quality, hash-checked.

Tips:

- Point the watch folder at a **dedicated export folder**, not a general working directory — everything new in that folder gets detected.
- Premiere's temporary/partial files are not offered; only files that have finished writing and stopped changing size.

## Next step

Your PC is ready. Now build and install the iPhone app: [IOS_BUILD_AND_SIDELOAD.md](IOS_BUILD_AND_SIDELOAD.md).
