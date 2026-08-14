import { useEffect, useRef, useState } from "react";
import { useAppDispatch, useAppState } from "../store";
import * as api from "../api";
import { formatRelative, isOnline } from "../format";
import { IconPause, IconPlay } from "../icons";

/** Top strip inside the content column: global pause toggle + the
 *  connected-device chip (first online trusted device, or a pinned one;
 *  multiple devices open a small switcher popover). */
export function TopBar() {
  const { devices, paused, chipDeviceId } = useAppState();
  const dispatch = useAppDispatch();
  const [popOpen, setPopOpen] = useState(false);
  const chipRef = useRef<HTMLDivElement | null>(null);

  // Close the switcher on any outside click.
  useEffect(() => {
    if (!popOpen) return;
    const onDown = (e: MouseEvent) => {
      if (chipRef.current && !chipRef.current.contains(e.target as Node)) {
        setPopOpen(false);
      }
    };
    window.addEventListener("mousedown", onDown);
    return () => window.removeEventListener("mousedown", onDown);
  }, [popOpen]);

  const togglePause = async () => {
    const next = !paused;
    dispatch({ type: "set-paused", paused: next });
    try {
      await api.pauseTransfers(next);
    } catch (err) {
      dispatch({ type: "set-paused", paused: !next });
      console.error("pause failed", err);
    }
  };

  const pinned = devices.find((d) => d.deviceId === chipDeviceId);
  const firstOnline = devices.find((d) => isOnline(d.lastSeenMs));
  const shown = pinned ?? firstOnline ?? devices[0];
  const shownOnline = shown ? isOnline(shown.lastSeenMs) : false;
  const switchable = devices.length > 1;

  return (
    <div className="topbar">
      <div className="topbar-spacer" />

      <button
        className={`pause-chip${paused ? " paused" : ""}`}
        onClick={() => void togglePause()}
        title={paused ? "Resume all transfers" : "Pause all transfers"}
      >
        {paused ? <IconPlay size={12} /> : <IconPause size={12} />}
        <span>{paused ? "Resume" : "Pause"}</span>
      </button>

      <div
        ref={chipRef}
        className={`device-chip${switchable ? " clickable" : ""}`}
        role={switchable ? "button" : undefined}
        tabIndex={switchable ? 0 : undefined}
        onClick={() => switchable && setPopOpen((o) => !o)}
        onKeyDown={(e) => {
          if (switchable && (e.key === "Enter" || e.key === " ")) {
            e.preventDefault();
            setPopOpen((o) => !o);
          }
        }}
        style={switchable ? { cursor: "pointer" } : undefined}
      >
        <span className={`pulse-dot${shownOnline ? "" : " off"}`} />
        {shown ? (
          <>
            <span className="device-chip-name">{shown.deviceName}</span>
            <span className="device-chip-meta">
              {shownOnline
                ? "online"
                : `seen ${formatRelative(shown.lastSeenMs)}`}
            </span>
          </>
        ) : (
          <>
            <span className="device-chip-name">no device</span>
            <span className="device-chip-meta">pair from your iPhone</span>
          </>
        )}

        {popOpen && switchable ? (
          <div className="device-pop" role="menu">
            {devices.map((d) => {
              const online = isOnline(d.lastSeenMs);
              return (
                <button
                  key={d.deviceId}
                  role="menuitem"
                  className={`device-pop-item${
                    shown && shown.deviceId === d.deviceId ? " selected" : ""
                  }`}
                  onClick={(e) => {
                    e.stopPropagation();
                    dispatch({ type: "set-chip-device", deviceId: d.deviceId });
                    setPopOpen(false);
                  }}
                >
                  <span className={`pulse-dot${online ? "" : " off"}`} />
                  <span className="device-pop-name">{d.deviceName}</span>
                  <span
                    className={`device-pop-state${online ? " online" : ""}`}
                  >
                    {online ? "online" : formatRelative(d.lastSeenMs)}
                  </span>
                </button>
              );
            })}
          </div>
        ) : null}
      </div>
    </div>
  );
}
