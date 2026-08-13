import { useEffect } from "react";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWebview } from "@tauri-apps/api/webview";
import { open } from "@tauri-apps/plugin-dialog";
import { useAppDispatch, useAppState } from "./store";
import { IconDrop } from "./icons";
import { Sidebar } from "./components/Sidebar";
import { PairingModal } from "./components/PairingModal";
import { DevicePicker } from "./components/DevicePicker";
import { Home } from "./views/Home";
import { Devices } from "./views/Devices";
import { Queue } from "./views/Queue";
import { History } from "./views/History";
import { WatchFolders } from "./views/WatchFolders";
import { SettingsView } from "./views/SettingsView";

export default function App() {
  const { view, dragging } = useAppState();
  const dispatch = useAppDispatch();

  // App-wide native drag-and-drop of files.
  useEffect(() => {
    let disposed = false;
    let unlisten: (() => void) | null = null;

    getCurrentWebview()
      .onDragDropEvent((event) => {
        const payload = event.payload;
        if (payload.type === "enter") {
          dispatch({ type: "set-dragging", dragging: true });
        } else if (payload.type === "leave") {
          dispatch({ type: "set-dragging", dragging: false });
        } else if (payload.type === "drop") {
          if (payload.paths.length > 0) {
            dispatch({ type: "set-pending", paths: payload.paths });
          } else {
            dispatch({ type: "set-dragging", dragging: false });
          }
        }
      })
      .then((un) => {
        if (disposed) un();
        else unlisten = un;
      })
      .catch((err) => console.error("drag-drop listener failed", err));

    return () => {
      disposed = true;
      unlisten?.();
    };
  }, [dispatch]);

  // Tray "Send Files…" → open the native picker, then the device picker.
  useEffect(() => {
    let disposed = false;
    let unlisten: (() => void) | null = null;

    listen("sendro://send-files", () => {
      void (async () => {
        try {
          const selected = await open({ multiple: true, title: "Send files" });
          if (selected === null) return;
          const paths = Array.isArray(selected) ? selected : [selected];
          if (paths.length > 0) {
            dispatch({ type: "set-pending", paths });
          }
        } catch (err) {
          console.error("tray send-files failed", err);
        }
      })();
    }).then((un) => {
      if (disposed) un();
      else unlisten = un;
    });

    return () => {
      disposed = true;
      unlisten?.();
    };
  }, [dispatch]);

  return (
    <div className="app">
      <Sidebar />
      <main className="main">
        {view === "home" ? <Home /> : null}
        {view === "devices" ? <Devices /> : null}
        {view === "queue" ? <Queue /> : null}
        {view === "history" ? <History /> : null}
        {view === "watch" ? <WatchFolders /> : null}
        {view === "settings" ? <SettingsView /> : null}
      </main>
      {dragging && view !== "home" ? (
        <div className="drop-overlay">
          <div className="drop-overlay-inner">
            <IconDrop size={24} />
            Release to send
          </div>
        </div>
      ) : null}
      <PairingModal />
      <DevicePicker />
    </div>
  );
}
