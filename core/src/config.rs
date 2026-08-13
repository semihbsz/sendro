//! Engine configuration (CORE_API.md `CoreConfig`).

use std::path::PathBuf;

/// Configuration handed to [`crate::Core::start`].
///
/// The first five fields are the pinned CORE_API.md contract. The trailing
/// fields are additive knobs with production defaults — primarily used to
/// make tests deterministic (short watch-folder poll interval, mDNS off).
#[derive(Debug, Clone)]
pub struct CoreConfig {
    pub device_name: String,
    /// settings.json, trusted_devices.json, history.json, watch_folders.json,
    /// offers.json live here.
    pub data_dir: PathBuf,
    /// iPhone→PC uploads land here.
    pub receive_dir: PathBuf,
    /// 48800; if busy, fall back +1..+20. `0` binds an ephemeral port
    /// (used by tests).
    pub preferred_port: u16,
    /// Max simultaneous transfers in state `Transferring` (default 2).
    pub concurrency: usize,

    // -- additive, non-contract knobs -------------------------------------
    /// Watch-folder stabilizer poll interval in milliseconds (default 2000).
    pub watch_poll_ms: u64,
    /// Consecutive unchanged polls before a watched file is "ready"
    /// (default 3).
    pub watch_stable_polls: u32,
    /// Advertise/browse over mDNS (default true; tests turn this off).
    pub mdns_enabled: bool,
}

impl CoreConfig {
    pub fn new(
        device_name: impl Into<String>,
        data_dir: impl Into<PathBuf>,
        receive_dir: impl Into<PathBuf>,
    ) -> Self {
        Self {
            device_name: device_name.into(),
            data_dir: data_dir.into(),
            receive_dir: receive_dir.into(),
            preferred_port: 48800,
            concurrency: 2,
            watch_poll_ms: 2000,
            watch_stable_polls: 3,
            mdns_enabled: true,
        }
    }
}
