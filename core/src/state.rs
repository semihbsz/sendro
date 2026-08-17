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
/// Outbound peer pairings — see [`StoredPeer`]. **Sensitive**: written with
/// [`atomic_write_json_private`], never with [`atomic_write_json`].
pub const PEERS_FILE: &str = "peers.json";
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

/// Like [`atomic_write_json`], but the file is created owner-only where the
/// platform lets us say so.
///
/// Used for `peers.json`, which holds raw bearer tokens (see [`StoredPeer`]).
/// The mode is applied to the *temp* file before the rename, so the final path
/// is never briefly world-readable.
///
/// On Windows there is no chmod; the file inherits the ACL of `%APPDATA%\…`,
/// which is already restricted to the signed-in user (and Administrators —
/// same trust boundary as the DPAPI-less credential stores Windows apps
/// normally use). Nothing here defends against an attacker who is already
/// running as the user.
pub fn atomic_write_json_private<T: Serialize>(path: &Path, value: &T) -> anyhow::Result<()> {
    let json = serde_json::to_vec_pretty(value).context("serialize json")?;
    let dir = path.parent().context("path has no parent dir")?;
    fs::create_dir_all(dir).with_context(|| format!("create dir {}", dir.display()))?;
    let tmp: PathBuf = dir.join(format!(
        ".{}.tmp-{}",
        path.file_name().and_then(|n| n.to_str()).unwrap_or("state"),
        Uuid::new_v4()
    ));
    fs::write(&tmp, &json).with_context(|| format!("write {}", tmp.display()))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if let Err(e) = fs::set_permissions(&tmp, fs::Permissions::from_mode(0o600)) {
            // Not fatal — but loud, because it means a password-equivalent
            // file is about to land with the default umask.
            tracing::warn!("could not restrict permissions on {}: {e}", tmp.display());
        }
    }
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

/// A peer *we* paired with, as stored at rest in `peers.json`.
///
/// # This file is as sensitive as a password store
///
/// Unlike [`StoredDevice`] — where we are the host and only ever keep
/// `SHA-256(token)` — here we are the **client**, so we must keep the raw
/// `device_token` to put it in `Authorization: Bearer …` on every request
/// (PROTOCOL.md §3). A hash is useless for that. Anyone who can read
/// `peers.json` can push files and text to those peers until the pairing is
/// revoked on the peer side.
///
/// Consequences, all deliberate:
/// * written only through [`atomic_write_json_private`] (0600 on unix, the
///   user-scoped `%APPDATA%` ACL on Windows),
/// * `token` is never logged, never emitted in a [`crate::CoreEvent`], and
///   never crosses the Tauri IPC boundary — [`crate::PairedPeer`] is the
///   public projection and has no token field,
/// * the 6-digit pairing *code* is never written here either: it only ever
///   exists in RAM long enough to derive the §4.2 proof.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StoredPeer {
    pub device_id: Uuid,
    pub device_name: String,
    pub platform: String,
    pub address: String,
    pub port: u16,
    pub paired_at_ms: i64,
    pub last_seen_ms: Option<i64>,
    /// The peer answered `404` on `GET /api/v1/outbox` — a §15 receiver host
    /// (TV/phone). Expected, never an error: it just means we push (§7) and
    /// never pull.
    #[serde(default)]
    pub receive_only: bool,
    /// Raw bearer token — see the type-level warning.
    pub token: String,
}

impl StoredPeer {
    pub fn public(&self) -> crate::peers::PairedPeer {
        crate::peers::PairedPeer {
            device_id: self.device_id,
            device_name: self.device_name.clone(),
            platform: self.platform.clone(),
            address: self.address.clone(),
            port: self.port,
            paired_at_ms: self.paired_at_ms,
            last_seen_ms: self.last_seen_ms,
            receive_only: self.receive_only,
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
