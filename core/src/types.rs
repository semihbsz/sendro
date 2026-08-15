//! Serde types shared across the public API (CORE_API.md) and the wire
//! protocol (PROTOCOL.md). All JSON uses `camelCase` field names.

use serde::{Deserialize, Serialize};
use uuid::Uuid;

pub const PROTOCOL_VERSION: u32 = 1;
pub const APP_NAME: &str = "sendro";
pub const PLATFORM: &str = "windows";

// ---------------------------------------------------------------------------
// Public API types (CORE_API.md)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostInfo {
    pub device_id: Uuid,
    pub device_name: String,
    pub platform: String,
    pub api_port: u16,
    pub local_ips: Vec<String>,
    pub protocol_version: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TrustedDevice {
    pub device_id: Uuid,
    pub device_name: String,
    pub platform: String,
    pub paired_at_ms: i64,
    pub last_seen_ms: Option<i64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TransferState {
    Queued,
    Hashing,
    Offered,
    Accepted,
    Transferring,
    Verifying,
    Saving,
    Completed,
    Rejected,
    Cancelled,
    Failed,
    Interrupted,
    Expired,
}

impl TransferState {
    /// A terminal state can never transition anywhere else.
    pub fn is_terminal(self) -> bool {
        matches!(
            self,
            TransferState::Completed
                | TransferState::Rejected
                | TransferState::Cancelled
                | TransferState::Failed
                | TransferState::Expired
        )
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransferSummary {
    pub transfer_id: Uuid,
    pub batch_id: Uuid,
    pub file_name: String,
    pub size_bytes: u64,
    pub sha256: Option<String>,
    pub state: TransferState,
    pub error: Option<String>,
    pub device_id: Uuid,
    pub device_name: String,
    /// "outgoing" | "incoming"
    pub direction: String,
    pub bytes_transferred: u64,
    pub speed_bps: u64,
    pub eta_seconds: Option<u64>,
    pub started_at_ms: Option<i64>,
    pub source_path: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HistoryEntry {
    pub transfer_id: Uuid,
    pub file_name: String,
    pub direction: String,
    pub peer_name: String,
    pub size_bytes: u64,
    pub started_at_ms: i64,
    pub ended_at_ms: i64,
    pub duration_ms: i64,
    pub avg_speed_bps: u64,
    pub verified: bool,
    pub final_state: TransferState,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WatchFolderConfig {
    pub id: Uuid,
    pub path: String,
    pub auto_send: bool,
    pub target_device_id: Option<Uuid>,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    pub device_name: String,
    pub receive_dir: String,
    pub preferred_port: u16,
    pub concurrency: usize,
    pub launch_on_startup: bool,
    pub minimize_to_tray: bool,
}

// ---------------------------------------------------------------------------
// Wire types (PROTOCOL.md)
// ---------------------------------------------------------------------------

/// `GET /api/v1/info` response — §5, exact shape.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InfoResponse {
    pub app: String,
    pub protocol_version: u32,
    pub device_id: Uuid,
    pub device_name: String,
    pub platform: String,
    pub api_port: u16,
}

/// `POST /api/v1/pair/start` request — §4.1.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairStartRequest {
    pub device_id: Uuid,
    pub device_name: String,
    pub platform: String,
    pub protocol_version: u32,
}

/// `POST /api/v1/pair/start` response — §4.1.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairStartResponse {
    pub pairing_id: Uuid,
    pub salt: String,
    pub expires_in_seconds: u64,
}

/// `POST /api/v1/pair/confirm` request — §4.2.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairConfirmRequest {
    pub pairing_id: Uuid,
    pub device_id: Uuid,
    pub proof: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairHostInfo {
    pub device_id: Uuid,
    pub device_name: String,
    pub platform: String,
}

/// `POST /api/v1/pair/confirm` response — §4.2.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairConfirmResponse {
    pub device_token: String,
    pub host: PairHostInfo,
}

/// Canonical Transfer JSON — §6.1, used in the outbox.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransferWire {
    pub transfer_id: Uuid,
    pub batch_id: Uuid,
    pub file_id: Uuid,
    pub file_name: String,
    pub extension: String,
    pub mime_type: String,
    pub size_bytes: u64,
    pub sha256: String,
    pub created_at_ms: i64,
    pub modified_at_ms: i64,
    pub offered_at_ms: i64,
    pub sender_name: String,
    pub auto_accept: bool,
}

/// Canonical Message JSON — §11, ephemeral text. Never persisted, never
/// written to history, never logged with its contents.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Message {
    pub message_id: Uuid,
    pub text: String,
    pub sent_at_ms: i64,
    pub sender_name: String,
}

/// A message received *from* a paired device, held in RAM for the host UI
/// (§11.2). Dismissing it frees the memory; nothing about it is persisted.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IncomingMessage {
    pub message_id: Uuid,
    pub text: String,
    pub sender_name: String,
    pub received_at_ms: i64,
}

/// `POST /api/v1/messages` request — §11.2.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SendMessageRequest {
    pub text: String,
}

/// `GET /api/v1/outbox` response — §6.2 + §11.1.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OutboxResponse {
    pub offers: Vec<TransferWire>,
    /// Ephemeral text messages, drained on read (at-most-once delivery).
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub messages: Vec<Message>,
}

/// `POST /api/v1/transfers/{id}/status` request — §6.5.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StatusReport {
    pub state: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub bytes_received: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub saved_to: Option<String>,
}

/// Generic `{"ok":true}` response.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OkResponse {
    pub ok: bool,
}

/// `GET /api/v1/ping` response — §4.3.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PingResponse {
    pub ok: bool,
    pub device_name: String,
}

/// `POST /api/v1/upload` success response — §7.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UploadResponse {
    pub ok: bool,
    pub saved_path: String,
}

/// Error body — §9: `{"error":"code","message":"..."}`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ErrorBody {
    pub error: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

/// A host discovered on the LAN via mDNS browse (future desktop features).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiscoveredHost {
    pub device_id: String,
    pub device_name: String,
    pub platform: String,
    pub port: u16,
    pub addresses: Vec<String>,
    pub protocol_version: String,
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}
