import { useEffect, useRef } from "react";
import { listen } from "@tauri-apps/api/event";
import { onAction } from "@tauri-apps/plugin-notification";
import { useAppState } from "../store";
import * as api from "../api";
import { formatBytes } from "../format";
import { notify, type NotifyPrefs } from "../notify";
import { usePrefs } from "../prefs";
import { isTerminal, type CoreEvent } from "../types";

/**
 * Turns core events into Windows toasts.
 *
 * It owns its own `core-event` subscription rather than hooking the store's,
 * so the notification rules (focus + current view + prefs) can be read from
 * live refs without re-subscribing on every render.
 *
 * Renders nothing.
 */
export function Notifications() {
  const { view } = useAppState();
  const [prefs] = usePrefs();

  const viewRef = useRef(view);
  const prefsRef = useRef<NotifyPrefs>(prefs);
  const focusedRef = useRef<boolean>(
    typeof document === "undefined" ? true : document.hasFocus(),
  );
  /** Transfers already announced, per phase, so repeated progress events
   *  don't produce a stream of toasts. */
  const announced = useRef<{
    incoming: Set<string>;
    finished: Set<string>;
  }>({ incoming: new Set(), finished: new Set() });

  viewRef.current = view;
  prefsRef.current = prefs;

  // Focus: the DOM knows, and it costs no IPC and no extra capability. A
  // window minimized to tray has a hidden webview, which is never focused.
  useEffect(() => {
    const onFocus = () => {
      focusedRef.current = true;
    };
    const onBlur = () => {
      focusedRef.current = false;
    };
    const onVisibility = () => {
      if (document.hidden) focusedRef.current = false;
    };
    window.addEventListener("focus", onFocus);
    window.addEventListener("blur", onBlur);
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      window.removeEventListener("focus", onFocus);
      window.removeEventListener("blur", onBlur);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, []);

  useEffect(() => {
    let disposed = false;
    const unlisteners: Array<() => void> = [];

    const fire = (
      category: Parameters<typeof notify>[0],
      title: string,
      body?: string,
    ) => {
      void notify(
        category,
        prefsRef.current,
        focusedRef.current,
        viewRef.current,
        body === undefined ? { title } : { title, body },
      );
    };

    listen<CoreEvent>("core-event", (event) => {
      const ev = event.payload;
      switch (ev.type) {
        case "transferUpdated": {
          const t = ev.transfer;
          const seen = announced.current;
          // A long editing day can push thousands of transfers through; the
          // dedupe sets are a guard against toast spam, not a log.
          if (seen.incoming.size > 2000) seen.incoming.clear();
          if (seen.finished.size > 2000) seen.finished.clear();
          if (
            t.direction === "incoming" &&
            !isTerminal(t.state) &&
            !seen.incoming.has(t.transferId)
          ) {
            seen.incoming.add(t.transferId);
            fire(
              "incoming",
              `Incoming from ${t.deviceName}`,
              `${t.fileName} · ${formatBytes(t.sizeBytes)}`,
            );
          }
          if (isTerminal(t.state) && !seen.finished.has(t.transferId)) {
            seen.finished.add(t.transferId);
            if (t.state === "completed") {
              fire(
                "completed",
                "Transfer complete",
                `${t.fileName} · verified byte-for-byte`,
              );
            } else if (t.state !== "cancelled") {
              fire(
                "failed",
                "Transfer failed",
                `${t.fileName} · ${t.error ?? t.state}`,
              );
            }
          }
          break;
        }
        case "messageReceived":
          // Title only, deliberately: §11 text is ephemeral and must not be
          // copied into the Windows notification centre.
          fire("message", `Message from ${ev.senderName}`);
          break;
        case "watchFileDetected":
          fire(
            "watch",
            ev.auto ? "Sending a new export" : "New export detected",
            `${ev.fileName} · ${formatBytes(ev.sizeBytes)}`,
          );
          break;
        case "guestUpload":
          fire(
            "guest",
            "A guest sent you a file",
            `${ev.fileName} · ${formatBytes(ev.sizeBytes)}`,
          );
          break;
        default:
          break;
      }
    }).then((un) => {
      if (disposed) un();
      else unlisteners.push(un);
    });

    // Best effort: on Windows the desktop toast is fired through notify-rust,
    // which does not report activation back to the plugin, so this listener
    // usually never fires. It costs nothing and it is correct wherever the
    // plugin does surface the click.
    onAction(() => {
      void api.showWindow().catch(() => undefined);
    })
      .then((listener) => {
        if (disposed) void listener.unregister();
        else unlisteners.push(() => void listener.unregister());
      })
      .catch(() => undefined);

    return () => {
      disposed = true;
      unlisteners.forEach((un) => un());
    };
  }, []);

  return null;
}
