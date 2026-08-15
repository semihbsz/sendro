import { useEffect, useRef } from "react";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWebview } from "@tauri-apps/api/webview";
import { open } from "@tauri-apps/plugin-dialog";
import { useAppDispatch, useAppState } from "./store";
import { IconDrop } from "./icons";
import { Titlebar } from "./components/Titlebar";
import { Rail } from "./components/Rail";
import { TopBar } from "./components/TopBar";
import { PairingModal } from "./components/PairingModal";
import { DevicePicker } from "./components/DevicePicker";
import { MessageCards } from "./components/MessageCards";
import { TextComposer } from "./components/TextComposer";
import { FilePreview } from "./components/FilePreview";
import { Notifications } from "./components/Notifications";
import { Send } from "./views/Send";
import { Flow } from "./views/Flow";
import { Watch } from "./views/Watch";
import { SettingsView } from "./views/SettingsView";

export default function App() {
  const { view, dragging, linkArmed } = useAppState();
  const dispatch = useAppDispatch();

  // The drop handler is registered once; a ref keeps it looking at the
  // current routing decision without tearing the listener down. Drops only
  // go to the link while its panel is actually on screen — dropping on FLOW
  // must still mean "send this to a device", live session or not.
  const toLink = linkArmed && view === "send";
  const toLinkRef = useRef(toLink);
  toLinkRef.current = toLink;

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
            // While the Sendro Link panel is open, a drop is "share this
            // over the link", not "send this to a device".
            dispatch({ type: "set-dragging", dragging: false });
            dispatch(
              toLinkRef.current
                ? { type: "stage-link-files", paths: payload.paths }
                : { type: "set-pending", paths: payload.paths },
            );
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
    <div className="shell">
      <div className="bg-glow" />
      <Titlebar />
      <div className="app-body">
        <Rail />
        <div className="content">
          <TopBar />
          {view === "send" ? <Send /> : null}
          {view === "flow" ? <Flow /> : null}
          {view === "watch" ? <Watch /> : null}
          {view === "settings" ? <SettingsView /> : null}
        </div>
      </div>
      {dragging && (view !== "send" || toLink) ? (
        <div className="drop-overlay">
          <div className="drop-overlay-inner">
            <div className="hero-rings" style={{ width: 72, height: 72 }}>
              <span className="ring-a" />
              <span className="ring-b" style={{ inset: 13 }} />
              <IconDrop size={24} />
            </div>
            <div className="drop-overlay-title">
              {toLink ? "Release to share on the link" : "Release to send"}
            </div>
            <div className="drop-overlay-sub">
              {toLink
                ? "shared with guests on this network only"
                : "original bytes · verified on arrival"}
            </div>
          </div>
        </div>
      ) : null}
      <MessageCards />
      <PairingModal />
      <DevicePicker />
      <TextComposer />
      <FilePreview />
      <Notifications />
    </div>
  );
}
