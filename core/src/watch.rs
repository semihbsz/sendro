//! Watch folders with a poll-based stabilizer.
//!
//! Every `watch_poll` (default 2 s) each enabled folder is scanned. A file
//! is "ready" when its size is > 0 and `(size, mtime)` has been unchanged
//! for `watch_stable_polls` (default 3) consecutive polls, and its name
//! does not match temp patterns (extensions tmp/part/partial/crdownload/
//! download/temp; prefixes "." and "~$").
//!
//! Ready + `auto_send` + a target device → offered directly with
//! `auto_accept = true` and `WatchFileDetected { auto: true }` is emitted.
//! Otherwise `WatchFileDetected { auto: false }` is emitted and the file is
//! held for [`Core::resolve_detected_file`].

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::SystemTime;

use anyhow::Context;
use tokio::sync::watch as tokio_watch;
use uuid::Uuid;
use walkdir::WalkDir;

use crate::types::WatchFolderConfig;
use crate::{Core, CoreEvent};

const TEMP_EXTENSIONS: &[&str] = &["tmp", "part", "partial", "crdownload", "download", "temp"];
const TEMP_PREFIXES: &[&str] = &[".", "~$"];

/// A detected-but-unsent file awaiting a Send/Ignore decision from the UI.
#[derive(Debug, Clone)]
pub(crate) struct PendingDetection {
    pub path: PathBuf,
    pub folder_id: Uuid,
}

/// True if `file_name` looks like an in-progress/temporary file.
pub fn is_temp_name(file_name: &str) -> bool {
    if TEMP_PREFIXES.iter().any(|p| file_name.starts_with(p)) {
        return true;
    }
    match file_name.rsplit_once('.') {
        Some((_, ext)) => TEMP_EXTENSIONS
            .iter()
            .any(|t| ext.eq_ignore_ascii_case(t)),
        None => false,
    }
}

#[derive(Debug, Clone, PartialEq)]
struct Snapshot {
    size: u64,
    mtime: SystemTime,
}

#[derive(Debug)]
struct FileTrack {
    snap: Snapshot,
    /// Consecutive polls (including the first observation) with an
    /// unchanged snapshot.
    stable_polls: u32,
    /// Already detected/offered; re-armed if the file changes again.
    handled: bool,
}

impl Core {
    // -- public watch-folder API (CORE_API.md) ------------------------------

    pub fn add_watch_folder(&self, cfg: WatchFolderConfig) -> anyhow::Result<()> {
        let meta = std::fs::metadata(&cfg.path)
            .with_context(|| format!("watch folder path {}", cfg.path))?;
        anyhow::ensure!(meta.is_dir(), "watch folder path is not a directory");
        {
            // Upsert by id: the desktop UI edits a folder (auto-send toggle,
            // target device, enabled) by re-submitting the same-id config.
            let mut folders = self.watch_folders.write();
            if let Some(existing) = folders.iter_mut().find(|f| f.id == cfg.id) {
                *existing = cfg;
            } else {
                folders.push(cfg);
            }
        }
        self.save_watch_folders();
        Ok(())
    }

    pub fn remove_watch_folder(&self, id: Uuid) -> bool {
        let removed = {
            let mut folders = self.watch_folders.write();
            let before = folders.len();
            folders.retain(|f| f.id != id);
            folders.len() != before
        };
        if removed {
            self.pending_detections.lock().retain(|_, d| d.folder_id != id);
            self.save_watch_folders();
        }
        removed
    }

    pub fn watch_folders(&self) -> Vec<WatchFolderConfig> {
        self.watch_folders.read().clone()
    }

    /// Resolve a `WatchFileDetected { auto: false }` from the UI:
    /// `send = true` offers the file to the folder's target device,
    /// `send = false` discards the detection.
    pub fn resolve_detected_file(&self, detection_id: Uuid, send: bool) {
        let Some(detection) = self.pending_detections.lock().remove(&detection_id) else {
            return;
        };
        if !send {
            return;
        }
        let target = self
            .watch_folders
            .read()
            .iter()
            .find(|f| f.id == detection.folder_id)
            .and_then(|f| f.target_device_id);
        let Some(device_id) = target else {
            tracing::warn!(
                "resolve_detected_file({detection_id}): watch folder has no target device"
            );
            return;
        };
        // Offer in the background; hashing may take a while for big files.
        let core = self.clone_arc();
        tokio::spawn(async move {
            if let Err(e) = core
                .offer_files(device_id, vec![detection.path], false)
                .await
            {
                tracing::error!("watch-folder manual send failed: {e}");
            }
        });
    }

    /// Upgrade `&self` to the owning Arc (Core is only ever Arc-shared;
    /// background tasks hold clones).
    pub(crate) fn clone_arc(&self) -> Arc<Core> {
        // Safety-free approach: Core keeps a weak self-reference set at
        // start(); see `self_ref`.
        self.self_ref
            .read()
            .as_ref()
            .and_then(|w| w.upgrade())
            .expect("Core::start stores the self reference before spawning tasks")
    }
}

