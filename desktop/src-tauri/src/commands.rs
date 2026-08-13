//! Thin 1:1 Tauri command wrappers around the `sendro-core` API.
//! Names mirror CORE_API.md exactly (snake_case).

use std::path::PathBuf;
use std::sync::atomic::Ordering;

use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_autostart::ManagerExt as _;
use tauri_plugin_opener::OpenerExt as _;
use uuid::Uuid;

use sendro_core::{
    HistoryEntry, HostInfo, Settings, TransferSummary, TrustedDevice, WatchFolderConfig,
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
