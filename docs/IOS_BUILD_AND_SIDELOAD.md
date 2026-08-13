# iOS: Build and Sideload — No Mac Required

This is the critical path for Windows-only users. You will:

1. Let **GitHub's macOS runners** build the app (you never touch a Mac).
2. Download the unsigned IPA to your Windows PC.
3. Sign and install it onto your iPhone from Windows with a **free Apple ID**.

Read the [Free Apple ID limits](#free-apple-id-limits--the-honest-part) section before you start so nothing surprises you — the headline is: **the app must be re-signed every 7 days** (a 2-minute USB operation), and that's the price of not paying Apple $99/year.

One reassuring note up front: **transfers never need the internet.** Only two things do — GitHub (to build the IPA) and Apple's servers (contacted once during each signing). Day-to-day Sendro use is 100% local.

---

## Step 1 — Push the repo to GitHub

If you haven't already:

```powershell
cd sendro
git remote add origin https://github.com/<you>/sendro.git
git push -u origin main
```

A **private** repository is fine — Actions works on private repos (free-tier minutes apply; macOS minutes are billed at a multiplier but the iOS build fits comfortably in the free allowance for personal use).

## Step 2 — Let GitHub Actions build the IPA

The workflow `.github/workflows/ios-build.yml` runs automatically on push (and can be run manually). On a macOS runner it:

1. Runs **XcodeGen** against `ios/project.yml` to generate the Xcode project (the project file itself is never committed).
2. Runs `xcodebuild` with code signing **disabled** (`CODE_SIGNING_ALLOWED=NO`) to produce `Sendro.app`.
3. Zips it into IPA layout (`Payload/Sendro.app` → `Sendro-unsigned.ipa`) and uploads it as a build **artifact**.

To watch it: your repo on github.com → **Actions** tab → the latest **iOS Build** run. Green check ≈ 3–6 minutes. To trigger manually: **Actions → iOS Build → Run workflow**.

## Step 3 — Download the artifact on Windows

1. Open the completed workflow run.
2. Scroll to the **Artifacts** section at the bottom.
3. Click **Sendro-unsigned** to download a zip; extract it to get `Sendro-unsigned.ipa`.

This IPA is *unsigned* — an iPhone will refuse to run it as-is. Signing happens on your PC in the next step.

## Step 4 — Sign and install from Windows

Two well-established tools do this. **Sideloadly is the recommended path** — fewer moving parts. AltStore is a solid alternative. Both use your free Apple ID to obtain a personal development certificate from Apple and sign the IPA on the fly. (UI details below are accurate as of 2026; both tools evolve, but the flow has been stable for years.)

### Option A — Sideloadly (recommended)

1. Download Sideloadly from <https://sideloadly.io> and install it on Windows.
2. Connect your **iPhone to the PC via USB** (Lightning/USB-C cable). Tap **Trust** on the phone if prompted, and enter your passcode.
3. In Sideloadly:
   - Your iPhone should appear in the device dropdown.
   - Drag `Sendro-unsigned.ipa` into the window (or click the IPA icon to browse).
   - Enter your **Apple ID email** under "Apple account".
4. Click **Start**. Enter your Apple ID password when asked. If your account has two-factor authentication (it should), approve the prompt / enter the 6-digit code on a trusted device.
5. Sideloadly fetches a free development certificate from Apple, signs the IPA, and installs it. About 1–2 minutes. Done when it says the install succeeded and the Sendro icon appears on your home screen.

**Bundle ID tip:** by default Sideloadly appends your Apple ID to the app's bundle identifier (e.g. `com.sendro.app.XXXXXX`). This is fine — leave it. It avoids collisions with other people's App IDs and doesn't affect anything Sendro does.

**Password note:** your Apple ID credentials go to Apple's servers to obtain the certificate (that's the one moment signing needs internet). If you're uncomfortable typing your main password into a third-party tool, create an [app-specific password](https://support.apple.com/102654) at appleid.apple.com and use that in Sideloadly.

### Option B — AltStore

