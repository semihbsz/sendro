//! Thin 1:1 Tauri command wrappers around the `sendro-core` API.
//! Names mirror CORE_API.md exactly (snake_case).

use std::path::PathBuf;
use std::sync::atomic::Ordering;

use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_autostart::ManagerExt as _;
use tauri_plugin_clipboard_manager::ClipboardExt as _;
use tauri_plugin_opener::OpenerExt as _;
use uuid::Uuid;

use sendro_core::{
    HistoryEntry, HostInfo, IncomingMessage, Settings, TransferSummary, TrustedDevice,
    WatchFolderConfig,
};

use crate::{tray::TrayState, AppState};

#[tauri::command]
pub fn get_info(state: State<'_, AppState>) -> HostInfo {
    state.core.info()
}

#[tauri::command]
pub fn get_settings(state: State<'_, AppState>) -> Settings {
    state.core.settings()
}

#[tauri::command]
pub fn update_settings(
    app: AppHandle,
    state: State<'_, AppState>,
    settings: Settings,
) -> Result<(), String> {
    let launch_on_startup = settings.launch_on_startup;
    state
        .core
        .update_settings(settings)
        .map_err(|e| e.to_string())?;

    // Keep the OS registration in sync with the persisted setting.
    let autolaunch = app.autolaunch();
    let result = if launch_on_startup {
        autolaunch.enable()
    } else {
        autolaunch.disable()
    };
    if let Err(err) = result {
        log::warn!("autostart sync failed: {err}");
    }
    Ok(())
}

#[tauri::command]
pub fn trusted_devices(state: State<'_, AppState>) -> Vec<TrustedDevice> {
    state.core.trusted_devices()
}

#[tauri::command]
pub fn revoke_device(state: State<'_, AppState>, device_id: Uuid) -> bool {
    state.core.revoke_device(device_id)
}

