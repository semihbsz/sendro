//! Core event stream (CORE_API.md). Serialized with
//! `#[serde(tag = "type", rename_all = "camelCase")]` — the desktop shell
//! re-emits these verbatim to the webview.

use serde::{Deserialize, Serialize};
use uuid::Uuid;

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
    #[serde(rename_all = "camelCase")]
    ServerStarted { port: u16 },
}