1. Download **AltServer for Windows** from <https://altstore.io> and install it. AltServer runs in your Windows tray.
2. AltStore's classic Windows setup requires **iTunes and iCloud installed from Apple's website** (not the Microsoft Store versions) — AltServer uses them to talk to the device. This requirement is the main reason Sideloadly is recommended instead; check altstore.io for current requirements as of your install date.
3. Connect the iPhone via USB → tray icon → **Install AltStore → (your device)** → enter your Apple ID.
4. On the phone, open AltStore and install `Sendro-unsigned.ipa` via **My Apps → + ** (get the IPA onto the phone via AltStore's own mechanisms, or use AltServer's tray "Sideload .ipa" option).
5. AltStore can auto-refresh the 7-day signature in the background whenever the phone is on the same Wi-Fi as a running AltServer — its one genuine advantage.

### Step 5 — Trust the developer certificate (on the iPhone)

First launch will fail with *"Untrusted Developer"* until you do this:

1. **Settings → General → VPN & Device Management** (on some iOS versions just "Device Management").
2. Under **Developer App**, tap the entry with your Apple ID email.
3. Tap **Trust "…"**, then **Trust** again in the dialog.

### Step 6 — Enable Developer Mode (iOS 16 and later)

Free-certificate apps count as developer apps, so iOS requires Developer Mode:

1. **Settings → Privacy & Security → Developer Mode** → toggle **On**. (If the toggle isn't visible, launch the Sendro icon once to make iOS surface it, or re-plug USB.)
2. iOS asks to **restart** the phone — accept.
3. After reboot, confirm **Turn On** and enter your passcode.

### Step 7 — First launch permissions

Open Sendro on the iPhone. Two permission prompts matter — **allow both**:

1. **"Sendro would like to find and connect to devices on your local network."** → **Allow.** Without this, the app cannot see your PC at all (no Bonjour, no HTTP). If you mistakenly denied it: **Settings → Privacy & Security → Local Network → Sendro → on**.
2. **Photos access ("Add Photos Only")** → **Allow.** Sendro only requests *add* access — it can save incoming photos/videos into your library but cannot read or browse your existing photos. If denied, incoming media falls back to the Files app; fix later in **Settings → Privacy & Security → Photos → Sendro → Add Photos Only**.

Now pair: open Sendro on both devices, your PC appears in the iPhone's device list, tap it, and type the 6-digit code shown on the Windows screen. You're done — send something.

---

## Free Apple ID limits — the honest part

Sideloading with a free Apple ID is genuinely free, and genuinely limited. Know exactly what you're signing up for:

| Limit | Free Apple ID | Paid ($99/yr) Developer account |
|---|---|---|
| App signature validity | **7 days** | 1 year |
| Sideloaded apps installed at once | **3** | Effectively unlimited |
| App IDs registered | **10 per 7-day window** | 100+ |
| Push notifications | **No** | Yes |

What the 7-day limit means in practice:

- **On day 8 the app refuses to launch** (iOS shows the icon greyed/"unavailable" or an "app is no longer available" style message). It doesn't uninstall itself.
- **Your data survives.** Pairing, settings, and anything already saved to Photos/Files are untouched. Re-signing restores the app exactly as it was — no re-pairing needed.
- **Re-signing takes about 2 minutes:** plug in USB, open Sideloadly, same IPA (or a newer one), Start. You can re-sign at any time — you don't have to wait for expiry. A weekly calendar reminder ("re-sign Sendro, 2 min") makes this painless.
- **USB is the reliable path** for install and re-sign. Sideloadly has a Wi-Fi re-sign option and AltStore can refresh over Wi-Fi, but both have more failure modes; when in doubt, use the cable.
- The **3-app / 10-App-ID** limits only matter if you sideload other apps too. The bundle-ID auto-suffix means Sendro consumes one App ID; re-signing the same bundle ID does not consume new ones.

If the weekly ritual ever gets old, a paid Apple Developer account ($99/year) lifts every limit above — same Sideloadly flow, 1-year signatures.

## Updating the app

New Sendro version = new push → CI builds a new IPA → download → Sideloadly over the top of the existing install (same bundle ID). Data persists. The full loop is described in [DEV_WORKFLOW.md](DEV_WORKFLOW.md).

## Internet requirements, summarized

| Activity | Internet needed? |
|---|---|
| Building the IPA (GitHub Actions) | Yes (GitHub) |
| Signing/installing/re-signing (Sideloadly/AltStore) | Yes (Apple's servers) |
| Pairing, discovery, transfers — all actual Sendro use | **No. LAN only.** |
