//! Transfer queue, outbox and host-side state machine (PROTOCOL.md §6).

use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use uuid::Uuid;
use walkdir::WalkDir;

use crate::state::{self, PersistedOffer};
use crate::types::{now_ms, TransferState, TransferSummary, TransferWire};
use crate::{Core, CoreEvent};

/// Offers expire 24 h after being published (§6.2).
pub const OFFER_TTL_MS: i64 = 24 * 60 * 60 * 1000;
/// Minimum interval between TransferUpdated events per transfer (~4/sec).
pub const EMIT_INTERVAL: Duration = Duration::from_millis(250);
/// Rolling window used for speed estimation.
const SPEED_WINDOW: Duration = Duration::from_secs(3);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Direction {
    Outgoing,
    Incoming,
}

impl Direction {
    fn as_str(self) -> &'static str {
        match self {
            Direction::Outgoing => "outgoing",
            Direction::Incoming => "incoming",
        }
    }
}

/// Rolling-window byte counter for speed_bps / eta.
#[derive(Debug, Default)]
pub(crate) struct SpeedWindow {
    samples: VecDeque<(Instant, u64)>,
}

impl SpeedWindow {
    pub fn push(&mut self, bytes: u64) {
        let now = Instant::now();
        self.samples.push_back((now, bytes));
        while let Some((t, _)) = self.samples.front() {
            if now.duration_since(*t) > SPEED_WINDOW {
                self.samples.pop_front();
            } else {
                break;
            }
        }
    }

    pub fn reset(&mut self) {
        self.samples.clear();
    }

    pub fn bps(&self) -> u64 {
        let now = Instant::now();
        let bytes: u64 = self
            .samples
            .iter()
            .filter(|(t, _)| now.duration_since(*t) <= SPEED_WINDOW)
            .map(|(_, b)| b)
            .sum();
        if bytes == 0 {
            return 0;
        }
        let oldest = self
            .samples
            .front()
            .map(|(t, _)| now.duration_since(*t))
            .unwrap_or_default();
        let secs = oldest.as_secs_f64().max(0.25);
        (bytes as f64 / secs) as u64
    }
}

pub(crate) struct TransferRecord {
    pub transfer_id: Uuid,
    pub batch_id: Uuid,
    pub file_id: Uuid,
    pub file_name: String,
    pub extension: String,
    pub mime_type: String,
    pub size_bytes: u64,
    pub sha256: Option<String>,
    pub created_at_ms: i64,
    pub modified_at_ms: i64,
    pub offered_at_ms: i64,
    pub auto_accept: bool,
    pub device_id: Uuid,
    pub device_name: String,
    pub direction: Direction,
    pub state: TransferState,
    pub error: Option<String>,
    pub bytes_transferred: u64,
    pub started_at_ms: Option<i64>,
    pub source_path: Option<PathBuf>,
    pub verified: bool,
    pub seq: u64,
    pub speed: SpeedWindow,
    pub last_emit: Option<Instant>,
    /// True for a §7 push *we* make to a peer host (peers.rs), as opposed to
    /// an offer a client pulls from us.
    ///
    /// These records share the queue and history with everything else — that
    /// is the point — but they must never touch the host-side machinery:
    /// they are not offers, so they are excluded from the outbox, from
    /// `offers.json`, from accept/reject/status, from the download route and
    /// from `retry_transfer`. Without this flag a peer that is *also* paired
    /// to us (same deviceId, both directions) would find our outgoing pushes
    /// in its outbox.
    pub is_peer: bool,
}

impl TransferRecord {
    pub fn summary(&self) -> TransferSummary {
        let speed_bps = if matches!(
            self.state,
            TransferState::Transferring | TransferState::Hashing
        ) {
            self.speed.bps()
        } else {
            0
        };
        let eta_seconds = if speed_bps > 0 && self.state == TransferState::Transferring {
            Some(self.size_bytes.saturating_sub(self.bytes_transferred) / speed_bps)
        } else {
            None
        };
        TransferSummary {
            transfer_id: self.transfer_id,
            batch_id: self.batch_id,
            file_name: self.file_name.clone(),
            size_bytes: self.size_bytes,
            sha256: self.sha256.clone(),
            state: self.state,
            error: self.error.clone(),
            device_id: self.device_id,
            device_name: self.device_name.clone(),
            direction: self.direction.as_str().to_string(),
            is_peer: self.is_peer,
            bytes_transferred: self.bytes_transferred,
            speed_bps,
            eta_seconds,
            started_at_ms: self.started_at_ms,
            source_path: self
                .source_path
                .as_ref()
                .map(|p| p.to_string_lossy().into_owned()),
        }
    }

