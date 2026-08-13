# Troubleshooting

Organized by symptom. Work top to bottom within a section — the causes are ordered by how often they're the culprit.

---

## iPhone doesn't see the PC

### 1. Are both devices actually on the same network?

Same Wi-Fi name isn't always the same network:

- Phone on **cellular** with Wi-Fi Assist, or Wi-Fi toggled off — check the Wi-Fi icon is solid in Control Center.
- PC on **Ethernet**, phone on **Wi-Fi guest network** — guest networks are separate networks.
- Mesh systems or extenders sometimes put devices on different subnets. Compare addresses: PC → `ipconfig` in a terminal (look at IPv4 Address); iPhone → Settings → Wi-Fi → tap the ⓘ next to your network. `192.168.1.x` and `192.168.1.y` = same subnet, good. `192.168.1.x` vs `192.168.4.y` = different subnets, discovery will fail.
- A **VPN active on either device** routes or blocks local traffic. Turn VPNs off on both while using Sendro.

### 2. Router AP/client isolation — the #1 cause on "everything looks right" setups

Many routers (and nearly all guest networks) have a feature called **AP Isolation**, **Client Isolation**, or **"Allow guests to access local network: off"** which forbids Wi-Fi devices from talking to each other at all. Symptoms match exactly: both devices online, internet works on both, Sendro sees nothing, even Manual Connect times out.

Fix: log into your router's admin page and disable AP/client isolation for your main network — the wording varies by vendor (look under Wireless → Advanced/Professional). And never use the guest SSID for Sendro.

### 3. Windows network profile set to "Public"

Windows blocks inbound connections on Public networks regardless of firewall rules you've allowed.

**Settings → Network & internet → Wi-Fi (or Ethernet) → (your network) → Network profile type → Private.**

### 4. Windows firewall rule missing

If you clicked Cancel on the first-run firewall prompt: **Windows Security → Firewall & network protection → Allow an app through firewall → Change settings** → find Sendro → tick **Private**. If it's not listed, **Allow another app…** and browse to the Sendro exe. See [WINDOWS_SETUP.md](WINDOWS_SETUP.md#the-firewall-prompt--do-not-click-through-this-blindly).

### 5. iPhone Local Network permission denied

