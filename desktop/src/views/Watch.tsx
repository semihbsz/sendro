import { useState } from "react";
import { open } from "@tauri-apps/plugin-dialog";
import { useAppDispatch, useAppState } from "../store";
import * as api from "../api";
import { baseName, formatBytes } from "../format";
import { IconFile, IconTrash, IconWatch } from "../icons";
import { EmptyState, FileName, Toggle } from "../components/common";
import { Modal } from "../components/Modal";
import { mapLimited } from "../bulk";
import type { WatchFolderConfig } from "../types";

export function Watch() {
  const { watchFolders, detections, devices } = useAppState();
  const dispatch = useAppDispatch();
  const [confirmIgnoreAll, setConfirmIgnoreAll] = useState(false);
  const [bulkBusy, setBulkBusy] = useState(false);

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

  /**
   * Bulk resolve every currently listed detection. There is no batch
   * endpoint (PROTOCOL.md §12) — it is a bounded-concurrency loop over the
   * single-item call, and one failure never aborts the rest.
   */
  const resolveAll = async (send: boolean) => {
    const ids = detections.map((d) => d.detectionId);
    if (ids.length === 0) return;
    setBulkBusy(true);
    // Clear the feed up front so the UI answers instantly.
    ids.forEach((detectionId) =>
      dispatch({ type: "remove-detection", detectionId }),
    );
    try {
      const failures = await mapLimited(ids, 4, (id) =>
        api.resolveDetectedFile(id, send),
      );
      if (failures.length > 0) {
        console.error(
          `${failures.length} of ${ids.length} detections failed to resolve`,
          failures,
        );
      }
    } finally {
      setBulkBusy(false);
    }
  };

  return (
    <div className="page" key="watch">
      <div className="page-head">
        <div className="page-head-text">
          <div className="page-title">Watch</div>
          <div className="page-sub">
            sendro sees the file the moment your nle writes it
          </div>
        </div>
        <button className="btn-solid" onClick={() => void addFolder()}>
          Add folder
        </button>
      </div>

      {detections.length > 1 ? (
        <div className="bulk-bar">
          <span className="bulk-count">
            {detections.length} files waiting on you
          </span>
          <span className="bulk-spacer" />
          <button
            className="btn-solid btn-sm"
            disabled={bulkBusy}
            onClick={() => void resolveAll(true)}
          >
            Send all ({detections.length})
          </button>
          <button
            className="btn-glass btn-sm"
            disabled={bulkBusy}
            onClick={() => setConfirmIgnoreAll(true)}
          >
            Ignore all
          </button>
        </div>
      ) : null}

      {detections.map((d) => (
        <div className="detect-banner" key={d.detectionId}>
          <div className="detect-icon">
            <IconFile size={18} />
          </div>
          <div className="detect-main">
            <div className="detect-title">
              New export detected ·{" "}
              <FileName
                className="detect-file"
                label={d.fileName}
                target={{ path: d.path, fileName: d.fileName }}
              />
            </div>
            <div className="detect-sub">
              {formatBytes(d.sizeBytes)} · {d.path}
            </div>
          </div>
          <div className="detect-actions">
            <button
              className="btn-solid btn-sm"
              onClick={() => void resolve(d.detectionId, true)}
            >
              Send
            </button>
            <button
              className="btn-glass"
              onClick={() => void resolve(d.detectionId, false)}
            >
              Ignore
            </button>
          </div>
        </div>
      ))}

      {watchFolders.length === 0 ? (
        <div className="glass-panel">
          <EmptyState
            icon={<IconWatch size={20} />}
            title="Nothing is being watched"
            subtitle="Point Sendro at your render or export folder and it offers new files to your iPhone automatically — or asks first."
            action={
              <button className="btn-glass" onClick={() => void addFolder()}>
                Add your first folder
              </button>
            }
          />
        </div>
      ) : (
        <div className="wf-list">
          {watchFolders.map((f) => (
            <div
              className={`wf-card${f.enabled ? "" : " disabled"}`}
              key={f.id}
            >
              <div className="wf-icon">
                <IconWatch size={18} />
              </div>
              <div className="wf-main">
                <div className="wf-name">{baseName(f.path)}</div>
                <div className="wf-path" title={f.path}>
                  {f.path}
                </div>
              </div>
              <div className="wf-controls">
                <label className="wf-toggle-group" title="Send new files without asking">
                  <span>Auto-send</span>
                  <Toggle
                    on={f.autoSend}
                    label="Auto-send"
                    onChange={(on) => void update({ ...f, autoSend: on })}
                  />
                </label>

                <select
                  className="target-select"
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

                <label className="wf-toggle-group" title="Pause watching this folder">
                  <Toggle
                    on={f.enabled}
                    label="Enabled"
                    onChange={(on) => void update({ ...f, enabled: on })}
                  />
                </label>

                <button
                  className="icon-btn danger"
                  title="Remove watch folder"
                  onClick={() => void remove(f.id)}
                >
                  <IconTrash size={14} />
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {confirmIgnoreAll ? (
        <Modal
          title="Ignore all detections?"
          onClose={() => setConfirmIgnoreAll(false)}
          cancelLabel="Keep them"
        >
          <div className="confirm-body">
            {detections.length} detected file
            {detections.length === 1 ? "" : "s"} will be dropped from this
            feed. The files stay on disk — they just won't be offered.
          </div>
          <div className="confirm-actions">
            <button
              className="btn-cancel"
              onClick={() => {
                setConfirmIgnoreAll(false);
                void resolveAll(false);
              }}
            >
              Ignore all
            </button>
          </div>
        </Modal>
      ) : null}
    </div>
  );
}
