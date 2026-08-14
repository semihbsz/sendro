import { useState } from "react";
import { Modal } from "./Modal";
import { useAppDispatch, useAppState } from "../store";
import * as api from "../api";
import { baseName, formatRelative, isOnline } from "../format";
import { IconChevronRight, IconPhone } from "../icons";
import { EmptyState } from "./common";

/** File extension badge text, e.g. "MOV" — falls back to a count. */
function badgeFor(paths: string[]): string {
  if (paths.length > 1) return `×${paths.length}`;
  const name = baseName(paths[0] ?? "");
  const dot = name.lastIndexOf(".");
  if (dot > 0 && dot < name.length - 1) {
    return name.slice(dot + 1).slice(0, 4);
  }
  return "DIR";
}

/** Shown when files are pending (drop / picker / tray) and a target
 *  device needs to be chosen. */
export function DevicePicker() {
  const { pendingPaths, devices } = useAppState();
  const dispatch = useAppDispatch();
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!pendingPaths || pendingPaths.length === 0) return null;

  const close = () => {
    dispatch({ type: "set-pending", paths: null });
    setError(null);
  };

  const send = async (deviceId: string) => {
    setSending(true);
    setError(null);
    try {
      await api.offerFiles(deviceId, pendingPaths, false);
      dispatch({ type: "set-pending", paths: null });
      dispatch({ type: "set-view", view: "flow" });
      const queue = await api.getQueue();
      dispatch({ type: "set-queue", queue });
    } catch (err) {
      setError(String(err));
    } finally {
      setSending(false);
    }
  };

  const summary =
    pendingPaths.length === 1
      ? baseName(pendingPaths[0] ?? "")
      : `${pendingPaths.length} items`;

  return (
    <Modal title="Send to…" onClose={close} cancelLabel="Cancel">
      <div className="picker-file">
        <span className="picker-file-badge">{badgeFor(pendingPaths)}</span>
        <div className="picker-file-main">
          <div className="picker-file-name">{summary}</div>
          <div className="picker-file-sub">
            {pendingPaths.length === 1
              ? "ready to send"
              : "sent as one batch, verified per file"}
          </div>
        </div>
      </div>

      {devices.length === 0 ? (
        <EmptyState
          icon={<IconPhone size={22} />}
          title="No paired devices"
          subtitle="Open Sendro on your iPhone and tap this PC to pair."
        />
      ) : (
        devices.map((d) => {
          const online = isOnline(d.lastSeenMs);
          return (
            <button
              key={d.deviceId}
              className="picker-device"
              disabled={sending}
              onClick={() => void send(d.deviceId)}
            >
              <span className={`picker-device-dot${online ? "" : " off"}`}>
                <span className="dot" />
              </span>
              <span className="picker-device-main">
                <span className="picker-device-name">{d.deviceName}</span>
                <span
                  className={`picker-device-state${online ? "" : " off"}`}
                >
                  {online
                    ? "online now"
                    : `last seen ${formatRelative(d.lastSeenMs)}`}
                </span>
              </span>
              <span className="picker-device-chev">
                <IconChevronRight size={12} />
              </span>
            </button>
          );
        })
      )}

      {error ? <div className="error-note">{error}</div> : null}
    </Modal>
  );
}
