# Sendro Transfer Protocol — v1

This document is the single source of truth for the Sendro wire protocol.
The Windows app (Rust, `core/`) and the iPhone app (Swift, `ios/`) both
implement exactly this. Do not deviate without bumping `protocolVersion`.

Design goals: local-network only, byte-for-byte fidelity, resumable,
authenticated, no cloud, no internet requirement at runtime.

---

## 1. Roles

- **Host** — the Windows PC. Runs the HTTP server, advertises over mDNS,
  owns outbound transfer offers ("outbox") per paired device.
- **Client** — the iPhone. Discovers hosts, pairs, long-polls its outbox,
  downloads files, verifies hashes, reports status.

The protocol is deliberately host-serves/client-pulls: the iPhone always
initiates TCP connections (plays nicest with iOS networking + backgrounding),
but semantically transfers are *pushed* by Windows via offers.

## 2. Discovery (mDNS / Bonjour)

Service type: `_sendro._tcp.` (local domain)

Instance name: the host device name (UTF-8, e.g. `Semih-PC`).

TXT records:

| key | value |
|-----|-------|
| `v`  | protocol version, currently `1` |
| `id` | host deviceId (UUID v4 string, lowercase) |
| `nm` | human device name |
| `pf` | platform: `windows` \| `ios` |

The advertised port is the HTTP API port (default **48800**, configurable;
if busy the host tries 48801..48820 and advertises the real one).

Manual fallback: the client may connect directly to `http://<ip>:<port>`
with no discovery; everything else is identical.

## 3. Transport & security model

Transport is HTTP/1.1 over the LAN. Authentication:

- Pairing establishes a per-device **deviceToken**: 32 random bytes,
  transmitted/stored base64url (43 chars, no padding).
- Every authenticated request carries `Authorization: Bearer <deviceToken>`.
- The host stores only `SHA-256(deviceToken)` at rest, plus device metadata.
- Unauthenticated endpoints: `GET /api/v1/info`, the two pairing endpoints.
  Everything else returns `401` with body `{"error":"unauthorized"}` when
  the token is missing/unknown/revoked.
- Pairing endpoints are rate-limited (max 5 attempts per pairing session,
  max 3 concurrent sessions; failed confirm burns the session).

Threat model note (documented for users): traffic is not TLS-encrypted;
this is a deliberate v1 trade-off for a personal trusted home/studio LAN.
Tokens never grant filesystem browsing — only files explicitly offered by
the host are downloadable, addressed by unguessable UUIDs, and only by the
device they were offered to.

## 4. Pairing

6-digit numeric code, displayed on Windows, typed on iPhone. Proof is an
HMAC so the code itself never crosses the wire.

### 4.1 `POST /api/v1/pair/start`  (unauthenticated)

Request:
```json
{ "deviceId": "uuid", "deviceName": "Semih's iPhone", "platform": "ios",
  "protocolVersion": 1 }
```

Host generates: `pairingId` (UUID), `code` (6 digits, crypto-random,
shown in Windows UI), `salt` (16 random bytes, base64url). Session expires
in 120 s.

Response `200`:
```json
{ "pairingId": "uuid", "salt": "base64url", "expiresInSeconds": 120 }
```

### 4.2 `POST /api/v1/pair/confirm`  (unauthenticated)

Client computes:
```
K     = HKDF-SHA256(ikm = UTF8(code), salt = salt, info = "sendro-pair-v1", len = 32)
proof = base64url( HMAC-SHA256(key = K, message = UTF8(pairingId + ":" + deviceId)) )
```

Request:
```json
{ "pairingId": "uuid", "deviceId": "uuid", "proof": "base64url" }
```

Host recomputes and constant-time-compares. On success `200`:
```json
{ "deviceToken": "base64url-32-bytes",
  "host": { "deviceId": "uuid", "deviceName": "Semih-PC", "platform": "windows" } }
```

Failures: `400` bad session/expired, `403` wrong proof (attempt counted),
`429` too many attempts. Client stores `deviceToken` in the iOS Keychain.

### 4.3 Verifying a stored pairing

