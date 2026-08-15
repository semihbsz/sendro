//! Core event stream (CORE_API.md). Serialized with
//! `#[serde(tag = "type", rename_all = "camelCase")]` — the desktop shell
//! re-emits these verbatim to the webview.

use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::link::LinkSession;
use crate::types::{TransferSummary, TrustedDevice};

/// Broadcast channel capacity for [`CoreEvent`]s.
pub const EVENT_CHANNEL_CAPACITY: usize = 512;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum CoreEvent {
    /// A pairing session started; `code` must be shown in the Windows UI.
    #[serde(rename_all = "camelCase")]
    PairingStarted {
        pairing_id: Uuid,
        code: String,
        device_name: String,
    },
    #[serde(rename_all = "camelCase")]
    PairingCompleted { device: TrustedDevice },
    #[serde(rename_all = "camelCase")]
    PairingFailed { pairing_id: Uuid },
    #[serde(rename_all = "camelCase")]
    TransferUpdated { transfer: TransferSummary },
    #[serde(rename_all = "camelCase")]
    WatchFileDetected {
        detection_id: Uuid,
        path: String,
        folder_id: Uuid,
        file_name: String,
        size_bytes: u64,
        auto: bool,
    },
    /// An ephemeral text message arrived from a paired device (§11.2).
    /// In-memory only — never persisted, never written to history.
    #[serde(rename_all = "camelCase")]
    MessageReceived {
        message_id: Uuid,
        text: String,
        sender_name: String,
        received_at_ms: i64,
    },
    /// A Sendro Link guest session was started, changed or ended (§14).
    /// `None` means there is no session any more (stopped or expired).
    /// RAM only — nothing about it is ever persisted.
    #[serde(rename_all = "camelCase")]
    LinkSessionChanged { session: Option<LinkSession> },
    /// A guest uploaded a file through the link session (§14.2). The
    /// transfer itself also shows up in the queue/history as `Guest (link)`.
    #[serde(rename_all = "camelCase")]
    GuestUpload {
        file_name: String,
        size_bytes: u64,
    },
    #[serde(rename_all = "camelCase")]
    ServerStarted { port: u16 },
}
