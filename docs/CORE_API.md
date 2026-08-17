# sendro-core public API contract

`core/` is a standalone Rust crate (`sendro-core`) with no Tauri dependency.
`desktop/src-tauri` depends on it by path and exposes thin Tauri commands.
This file pins the seam so both sides match. All JSON serialization uses
`#[serde(rename_all = "camelCase")]`.

```rust
pub struct CoreConfig {
    pub device_name: String,
    pub data_dir: PathBuf,        // settings.json, trusted_devices.json, history.json live here
    pub receive_dir: PathBuf,     // iPhone→PC uploads land here
    pub preferred_port: u16,      // 48800; fall back +1..+20
    pub concurrency: usize,       // default 2
}

pub struct Core; // Arc-shared engine
impl Core {
    pub async fn start(cfg: CoreConfig) -> anyhow::Result<Arc<Core>>; // http server + mdns advertise
    pub async fn shutdown(self: &Arc<Core>);
    pub fn info(&self) -> HostInfo;                    // deviceId, deviceName, port, localIps: Vec<String>
    pub fn subscribe(&self) -> tokio::sync::broadcast::Receiver<CoreEvent>;

    // pairing / trust
    pub fn trusted_devices(&self) -> Vec<TrustedDevice>;
    pub fn revoke_device(&self, device_id: Uuid) -> bool;

    // sending
    pub async fn offer_files(&self, device_id: Uuid, paths: Vec<PathBuf>, auto_accept: bool)
        -> anyhow::Result<Vec<TransferSummary>>;      // expands folders recursively
    pub fn queue(&self) -> Vec<TransferSummary>;
    pub fn cancel_transfer(&self, id: Uuid) -> bool;
    pub fn retry_transfer(&self, id: Uuid) -> bool;    // re-offers a Failed/Interrupted/Expired transfer
    pub fn pause_transfers(&self, paused: bool);       // global pause gate (tray + UI)
    pub fn clear_completed(&self);
    pub fn history(&self) -> Vec<HistoryEntry>;
    pub fn clear_history(&self);

    // ephemeral text messages (PROTOCOL.md §11) — RAM only, never persisted,
    // never in history, contents never logged
    pub fn send_message(&self, device_id: Uuid, text: String) -> anyhow::Result<()>;
        // ≤32 KiB UTF-8, non-empty, device must be paired; pushes onto that
        // device's in-memory inbox (cap 20, oldest dropped) and wakes its
        // outbox long-poll. Drained into the outbox response at most once.
    pub fn pending_message_count(&self, device_id: Uuid) -> usize; // diagnostics/tests
    pub fn incoming_messages(&self) -> Vec<IncomingMessage>;  // oldest first, max 20
    pub fn dismiss_message(&self, message_id: Uuid) -> bool;  // discards it forever
    pub fn clear_messages(&self);

    // peers — this PC as a *client* (PROTOCOL.md §4/§7/§11.2/§15).
    // Mirror image of `trusted_devices`: those pair TO this PC and pull from
    // it; these are devices this PC pairs TO and pushes to. A device can be
    // in both lists.
    pub fn discovered_peers(&self) -> Vec<DiscoveredPeer>;   // live mDNS browse
    pub async fn pair_with_peer(&self, address: String, port: u16)
        -> anyhow::Result<PeerPairingSession>;               // §4.1 + /info check
    pub async fn confirm_peer_pairing(&self, pairing_id: String, code: String)
        -> anyhow::Result<PairedPeer>;                       // §4.2 proof
    pub fn paired_peers(&self) -> Vec<PairedPeer>;
    pub fn forget_peer(&self, device_id: Uuid) -> bool;
    pub async fn ping_peer(&self, device_id: Uuid) -> bool;  // §4.3, self-heals address
    pub async fn send_files_to_peer(&self, device_id: Uuid, paths: Vec<PathBuf>)
        -> anyhow::Result<Vec<TransferSummary>>;             // §7 push, folders expanded
    pub async fn send_message_to_peer(&self, device_id: Uuid, text: String)
        -> anyhow::Result<()>;                               // §11.2
    pub fn refresh_peers(&self);                             // re-emit PeersChanged

    // watch folders
    pub fn add_watch_folder(&self, cfg: WatchFolderConfig) -> anyhow::Result<()>;
    pub fn remove_watch_folder(&self, id: Uuid) -> bool;
    pub fn watch_folders(&self) -> Vec<WatchFolderConfig>;
    pub fn resolve_detected_file(&self, detection_id: Uuid, send: bool); // Send / Ignore from UI

    // settings
    pub fn settings(&self) -> Settings;
    pub fn update_settings(&self, s: Settings) -> anyhow::Result<()>;
}
```

