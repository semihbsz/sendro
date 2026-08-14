import { useEffect, useState } from "react";
import { getVersion } from "@tauri-apps/api/app";
import { open } from "@tauri-apps/plugin-dialog";
import { useAppDispatch, useAppState } from "../store";
import * as api from "../api";
import { formatDate, formatRelative, isOnline } from "../format";
import { IconPhone, IconWifi } from "../icons";
import { Toggle } from "../components/common";
import type { Settings } from "../types";

/** Trusted devices — folded into Settings in the new IA (the rail has no
 *  Devices tab; the gear is where trust is managed). */
function DevicesSection() {
  const { devices } = useAppState();
  const dispatch = useAppDispatch();

  const revoke = async (deviceId: string) => {
    try {
      await api.revokeDevice(deviceId);
      const next = await api.trustedDevices();
      dispatch({ type: "set-devices", devices: next });
    } catch (err) {
      console.error("revoke failed", err);
    }
  };

  return (
    <div className="settings-section">
      <span className="strip-label">Devices</span>
      <div className="settings-panel">
        {devices.length === 0 ? (
          <div className="device-row">
            <div className="device-row-icon">
              <IconPhone size={17} />
            </div>
            <div className="device-row-main">
              <div className="device-row-name">No devices paired yet</div>
              <div className="device-row-sub">
                pair below — the 6-digit code appears on this screen
              </div>
            </div>
          </div>
        ) : (
          devices.map((d) => {
            const online = isOnline(d.lastSeenMs);
            return (
              <div className="device-row" key={d.deviceId}>
                <div className="device-row-icon">
                  <IconPhone size={17} />
                </div>
                <div className="device-row-main">
                  <div className="device-row-name">{d.deviceName}</div>
                  <div className="device-row-sub">
                    {d.platform === "ios" ? "iOS" : d.platform} · paired{" "}
                    {formatDate(d.pairedAtMs)} ·{" "}
                    {online
                      ? "online now"
                      : `last seen ${formatRelative(d.lastSeenMs)}`}
                  </div>
                </div>
                <span className={`online-pill${online ? "" : " off"}`}>
                  <span
                    className={`pulse-dot${online ? "" : " off"}`}
                    style={{ width: 6, height: 6 }}
                  />
                  {online ? "online" : "offline"}
                </span>
                <button
                  className="btn-revoke"
                  onClick={() => void revoke(d.deviceId)}
                  title="Revoke trust — this device will need to pair again"
                >
                  Revoke
                </button>
              </div>
            );
          })
        )}
      </div>

      <div className="pair-hint">
        <IconWifi size={17} />
        <div>
          <div className="pair-hint-title">How to pair</div>
          <div className="pair-hint-body">
            Make sure both devices are on the same Wi-Fi network, then:
            <ol>
              <li>Open Sendro on your iPhone.</li>
              <li>Tap this PC when it appears under nearby devices.</li>
              <li>Type the 6-digit code that pops up on this screen.</li>
            </ol>
          </div>
        </div>
      </div>
    </div>
  );
}

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
      <div className="page" key="settings">
        <div className="page-title">Settings</div>
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
    <div className="page" key="settings">
      <div className="page-head">
        <div className="page-head-text">
          <div className="page-title">Settings</div>
          <div className="page-sub">
            name & port changes apply after the local server restarts
          </div>
        </div>
        {savedTick && !dirty ? <span className="save-note">saved</span> : null}
        <button
          className="btn-solid"
          disabled={!dirty || saving}
          onClick={() => void save()}
        >
          {saving ? "Saving…" : "Save changes"}
        </button>
      </div>

      {error ? <div className="error-note">{error}</div> : null}

      <DevicesSection />

      <div className="settings-section">
        <span className="strip-label">General</span>
        <div className="settings-panel">
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
              <button
                className="btn-glass btn-sm"
                onClick={() => void changeReceiveDir()}
              >
                Change…
              </button>
              <button
                className="btn-ghost-text"
                style={{ padding: "10px 12px" }}
                onClick={() => void api.openReceiveFolder()}
              >
                Open
              </button>
            </div>
          </div>
        </div>
      </div>

      <div className="settings-section">
        <span className="strip-label">Transfer</span>
        <div className="settings-panel">
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

      <div className="settings-section">
        <span className="strip-label">Windows</span>
        <div className="settings-panel">
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

      <div className="settings-section">
        <span className="strip-label">About</span>
        <div className="settings-panel">
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
    </div>
  );
}
