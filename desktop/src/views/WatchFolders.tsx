import { open } from "@tauri-apps/plugin-dialog";
import { useAppDispatch, useAppState } from "../store";
import * as api from "../api";
import { baseName, formatBytes } from "../format";
import {
  IconFile,
  IconPlus,
  IconSend,
  IconTrash,
  IconWatch,
  IconX,
} from "../icons";
import { EmptyState, Toggle } from "../components/common";
import type { WatchFolderConfig } from "../types";

export function WatchFolders() {
  const { watchFolders, detections, devices } = useAppState();
  const dispatch = useAppDispatch();

  const refresh = async () => {
    const folders = await api.watchFolders();
    dispatch({ type: "set-watch-folders", folders });
  };

  const addFolder = async () => {
    try {
      const selected = await open({
        directory: true,
        title: "Watch a folder",
      });
      if (selected === null) return;
      const path = Array.isArray(selected) ? selected[0] : selected;
      if (!path) return;
      const cfg: WatchFolderConfig = {
        id: crypto.randomUUID(),
        path,
        autoSend: false,
        targetDeviceId: devices[0]?.deviceId ?? null,
        enabled: true,
      };
      await api.addWatchFolder(cfg);
      await refresh();
    } catch (err) {
      console.error("add watch folder failed", err);
    }
  };

  const update = async (cfg: WatchFolderConfig) => {
    try {
      // add_watch_folder upserts by id (same id → replace config).
      await api.addWatchFolder(cfg);
      await refresh();
    } catch (err) {
      console.error("update watch folder failed", err);
    }
  };

  const remove = async (id: string) => {
    try {
      await api.removeWatchFolder(id);
      await refresh();
    } catch (err) {
      console.error("remove watch folder failed", err);
    }
  };

  const resolve = async (detectionId: string, send: boolean) => {
    dispatch({ type: "remove-detection", detectionId });
    try {
      await api.resolveDetectedFile(detectionId, send);
    } catch (err) {
      console.error("resolve detection failed", err);
    }
  };

  return (
    <div className="view">
      <div className="view-header">
        <div>
          <div className="view-title">Watch Folders</div>
          <div className="view-subtitle">
            Sendro notices new exports the moment your NLE writes them.
          </div>
        </div>
        <div className="view-actions">
          <button className="btn btn-primary" onClick={() => void addFolder()}>
            <IconPlus size={14} />
            Add Folder
          </button>
        </div>
      </div>

      {detections.length > 0 ? (
        <>
          <span className="section-label">New exports</span>
          <div className="panel" style={{ marginTop: "calc(-1 * var(--s-3))" }}>
            <div className="list">
              {detections.map((d) => (
                <div className="row detection" key={d.detectionId}>
                  <span className="row-icon">
                    <IconFile size={16} />
                  </span>
                  <div className="row-main">
                    <div className="row-title">
                      New export detected: {d.fileName}
                    </div>
                    <div className="row-sub">
                      {formatBytes(d.sizeBytes)} · {d.path}
                    </div>
                  </div>
                  <div className="row-actions">
                    <button
                      className="btn btn-sm btn-primary"
                      onClick={() => void resolve(d.detectionId, true)}
                    >
                      <IconSend size={12} />
                      Send
                    </button>
                    <button
                      className="btn btn-sm btn-ghost"
                      onClick={() => void resolve(d.detectionId, false)}
                    >
                      <IconX size={12} />
                      Ignore
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </>
      ) : null}

      <div className="panel">
        {watchFolders.length === 0 ? (
          <EmptyState
            icon={<IconWatch size={20} />}
            title="No watch folders"
            subtitle="Point Sendro at your render or export folder and it will offer new files to your iPhone automatically — or ask first."
            action={
              <button className="btn" onClick={() => void addFolder()}>
                <IconPlus size={14} />
                Add your first folder
              </button>
            }
          />
        ) : (
          <div className="list">
            {watchFolders.map((f) => (
              <div className="row" key={f.id}>
                <span className="row-icon">
                  <IconWatch size={17} />
                </span>
                <div className="row-main">
                  <div className="row-title">{baseName(f.path)}</div>
                  <div className="row-sub" title={f.path}>
                    {f.path}
                  </div>
                </div>

                <div
                  className="settings-control"
                  style={{ gap: "var(--s-4)" }}
                >
                  <label
                    className="status-row"
                    style={{ gap: "var(--s-2)" }}
                    title="Send new files without asking"
                  >
                    <span>Auto-send</span>
                    <Toggle
                      on={f.autoSend}
                      label="Auto-send"
                      onChange={(on) => void update({ ...f, autoSend: on })}
                    />
                  </label>

                  <select
                    className="input"
                    style={{ width: 150 }}
                    value={f.targetDeviceId ?? ""}
                    onChange={(e) =>
                      void update({
                        ...f,
                        targetDeviceId:
                          e.target.value === "" ? null : e.target.value,
                      })
                    }
                    title="Target device"
                  >
                    <option value="">Ask every time</option>
                    {devices.map((d) => (
                      <option key={d.deviceId} value={d.deviceId}>
                        {d.deviceName}
                      </option>
                    ))}
                  </select>

                  <Toggle
                    on={f.enabled}
                    label="Enabled"
                    onChange={(on) => void update({ ...f, enabled: on })}
                  />

                  <button
                    className="btn btn-icon btn-danger-ghost"
                    title="Remove watch folder"
                    onClick={() => void remove(f.id)}
                  >
                    <IconTrash size={15} />
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