Serde types (JSON field names in comments where non-obvious):

```rust
pub struct HostInfo { device_id, device_name, platform: "windows", api_port: u16, local_ips: Vec<String>, protocol_version: u32 }

pub struct TrustedDevice { device_id: Uuid, device_name: String, platform: String, paired_at_ms: i64, last_seen_ms: Option<i64> }

pub enum TransferState { Queued, Hashing, Offered, Accepted, Transferring, Verifying, Saving,
                         Completed, Rejected, Cancelled, Failed, Interrupted, Expired }
// serialized lowercase strings: "queued", "hashing", ...

pub struct TransferSummary {
    transfer_id: Uuid, batch_id: Uuid, file_name: String, size_bytes: u64,
    sha256: Option<String>, state: TransferState, error: Option<String>,
    device_id: Uuid, device_name: String, direction: String, // "outgoing" | "incoming"
    is_peer: bool,          // true = a §7 push to a peer host, not a pull-offer
    bytes_transferred: u64, speed_bps: u64, eta_seconds: Option<u64>,
    started_at_ms: Option<i64>, source_path: Option<String>,
}

pub struct HistoryEntry { transfer_id, file_name, direction, peer_name, size_bytes,
    started_at_ms, ended_at_ms, duration_ms, avg_speed_bps, verified: bool, final_state: TransferState }

pub struct WatchFolderConfig { id: Uuid, path: String, auto_send: bool,
    target_device_id: Option<Uuid>, enabled: bool }

pub struct Settings { device_name, receive_dir, preferred_port, concurrency,
    launch_on_startup: bool, minimize_to_tray: bool }

// §11 wire shape, host → client (rides the outbox response's `messages` array)
pub struct Message { message_id: Uuid, text: String, sent_at_ms: i64, sender_name: String }

// §11.2 client → host, held in RAM for the UI until dismissed
pub struct IncomingMessage { message_id: Uuid, text: String, sender_name: String,
    received_at_ms: i64 }

// constants: messages::MAX_MESSAGE_BYTES = 32 * 1024, messages::MAX_INBOX = 20,
//            messages::MAX_INCOMING = 20

// Peers (PROTOCOL.md §2 discovery, §4 pairing, §15 receiver hosts)

pub struct DiscoveredPeer {
    device_id: Uuid, device_name: String, platform: String,
    address: String, port: u16, protocol_version: u32, last_seen_ms: i64,
    paired: bool,     // already in trusted_devices.json OR peers.json
    reachable: bool,  // the last GET /info probe answered
}

pub struct PairedPeer {
    device_id: Uuid, device_name: String, platform: String,
    address: String, port: u16, paired_at_ms: i64, last_seen_ms: Option<i64>,
    receive_only: bool,   // it answers 404 on the outbox (§15.1) — expected
}
// NB: no token field. The raw bearer token stays in the core.

pub struct PeerPairingSession {
    pairing_id: Uuid, device_id: Uuid, device_name: String, platform: String,
    address: String, port: u16, expires_in_seconds: u64,
}

pub enum CoreEvent {
    PairingStarted { pairing_id: Uuid, code: String, device_name: String }, // SHOW CODE IN UI
    PairingCompleted { device: TrustedDevice },
    PairingFailed { pairing_id: Uuid },
    TransferUpdated { transfer: TransferSummary },
    WatchFileDetected { detection_id: Uuid, path: String, folder_id: Uuid, file_name: String, size_bytes: u64, auto: bool },
    MessageReceived { message_id: Uuid, text: String, sender_name: String, received_at_ms: i64 }, // §11.2, ephemeral
    PeersChanged { peers: Vec<DiscoveredPeer> },  // §2 live browse, debounced ~500 ms
    ServerStarted { port: u16 },
}
// serialized with #[serde(tag = "type", rename_all = "camelCase")]
```

