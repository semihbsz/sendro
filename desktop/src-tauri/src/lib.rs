//! Sendro desktop shell — thin Tauri glue around the `sendro-core` engine.
//!
//! Responsibilities:
//! * bootstrap `CoreConfig` from `settings.json` on disk (with sane defaults),
//! * start the core and hold `Arc<Core>` in managed state,
//! * re-emit every `CoreEvent` to the webview as the `"core-event"` Tauri event,
//! * system tray + minimize-to-tray + autostart.

mod commands;
mod tray;

use std::path::{Path, PathBuf};
use std::sync::atomic::AtomicBool;
use std::sync::Arc;

use anyhow::Context as _;
use serde::Deserialize;
use tauri::{AppHandle, Emitter, Manager, RunEvent, WindowEvent};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt as _};
use tokio::sync::broadcast::error::RecvError;

use sendro_core::{Core, CoreConfig, CoreEvent};

pub const DEFAULT_PORT: u16 = 48800;
pub const DEFAULT_CONCURRENCY: usize = 2;

/// Shared application state, managed by Tauri.
pub struct AppState {
    pub core: Arc<Core>,
    /// UI-facing mirror of the core's global pause gate (core exposes no getter).
    pub paused: AtomicBool,
}

/// Minimal, tolerant mirror of the core `Settings` JSON, used only to build
/// `CoreConfig` from `data_dir/settings.json` *before* the core is running.
/// Once started, the core owns settings (`settings()` / `update_settings()`).
#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct BootstrapSettings {
    device_name: Option<String>,
    receive_dir: Option<String>,
    preferred_port: Option<u16>,
    concurrency: Option<usize>,
    launch_on_startup: Option<bool>,
}

fn default_device_name() -> String {
    let name = gethostname::gethostname().to_string_lossy().trim().to_string();
    if name.is_empty() {
        "Sendro PC".to_string()
    } else {
        name
    }
}

fn default_receive_dir() -> PathBuf {
    dirs::download_dir()
        .or_else(|| dirs::home_dir().map(|h| h.join("Downloads")))
        .unwrap_or_else(|| PathBuf::from("."))
        .join("Sendro")
}

fn load_bootstrap_settings(data_dir: &Path) -> BootstrapSettings {
    let path = data_dir.join("settings.json");
    match std::fs::read(&path) {
        Ok(bytes) => serde_json::from_slice(&bytes).unwrap_or_default(),
        Err(_) => BootstrapSettings::default(),
    }
}

/// Show, restore and focus the main window (tray double-duty helper).
pub(crate) fn show_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

fn setup(app: &tauri::App) -> anyhow::Result<()> {
    let data_dir = app
        .path()
        .app_data_dir()
        .context("could not resolve app data dir")?;
    std::fs::create_dir_all(&data_dir)
        .with_context(|| format!("could not create data dir {}", data_dir.display()))?;

    let boot = load_bootstrap_settings(&data_dir);

    let receive_dir = boot
        .receive_dir
        .as_deref()
        .filter(|p| !p.trim().is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(default_receive_dir);
    // Best effort — the core creates it on demand too.
    let _ = std::fs::create_dir_all(&receive_dir);

    let device_name = boot
        .device_name
        .clone()
        .filter(|n| !n.trim().is_empty())
        .unwrap_or_else(default_device_name);
    let mut cfg = CoreConfig::new(device_name, data_dir.clone(), receive_dir);
    cfg.preferred_port = boot.preferred_port.unwrap_or(DEFAULT_PORT);
    cfg.concurrency = boot.concurrency.unwrap_or(DEFAULT_CONCURRENCY).clamp(1, 4);

    let core = tauri::async_runtime::block_on(Core::start(cfg))
        .context("failed to start sendro core")?;

    // Keep the OS launch-on-startup registration in sync with the setting.
    if let Some(launch) = boot.launch_on_startup {
        let autolaunch = app.autolaunch();
        let result = if launch {
            autolaunch.enable()
        } else {
            autolaunch.disable()
        };
        if let Err(err) = result {
            log::warn!("autostart sync failed: {err}");
        }
    }

    app.manage(AppState {
        core: Arc::clone(&core),
        paused: AtomicBool::new(false),
    });

    tray::create_tray(app.handle())?;

    // Forward every CoreEvent to the webview as "core-event".
    let handle = app.handle().clone();
    let mut rx = core.subscribe();
    tauri::async_runtime::spawn(async move {
        loop {
            match rx.recv().await {
                Ok(event) => {
                    // Surface the pairing code even when minimized to tray.
                    if matches!(event, CoreEvent::PairingStarted { .. }) {
                        show_main_window(&handle);
                    }
                    if let Err(err) = handle.emit("core-event", &event) {
                        log::warn!("failed to emit core-event: {err}");
                    }
                }
                Err(RecvError::Lagged(skipped)) => {
                    log::warn!("core-event stream lagged, skipped {skipped} events");
                }
                Err(RecvError::Closed) => break,
            }
        }
    });

    Ok(())
}

pub fn run() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_autostart::init(
            MacosLauncher::LaunchAgent,
            None,
        ))
        .setup(|app| {
            setup(app)?;
            Ok(())
        })
        .on_window_event(|window, event| {
            // Minimize to tray: intercept close of the main window.
            if let WindowEvent::CloseRequested { api, .. } = event {
                if window.label() != "main" {
                    return;
                }
                let minimize = window
                    .app_handle()
                    .try_state::<AppState>()
                    .map(|state| state.core.settings().minimize_to_tray)
                    .unwrap_or(false);
                if minimize {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_info,
            commands::get_settings,
            commands::update_settings,
            commands::trusted_devices,
            commands::revoke_device,
            commands::offer_files,
            commands::get_queue,
            commands::cancel_transfer,
            commands::retry_transfer,
            commands::pause_transfers,
            commands::clear_completed,
            commands::get_history,
            commands::clear_history,
            commands::watch_folders,
            commands::add_watch_folder,
            commands::remove_watch_folder,
            commands::resolve_detected_file,
            commands::open_receive_folder,
        ])
        .build(tauri::generate_context!())
        .expect("error while building Sendro");

    app.run(|app_handle, event| {
        if let RunEvent::Exit = event {
            // Graceful shutdown: stop the HTTP server + mDNS advertisement.
            if let Some(state) = app_handle.try_state::<AppState>() {
                let core = Arc::clone(&state.core);
                tauri::async_runtime::block_on(async move { core.shutdown().await });
            }
        }
    });
}
