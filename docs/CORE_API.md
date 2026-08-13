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
    bytes_transferred: u64, speed_bps: u64, eta_seconds: Option<u64>,
    started_at_ms: Option<i64>, source_path: Option<String>,
}

pub struct HistoryEntry { transfer_id, file_name, direction, peer_name, size_bytes,
    started_at_ms, ended_at_ms, duration_ms, avg_speed_bps, verified: bool, final_state: TransferState }

pub struct WatchFolderConfig { id: Uuid, path: String, auto_send: bool,
    target_device_id: Option<Uuid>, enabled: bool }

pub struct Settings { device_name, receive_dir, preferred_port, concurrency,
    launch_on_startup: bool, minimize_to_tray: bool }

pub enum CoreEvent {
    PairingStarted { pairing_id: Uuid, code: String, device_name: String }, // SHOW CODE IN UI
    PairingCompleted { device: TrustedDevice },
    PairingFailed { pairing_id: Uuid },
    TransferUpdated { transfer: TransferSummary },
    WatchFileDetected { detection_id: Uuid, path: String, folder_id: Uuid, file_name: String, size_bytes: u64, auto: bool },
    ServerStarted { port: u16 },
}
// serialized with #[serde(tag = "type", rename_all = "camelCase")]
```

Tauri commands (desktop glue) are 1:1 thin wrappers named the same in
snake_case, and every `CoreEvent` is re-emitted to the webview as Tauri
event `"core-event"` with the serde JSON payload. The React app maintains
state from `queue()`/`history()` snapshots + `core-event` deltas.