#[tauri::command]
pub async fn offer_files(
    state: State<'_, AppState>,
    device_id: Uuid,
    paths: Vec<PathBuf>,
    auto_accept: bool,
) -> Result<Vec<TransferSummary>, String> {
    state
        .core
        .offer_files(device_id, paths, auto_accept)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub fn get_queue(state: State<'_, AppState>) -> Vec<TransferSummary> {
    state.core.queue()
}

#[tauri::command]
pub fn cancel_transfer(state: State<'_, AppState>, id: Uuid) -> bool {
    state.core.cancel_transfer(id)
}

#[tauri::command]
pub fn retry_transfer(state: State<'_, AppState>, id: Uuid) -> bool {
    state.core.retry_transfer(id)
}

#[tauri::command]
pub fn pause_transfers(app: AppHandle, state: State<'_, AppState>, paused: bool) {
    state.core.pause_transfers(paused);
    state.paused.store(paused, Ordering::SeqCst);

    // Mirror into the tray's checkable menu item and notify the webview
    // (tray toggles go the other way through the same event).
    if let Some(tray) = app.try_state::<TrayState>() {
        let _ = tray.pause_item.set_checked(paused);
    }
    let _ = app.emit("sendro://paused", serde_json::json!({ "paused": paused }));
}

#[tauri::command]
pub fn clear_completed(state: State<'_, AppState>) {
    state.core.clear_completed();
}

#[tauri::command]
pub fn get_history(state: State<'_, AppState>) -> Vec<HistoryEntry> {
    state.core.history()
}

#[tauri::command]
pub fn clear_history(state: State<'_, AppState>) {
    state.core.clear_history();
}

#[tauri::command]
pub fn watch_folders(state: State<'_, AppState>) -> Vec<WatchFolderConfig> {
    state.core.watch_folders()
}

#[tauri::command]
pub fn add_watch_folder(state: State<'_, AppState>, cfg: WatchFolderConfig) -> Result<(), String> {
    state.core.add_watch_folder(cfg).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn remove_watch_folder(state: State<'_, AppState>, id: Uuid) -> bool {
    state.core.remove_watch_folder(id)
}

#[tauri::command]
pub fn resolve_detected_file(state: State<'_, AppState>, detection_id: Uuid, send: bool) {
    state.core.resolve_detected_file(detection_id, send);
}

// ---------------------------------------------------------------------------
// Ephemeral text messages (PROTOCOL.md §11) — RAM only, never persisted.
// ---------------------------------------------------------------------------

#[tauri::command]
pub fn send_message(
    state: State<'_, AppState>,
    device_id: Uuid,
    text: String,
) -> Result<(), String> {
    state
        .core
        .send_message(device_id, text)
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub fn incoming_messages(state: State<'_, AppState>) -> Vec<IncomingMessage> {
    state.core.incoming_messages()
}

#[tauri::command]
pub fn dismiss_message(state: State<'_, AppState>, id: Uuid) -> bool {
    state.core.dismiss_message(id)
}

#[tauri::command]
pub fn clear_messages(state: State<'_, AppState>) {
    state.core.clear_messages();
}

// ---------------------------------------------------------------------------
// Clipboard paste → temp PNG, fed into the normal offer flow.
// ---------------------------------------------------------------------------

/// Refuse absurd clipboard bitmaps before allocating an encoder buffer
/// (80 MP is well beyond any real screenshot).
const MAX_PASTE_PIXELS: u64 = 80_000_000;

/// Keep only filename-safe characters from the caller-supplied timestamp;
/// fall back to epoch seconds when nothing usable is left.
fn sanitize_stamp(stamp: Option<String>) -> String {
    let cleaned: String = stamp
        .unwrap_or_default()
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || matches!(c, ' ' | '-' | '_' | '.'))
        .take(48)
        .collect();
    let cleaned = cleaned.trim().to_string();
    if cleaned.is_empty() {
        let secs = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        secs.to_string()
    } else {
        cleaned
    }
}

/// Best-effort housekeeping: drop pasted temp files older than a day so the
/// scratch directory does not grow without bound.
fn prune_paste_dir(dir: &std::path::Path) {
    const MAX_AGE: std::time::Duration = std::time::Duration::from_secs(24 * 60 * 60);
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let stale = entry
            .metadata()
            .and_then(|m| m.modified())
            .map(|m| m.elapsed().map(|age| age > MAX_AGE).unwrap_or(false))
            .unwrap_or(false);
        if stale {
            let _ = std::fs::remove_file(entry.path());
        }
    }
}

/// Pull the clipboard bitmap out of the plugin as owned pixels.
///
/// Kept as a small *synchronous* helper so the borrowed `Clipboard` state and
/// the borrowed `Image` never live inside the async command's future — only
/// the owned `Vec<u8>` crosses back out.
fn read_clipboard_rgba(app: &AppHandle) -> Option<(Vec<u8>, u32, u32)> {
    // `Err` here just means "the clipboard does not hold a bitmap", which is
    // the common case (text, files, empty) — not an error worth surfacing.
    let image = app.clipboard().read_image().ok()?;
    let rgba = image.rgba().to_vec();
    Some((rgba, image.width(), image.height()))
}

/// Paste an image from the clipboard: read it, encode a PNG into
/// `%TEMP%/sendro-paste/` and return its path so the UI can feed it into the
/// ordinary `offer_files` flow. `Ok(None)` means the clipboard holds no
/// bitmap — the caller then tries text.
///
/// Async on purpose: the clipboard read and the PNG encode both happen off
/// the UI thread (the plugin explicitly warns against reading on the main
/// thread), and the raw pixels never cross the IPC boundary.
#[tauri::command]
pub async fn paste_clipboard_image(
    app: AppHandle,
    stamp: Option<String>,
) -> Result<Option<String>, String> {
    let Some((rgba, width, height)) = read_clipboard_rgba(&app) else {
        return Ok(None);
    };
    encode_pasted_png(rgba, width, height, stamp).map(Some)
}

/// Two pastes inside the same second must not collide: the returned path is
/// handed straight to `offer_files`, which hashes the file, so overwriting a
/// still-in-flight paste would corrupt it. Same ` (n)` convention as §8.
fn unique_paste_path(dir: &std::path::Path, stem: &str) -> std::path::PathBuf {
    let first = dir.join(format!("{stem}.png"));
    if !first.exists() {
        return first;
    }
    for n in 2..1000u32 {
        let candidate = dir.join(format!("{stem} ({n}).png"));
        if !candidate.exists() {
            return candidate;
        }
    }
    first
}

/// Encode raw RGBA to a PNG in `%TEMP%/sendro-paste/`, returning its path.
fn encode_pasted_png(
    mut rgba: Vec<u8>,
    width: u32,
    height: u32,
    stamp: Option<String>,
) -> Result<String, String> {
    if width == 0 || height == 0 {
        return Err("clipboard image has no pixels".to_string());
    }
    let pixels = u64::from(width) * u64::from(height);
    if pixels > MAX_PASTE_PIXELS {
        return Err("clipboard image is too large to paste".to_string());
    }
    let expected = (pixels * 4) as usize;
    if rgba.len() < expected {
        return Err(format!(
            "clipboard image data is truncated ({} of {expected} bytes)",
            rgba.len()
        ));
    }
    rgba.truncate(expected);

    let dir = std::env::temp_dir().join("sendro-paste");
    std::fs::create_dir_all(&dir)
        .map_err(|e| format!("could not create {}: {e}", dir.display()))?;
    prune_paste_dir(&dir);

    let path = unique_paste_path(&dir, &format!("Pasted {}", sanitize_stamp(stamp)));
    let buffer = image::RgbaImage::from_raw(width, height, rgba)
        .ok_or_else(|| "clipboard image data does not match its size".to_string())?;
    buffer
        .save_with_format(&path, image::ImageFormat::Png)
        .map_err(|e| format!("could not encode PNG: {e}"))?;
    Ok(path.to_string_lossy().into_owned())
}

/// Opens the configured receive folder in Explorer.
/// Reads the folder path via the settings JSON shape (`receiveDir`) so this
/// stays agnostic to the concrete Rust type core uses for the field.
#[tauri::command]
pub fn open_receive_folder(app: AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    let settings = serde_json::to_value(state.core.settings()).map_err(|e| e.to_string())?;
    let dir = settings
        .get("receiveDir")
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty())
        .ok_or_else(|| "receive folder is not configured".to_string())?
        .to_string();
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    app.opener()
        .open_path(dir, None::<&str>)
        .map_err(|e| e.to_string())
}