`GET /api/v1/ping` (authenticated) → `200 {"ok":true,"deviceName":"Semih-PC"}`.
Used on app launch and network changes.

## 5. Info

`GET /api/v1/info` (unauthenticated):
```json
{ "app": "sendro", "protocolVersion": 1, "deviceId": "uuid",
  "deviceName": "Semih-PC", "platform": "windows", "apiPort": 48800 }
```

## 6. Transfers

### 6.1 Model

A **transfer** is one file offered by the host to one paired device.
Multi-file sends are N transfers sharing a `batchId`.

Transfer JSON (canonical shape, used everywhere):
```json
{
  "transferId": "uuid",
  "batchId": "uuid",
  "fileId": "uuid",
  "fileName": "BMW E36 Final.mp4",
  "extension": "mp4",
  "mimeType": "video/mp4",
  "sizeBytes": 8492372918,
  "sha256": "lowercase-hex-64",
  "createdAtMs": 1755072000000,
  "modifiedAtMs": 1755072000000,
  "offeredAtMs": 1755073000000,
  "senderName": "Semih-PC",
  "autoAccept": false
}
```

- `sha256` is computed by the host by streaming the file **before** the
  offer is published (state `Hashing` in the UI). It is authoritative.
- `mimeType` is best-effort from the extension; unknown →
  `application/octet-stream`. Never affects file bytes.
- `autoAccept: true` marks offers originating from a watch-folder rule the
  user flagged auto-send; the client only honors it if its own
  "Auto Accept From Trusted Devices" setting is on.

### 6.2 Outbox long-poll (client → host)

`GET /api/v1/outbox?waitSeconds=25` (authenticated)

Returns immediately if offers are pending, else holds up to `waitSeconds`
(cap 30). Response `200`:
```json
{ "offers": [ Transfer, ... ] }
```
Empty array on timeout. Offers stay in the outbox until accepted, rejected,
cancelled by host, or expired (24 h). Re-delivery is idempotent — client
dedupes by `transferId`.

### 6.3 Accept / reject

`POST /api/v1/transfers/{transferId}/accept` → `200 {"ok":true}`
`POST /api/v1/transfers/{transferId}/reject` → `200 {"ok":true}`

Accepting moves the host-side state to `Accepted` and authorizes download.

### 6.4 Download (the actual bytes)

`GET /api/v1/transfers/{transferId}/file` (authenticated)

- Plain binary body. **No transformation, no compression**
  (`Content-Encoding: identity`), streamed from disk in chunks.
- Headers: `Content-Length`, `Content-Type` (from `mimeType`), `ETag: "<sha256>"`,
  `Accept-Ranges: bytes`, `X-Sendro-Sha256: <hex>`,
  `Content-Disposition: attachment; filename*=UTF-8''<RFC5987-encoded>`.
- **Range support is mandatory**: single-range `Range: bytes=<start>-` and
  `bytes=<start>-<end>` → `206` with correct `Content-Range`. Invalid range
  → `416`. Client resumes by sending `Range: bytes=<bytesOnDisk>-` after
  interruption; `If-Range: "<sha256>"` guards against the file changing.
- Host tracks bytes served for progress display but the client's status
  reports (6.5) are authoritative for client-side progress.

**Concurrency and backpressure.** A host serves at most `settings.concurrency`
(default 2) simultaneous streams. Requesting a file while every slot is taken
is the normal case — sending ten files at once means eight of them arrive
early — so the host **waits** for a slot rather than refusing: the request is
parked for up to 45 s and then served like any other. Only if that wait runs
out does the host answer `503` + `Retry-After: <seconds>` with
`{"error":"rate_limited"}`.

The same `503` + `Retry-After` is returned while the host's global pause gate
is on (`{"message":"transfers paused"}`).

A `503` is **backpressure, never a failure.** A client MUST NOT surface it as
a failed transfer, MUST NOT report `failed` in §6.5, and MUST retry after
`Retry-After`. Clients also queue locally — never more concurrent downloads
per host than the host's own gate — so in practice a `503` is only seen when
something else (a second device, a §14 guest) holds the slots.

### 6.5 Status reporting (client → host)

