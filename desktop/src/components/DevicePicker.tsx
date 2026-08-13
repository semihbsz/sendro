import { useState } from "react";
import { Modal } from "./Modal";
import { useAppDispatch, useAppState } from "../store";
import * as api from "../api";
import { baseName, formatRelative, isOnline } from "../format";
import { IconFile, IconPhone } from "../icons";
import { EmptyState } from "./common";

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
      dispatch({ type: "set-view", view: "queue" });
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
    <Modal title="Send to…" onClose={close}>
      <div className="picker-file-summary">
        <IconFile size={16} />
        <span
          style={{
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
        >
          {summary}
        </span>
      </div>

      {devices.length === 0 ? (
        <EmptyState
          icon={<IconPhone size={20} />}
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
              <span className="row-icon">
                <IconPhone size={17} />
              </span>
              <span className="row-main">
                <span className="row-title">{d.deviceName}</span>
                <span className="row-sub status-row">
                  <span className={`status-dot${online ? "" : " off"}`} />
                  {online
                    ? "Online"
                    : `Last seen ${formatRelative(d.lastSeenMs)}`}
                </span>
              </span>
            </button>
          );
        })
      )}

      {error ? (
        <div
          style={{
            marginTop: "var(--s-3)",
            color: "var(--danger)",
            fontSize: "var(--fs-sm)",
          }}
        >
          {error}
        </div>
      ) : null}
    </Modal>
  );
}
