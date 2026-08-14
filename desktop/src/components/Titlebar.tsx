import { useEffect, useState } from "react";
import { getCurrentWindow } from "@tauri-apps/api/window";

/** Custom 40px window chrome: brand + minimize / maximize / close.
 *  The bar itself is a Tauri drag region; close goes through the normal
 *  CloseRequested flow so minimize-to-tray keeps working (Rust side). */
export function Titlebar() {
  const [maximized, setMaximized] = useState(false);

  useEffect(() => {
    const win = getCurrentWindow();
    let disposed = false;
    let unlisten: (() => void) | null = null;

    const sync = () => {
      win
        .isMaximized()
        .then((m) => {
          if (!disposed) setMaximized(m);
        })
        .catch(() => undefined);
    };
    sync();

    win
      .onResized(sync)
      .then((un) => {
        if (disposed) un();
        else unlisten = un;
      })
      .catch(() => undefined);

    return () => {
      disposed = true;
      unlisten?.();
    };
  }, []);

  const win = () => getCurrentWindow();

  return (
    <header className="titlebar" data-tauri-drag-region>
      <img
        className="tb-mark"
        src="/sendro-icon.svg"
        alt=""
        draggable={false}
      />
      <span className="tb-word">Sendro</span>
      <div className="tb-spacer" data-tauri-drag-region />
      <div className="tb-controls">
        <button
          className="tb-btn"
          aria-label="Minimize"
          onClick={() => void win().minimize()}
        >
          <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">
            <rect x="0" y="4.5" width="10" height="1" fill="currentColor" />
          </svg>
        </button>
        <button
          className="tb-btn"
          aria-label={maximized ? "Restore" : "Maximize"}
          onClick={() => void win().toggleMaximize()}
        >
          {maximized ? (
            <svg
              width="10"
              height="10"
              viewBox="0 0 10 10"
              fill="none"
              stroke="currentColor"
              strokeWidth="1"
              aria-hidden="true"
            >
              <rect x="0.5" y="2.5" width="7" height="7" />
              <path d="M2.5 2.5v-2h7v7h-2" />
            </svg>
          ) : (
            <svg
              width="9"
              height="9"
              viewBox="0 0 9 9"
              fill="none"
              stroke="currentColor"
              strokeWidth="1"
              aria-hidden="true"
            >
              <rect x="0.5" y="0.5" width="8" height="8" />
            </svg>
          )}
        </button>
        <button
          className="tb-btn close"
          aria-label="Close"
          onClick={() => void win().close()}
        >
          <svg
            width="10"
            height="10"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            aria-hidden="true"
          >
            <path d="m5 5 14 14M19 5 5 19" />
          </svg>
        </button>
      </div>
    </header>
  );
}