`POST /api/v1/transfers/{transferId}/status` (authenticated)
```json
{ "state": "downloading", "bytesReceived": 123456789 }
```
`state` ∈ `downloading | verifying | verified | saving | completed |
failed | cancelled`. Optional `"error": "human readable"`, and for
`completed`: `"savedTo": "photos" | "files" | "temp"`.
Host mirrors these into its queue/history. `verified` is only sent after
the client's own streamed SHA-256 of the received file equals `sha256`
(case-insensitive hex compare). On mismatch client sends
`failed` + `"error":"integrity"` and deletes the corrupt temp file; host
marks the transfer `Failed(IntegrityMismatch)` and allows retry (retry =
new download of the same transfer, full or ranged).

### 6.6 Host-side transfer states

`Queued → Hashing → Offered → Accepted → Transferring → Verifying →
Saving → Completed` with side exits `Rejected`, `Cancelled`, `Failed`,
`Interrupted` (client went away mid-download; offer stays resumable),
`Expired`.

## 7. Reverse direction (iPhone → Windows), v1-lite

Kept minimal but real:

`POST /api/v1/upload` (authenticated, multipart NOT used — raw body):
Headers: `X-Sendro-File-Name` (RFC5987 UTF-8 encoded), `X-Sendro-Sha256`,
`Content-Length`. Host streams body to the configured receive folder
(collision-safe rename: `name (2).ext`), verifies SHA-256 while writing,
`200 {"ok":true,"savedPath":"..."} ` on match, `422 {"error":"integrity"}`
and deletes the partial on mismatch.

## 8. Filenames & Unicode

Filenames are arbitrary UTF-8 (`Çekmeköy Reşadiye Drone.MOV`,
`final gerçekten final 5.mp4`). On-wire they travel only inside JSON
strings or RFC5987 `filename*`. Receivers sanitize path separators and
reserved characters for the local filesystem but must preserve everything
else, including case and spaces. Duplicate incoming names get ` (n)`
suffixed before the extension.

## 9. Errors

All errors: appropriate HTTP status + `{"error":"code","message":"..."}`.
Codes: `unauthorized`, `not_found`, `gone`, `bad_request`, `integrity`,
`rate_limited`, `expired`, `conflict`, `insufficient_storage` (client-side
concept, surfaced in status reports).

## 10. Version negotiation

Client checks `protocolVersion` from `/api/v1/info` (or TXT `v`). If the
major version is unknown, show "Update Sendro" — do not attempt transfer.

## 11. Text messages (ephemeral clipboard bridge)

Sendro can send a short text payload between paired devices — for pasting a
link, a caption, a code, a path. **Messages are never written to disk on
either side and are never added to history.** They live in RAM only, are
delivered at most once, and disappear when the receiving user dismisses
them (or when the app quits).

Message JSON:
```json
{
  "messageId": "uuid",
  "text": "https://example.com/whatever",
  "sentAtMs": 1755073000000,
  "senderName": "Semih-PC"
}
```

Limits: `text` is UTF-8, max **32 KiB** encoded. Longer input is rejected
with `413` + `{"error":"bad_request","message":"message too long"}`.
A device's in-memory inbox holds at most **20** undelivered messages;
pushing past that drops the oldest.

### 11.1 Host → client

Messages ride the existing outbox long poll (§6.2). The response gains a
`messages` array (absent or empty when there are none):

```json
{ "offers": [ ... ], "messages": [ Message, ... ] }
```

Delivery is **at-most-once**: the host removes a message from the device's
inbox the moment it is written into an outbox response. It is not retried
and not persisted — this is deliberate (a message is a clipboard hop, not
a mailbox). A message pending for a device makes the long poll return
immediately, exactly like a pending offer.

### 11.2 Client → host

`POST /api/v1/messages` (authenticated)
```json
{ "text": "one two three" }
```
→ `200 {"ok":true}`. The host surfaces it as a transient in-app card
(sender name + text + Copy + Dismiss) and holds it in memory only; closing
the card discards it. Same 32 KiB limit and `413` behaviour.

### 11.3 UI contract (both apps)

