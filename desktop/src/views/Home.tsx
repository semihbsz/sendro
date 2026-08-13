import { open } from "@tauri-apps/plugin-dialog";
import { useAppDispatch, useAppState } from "../store";
import {
  IconDrop,
  IconFolder,
  IconFile,
  IconPhone,
  IconQueue,
  IconShieldCheck,
} from "../icons";
import { formatBytes, isOnline } from "../format";
import { StateChip } from "../components/common";
import { isTerminal } from "../types";

async function pickFiles(): Promise<string[] | null> {
  const selected = await open({ multiple: true, title: "Send files" });
  if (selected === null) return null;
  return Array.isArray(selected) ? selected : [selected];
}

async function pickFolder(): Promise<string[] | null> {
  const selected = await open({ directory: true, title: "Send a folder" });
  if (selected === null) return null;
  return Array.isArray(selected) ? selected : [selected];
}

export function Home() {
  const { devices, queue, dragging } = useAppState();
  const dispatch = useAppDispatch();

  const choose = async (picker: () => Promise<string[] | null>) => {
    try {
      const paths = await picker();
      if (paths && paths.length > 0) {
        dispatch({ type: "set-pending", paths });
      }
    } catch (err) {
      console.error("file picker failed", err);
    }
  };

  const onlineDevices = devices.filter((d) => isOnline(d.lastSeenMs));
  const active = queue.filter((t) => !isTerminal(t.state)).slice(0, 3);

  return (
    <div className="view">
      <div className="view-header">
        <div>
          <div className="view-title">Send to your iPhone</div>
          <div className="view-subtitle">
            Original files, byte for byte. Never touches the cloud.
          </div>
        </div>
      </div>

      <div className={`dropzone${dragging ? " dragging" : ""}`}>
        <div className="dropzone-icon">
          <IconDrop size={26} />
        </div>
        <div className="dropzone-title">
          {dragging ? "Release to send" : "Drop files anywhere"}
        </div>
        <div className="dropzone-sub">
          {devices.length === 0
            ? "Pair your iPhone first — open Sendro on it and tap this PC."
            : onlineDevices.length > 0
              ? `${onlineDevices.length === 1 ? (onlineDevices[0]?.deviceName ?? "1 device") : `${onlineDevices.length} devices`} online and ready`
              : "Your paired devices will pick transfers up when they come online."}
        </div>
        <div className="dropzone-actions">
          <button
            className="btn btn-primary"
            onClick={() => void choose(pickFiles)}
          >
            <IconFile size={15} />
            Choose Files
          </button>
          <button className="btn" onClick={() => void choose(pickFolder)}>
            <IconFolder size={15} />
            Choose Folder
          </button>
        </div>
      </div>

      <div className="home-grid">
        <div className="hint-card">
          <span className="hint-icon">
            <IconShieldCheck size={18} />
          </span>
          <div>
            <div className="hint-title">Private by design</div>
            <div className="hint-body">
              Transfers stay on your local network and every file is verified
              with SHA-256 on arrival.
            </div>
          </div>
        </div>
        <div className="hint-card">
          <span className="hint-icon">
            <IconPhone size={18} />
          </span>
          <div>
            <div className="hint-title">
              {devices.length === 0 ? "Pair your iPhone" : "Paired devices"}
            </div>
            <div className="hint-body">
              {devices.length === 0
                ? "Open Sendro on your iPhone, tap this PC, then type the 6-digit code shown here."
                : `${devices.length} ${devices.length === 1 ? "device" : "devices"} trusted · ${onlineDevices.length} online now.`}
            </div>
          </div>
        </div>
      </div>

      {active.length > 0 ? (
        <div className="panel">
          <div className="row" style={{ paddingBottom: 0, border: "none" }}>
            <span className="section-label">In flight</span>
            <span style={{ flex: 1 }} />
            <button
              className="btn btn-sm btn-ghost"
              onClick={() => dispatch({ type: "set-view", view: "queue" })}
            >
              <IconQueue size={13} />
              Open queue
            </button>
          </div>
          <div className="list">
            {active.map((t) => (
              <div className="row" key={t.transferId}>
                <span className="row-icon">
                  <IconFile size={16} />
                </span>
                <div className="row-main">
                  <div className="row-title">{t.fileName}</div>
                  <div className="row-sub">
                    {formatBytes(t.sizeBytes)} → {t.deviceName}
                  </div>
                </div>
                <StateChip state={t.state} direction={t.direction} />
              </div>
            ))}
          </div>
        </div>
      ) : null}
    </div>
  );
}