    pub fn wire(&self, sender_name: &str) -> Option<TransferWire> {
        Some(TransferWire {
            transfer_id: self.transfer_id,
            batch_id: self.batch_id,
            file_id: self.file_id,
            file_name: self.file_name.clone(),
            extension: self.extension.clone(),
            mime_type: self.mime_type.clone(),
            size_bytes: self.size_bytes,
            sha256: self.sha256.clone()?,
            created_at_ms: self.created_at_ms,
            modified_at_ms: self.modified_at_ms,
            offered_at_ms: self.offered_at_ms,
            sender_name: sender_name.to_string(),
            auto_accept: self.auto_accept,
        })
    }

    fn expired(&self, now: i64) -> bool {
        self.state == TransferState::Offered && now - self.offered_at_ms > OFFER_TTL_MS
    }
}

fn file_times(meta: &std::fs::Metadata) -> (i64, i64) {
    let to_ms = |t: std::io::Result<std::time::SystemTime>| -> i64 {
        t.ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_millis() as i64)
            .unwrap_or_else(now_ms)
    };
    (to_ms(meta.created()), to_ms(meta.modified()))
}

fn mime_for(path: &Path) -> String {
    mime_guess::from_path(path)
        .first_raw()
        .unwrap_or("application/octet-stream")
        .to_string()
}

fn extension_of(path: &Path) -> String {
    path.extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_string()
}

fn is_hidden_name(name: &str) -> bool {
    name.starts_with('.')
}

/// Expand files + folders (recursive, skipping hidden entries) into a flat
/// file list. Symlinks are not followed.
pub(crate) fn expand_paths(paths: &[PathBuf]) -> anyhow::Result<Vec<PathBuf>> {
    let mut files = Vec::new();
    for path in paths {
        let meta = std::fs::metadata(path)
            .map_err(|e| anyhow::anyhow!("cannot access {}: {e}", path.display()))?;
        if meta.is_file() {
            files.push(path.clone());
        } else if meta.is_dir() {
            for entry in WalkDir::new(path)
                .follow_links(false)
                .into_iter()
                .filter_entry(|e| {
                    // Keep the root even if the dropped folder itself is
                    // dot-named; skip hidden children.
                    e.depth() == 0
                        || e.file_name()
                            .to_str()
                            .map(|n| !is_hidden_name(n))
                            .unwrap_or(true)
                })
            {
                let entry = entry?;
                if entry.file_type().is_file() {
                    files.push(entry.into_path());
                }
            }
        }
    }
    Ok(files)
}