- Incoming message appears as a card: "<sender> sent you text", the text
  itself (selectable, scrollable if long), a **Copy** button, and a
  **Dismiss/Close** button that removes it permanently.
- Copy writes to the OS clipboard. Dismiss frees the memory.
- Nothing about the message is logged, persisted, or shown in history.

### 11.4 Local notes shelf (client-side, optional)

The ephemerality rules above are about **the wire and the host**: a host holds
a message in RAM until it is delivered or dismissed and never writes it down.
That does not change.

A *client* may additionally keep its own copy of text it sent or received, on
that device only, as a "notes shelf" — so a Wi-Fi password can be read again
after the card is gone. Where a client does this it MUST:

- keep the copy **local**: never upload it, never sync it, never put it in
  transfer history, never place the text in a notification body;
- **time-box** it to at most **24 hours**, with the expiry stamped on each
  note at creation, enforced on load, on every write and by a periodic sweep,
  and with no way for the user or the app to extend it;
- store it **app-private** and encrypted at rest where the platform offers it
  (iOS: `.completeFileProtection`; Android: app-internal storage);
- offer **delete one** and **clear all**;
- bound the shelf (Sendro uses 200 notes, oldest dropped first).

Both Sendro clients implement this as the **Notes** tab. The host implements
nothing for it — there is no protocol surface here, which is the point.

## 12. Bulk accept

Accepting many offers is a client-side loop over §6.3
(`POST /api/v1/transfers/{id}/accept`) — there is no batch endpoint, so a
partial failure only affects the individual transfer. Clients that expose
an "Accept all" affordance must issue the calls with bounded concurrency
(≤4 in flight) and report per-item failures without aborting the rest.

## 13. QR pairing (optical channel)

Typing six digits is optional: the host can render the *same* pairing
session as a QR code. The code travels over the optical channel (your own
screen → your own camera), never over the network, so §4's security
property is preserved — scanning proves physical presence exactly like
typing does.

The host opens a normal §4.1 pairing session, then encodes:

```
sendro://pair?v=1&h=<host-ip>&p=<port>&id=<hostDeviceId>&n=<pct-encoded name>
             &pid=<pairingId>&s=<salt base64url>&c=<6-digit code>
```

- Every value is percent-encoded. `n` is the host's display name.
- `h` must be a routable LAN address of the host. When the host has several
  (Ethernet + Wi-Fi + hotspot), it renders one QR per address, or lets the
  user flip between them; the client may also fall back to mDNS using `id`.
- The QR is only valid for the session's 120 s lifetime. Re-rendering after
  expiry starts a fresh session.

Client flow: scan → parse → verify `v` == 1 → `GET /api/v1/info` at `h:p`
(sanity-check `app == "sendro"`, matching `deviceId`, protocol version) →
compute the §4.2 proof from `c`, `s`, `pid` → `POST /api/v1/pair/confirm`.
No new endpoints. A `sendro://pair?…` URL opened from the iOS Camera app or
any QR reader must drive the same flow via the app's URL handler.

Hosts must treat a scanned session exactly like a typed one (same attempt
limits, same expiry). Clients must refuse `sendro://` URLs that arrive from
anywhere except a QR scan or an OS URL open — never from page content.

## 14. Sendro Link (guest web session)

For someone on the same Wi-Fi who does not have Sendro installed, the host
can open a **temporary, unauthenticated-by-URL web session**: a small page
served by the host itself, addressed by an unguessable token. No cloud, no
account, still LAN-only.

The user starts it explicitly from the PC UI, picks a duration, and can
stop it at any time. Everything about it is opt-in and ephemeral.

### 14.1 Session

- `linkToken`: 24 random bytes, base64url (32 chars). Unguessable; it *is*
  the credential, so the URL must only be shared deliberately (QR/AirDrop).
- Fields: `expiresAtMs` (default 30 min, max 24 h), `allowUpload` (bool),
  `sharedFiles` (explicit list — the guest can never browse the PC).
- Base URL: `http://<host-ip>:<port>/link/<linkToken>/`
- Expiry or an explicit stop makes every route below return `410 gone`.
  A stopped/expired token is never reused.

### 14.2 Guest routes (no Bearer auth; the token in the path is the key)

