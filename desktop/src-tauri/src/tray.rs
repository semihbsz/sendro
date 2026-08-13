//! System tray: Open Sendro / Send Files… / Pause Transfers (checkable) /
//! Settings / Quit, plus click-to-open behavior.

use std::sync::atomic::Ordering;

use tauri::{
    menu::{CheckMenuItem, Menu, MenuItem, PredefinedMenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Emitter, Manager, Wry,
};

use crate::{show_main_window, AppState};

const MENU_OPEN: &str = "open";
const MENU_SEND: &str = "send-files";
const MENU_PAUSE: &str = "pause";
const MENU_SETTINGS: &str = "settings";
const MENU_QUIT: &str = "quit";

/// Managed handle to the checkable "Pause Transfers" item so the webview's
/// pause toggle can keep it in sync (see `commands::pause_transfers`).
pub struct TrayState {
    pub pause_item: CheckMenuItem<Wry>,
}

pub fn create_tray(app: &AppHandle) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, MENU_OPEN, "Open Sendro", true, None::<&str>)?;
    let send = MenuItem::with_id(app, MENU_SEND, "Send Files…", true, None::<&str>)?;
    let pause = CheckMenuItem::with_id(
        app,
        MENU_PAUSE,
        "Pause Transfers",
        true,
        false,
        None::<&str>,
    )?;
    let settings = MenuItem::with_id(app, MENU_SETTINGS, "Settings", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, MENU_QUIT, "Quit Sendro", true, None::<&str>)?;
    let sep1 = PredefinedMenuItem::separator(app)?;
    let sep2 = PredefinedMenuItem::separator(app)?;

    let menu = Menu::with_items(app, &[&open, &send, &sep1, &pause, &settings, &sep2, &quit])?;

    app.manage(TrayState {
        pause_item: pause.clone(),
    });

    let mut builder = TrayIconBuilder::with_id("sendro-tray")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .tooltip("Sendro — private LAN transfer")
        .on_menu_event(on_menu_event)
        .on_tray_icon_event(|tray, event| {
            // Left click (or double click) on the tray icon opens the window.
            match event {
                TrayIconEvent::Click {
                    button: MouseButton::Left,
                    button_state: MouseButtonState::Up,
                    ..
                }
                | TrayIconEvent::DoubleClick {
                    button: MouseButton::Left,
                    ..
                } => show_main_window(tray.app_handle()),
                _ => {}
            }
        });

    if let Some(icon) = app.default_window_icon() {
        builder = builder.icon(icon.clone());
    }

    builder.build(app)?;
    Ok(())
}

fn on_menu_event(app: &AppHandle, event: tauri::menu::MenuEvent) {
    match event.id().as_ref() {
        MENU_OPEN => show_main_window(app),
        MENU_SEND => {
            show_main_window(app);
            let _ = app.emit("sendro://send-files", ());
        }
        MENU_PAUSE => {
            // The check item toggles itself before the event fires; its new
            // checked state is the desired pause state.
            let paused = app
                .try_state::<TrayState>()
                .and_then(|tray| tray.pause_item.is_checked().ok())
                .unwrap_or(false);
            if let Some(state) = app.try_state::<AppState>() {
                state.core.pause_transfers(paused);
                state.paused.store(paused, Ordering::SeqCst);
            }
            let _ = app.emit("sendro://paused", serde_json::json!({ "paused": paused }));
        }
        MENU_SETTINGS => {
            show_main_window(app);
            let _ = app.emit("sendro://navigate", "settings");
        }
        MENU_QUIT => app.exit(0),
        _ => {}
    }
}
