# Security

Sendro's security posture is deliberately scoped: **strong authentication and integrity, no transport encryption in v1, designed for one person's trusted home/studio LAN.** This document says exactly what that means, what protects you, what doesn't, and what changes in v2. No hand-waving.

## Threat model

**In scope (what Sendro defends against):**

- A random device on your LAN pairing itself with your PC without your involvement.
- Anyone downloading files from your PC without having been explicitly offered them.
- Any endpoint being used to browse or read your PC's filesystem.
- Corrupted or truncated files being saved as if they were good.
- Someone who steals Sendro's data files from disk gaining reusable credentials.
- Brute-forcing the pairing code, online.

**Explicitly out of scope in v1:**

- A **passive eavesdropper already on your Wi-Fi** reading transferred file contents or metadata in flight.
- An **active man-in-the-middle on your LAN** (e.g. ARP spoofing) tampering with or capturing traffic, including capturing a bearer token in flight.
- A compromised Windows PC or iPhone (if the endpoint is owned, no transfer protocol saves you).
- Untrusted networks: cafés, hotels, offices, any shared Wi-Fi. **Do not use Sendro there.**

If your Wi-Fi is WPA2/WPA3 with a password only you know, and the devices on it are your own, the out-of-scope items require an attacker who has already breached your network — at which point they can attack far more than Sendro.

## What actually protects you

### Pairing: the code never crosses the wire

Pairing uses a 6-digit crypto-random code displayed on the Windows screen and typed on the iPhone. The phone does not send the code. Instead it derives a key and sends a proof:

```
K     = HKDF-SHA256(ikm = code, salt = per-session 16-byte salt, info = "sendro-pair-v1")
proof = HMAC-SHA256(K, pairingId + ":" + deviceId)
```

The host recomputes and compares in constant time. A network observer sees only the salt, the pairingId, and an HMAC — with 10^6 possible codes an *offline* brute-force of a captured proof is feasible in principle, which is why the *online* controls below and the 120-second session lifetime keep the window tiny, and why you should only pair on your own network. Practical upshot: nobody pairs with your PC without physically reading your monitor within a 2-minute window.

Online guessing is throttled hard: **max 5 attempts per pairing session, max 3 concurrent sessions, a failed confirm burns the session, sessions expire in 120 seconds.** The expected cost of guessing a 6-digit code at 5 attempts per human-initiated session makes it a non-strategy.

### Bearer tokens: random, and hashed at rest

Successful pairing issues the device a 32-byte random `deviceToken` (base64url). Every authenticated request carries it as `Authorization: Bearer …`.

- The Windows host stores **only `SHA-256(deviceToken)`** at rest (`trusted_devices.json`). Someone who copies your `%APPDATA%\Sendro` folder gets hashes of unguessable 256-bit values — useless for authenticating.
- The iPhone stores its token in the **iOS Keychain**, not in a file.
- Revoking a device on the PC invalidates its token immediately; the phone must re-pair.

### Per-device offer isolation

Authorization is not just "has a valid token":

- A transfer is offered **to one specific device**. Only that device's token can list it, accept it, or download its bytes. Device B cannot touch an offer made to device A.
- Transfers are addressed by **unguessable UUIDs** and expire after 24 hours.

### No filesystem browsing, ever

There is no endpoint that lists directories, stats paths, or fetches a file by path. The complete downloadable universe for a device is: the files you explicitly offered to it, while those offers are live. A stolen token's worst case is bounded by your own outbox for that device.

### Everything else

- Unauthenticated surface is minimal: `/api/v1/info` (name/version/port — the same data broadcast in mDNS TXT anyway) and the two rate-limited pairing endpoints. Everything else is 401 without a valid token.
- Integrity is end-to-end: the host hashes before offering, the client re-hashes after receiving, mismatches are deleted and reported — see `docs/PROTOCOL.md` §6.5. This defends against corruption; without TLS it is *not* a defense against a capable active attacker, who could tamper with both bytes and the advertised hash in flight (see the trade-off below).
- Uploads (iPhone → PC) are hash-verified during write and land only in the one configured receive folder, with collision-safe renaming — no path traversal via filenames (separators and reserved characters are sanitized per PROTOCOL.md §8).

## The trade-off: no TLS in v1

This is the honest headline: **v1 traffic is plain HTTP.** Concretely, a device that is *already on your Wi-Fi* and positioned to observe traffic could:

- read the bytes of files as they transfer, and filenames/metadata;
- capture a bearer token in flight and, until revoked, use it — which per the isolation above yields access to that device's pending offers, not your filesystem;
- as an *active* attacker, tamper with traffic.

Why ship that way?

1. **The target environment is a single-user, password-protected home/studio Wi-Fi.** The attacker who can exploit the gap must already be inside your WPA2/WPA3 network. For the target user, that set is empty or it's game over anyway.
2. **TLS without a CA is not a checkbox.** Self-signed certificates that apps blindly accept are security theater; doing it *right* means generating a cert and pinning it through the pairing ceremony — real design work that v1 spent on integrity, auth, and resume instead.
3. **What was non-negotiable, v1 has:** you can't be paired-with silently, tokens aren't reusable from disk, offers are isolated per device, the filesystem is unreachable, and corrupt data is never saved.

**Rules of use that follow:** treat Sendro as home/studio-only; keep your Wi-Fi password strong and WPA2/WPA3; don't run it on shared or guest networks; revoke devices you no longer use (PC → Settings → Trusted devices).

## v2 roadmap: pinned self-signed TLS

The plan, already sketched to fit the existing pairing flow:

1. Host generates a long-lived **self-signed certificate** at first run.
2. The certificate's fingerprint is **exchanged and pinned during pairing** — the pairing ceremony already provides the authenticated channel bootstrap (the HKDF key `K` can authenticate the fingerprint), so the phone learns exactly which cert is "my PC" with no CA involved.
3. All subsequent traffic is HTTPS; the client accepts **only** the pinned certificate — no trust-on-first-use ambiguity after pairing, no dialogs.

That closes the passive-observer and token-capture gaps while keeping the no-cloud, no-account, no-CA character of the app. `protocolVersion` will bump accordingly.

## Reporting

Found something wrong with this analysis or the implementation? Open a GitHub issue — or, if it's sensitive, use GitHub's private vulnerability reporting on the repository.