impl Core {
    fn next_seq(&self) -> u64 {
        self.seq
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed)
    }

    /// Emit a TransferUpdated for `id`, throttled to ~4/sec unless `force`.
    pub(crate) fn emit_transfer(&self, id: Uuid, force: bool) {
        let summary = {
            let mut transfers = self.transfers.write();
            let Some(rec) = transfers.get_mut(&id) else {
                return;
            };
            if !force {
                if let Some(last) = rec.last_emit {
                    if last.elapsed() < EMIT_INTERVAL {
                        return;
                    }
                }
            }
            rec.last_emit = Some(Instant::now());
            rec.summary()
        };
        self.emit(CoreEvent::TransferUpdated { transfer: summary });
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /// Offer files (and folders, expanded recursively) to a paired device.
    /// Each file is hashed with streaming SHA-256 (state `Hashing`, progress
    /// events emitted) and then published to the device's outbox
    /// (state `Offered`).
    pub async fn offer_files(
        &self,
        device_id: Uuid,
        paths: Vec<PathBuf>,
        auto_accept: bool,
    ) -> anyhow::Result<Vec<TransferSummary>> {
        let device = self
            .trusted_device(device_id)
            .ok_or_else(|| anyhow::anyhow!("device {device_id} is not paired"))?;
        let files = expand_paths(&paths)?;
        if files.is_empty() {
            anyhow::bail!("no files to offer");
        }

        let batch_id = Uuid::new_v4();
        let mut ids = Vec::with_capacity(files.len());

        // Queue everything first so the UI sees the whole batch immediately.
        for path in &files {
            let meta = std::fs::metadata(path)
                .map_err(|e| anyhow::anyhow!("cannot stat {}: {e}", path.display()))?;
            let (created_at_ms, modified_at_ms) = file_times(&meta);
            let file_name = crate::filename::sanitize(
                path.file_name()
                    .and_then(|n| n.to_str())
                    .unwrap_or("file"),
            );
            let rec = TransferRecord {
                transfer_id: Uuid::new_v4(),
                batch_id,
                file_id: Uuid::new_v4(),
                file_name,
                extension: extension_of(path),
                mime_type: mime_for(path),
                size_bytes: meta.len(),
                sha256: None,
                created_at_ms,
                modified_at_ms,
                offered_at_ms: 0,
                auto_accept,
                device_id,
                device_name: device.device_name.clone(),
                direction: Direction::Outgoing,
                state: TransferState::Queued,
                error: None,
                bytes_transferred: 0,
                started_at_ms: None,
                source_path: Some(path.clone()),
                verified: false,
                seq: self.next_seq(),
                speed: SpeedWindow::default(),
                last_emit: None,
                is_peer: false,
            };
            let id = rec.transfer_id;
            self.transfers.write().insert(id, rec);
            ids.push(id);
            self.emit_transfer(id, true);
        }

        // Hash sequentially (disk-bound anyway), streaming in ~1 MiB chunks.
        for &id in &ids {
            let path = {
                let mut transfers = self.transfers.write();
                let rec = transfers.get_mut(&id).expect("record just inserted");
                rec.state = TransferState::Hashing;
                rec.source_path.clone().expect("outgoing has source")
            };
            self.emit_transfer(id, true);

            let hash_result = crate::hashing::sha256_file(&path, |hashed| {
                if let Some(rec) = self.transfers.write().get_mut(&id) {
                    rec.bytes_transferred = hashed;
                    rec.speed.push(0); // keep window warm without fake speed
                }
                self.emit_transfer(id, false);
            })
            .await;

            match hash_result {
                Ok((hex, total)) => {
                    if let Some(rec) = self.transfers.write().get_mut(&id) {
                        rec.sha256 = Some(hex);
                        rec.size_bytes = total;
                        rec.bytes_transferred = 0;
                        rec.state = TransferState::Offered;
                        rec.offered_at_ms = now_ms();
                        rec.speed.reset();
                    }
                }
                Err(e) => {
                    if let Some(rec) = self.transfers.write().get_mut(&id) {
                        rec.state = TransferState::Failed;
                        rec.error = Some(format!("hashing failed: {e}"));
                    }
                }
            }
            self.emit_transfer(id, true);
        }

        self.save_offers();
        self.notify_device(device_id);

        let transfers = self.transfers.read();
        Ok(ids
            .iter()
            .filter_map(|id| transfers.get(id).map(|r| r.summary()))
            .collect())
    }

    /// Offers currently deliverable to `device_id` via the outbox:
    /// published (`Offered`) plus `Interrupted` ones (idempotent
    /// re-delivery lets the client resume after a host restart; it dedupes
    /// by transferId). Empty while the global pause gate is on.
    pub(crate) fn pending_offers_for(&self, device_id: Uuid) -> Vec<TransferWire> {
        if self.is_paused() {
            return Vec::new();
        }
        let sender_name = self.settings.read().device_name.clone();
        let now = now_ms();
        let mut records: Vec<TransferWire> = Vec::new();
        let transfers = self.transfers.read();
        let mut list: Vec<&TransferRecord> = transfers
            .values()
            .filter(|r| {
                r.direction == Direction::Outgoing
                    && !r.is_peer
                    && r.device_id == device_id
                    && matches!(
                        r.state,
                        TransferState::Offered | TransferState::Interrupted
                    )
                    && !r.expired(now)
            })
            .collect();
        list.sort_by_key(|r| r.seq);
        for rec in list {
            if let Some(wire) = rec.wire(&sender_name) {
                records.push(wire);
            }
        }
        records
    }

    /// `POST /transfers/{id}/accept` — only by the device it was offered to.
    pub(crate) fn accept_transfer(&self, id: Uuid, device_id: Uuid) -> Result<(), TransferError> {
        self.with_record_for(id, device_id, |rec| match rec.state {
            TransferState::Offered | TransferState::Interrupted => {
                rec.state = TransferState::Accepted;
                Ok(())
            }
            _ => Err(TransferError::Conflict),
        })??;
        self.save_offers();
        self.emit_transfer(id, true);
        Ok(())
    }

    /// `POST /transfers/{id}/reject`.
    pub(crate) fn reject_transfer(&self, id: Uuid, device_id: Uuid) -> Result<(), TransferError> {
        self.with_record_for(id, device_id, |rec| match rec.state {
            TransferState::Offered
            | TransferState::Accepted
            | TransferState::Interrupted => {
                rec.state = TransferState::Rejected;
                Ok(())
            }
            _ => Err(TransferError::Conflict),
        })??;
        self.save_offers();
        self.emit_transfer(id, true);
        Ok(())
    }

    /// `POST /transfers/{id}/status` — mirror client-reported state (§6.5).
    pub(crate) fn report_status(
        &self,
        id: Uuid,
        device_id: Uuid,
        state: &str,
        bytes_received: Option<u64>,
        mut error: Option<String>,
    ) -> Result<(), TransferError> {
        let mut completed_now = false;
        self.with_record_for(id, device_id, |rec| {
            if rec.state.is_terminal() && state != "completed" {
                // Late/duplicate report after a terminal transition.
                return Ok(());
            }
            if let Some(bytes) = bytes_received {
                rec.bytes_transferred = bytes.min(rec.size_bytes);
            }
            match state {
                "downloading" => rec.state = TransferState::Transferring,
                "verifying" => rec.state = TransferState::Verifying,
                // `verified` keeps the host in Verifying until `completed`.
                "verified" => {
                    rec.state = TransferState::Verifying;
                    rec.verified = true;
                }
                "saving" => rec.state = TransferState::Saving,
                "completed" => {
                    if rec.state != TransferState::Completed {
                        rec.state = TransferState::Completed;
                        rec.bytes_transferred = rec.size_bytes;
                        completed_now = true;
                    }
                }
                "failed" => {
                    rec.state = TransferState::Failed;
                    rec.error = Some(match error.take() {
                        Some(e) if e == "integrity" => "IntegrityMismatch".to_string(),
                        Some(e) => e,
                        None => "failed".to_string(),
                    });
                }
                "cancelled" => rec.state = TransferState::Cancelled,
                _ => return Err(TransferError::BadRequest),
            }
            Ok(())
        })??;
        if completed_now {
            self.record_history(id);
        }
        self.save_offers();
        self.emit_transfer(id, true);
        Ok(())
    }

    /// Run `f` on the record if it exists AND belongs to `device_id`
    /// (otherwise NotFound — we never reveal other devices' transfers).
    fn with_record_for<T>(
        &self,
        id: Uuid,
        device_id: Uuid,
        f: impl FnOnce(&mut TransferRecord) -> T,
    ) -> Result<T, TransferError> {
        let mut transfers = self.transfers.write();
        match transfers.get_mut(&id) {
            // `!is_peer`: our own pushes to a peer are not that peer's
            // transfers, even though they carry its deviceId.
            Some(rec) if rec.device_id == device_id && !rec.is_peer => Ok(f(rec)),
            _ => Err(TransferError::NotFound),
        }
    }

    // ------------------------------------------------------------------
    // Download bookkeeping (used by the file handler)
    // ------------------------------------------------------------------

    /// Try to reserve a streaming slot for `id`. At most `concurrency`
    /// distinct transfers may stream simultaneously; a reconnect on an
    /// already-active transfer is always allowed.
    pub(crate) fn try_acquire_stream(&self, id: Uuid) -> bool {
        let limit = self.concurrency();
        let mut active = self.active_streams.lock();
        if !active.contains_key(&id) && active.len() >= limit {
            return false;
        }
        *active.entry(id).or_insert(0) += 1;
        true
    }

    /// Called from the counting stream on every chunk served.
    pub(crate) fn note_bytes_served(&self, id: Uuid, absolute_position: u64, chunk: u64) {
        {
            let mut transfers = self.transfers.write();
            if let Some(rec) = transfers.get_mut(&id) {
                rec.bytes_transferred = rec.bytes_transferred.max(absolute_position);
                rec.speed.push(chunk);
            }
        }
        self.emit_transfer(id, false);
    }

    /// Called exactly once when a download stream ends (fully served or not).
    pub(crate) fn note_stream_end(&self, id: Uuid, completed: bool) {
        {
            let mut active = self.active_streams.lock();
            if let Some(count) = active.get_mut(&id) {
                *count -= 1;
                if *count == 0 {
                    active.remove(&id);
                }
            }
        }
        if !completed {
            let mut changed = false;
            {
                let mut transfers = self.transfers.write();
                if let Some(rec) = transfers.get_mut(&id) {
                    // Client went away mid-stream → resumable Interrupted,
                    // unless the client already reported a later state.
                    if rec.state == TransferState::Transferring {
                        rec.state = TransferState::Interrupted;
                        rec.speed.reset();
                        changed = true;
                    }
                }
            }
            if changed {
                self.save_offers();
            }
        }
        self.emit_transfer(id, true);
    }

    /// Move a record into Transferring when a download stream opens.
    pub(crate) fn mark_transferring(&self, id: Uuid, start_offset: u64) {
        {
            let mut transfers = self.transfers.write();
            if let Some(rec) = transfers.get_mut(&id) {
                rec.state = TransferState::Transferring;
                rec.bytes_transferred = start_offset;
                if rec.started_at_ms.is_none() {
                    rec.started_at_ms = Some(now_ms());
                }
                rec.speed.reset();
            }
        }
        self.save_offers();
        self.emit_transfer(id, true);
    }

    pub(crate) fn mark_failed(&self, id: Uuid, error: &str) {
        {
            let mut transfers = self.transfers.write();
            if let Some(rec) = transfers.get_mut(&id) {
                rec.state = TransferState::Failed;
                rec.error = Some(error.to_string());
            }
        }
        self.save_offers();
        self.emit_transfer(id, true);
    }

    // ------------------------------------------------------------------
    // Public queue API (CORE_API.md)
    // ------------------------------------------------------------------

    pub fn queue(&self) -> Vec<TransferSummary> {
        let transfers = self.transfers.read();
        let mut list: Vec<&TransferRecord> = transfers.values().collect();
        list.sort_by_key(|r| r.seq);
        list.iter().map(|r| r.summary()).collect()
    }

    pub fn cancel_transfer(&self, id: Uuid) -> bool {
        let (cancelled, device_id) = {
            let mut transfers = self.transfers.write();
            match transfers.get_mut(&id) {
                Some(rec) if !rec.state.is_terminal() => {
                    rec.state = TransferState::Cancelled;
                    (true, Some(rec.device_id))
                }
                _ => (false, None),
            }
        };
        if cancelled {
            self.save_offers();
            self.emit_transfer(id, true);
            if let Some(device_id) = device_id {
                self.notify_device(device_id);
            }
        }
        cancelled
    }

    /// Re-offer a Failed/Interrupted/Expired transfer.
    pub fn retry_transfer(&self, id: Uuid) -> bool {
        let device_id = {
            let mut transfers = self.transfers.write();
            match transfers.get_mut(&id) {
                Some(rec)
                    if rec.direction == Direction::Outgoing
                        && !rec.is_peer
                        && matches!(
                            rec.state,
                            TransferState::Failed
                                | TransferState::Interrupted
                                | TransferState::Expired
                        )
                        && rec.sha256.is_some() =>
                {
                    rec.state = TransferState::Offered;
                    rec.error = None;
                    rec.offered_at_ms = now_ms();
                    Some(rec.device_id)
                }
                _ => None,
            }
        };
        match device_id {
            Some(device_id) => {
                self.save_offers();
                self.emit_transfer(id, true);
                self.notify_device(device_id);
                true
            }
            None => false,
        }
    }

    pub fn clear_completed(&self) {
        {
            let mut transfers = self.transfers.write();
            transfers.retain(|_, r| !r.state.is_terminal());
        }
        self.save_offers();
    }

    // ------------------------------------------------------------------
    // Persistence of active offers (restart survival)
    // ------------------------------------------------------------------

    pub(crate) fn save_offers(&self) {
        let snapshot: Vec<PersistedOffer> = {
            let transfers = self.transfers.read();
            let mut list: Vec<&TransferRecord> = transfers
                .values()
                .filter(|r| {
                    r.direction == Direction::Outgoing
                        && !r.is_peer
                        && !r.state.is_terminal()
                        && r.sha256.is_some()
                })
                .collect();
            list.sort_by_key(|r| r.seq);
            list.iter()
                .map(|r| PersistedOffer {
                    transfer_id: r.transfer_id,
                    batch_id: r.batch_id,
                    file_id: r.file_id,
                    file_name: r.file_name.clone(),
                    extension: r.extension.clone(),
                    mime_type: r.mime_type.clone(),
                    size_bytes: r.size_bytes,
                    sha256: r.sha256.clone().expect("filtered on sha256"),
                    created_at_ms: r.created_at_ms,
                    modified_at_ms: r.modified_at_ms,
                    offered_at_ms: r.offered_at_ms,
                    auto_accept: r.auto_accept,
                    device_id: r.device_id,
                    device_name: r.device_name.clone(),
                    source_path: r
                        .source_path
                        .as_ref()
                        .map(|p| p.to_string_lossy().into_owned())
                        .unwrap_or_default(),
                    state: r.state,
                    bytes_transferred: r.bytes_transferred,
                    started_at_ms: r.started_at_ms,
                })
                .collect()
        };
        if let Err(e) =
            state::atomic_write_json(&self.data_dir.join(state::OFFERS_FILE), &snapshot)
        {
            tracing::error!("failed to persist offers: {e}");
        }
    }

    /// Restore active offers from a previous run. `Offered` stays `Offered`;
    /// anything that had progressed comes back as `Interrupted` so the
    /// client can resume with a ranged request. Missing/changed source
    /// files come back as `Failed`.
    pub(crate) fn restore_offers(&self) {
        let offers: Vec<PersistedOffer> =
            state::load_json(&self.data_dir.join(state::OFFERS_FILE)).unwrap_or_default();
        if offers.is_empty() {
            return;
        }
        let mut transfers = self.transfers.write();
        for offer in offers {
            let path = PathBuf::from(&offer.source_path);
            let (state, error) = match std::fs::metadata(&path) {
                Ok(meta) if meta.len() == offer.size_bytes => match offer.state {
                    TransferState::Offered => (TransferState::Offered, None),
                    _ => (TransferState::Interrupted, None),
                },
                Ok(_) => (
                    TransferState::Failed,
                    Some("source file changed on disk".to_string()),
                ),
                Err(_) => (
                    TransferState::Failed,
                    Some("source file missing".to_string()),
                ),
            };
            let rec = TransferRecord {
                transfer_id: offer.transfer_id,
                batch_id: offer.batch_id,
                file_id: offer.file_id,
                file_name: offer.file_name,
                extension: offer.extension,
                mime_type: offer.mime_type,
                size_bytes: offer.size_bytes,
                sha256: Some(offer.sha256),
                created_at_ms: offer.created_at_ms,
                modified_at_ms: offer.modified_at_ms,
                offered_at_ms: offer.offered_at_ms,
                auto_accept: offer.auto_accept,
                device_id: offer.device_id,
                device_name: offer.device_name,
                direction: Direction::Outgoing,
                state,
                error,
                bytes_transferred: offer.bytes_transferred,
                started_at_ms: offer.started_at_ms,
                source_path: Some(path),
                verified: false,
                seq: self.next_seq(),
                speed: SpeedWindow::default(),
                last_emit: None,
                is_peer: false,
            };
            transfers.insert(rec.transfer_id, rec);
        }
    }

    /// Expire offers older than 24 h (maintenance sweep).
    pub(crate) fn expire_stale_offers(&self) {
        let now = now_ms();
        let expired: Vec<Uuid> = {
            let mut transfers = self.transfers.write();
            let ids: Vec<Uuid> = transfers
                .values()
                .filter(|r| r.expired(now))
                .map(|r| r.transfer_id)
                .collect();
            for id in &ids {
                if let Some(rec) = transfers.get_mut(id) {
                    rec.state = TransferState::Expired;
                }
            }
            ids
        };
        if !expired.is_empty() {
            self.save_offers();
            for id in expired {
                self.emit_transfer(id, true);
            }
        }
    }
}

/// Errors surfaced by transfer operations, mapped to HTTP in server.rs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransferError {
    NotFound,
    Conflict,
    BadRequest,
}
