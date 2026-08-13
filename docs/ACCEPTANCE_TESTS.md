# Acceptance Tests

Five manual end-to-end tests that define "Sendro works." Run all five before any release that touches transfer, storage, or protocol code (see the [release checklist](DEV_WORKFLOW.md#release-checklist)). Each is a step-by-step script with expected results; log outcomes in the [results table](#results-log) at the bottom.

## Common setup (all tests)

- Windows PC with the release build installed (not dev mode), firewall allowed on Private network, network profile = Private.
- iPhone with the matching IPA sideloaded, signature valid (re-sign if near day 7), Local Network permission **on**, Photos permission **Add Photos Only**.
- Both devices on the same Wi-Fi, 5 GHz preferred, no VPNs.
- Devices paired. If not: pair once via the 6-digit code first.
- Useful during tests: PC-side hash of any file via `certutil -hashfile "<path>" SHA256` in a terminal.

---

## Test 1 — Offline router: JPG transfer with zero internet

**Purpose:** prove transfers are fully local — no cloud dependency of any kind.

**Steps:**

1. Disconnect your router's internet: unplug the WAN/modem cable (or disable the WAN interface in the router admin). Confirm both devices show "no internet" (PC: globe icon with warning; iPhone: web pages fail) while **still connected to the Wi-Fi**.
2. Launch Sendro on the PC (or confirm it's running in the tray).
3. Open Sendro on the iPhone. **Expected:** the PC appears in the device list within a few seconds (Bonjour needs no internet).
4. On the PC, send a JPG photo (a few MB, ideally one with a non-ASCII name, e.g. `Çekmeköy test 1.jpg`) to the iPhone.
5. On the iPhone, accept the offer.
6. Wait for completion; open the Photos app.

**Expected results:**

- Discovery, pairing check, offer, download, verification, and Photos import all succeed with the internet physically absent.
- iPhone shows the transfer reaching `verified` then `completed`; PC history shows `Completed`, verified ✓.
- The photo is in the Photos library, visually identical, with the filename preserved (check via the Files fallback or the transfer detail if needed).
- **Bonus check:** hash the original on the PC with `certutil`; the value must equal the `sha256` shown in the transfer detail.
- Reconnect the WAN afterwards.

**Fail if:** discovery requires internet, any step errors with the router offline, or the image was recompressed (byte count differs).

---

## Test 2 — 5+ GB MOV: large-file streaming

**Purpose:** prove big video files stream without memory blowups, size limits, or timeout failures.

**Steps:**

1. Pick or create a MOV **larger than 5 GB** (a long 4K clip; concatenating exports works too). Record its exact size in bytes (right-click → Properties) and its `certutil` SHA-256.
2. Ensure the iPhone has at least ~2× the file size free (Settings → General → iPhone Storage).
3. Disable PC sleep for the duration (Settings → System → Power & battery → sleep "Never" while plugged in).
4. Send the MOV from the PC to the iPhone; accept on the phone; keep Sendro foregrounded with the screen on.
5. Observe progress on both sides (speed, ETA); wait through `downloading → verifying → saving → completed`.
6. In Photos, confirm the video plays, scrub through it, and check its duration matches the original.

**Expected results:**

- Transfer completes at a rate consistent with your Wi-Fi (see [speed table](TROUBLESHOOTING.md#speed-lower-than-expected)); progress advances smoothly — no stall at high percentages, no app crash, no memory-pressure kill on the phone.
- The `verifying` phase completes and reports `verified` — meaning the phone streamed a SHA-256 over all 5+ GB and it matched.
- Byte count on the received file equals the original exactly; video plays end to end.
- PC memory usage stays flat during the transfer (Task Manager) — streaming, not buffering the file in RAM.

**Fail if:** the transfer caps out or errors near 4 GB (32-bit length bug), verification is skipped, memory balloons, or the saved video is shorter/corrupt.

---

## Test 3 — Mixed batch: JPG / HEIC / MOV / MP4 / PDF / ZIP / PSD

**Purpose:** prove correct routing (Photos vs Files), MIME handling, and per-file integrity in a multi-file batch.

**Steps:**

1. Assemble a folder with at least one of each: `.jpg`, `.heic`, `.mov`, `.mp4`, `.pdf`, `.zip`, `.psd`. Include at least one filename with spaces and non-ASCII characters (e.g. `final gerçekten final 5.mp4`). Record `certutil` hashes for the PDF and ZIP.
2. On the PC, select **all seven files** (or the folder) and send them to the iPhone as one batch.
3. On the iPhone, accept the offers.
4. Let the whole batch complete (default concurrency processes them a couple at a time).
5. Verify destinations:
   - **Photos app:** JPG, HEIC, MOV, MP4 present and opening correctly.
   - **Files app (Sendro folder):** PDF, ZIP, PSD present.
6. Open the PDF in Files; AirDrop-free check the ZIP by opening it in Files (iOS extracts natively); confirm the PSD's byte size matches the original.

**Expected results:**

- All 7 transfers complete and verify individually; PC history shows 7 × `Completed`, all verified ✓, sharing one batch.
- Media (JPG/HEIC/MOV/MP4) lands in Photos; documents (PDF/ZIP/PSD) land in Files — nothing misrouted, nothing "converted" (HEIC stays HEIC).
- Filenames preserved exactly, including Unicode, case, and spaces (check in Files where names are visible).
- ZIP extracts without CRC errors on the phone (a strong practical integrity check on top of SHA-256).
- One file failing (hypothetically) would not abort the others — per-file independence.

**Fail if:** any file is misrouted, renamed beyond a legitimate ` (n)` duplicate suffix, mangled (Unicode broken), recompressed, or a single failure kills the batch.

---

## Test 4 — Mid-transfer interruption: resume + hash verify

**Purpose:** prove HTTP Range resume works and that verification always covers the *whole* file, including the pre-interruption bytes.

**Steps:**

1. Pick a file large enough to give you a comfortable interruption window (≥ 2 GB recommended). Record its `certutil` SHA-256.
2. Start the transfer; accept on the iPhone.
3. At roughly **40–60% progress**, kill the connection abruptly. Any one of (test at least the first; ideally repeat with another):
   - Toggle Wi-Fi **off** on the iPhone (Settings → Wi-Fi, not just Control Center, which may keep the radio semi-alive).
   - Pull the PC's Ethernet cable / disable its Wi-Fi.
4. Observe both sides for ~30 seconds. **Expected:** iPhone shows the transfer paused/retrying; PC shows the transfer as `Interrupted` — not `Failed` — and keeps the offer alive.
5. Restore the network. Reopen/foreground Sendro on the iPhone if needed.
6. **Expected:** download resumes **from where it stopped** — watch the progress bar continue from ~50%, not restart at 0 (also verifiable from bytes-received counters).
7. Let it complete through `verifying` to `completed`.
8. Compare the transfer's reported SHA-256 to your recorded `certutil` hash.

**Expected results:**

- Resume, not restart: total bytes downloaded across both segments ≈ file size (allowing a small re-request overlap), and elapsed time reflects a resume.
- Final hash matches the original exactly — proving the verify pass covered pre- and post-interruption bytes as one contiguous correct file.
- No duplicate or partial file appears in Photos/Files; exactly one good copy after completion.
- **Edge case worth one run:** modify the source file on the PC *during* the interruption (append a byte), then let it resume. **Expected:** the `If-Range` guard rejects the stale resume; the transfer restarts or fails cleanly with an integrity-safe outcome — under no circumstances does a Frankenstein file get saved as verified.

**Fail if:** progress restarts from zero (no Range support), the file completes but verification is skipped, or a modified-during-interruption source ever yields a "verified" save.

---

## Test 5 — Watch folder auto-send, end to end

**Purpose:** prove the hands-off flow: file appears in the export folder → arrives verified in Photos with no manual step on either device.

**Steps:**

1. On the PC: **Watch Folders → Add folder** → `D:\Exports\Instagram` (create it if needed) → target device: your iPhone → **Auto-send: On** → Enabled.
2. On the iPhone: Sendro settings → **Auto Accept From Trusted Devices → On**.
3. Close the Sendro window on the PC so it's running **from the tray only** (this validates the background path).
4. Lock the iPhone or leave Sendro backgrounded — then, to be fair to iOS's background limits, unlock and open Sendro after step 5. (Foreground guarantees delivery; note in the log whether background pickup also worked.)
5. Simulate a Premiere export: copy a test MP4 (≥ 100 MB, so you can watch progress) **into** `D:\Exports\Instagram`. For realism, copy it in via a slow mechanism or an actual Premiere export, so the file grows over time.
6. Watch the PC side (tray → open window if needed): the file should appear as detected only **after it finishes being written**, then move `Hashing → Offered` with the auto-accept flag.
7. On the iPhone with Sendro open: **Expected:** the transfer starts **without you tapping Accept**.
8. Wait for `completed`; confirm the video is in Photos and plays.
9. Negative check A: drop a second file in with the iPhone's **Auto Accept OFF** → the offer must arrive but **wait for a manual Accept**.
10. Negative check B: disable the watch-folder rule on the PC, drop in a third file → **nothing** should be detected or offered.

**Expected results:**

- The primary flow completes with zero manual interaction on either device (given Sendro foregrounded on the phone): file lands in the export folder → verified in Photos.
- Partial/growing files are never offered mid-write; the hash is computed after the file is complete, and the received bytes verify against it.
- Auto-accept is honored only when **both** the PC-side rule flag and the iPhone-side setting are on (negative check A proves the phone-side gate).
- Disabled rules are truly inert (negative check B).

**Fail if:** a growing file is offered and fails verification, auto-accept fires with the phone-side setting off, or the tray-only PC misses new files.

---

## Results log

Copy this table into the release notes / a `test-results/` entry per run.

| # | Test | Build (Win / iOS) | Date | Tester | Network (band, router) | Result | Time / speed | Notes & anomalies |
|---|------|-------------------|------|--------|------------------------|--------|--------------|-------------------|
| 1 | Offline router JPG | v0.x.y / v0.x.y | | | | ☐ Pass ☐ Fail | | |
| 2 | 5+ GB MOV | | | | | ☐ Pass ☐ Fail | | |
| 3 | Mixed batch ×7 | | | | | ☐ Pass ☐ Fail | | |
| 4 | Interrupt + resume | | | | | ☐ Pass ☐ Fail | | |
| 4b | If-Range edge case | | | | | ☐ Pass ☐ Fail | | |
| 5 | Watch folder auto-send | | | | | ☐ Pass ☐ Fail | | |
| 5b | Auto-accept gate (neg.) | | | | | ☐ Pass ☐ Fail | | |

**A release ships only when tests 1–5 all pass on the actual release artifacts** (installed Windows build + sideloaded IPA), not dev builds.
