import { useState } from "react";
import { Modal } from "./Modal";
import { useAppDispatch, useAppState } from "../store";
import * as api from "../api";
import { baseName, formatRelative } from "../format";
import { IconChevronRight, IconPhone } from "../icons";
import { EmptyState } from "./common";
import { platformIcon, sendFilesTo, sendTargets } from "../targets";

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
  const { pendingPaths, devices, peers } = useAppState();
  const dispatch = useAppDispatch();
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!pendingPaths || pendingPaths.length === 0) return null;

  // Devices that pull from this PC *and* peers this PC pushes to — one list,
  // because from here they are the same thing: somewhere to send.
  const targets = sendTargets(devices, peers);

  const close = () => {
    dispatch({ type: "set-pending", paths: null });
    setError(null);
  };

  const send = async (target: (typeof targets)[number]) => {
    setSending(true);
    setError(null);
    try {
      await sendFilesTo(target, pendingPaths);
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

      {targets.length === 0 ? (
        <EmptyState
          icon={<IconPhone size={22} />}
          title="No paired devices"
          subtitle="Pair a phone with this PC, or pair this PC with a phone or TV — Settings › Devices."
        />
      ) : (
        targets.map((t) => (
          <button
            key={t.deviceId}
            className="picker-device"
            disabled={sending}
            onClick={() => void send(t)}
          >
            <span className={`picker-device-dot${t.online ? "" : " off"}`}>
              <span className="dot" />
            </span>
            <span className="picker-device-icon">{platformIcon(t.platform, 15)}</span>
            <span className="picker-device-main">
              <span className="picker-device-name">{t.deviceName}</span>
              <span className={`picker-device-state${t.online ? "" : " off"}`}>
                {t.online
                  ? "online now"
                  : t.kind === "peer"
                    ? `${t.address}:${t.port}`
                    : `last seen ${formatRelative(t.lastSeenMs)}`}
              </span>
            </span>
            <span className="picker-device-chev">
              <IconChevronRight size={12} />
            </span>
          </button>
        ))
      )}

      {error ? <div className="error-note">{error}</div> : null}
    </Modal>
  );
}