| route | purpose |
|---|---|
| `GET /link/<t>/` | the guest page (self-contained HTML+CSS+JS, no CDN, works offline) |
| `GET /link/<t>/api/session` | `{ "hostName": "...", "expiresAtMs": …, "allowUpload": true, "files": [ {fileId, fileName, sizeBytes, mimeType, sha256} ] }` |
| `GET /link/<t>/api/file/<fileId>` | bytes; identical semantics to §6.4 (identity encoding, `Accept-Ranges`, `Content-Disposition` RFC5987, ETag = sha256) |
| `POST /link/<t>/api/upload` | raw body, `X-Sendro-File-Name` (RFC5987), optional `X-Sendro-Sha256`; only when `allowUpload`; lands in the receive folder like §7 |

Guest uploads appear in the host's queue/history marked as coming from
`Guest (link)`. Rate limits: max 8 concurrent guest connections, max 200
uploads per session.

### 14.3 Safety rules (mandatory)

- Only files the user explicitly added to the session are reachable; there
  is no directory listing and no path traversal (fileId → path lookup only).
- The token appears in the URL, so the guest page must not embed it in any
  outbound link, and the host must not log full URLs.
- The session is RAM-only: it never survives an app restart.
- Starting a link session requires an explicit user action in the PC UI;
  it is never started by a request from the network.

## 15. Receiver host mode (Android TV)

Until now only the Windows PC acted as **host**; phones and the TV were
clients. That leaves phone → TV impossible, because two clients cannot talk.
So the Android app can also run the *host* side — a deliberately reduced
one, since a TV only ever receives.

Nothing new is invented: the TV speaks the same protocol the PC speaks, so
existing clients need no protocol change.

### 15.1 Which endpoints the receiver host implements

| endpoint | required | note |
|---|---|---|
| `GET /api/v1/info` | yes | `platform: "androidtv"` (or `"android"`), same shape as §5 |
| `POST /api/v1/pair/start`, `/pair/confirm` | yes | §4 verbatim — same HKDF/HMAC, same 120 s expiry, same attempt caps |
| `GET /api/v1/ping` | yes | §4.3 |
| `POST /api/v1/upload` | yes | §7 verbatim — this is how a phone or PC pushes a file to the TV |
| `POST /api/v1/messages` | yes | §11.2 — text/links land on the TV as a card |
| `GET /api/v1/outbox`, transfer routes | **no** | a receiver never offers files; return `404 not_found` |

mDNS: the receiver advertises `_sendro._tcp` exactly as §2, with `pf` set to
its platform and `v=1`. Clients must therefore treat a discovered peer's
`pf` as informational only and decide capability from `/api/v1/info` plus a
`404` on the outbox — never assume a peer can send.

The default port is the same 48800 with the same fallback scan.

### 15.2 Pairing a phone to the TV (§13 with the TV as host)

The TV renders the §13 `sendro://pair?…` QR on the big screen; the phone
scans it with its camera and confirms. This is the *only* pleasant pairing
path on a device with no keyboard, and it is exactly the same optical-channel
argument as §13: what you can scan, you can see, so you are in the room.

The typed 6-digit code stays available for a sender without a camera (the
PC): the TV shows the digits, the user types them on the PC.

### 15.3 Sending to the TV from the PC

Two directions are now possible and both are legitimate:

- **PC as host, TV as client** (the original path, already implemented):
  the PC offers, the TV long-polls, accepts and downloads. Preferred when
  the PC drives the interaction — the PC's queue and history stay accurate,
  and resume/Range works.
- **TV as host, PC as client**: the PC pushes with §7 upload. Simpler, no
  offer/accept round trip, but no resume.

Clients should prefer the first when the peer exposes an outbox.

### 15.4 Receiver-side obligations

- Verify SHA-256 on every upload before reporting success, exactly as §7.
- Never re-encode: bytes land on disk unchanged.
- Uploads are only accepted from paired devices with a valid bearer token.
- The receiver host is **off by default on phones** (a phone is normally a
  client) and **on by default on a TV**, with a user-visible toggle either
  way. Turning it off stops the server and the mDNS advertisement.
