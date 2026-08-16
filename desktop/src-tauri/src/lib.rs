//! Sendro desktop shell — thin Tauri glue around the `sendro-core` engine.
//!
//! Responsibilities:
//! * bootstrap `CoreConfig` from `settings.json` on disk (with sane defaults),
//! * start the core and hold `Arc<Core>` in managed state,
//! * re-emit every `CoreEvent` to the webview as the `"core-event"` Tauri event,
//! * system tray + minimize-to-tray + autostart.

mod commands;
mod preview;
mod tray;
mod updates;

use std::path::{Path, PathBuf};
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};

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
    /// Paths the local user explicitly handed to Sendro this session. The
    /// preview commands will not touch anything that is neither in here nor
    /// inside the receive folder — see `preview.rs`.
    pub preview_paths: Mutex<preview::PathRegistry>,
}

impl AppState {
    /// Remember paths as previewable. The lock is taken and released here, so
    /// no caller ever holds it across an `.await`.
    pub fn remember_previewable(&self, paths: impl IntoIterator<Item = PathBuf>) {
        let mut registry = self
            .preview_paths
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        for path in paths {
            registry.insert(path);
        }
    }
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
        preview_paths: Mutex::new(preview::PathRegistry::default()),
    });

    tray::create_tray(app.handle())?;

    // Forward every CoreEvent to the webview as "core-event".
    let handle = app.handle().clone();
    let mut rx = core.subscribe();
    tauri::async_runtime::spawn(async move {
        loop {
            match rx.recv().await {
                Ok(event) => {
                    // Surface the pairing code — and an incoming text card —
                    // even when minimized to tray.
                    if matches!(
                        event,
                        CoreEvent::PairingStarted { .. } | CoreEvent::MessageReceived { .. }
                    ) {
                        show_main_window(&handle);
                    }
                    remember_event_paths(&handle, &event);
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

/// Paths the *core* tells us about are paths the local user already sees in
/// the UI, so they become previewable: a watch-folder detection, and the
/// source path of any transfer. Nothing else ever enters the registry.
fn remember_event_paths(app: &AppHandle, event: &CoreEvent) {
    let Some(state) = app.try_state::<AppState>() else {
        return;
    };
    match event {
        CoreEvent::WatchFileDetected { path, .. } => {
            state.remember_previewable([PathBuf::from(path)]);
        }
        CoreEvent::TransferUpdated { transfer } => {
            if let Some(source) = transfer.source_path.as_ref() {
                state.remember_previewable([PathBuf::from(source)]);
            }
        }
        _ => {}
    }
}

pub fn run() {
    let context = tauri::generate_context!();

    // UPDATES.md §3 — decided *before* the builder runs. `tauri-plugin-updater`
    // deserializes `plugins.updater` in its own `initialize`, so registering it
    // against a missing or placeholder config turns "updates are not set up
    // yet" into "Sendro does not start". See src/updates.rs.
    let updater = updates::inspect(context.config());
    if !updater.configured {
        log::warn!(
            "in-app updates disabled: {}",
            updater
                .reason
                .as_deref()
                .unwrap_or("no reason given (this is a bug)")
        );
    }

    let mut builder = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_clipboard_manager::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_autostart::init(
            MacosLauncher::LaunchAgent,
            None,
        ));

    if updater.configured {
        // The process plugin exists only so the webview can relaunch after an
        // install; it is pointless without the updater and is gated with it.
        builder = builder
            .plugin(tauri_plugin_updater::Builder::new().build())
            .plugin(tauri_plugin_process::init());
    }

    let app = builder
        .manage(updater)
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
            commands::send_message,
            commands::incoming_messages,
            commands::dismiss_message,
            commands::clear_messages,
            commands::paste_clipboard_image,
            commands::start_qr_pairing,
            commands::start_link_session,
            commands::stop_link_session,
            commands::link_session,
            commands::add_link_files,
            commands::remove_link_file,
            commands::network_interfaces,
            commands::show_window,
            updates::updater_status,
            preview::preview_file,
            preview::read_text_preview,
            preview::open_previewed_file,
            preview::reveal_previewed_file,
        ])
        .build(context)
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