Tauri commands (desktop glue) are 1:1 thin wrappers named the same in
snake_case, and every `CoreEvent` is re-emitted to the webview as Tauri
event `"core-event"` with the serde JSON payload. The React app maintains
state from `queue()`/`history()` snapshots + `core-event` deltas.

`MessageReceived` also raises the main window (like `PairingStarted`) so the
card is visible when Sendro is minimized to tray.

## Peers: the PC as a client (PROTOCOL.md §4/§7/§11.2/§15)

Until now the PC was host-only: it advertised over mDNS and waited. It now
also **browses** and **pushes**, symmetric with the phone.

* **Discovery** runs for the life of the process (`discovery::browse_loop`),
  not per request: one `ServiceDaemon` both advertises and browses, results
  land in a live map keyed by mDNS fullname, each peer is probed with
  `GET /api/v1/info` (on discovery and every 30 s), and changes are published
  as `PeersChanged`, debounced 500 ms. Our own advertisement is excluded by
  `deviceId`. An interface change re-registers the advertisement *and*
  restarts the browse — one trigger, both directions.
* **Trust is stored in two places, on purpose:**

  | direction | we are | secret at rest | file |
  |---|---|---|---|
  | device → PC | host | `SHA-256(token)` | `trusted_devices.json` |
  | PC → peer | client | the **raw** token | `peers.json` |

  A client cannot authenticate with a hash, so `peers.json` necessarily holds
  password-equivalent material. It is written with
  `state::atomic_write_json_private` (mode `0600` on unix, the user-scoped
  `%APPDATA%` ACL on Windows), the token never leaves the core (neither
  `PairedPeer` nor any event carries it), and the 6-digit pairing **code** is
  never persisted anywhere — it exists just long enough to derive the §4.2
  proof.
* **Sending** to a peer is a §7 upload: hash the file, then stream it in
  ≤1 MiB chunks with `X-Sendro-Sha256` and an RFC 5987 `X-Sendro-File-Name`,
  with an explicit `Content-Length`. These transfers share the queue and
  history with everything else (`direction: "outgoing"`, `is_peer: true`), so
  Flow shows progress/speed/ETA/cancel unchanged. `422` → `Failed` with error
  `IntegrityMismatch`; retry means resending from byte 0, because §7 has no
  ranged upload. Peer transfers are excluded from the outbox, `offers.json`,
  the download route and `retry_transfer` — they are pushes, not offers.
* **A `404` on the peer's outbox means receive-only** (§15.1) and is never an
  error. It is probed once at pairing (and again on ping only while the flag
  is set, since a `404` cannot drain a message).
* Concurrency respects the user's setting: at most `Settings::concurrency`
  uploads in flight per call.

## Tauri commands beyond the 1:1 core wrappers

```
discovered_peers()                -> DiscoveredPeer[]
pair_with_peer(address, port)     -> Result<PeerPairingSession, String>
confirm_peer_pairing(pairingId, code) -> Result<PairedPeer, String>
paired_peers()                    -> PairedPeer[]
forget_peer(deviceId)             -> bool
ping_peer(deviceId)               -> Result<bool, String>
send_files_to_peer(deviceId, paths) -> Result<TransferSummary[], String>
send_message_to_peer(deviceId, text) -> Result<(), String>
refresh_peers()                   -> void
send_message(deviceId, text)      -> Result<(), String>       // Core::send_message
incoming_messages()               -> IncomingMessage[]        // Core::incoming_messages
dismiss_message(id)               -> bool                     // Core::dismiss_message
clear_messages()                  -> void                     // Core::clear_messages
paste_clipboard_image(stamp?)     -> Result<string|null, String>
open_receive_folder()             -> Result<(), String>
```