/// The stabilizer loop; spawned by [`Core::start`].
pub(crate) async fn watch_loop(core: Arc<Core>, mut shutdown: tokio_watch::Receiver<bool>) {
    let mut tracks: HashMap<Uuid, HashMap<PathBuf, FileTrack>> = HashMap::new();
    let poll = core.watch_poll;
    let needed = core.watch_stable_polls;
    loop {
        tokio::select! {
            _ = tokio::time::sleep(poll) => {}
            _ = shutdown.wait_for(|v| *v) => break,
        }
        let folders = core.watch_folders();
        tracks.retain(|id, _| folders.iter().any(|f| f.id == *id && f.enabled));
        for folder in folders.iter().filter(|f| f.enabled) {
            let folder_tracks = tracks.entry(folder.id).or_default();
            poll_folder(&core, folder, folder_tracks, needed).await;
        }
    }
}

async fn poll_folder(
    core: &Arc<Core>,
    folder: &WatchFolderConfig,
    tracks: &mut HashMap<PathBuf, FileTrack>,
    needed_polls: u32,
) {
    let root = PathBuf::from(&folder.path);
    let mut seen: Vec<PathBuf> = Vec::new();

    // Directory scan is cheap (metadata only); run on a blocking thread so
    // a slow network drive cannot stall the runtime.
    let entries = {
        let root = root.clone();
        tokio::task::spawn_blocking(move || {
            let mut out = Vec::new();
            for entry in WalkDir::new(&root)
                .follow_links(false)
                .into_iter()
                .filter_entry(|e| {
                    e.depth() == 0
                        || e.file_name()
                            .to_str()
                            .map(|n| !n.starts_with('.'))
                            .unwrap_or(true)
                })
                .flatten()
            {
                if !entry.file_type().is_file() {
                    continue;
                }
                let Some(name) = entry.file_name().to_str().map(String::from) else {
                    continue;
                };
                if is_temp_name(&name) {
                    continue;
                }
                if let Ok(meta) = entry.metadata() {
                    let mtime = meta.modified().unwrap_or(SystemTime::UNIX_EPOCH);
                    out.push((entry.into_path(), meta.len(), mtime));
                }
            }
            out
        })
        .await
        .unwrap_or_default()
    };

    for (path, size, mtime) in entries {
        seen.push(path.clone());
        let snap = Snapshot { size, mtime };
        let ready = match tracks.get_mut(&path) {
            Some(track) => {
                if track.snap == snap {
                    track.stable_polls = track.stable_polls.saturating_add(1);
                } else {
                    // Still being written — restart the stability countdown
                    // and re-arm handled files (a new version appeared).
                    track.snap = snap;
                    track.stable_polls = 1;
                    track.handled = false;
                }
                !track.handled && track.snap.size > 0 && track.stable_polls >= needed_polls
            }
            None => {
                tracks.insert(
                    path.clone(),
                    FileTrack {
                        snap,
                        stable_polls: 1,
                        handled: false,
                    },
                );
                // A brand-new file is never ready on first sight, even with
                // needed_polls == 1? No: honor the configured threshold.
                needed_polls <= 1 && size > 0
            }
        };
        if ready {
            if let Some(track) = tracks.get_mut(&path) {
                track.handled = true;
            }
            handle_ready_file(core, folder, path, size).await;
        }
    }

    // Forget files that disappeared so a re-created file is re-detected.
    tracks.retain(|path, _| seen.contains(path));
}

async fn handle_ready_file(
    core: &Arc<Core>,
    folder: &WatchFolderConfig,
    path: PathBuf,
    size_bytes: u64,
) {
    let detection_id = Uuid::new_v4();
    let file_name = path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("file")
        .to_string();
    let auto = folder.auto_send
        && folder
            .target_device_id
            .map(|d| core.trusted_device(d).is_some())
            .unwrap_or(false);

    core.emit(CoreEvent::WatchFileDetected {
        detection_id,
        path: path.to_string_lossy().into_owned(),
        folder_id: folder.id,
        file_name,
        size_bytes,
        auto,
    });

    if auto {
        let device_id = folder.target_device_id.expect("checked above");
        let core = core.clone();
        // Hashing can take minutes for big renders — never block the poll loop.
        tokio::spawn(async move {
            if let Err(e) = core.offer_files(device_id, vec![path], true).await {
                tracing::error!("watch-folder auto-send failed: {e}");
            }
        });
    } else {
        core.pending_detections.lock().insert(
            detection_id,
            PendingDetection {
                path,
                folder_id: folder.id,
            },
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn temp_patterns() {
        for name in [
            "video.tmp",
            "video.TMP",
            "movie.part",
            "movie.partial",
            "chrome.crdownload",
            "safari.download",
            "x.temp",
            ".DS_Store",
            ".hidden.mp4",
            "~$report.docx",
        ] {
            assert!(is_temp_name(name), "{name} should be temp");
        }
        for name in ["final.mp4", "IMG_4822.HEIC", "raw", "a.tmp.mov"] {
            assert!(!is_temp_name(name), "{name} should not be temp");
        }
    }
}