If you tapped "Don't Allow" on the first-launch local network prompt, discovery *and* connections are dead:
**Settings → Privacy & Security → Local Network → Sendro → on.** If Sendro is missing from that list entirely, see the [dedicated section below](#local-network-permission-toggle-is-missing).

### 6. mDNS itself blocked → use Manual Connect

Some routers, "security" software, or corporate/hotel networks eat multicast (mDNS/Bonjour) even when direct connections work. Sendro plans for this:

1. On Windows, open Sendro **Settings** — it displays the PC's current **IP addresses and port**, e.g. `192.168.1.20:48800`.
2. On the iPhone: **Manual Connect**, type that `IP:port`.

Everything after connection (pairing, transfers, verify) is identical to the discovered path. If Manual Connect works but discovery never does, the network is filtering multicast — Manual Connect is a perfectly fine permanent workflow.

---

## Pairing code rejected

- **Typo or timeout.** The code expires after **120 seconds** and each session allows a limited number of attempts before it's burned. Cancel on the phone, start pairing fresh on the PC, type the new code carefully (it's 6 digits, shown on the *Windows* screen).
- **Too many attempts → temporarily rate-limited** (HTTP 429 under the hood). Wait a minute, start a new pairing session.
- **Clock or session confusion after network changes:** close the pairing screen on both sides and start over from the PC.
- Still failing every time with a fresh code typed correctly? Make sure the phone is talking to the *right* PC (if you run Sendro on two machines, the code on machine A won't pair against machine B). Check the device name shown on the phone matches your PC's name.

---

## Transfer stalls mid-way

- **PC went to sleep.** The most common stall. Windows sleep kills the server; the transfer shows `Interrupted` and resumes when the PC wakes. For big transfers, disable sleep first: **Settings → System → Power & battery → Screen and sleep → "When plugged in, put my device to sleep after" → Never** (restore it afterwards, or use a coffee-break profile). A laptop lid closing counts as sleep too.
- **iPhone locked/backgrounded for a long time.** iOS suspends apps aggressively. Keep Sendro in the foreground for very large files, or just reopen it — the download **resumes from where it stopped** via HTTP Range; nothing is lost and the final hash check still covers the whole file.
- **Wi-Fi roaming.** Walking between mesh nodes/access points can drop the connection for a few seconds. Sendro retries and resumes automatically; if it stays stuck, toggle the phone's Wi-Fi off/on and reopen the app.
- **Another device hogging the network** (someone streaming 4K, a backup running) slows things down but shouldn't stall. A genuinely frozen transfer with healthy network usually means the PC slept — check it first.

After any interruption: the offer stays valid on the PC (`Interrupted` state, resumable, offers expire after 24 h). Retry from either side re-uses Range resume.

---

## "Integrity check failed"

What it means: the iPhone downloaded the file, computed its SHA-256, and it **did not match** the hash the PC computed before sending. Sendro deletes the corrupt temp file rather than saving bad data — this is the feature working, not breaking.

What to do:

1. Just **retry** the transfer (PC side: Retry on the failed item). A retry is a fresh download; transient network corruption almost never repeats.
2. If the *same file* fails repeatedly:
   - Was the file **modified on the PC after it was offered**? (E.g. Premiere re-exported over it.) Re-offer the file so it gets re-hashed.
   - Rule out disk trouble on the PC: copy the file elsewhere locally and compare hashes (`certutil -hashfile "D:\Exports\file.mp4" SHA256` in a terminal, run twice — differing outputs mean failing storage).
3. If *many different files* fail: suspect flaky RAM/storage on either device or a badly misbehaving network middlebox. This is rare and worth investigating outside Sendro.

The important guarantee: an integrity failure is **never silently saved**. Anything that reached Photos/Files passed the hash check.

---

## Photos permission denied / files not appearing in Photos

- Incoming photos/videos fall back to the **Files app** (in Sendro's folder) when Photos access is missing. Nothing is lost.
- Fix: **Settings → Privacy & Security → Photos → Sendro → Add Photos Only.** ("Add Photos Only" is all Sendro ever requests — it cannot read your library.)
- Non-media files (PDF, ZIP, PSD, project files) go to **Files** by design; only formats Photos accepts (JPG/HEIC/PNG/MOV/MP4 etc.) are imported to the library.
- A file Photos refuses to import (exotic codec/format) also falls back to Files, with a note in the transfer detail.

---

## App won't open after 7 days (sideload signature expired)

Expected behavior with a free Apple ID — the personal signing certificate lasts **7 days**, then iOS refuses to launch the app (greyed icon / "no longer available" message).

- **Nothing is lost**: pairing, settings, and all transferred files remain.
- Fix: plug the iPhone into the PC via USB, open Sideloadly, re-sign the same IPA (~2 minutes). Full details and the option to escape the cycle ($99/yr paid account → 1-year signatures) in [IOS_BUILD_AND_SIDELOAD.md](IOS_BUILD_AND_SIDELOAD.md#free-apple-id-limits--the-honest-part).
- Tip: re-sign proactively — set a weekly reminder. You can re-sign on day 3 or day 6; it just restarts the 7-day clock.

---

## Local Network permission toggle is missing

Sometimes **Settings → Privacy & Security → Local Network** simply doesn't list Sendro (iOS only adds apps to the list after they trigger the prompt, and occasionally loses the plot after re-signing).

In escalating order:

1. Force-quit Sendro (swipe up, swipe the card away) and relaunch, so it re-attempts local network access.
2. Reboot the iPhone — this fixes most stuck-permission states.
3. Delete Sendro, re-install via Sideloadly, launch, and answer **Allow** to the fresh prompt. (You'll need to re-pair with the PC after a delete.)
4. Nuclear option that resets *all* apps' privacy answers: **Settings → General → Transfer or Reset iPhone → Reset → Reset Location & Privacy.**

---

## Speed lower than expected

Set expectations first — Sendro moves bytes as fast as your Wi-Fi allows, and Wi-Fi marketing numbers are not throughput:

| Setup | Realistic sustained speed |
|---|---|
| Wi-Fi 6 (802.11ax), 5 GHz, phone near router | 40–80 MB/s |
| Wi-Fi 5 (802.11ac), 5 GHz, good signal | 20–50 MB/s |
| 2.4 GHz band, any generation | 3–10 MB/s |
| Through two walls / far from router | much less |

So a 10 GB export ≈ 3–8 minutes on a good 5 GHz link. To improve:

- **Get the phone on 5 GHz (or 6 GHz)**, not 2.4 GHz. If your router broadcasts one combined SSID, standing near the router usually gets you the fast band; some routers let you split the bands into separate names.
- Prefer **PC on Ethernet** — then Wi-Fi capacity is spent only on the phone's half.
- Reduce distance/obstacles during big transfers; screen-on and app foregrounded on the phone helps iOS keep the radio at full speed.
- **There is no USB transfer mode, by design.** Sendro is a network app; Apple provides no sanctioned way for a Windows app + sideloaded iOS app to do arbitrary file transfer over the cable. The cable is only used for signing.

---

## iPhone storage full

- Sendro checks free space before and during download; if the phone can't hold the file, the transfer fails cleanly with an `insufficient_storage` error surfaced on both devices — no half-saved files.
- Note the real requirement: Sendro downloads to a temp file, verifies, then imports to Photos — so **during** import you briefly need roughly the file's size available (iOS may then duplicate into the Photos library and clean the temp). For very large videos, keep comfortably more free space than the file size (2× is safe).
- Free space, then hit Retry on the PC — the offer remains valid for 24 hours.

---

## Still stuck?

Grab the details that matter before filing an issue or digging in: Windows Sendro version + iOS Sendro version, both devices' IPs and the port (Sendro Settings on the PC), router model, and whether Manual Connect works when discovery doesn't. That combination pinpoints 90% of problems.
