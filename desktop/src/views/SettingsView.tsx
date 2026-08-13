import { useEffect, useState } from "react";
import { getVersion } from "@tauri-apps/api/app";
import { open } from "@tauri-apps/plugin-dialog";
import { useAppDispatch, useAppState } from "../store";
import * as api from "../api";
import { IconFolder } from "../icons";
import { Toggle } from "../components/common";
import type { Settings } from "../types";

export function SettingsView() {
  const { settings, info } = useAppState();
  const dispatch = useAppDispatch();
  const [draft, setDraft] = useState<Settings | null>(settings);
  const [saving, setSaving] = useState(false);
  const [savedTick, setSavedTick] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [version, setVersion] = useState<string>("");

  useEffect(() => {
    setDraft(settings);
  }, [settings]);

  useEffect(() => {
    getVersion()
      .then(setVersion)
      .catch(() => setVersion(""));
  }, []);

  if (!draft) {
    return (
      <div className="view">
        <div className="view-title">Settings</div>
      </div>
    );
  }

  const dirty =
    settings !== null && JSON.stringify(draft) !== JSON.stringify(settings);

  const set = <K extends keyof Settings>(key: K, value: Settings[K]) => {
    setDraft((d) => (d ? { ...d, [key]: value } : d));
    setSavedTick(false);
  };

  const save = async () => {
    if (!draft) return;
    setSaving(true);
    setError(null);
    try {
      await api.updateSettings(draft);
      const fresh = await api.getSettings();
      dispatch({ type: "set-settings", settings: fresh });
      setSavedTick(true);
    } catch (err) {
      setError(String(err));
    } finally {
      setSaving(false);
    }
  };

  const changeReceiveDir = async () => {
    try {
      const selected = await open({
        directory: true,
        title: "Choose receive folder",
        defaultPath: draft.receiveDir,
      });
      if (selected === null) return;
      const path = Array.isArray(selected) ? selected[0] : selected;
      if (path) set("receiveDir", path);
    } catch (err) {
      console.error("choose folder failed", err);
    }
  };

  return (
    <div className="view">
      <div className="view-header">
        <div>
          <div className="view-title">Settings</div>
          <div className="view-subtitle">
            Changes to name or port apply after the next restart of the local
            server.
          </div>
        </div>
        <div className="view-actions">
          {savedTick && !dirty ? (
            <span className="chip chip-accent">Saved</span>
          ) : null}
          <button
            className="btn btn-primary"
            disabled={!dirty || saving}
            onClick={() => void save()}
          >
            {saving ? "Saving…" : "Save Changes"}
          </button>
        </div>
      </div>

      {error ? (
        <div
          className="panel panel-pad"
          style={{ color: "var(--danger)", fontSize: "var(--fs-sm)" }}
        >
          {error}
        </div>
      ) : null}

      <span className="section-label">General</span>
      <div className="panel" style={{ marginTop: "calc(-1 * var(--s-3))" }}>
        <div className="list">
          <div className="settings-row">
            <div>
              <div className="field-label">Device name</div>
              <div className="field-hint">
                How this PC appears on your iPhone.
              </div>
            </div>
            <div className="settings-control">
              <input
                className="input"
                style={{ width: 220 }}
                value={draft.deviceName}
                onChange={(e) => set("deviceName", e.target.value)}
                spellCheck={false}
              />
            </div>
          </div>

          <div className="settings-row">
            <div>
              <div className="field-label">Receive folder</div>
              <div className="field-hint mono">{draft.receiveDir}</div>
            </div>
            <div className="settings-control">
              <button className="btn" onClick={() => void changeReceiveDir()}>
                <IconFolder size={14} />
                Change…
              </button>
              <button
                className="btn btn-ghost"
                onClick={() => void api.openReceiveFolder()}
              >
                Open
              </button>
            </div>
          </div>
        </div>
      </div>

      <span className="section-label">Transfer</span>
      <div className="panel" style={{ marginTop: "calc(-1 * var(--s-3))" }}>
        <div className="list">
          <div className="settings-row">
            <div>
              <div className="field-label">Port</div>
              <div className="field-hint">
                Preferred port for the local server. Default 48800.
              </div>
            </div>
            <div className="settings-control">
              <input
                className="input"
                type="number"
                min={1024}
                max={65535}
                style={{ width: 110 }}
                value={draft.preferredPort}
                onChange={(e) => {
                  const v = Number(e.target.value);
                  if (Number.isInteger(v)) set("preferredPort", v);
                }}
              />
            </div>
          </div>

          <div className="settings-row">
            <div>
              <div className="field-label">Simultaneous transfers</div>
              <div className="field-hint">
                How many files move at once. Lower is gentler on Wi-Fi.
              </div>
            </div>
            <div className="settings-control">
              <select
                className="input"
                style={{ width: 80 }}
                value={draft.concurrency}
                onChange={(e) => set("concurrency", Number(e.target.value))}
              >
                {[1, 2, 3, 4].map((n) => (
                  <option key={n} value={n}>
                    {n}
                  </option>
                ))}
              </select>
            </div>
          </div>
        </div>
      </div>

      <span className="section-label">Windows</span>
      <div className="panel" style={{ marginTop: "calc(-1 * var(--s-3))" }}>
        <div className="list">
          <div className="settings-row">
            <div>
              <div className="field-label">Launch on startup</div>
              <div className="field-hint">
                Start Sendro quietly when you sign in to Windows.
              </div>
            </div>
            <Toggle
              on={draft.launchOnStartup}
              label="Launch on startup"
              onChange={(on) => set("launchOnStartup", on)}
            />
          </div>

          <div className="settings-row">
            <div>
              <div className="field-label">Minimize to tray</div>
              <div className="field-hint">
                Closing the window keeps Sendro running in the system tray.
              </div>
            </div>
            <Toggle
              on={draft.minimizeToTray}
              label="Minimize to tray"
              onChange={(on) => set("minimizeToTray", on)}
            />
          </div>
        </div>
      </div>

      <span className="section-label">About</span>
      <div
        className="panel panel-pad"
        style={{ marginTop: "calc(-1 * var(--s-3))" }}
      >
        <div className="about-line">
          <span>Sendro for Windows</span>
          <span className="mono">{version ? `v${version}` : "—"}</span>
        </div>
        <div className="about-line">
          <span>Local address</span>
          <span className="mono">
            {info
              ? info.localIps.length > 0
                ? info.localIps
                    .map((ip) => `${ip}:${info.apiPort}`)
                    .join("  ·  ")
                : `port ${info.apiPort}`
              : "—"}
          </span>
        </div>
        <div className="about-line">
          <span>Protocol</span>
          <span className="mono">v{info?.protocolVersion ?? "—"}</span>
        </div>
        <div className="about-line">
          <span style={{ color: "var(--text-tertiary)" }}>
            Local-only. Private by design.
          </span>
          <span />
        </div>
      </div>
    </div>
  );
}