`paste_clipboard_image` has no core counterpart: it reads the clipboard
bitmap through `tauri-plugin-clipboard-manager` **in Rust**, encodes it with
the `image` crate into `%TEMP%/sendro-paste/Pasted <stamp>.png` and returns
the path, which the UI then feeds into the normal `offer_files` flow. Raw
pixels never cross the IPC boundary. `Ok(null)` means "no bitmap on the
clipboard" — the caller falls back to `readText()`. Files in that scratch
directory older than 24 h are pruned on each call.

Clipboard capability permissions (`src-tauri/capabilities/default.json`):
`clipboard-manager:allow-read-text`, `clipboard-manager:allow-read-image`,
`clipboard-manager:allow-write-text`. The plugin's `default` permission set
is deliberately empty upstream, so each one is listed explicitly.

Bulk affordances are client-side loops (PROTOCOL.md §12) over the existing
single-item commands — `resolve_detected_file` for Watch's "Send all (N)" /
"Ignore all", `retry_transfer` for Flow's "Retry all failed" — run at most 4
in flight (`src/bulk.ts`), reporting per-item failures without aborting.

## QR pairing + Sendro Link + hotspot (PROTOCOL.md §13, §14)

```rust
impl Core {
    // §13 — open a pairing session for QR display. Same session machinery as
    // the typed code (same 120 s expiry, same attempt limits); returns the
    // payload the UI encodes into a QR, plus the code for the typed fallback.
    pub fn start_qr_pairing(&self) -> QrPairing;

    // §14 — guest link sessions (RAM only, never persisted)
    pub fn start_link_session(&self, opts: LinkOptions) -> anyhow::Result<LinkSession>;
    pub fn stop_link_session(&self) -> bool;
    pub fn link_session(&self) -> Option<LinkSession>;
    pub fn add_link_files(&self, paths: Vec<PathBuf>) -> anyhow::Result<LinkSession>;
    pub fn remove_link_file(&self, file_id: Uuid) -> bool;

    // network surface, for the hotspot / no-router screen
    pub fn network_interfaces(&self) -> Vec<NetIface>;
}

pub struct QrPairing {
    pairing_id: Uuid, code: String, salt: String,
    // one URL per routable address, best candidate first
    urls: Vec<QrUrl>, expires_in_seconds: u32,
}
pub struct QrUrl { address: String, url: String, kind: String } // kind: "lan" | "hotspot" | "other"

pub struct LinkOptions { expires_in_minutes: u32, allow_upload: bool, paths: Vec<PathBuf> }
pub struct LinkSession {
    token: String, url: String, urls: Vec<QrUrl>, expires_at_ms: i64,
    allow_upload: bool, files: Vec<LinkFile>, guest_uploads: u32,
}
pub struct LinkFile { file_id: Uuid, file_name: String, size_bytes: u64,
                      mime_type: String, sha256: Option<String> }

pub struct NetIface { name: String, address: String, kind: String, // "lan" | "hotspot" | "other"
                      is_up: bool }
```

New `CoreEvent` variants: `LinkSessionChanged { session: Option<LinkSession> }`,
`GuestUpload { file_name: String, size_bytes: u64 }`.

Tauri commands (thin wrappers, same names in snake_case):
```
start_qr_pairing() -> QrPairing
start_link_session(opts) / stop_link_session() / link_session() /
add_link_files(paths) / remove_link_file(id)
network_interfaces() -> NetIface[]
preview_file(path) -> Result<PreviewInfo, String>   // desktop-only helper
```

`preview_file` returns `{ kind: "image" | "video" | "audio" | "pdf" | "text" | "other",
mimeType, sizeBytes, exists }` so the UI can decide between an inline preview
(via Tauri's asset protocol) and "Open in default app". The desktop capability
list must scope `asset:` reads to the receive folder and to files the user
explicitly sent — never a blanket filesystem grant.

Desktop notifications use `tauri-plugin-notification`
(`notification:default`, `notification:allow-notify`,
`notification:allow-request-permission`, `notification:allow-is-permission-granted`).
