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

## 12. Bulk accept

Accepting many offers is a client-side loop over §6.3
(`POST /api/v1/transfers/{id}/accept`) — there is no batch endpoint, so a
partial failure only affects the individual transfer. Clients that expose
an "Accept all" affordance must issue the calls with bounded concurrency
(≤4 in flight) and report per-item failures without aborting the rest.
