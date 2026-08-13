//! JSON persistence in `data_dir` with atomic writes (temp file + rename).

use std::fs;
use std::path::{Path, PathBuf};

use anyhow::Context;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::types::{TransferState, TrustedDevice};

pub const SETTINGS_FILE: &str = "settings.json";
pub const TRUSTED_DEVICES_FILE: &str = "trusted_devices.json";
pub const WATCH_FOLDERS_FILE: &str = "watch_folders.json";
pub const HISTORY_FILE: &str = "history.json";
pub const OFFERS_FILE: &str = "offers.json";
pub const IDENTITY_FILE: &str = "identity.json";

pub const HISTORY_CAP: usize = 500;

/// Atomically write `value` as pretty JSON to `path` (write temp + rename).
pub fn atomic_write_json<T: Serialize>(path: &Path, value: &T) -> anyhow::Result<()> {
    let json = serde_json::to_vec_pretty(value).context("serialize json")?;
    let dir = path.parent().context("path has no parent dir")?;
    fs::create_dir_all(dir).with_context(|| format!("create dir {}", dir.display()))?;
    let tmp: PathBuf = dir.join(format!(
        ".{}.tmp-{}",
        path.file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("state"),
        Uuid::new_v4()
    ));
    fs::write(&tmp, &json).with_context(|| format!("write {}", tmp.display()))?;
    // Rename is atomic on the same filesystem (both Windows and Unix).
    if let Err(e) = fs::rename(&tmp, path) {
        let _ = fs::remove_file(&tmp);
        return Err(e).with_context(|| format!("rename to {}", path.display()));
    }
    Ok(())
}

/// Load JSON from `path`; `None` if the file does not exist or is corrupt
/// (corruption is logged, not fatal — the file will be rewritten).
pub fn load_json<T: DeserializeOwned>(path: &Path) -> Option<T> {
    let bytes = match fs::read(path) {
        Ok(b) => b,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return None,
        Err(e) => {
            tracing::warn!("failed to read {}: {e}", path.display());
            return None;
        }
    };
    match serde_json::from_slice(&bytes) {
        Ok(v) => Some(v),
        Err(e) => {
            tracing::warn!("corrupt state file {}: {e}", path.display());
            None
        }
    }
}

/// Host identity persisted across restarts.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Identity {
    pub device_id: Uuid,
}

/// A trusted device as stored at rest: public metadata plus the SHA-256 of
/// its bearer token (lowercase hex). The raw token is never stored.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StoredDevice {
    pub device_id: Uuid,
    pub device_name: String,
    pub platform: String,
    pub paired_at_ms: i64,
    pub last_seen_ms: Option<i64>,
    pub token_sha256: String,
}

impl StoredDevice {
    pub fn public(&self) -> TrustedDevice {
        TrustedDevice {
            device_id: self.device_id,
            device_name: self.device_name.clone(),
            platform: self.platform.clone(),
            paired_at_ms: self.paired_at_ms,
            last_seen_ms: self.last_seen_ms,
        }
    }
}

/// Minimal persisted form of an active outgoing offer so interrupted
/// transfers survive a PC restart (restored as Offered/Interrupted).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PersistedOffer {
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
    pub auto_accept: bool,
    pub device_id: Uuid,
    pub device_name: String,
    pub source_path: String,
    pub state: TransferState,
    pub bytes_transferred: u64,
    pub started_at_ms: Option<i64>,
}
