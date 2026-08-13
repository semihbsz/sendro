//! Transfer history (history.json, capped at 500 entries, newest first).

use uuid::Uuid;

use crate::state::{self, HISTORY_CAP};
use crate::types::{now_ms, HistoryEntry};
use crate::Core;

impl Core {
    pub fn history(&self) -> Vec<HistoryEntry> {
        self.history.read().clone()
    }

    pub fn clear_history(&self) {
        self.history.write().clear();
        self.save_history();
    }

    pub(crate) fn save_history(&self) {
        let snapshot = self.history.read().clone();
        if let Err(e) =
            state::atomic_write_json(&self.data_dir.join(state::HISTORY_FILE), &snapshot)
        {
            tracing::error!("failed to persist history: {e}");
        }
    }

    /// Write a HistoryEntry for a transfer that just reached a final state
    /// worth recording (Completed — see PROTOCOL.md §6.5).
    pub(crate) fn record_history(&self, transfer_id: Uuid) {
        let entry = {
            let transfers = self.transfers.read();
            let Some(rec) = transfers.get(&transfer_id) else {
                return;
            };
            let ended_at_ms = now_ms();
            let started_at_ms = rec.started_at_ms.unwrap_or(ended_at_ms);
            let duration_ms = (ended_at_ms - started_at_ms).max(0);
            let avg_speed_bps = if duration_ms > 0 {
                (rec.size_bytes as u128 * 1000 / duration_ms as u128) as u64
            } else {
                0
            };
            HistoryEntry {
                transfer_id: rec.transfer_id,
                file_name: rec.file_name.clone(),
                direction: rec.summary().direction,
                peer_name: rec.device_name.clone(),
                size_bytes: rec.size_bytes,
                started_at_ms,
                ended_at_ms,
                duration_ms,
                avg_speed_bps,
                verified: rec.verified,
                final_state: rec.state,
            }
        };
        {
            let mut history = self.history.write();
            history.insert(0, entry);
            history.truncate(HISTORY_CAP);
        }
        self.save_history();
    }
}
