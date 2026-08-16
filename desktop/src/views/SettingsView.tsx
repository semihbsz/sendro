import { useEffect, useState } from "react";
import { open } from "@tauri-apps/plugin-dialog";
import { useAppDispatch, useAppState } from "../store";
import * as api from "../api";
import { formatDate, formatRelative, isOnline } from "../format";
import { IconPhone, IconWifi } from "../icons";
import { Toggle } from "../components/common";
import { ConnectionCard } from "../components/ConnectionCard";
import { usePrefs } from "../prefs";
import { useUpdates } from "../updates";
import {
  ensurePermission,
  permissionGranted,
  resetPermission,
  NOTIFY_CATEGORIES,
} from "../notify";
import type { Settings } from "../types";

/** Trusted devices — folded into Settings in the new IA (the rail has no
 *  Devices tab; the gear is where trust is managed). */
function DevicesSection() {
  const { devices } = useAppState();
  const dispatch = useAppDispatch();
  const [pairError, setPairError] = useState<string | null>(null);

  /** §13: opens a real pairing session and shows it as a QR in the modal. */
  const showQr = async () => {
    setPairError(null);
    try {
      const qr = await api.startQrPairing();
      dispatch({ type: "set-qr-pairing", qr });
    } catch (err) {
      setPairError(String(err));
    }
  };

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
      <div className="settings-section-head">
        <span className="strip-label">Devices</span>
        <span className="finished-head-spacer" />
        <button className="btn-glass btn-sm" onClick={() => void showQr()}>
          Pair with a QR code
        </button>
      </div>
      {pairError ? <div className="error-note">{pairError}</div> : null}
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
            Or skip all that: hit “Pair with a QR code” and point the iPhone's
            camera at the screen. Same session, same 2-minute expiry — the
            code only ever travels from your screen to your camera.
          </div>
        </div>
      </div>
    </div>
  );
}

/** Windows toast notifications — per-category, remembered on this PC. */
function NotificationsSection() {
  const [prefs, setPrefs] = usePrefs();
  const [granted, setGranted] = useState<boolean | null>(null);

  useEffect(() => {
    permissionGranted().then(setGranted).catch(() => setGranted(false));
  }, []);

  const ask = async () => {
    resetPermission();
    const ok = await ensurePermission();
    setGranted(ok);
  };

  return (
    <div className="settings-section">
      <span className="strip-label">Notifications</span>
      <div className="settings-panel">
        <div className="settings-row">
          <div>
            <div className="field-label">Windows notifications</div>
            <div className="field-hint">
              Sendro stays quiet while you're looking at the relevant screen —
              you only get a toast when the window is behind something or in
              the tray.
            </div>
          </div>
          <div className="settings-control">
            {granted === false ? (
              <button className="btn-glass btn-sm" onClick={() => void ask()}>
                Allow notifications
              </button>
            ) : null}
            <Toggle
              on={prefs.enabled}
              label="Windows notifications"
              onChange={(on) => setPrefs({ ...prefs, enabled: on })}
            />
          </div>
        </div>

        {NOTIFY_CATEGORIES.map((c) => (
          <div className="settings-row" key={c.key}>
            <div>
              <div className="field-label">{c.label}</div>
              <div className="field-hint">{c.hint}</div>
            </div>
            <Toggle
              on={prefs.categories[c.key]}
              disabled={!prefs.enabled}
              label={c.label}
              onChange={(on) =>
                setPrefs({
                  ...prefs,
                  categories: { ...prefs.categories, [c.key]: on },
                })
              }
            />
          </div>
        ))}
      </div>
    </div>
  );
}

/**
 * In-app updates (UPDATES.md §3). The card itself floats over the app; this
 * is the always-there half: the auto-check switch, the manual button, and the
 * version this build actually is.
 */
function UpdatesSection() {
  const u = useUpdates();
  const unsupported = u.phase === "unsupported";

  const status = (() => {
    if (unsupported) {
      return {
        tone: "warn" as const,
        text:
          u.unsupportedReason ??
          "Updates are not configured in this build — install new versions from the release page.",
      };
    }
    if (u.phase === "checking") return { tone: null, text: "Checking…" };
    if (u.error) {
      return { tone: "bad" as const, text: u.error.message };
    }
    if (u.phase === "downloading" || u.phase === "installing") {
      return { tone: null, text: "Downloading the update…" };
    }
    if (u.release) {
      return {
        tone: "warn" as const,
        text: `Sendro ${u.release.version} is ready to install.`,
      };
    }
    if (u.lastCheckedMs !== null) {
      return {
        tone: null,
        text: `You're on the latest version — checked ${formatRelative(u.lastCheckedMs)}.`,
      };
    }
    return {
      tone: null,
      text: u.autoCheck
        ? "Sendro checks shortly after launch and every 6 hours."
        : "Automatic checks are off — use “Check now”.",
    };
  })();

  return (
    <div className="settings-section">
      <span className="strip-label">Updates</span>
      <div className="settings-panel">
        <div className="settings-row">
          <div>
            <div className="field-label">Check for updates automatically</div>
            <div className="field-hint">
              A plain HTTPS request for one small file — no identifiers, no
              telemetry. Nothing is ever downloaded without your click.
            </div>
          </div>
          <Toggle
            on={u.autoCheck}
            disabled={unsupported}
            label="Check for updates automatically"
            onChange={u.setAutoCheck}
          />
        </div>

        <div className="settings-row">
          <div>
            <div className="field-label">This version</div>
            <div className="field-hint mono">
              v{u.currentVersion || "—"}
              {u.release ? `  →  v${u.release.version} available` : ""}
            </div>
            <div className={`update-status${status.tone ? ` ${status.tone}` : ""}`}>
              {status.text}
            </div>
          </div>
          <div className="settings-control">
            {unsupported || u.error ? (
              <button
                className="btn-glass btn-sm"
                onClick={() => void u.openReleasePage()}
              >
                Open release page
              </button>
            ) : null}
            <button
              className="btn-glass btn-sm"
              disabled={unsupported || u.phase === "checking"}
              onClick={() => void u.checkNow()}
            >
              {u.phase === "checking" ? "Checking…" : "Check now"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

export function SettingsView() {
  const { settings, info } = useAppState();
  const dispatch = useAppDispatch();
  const { currentVersion: version } = useUpdates();
  const [draft, setDraft] = useState<Settings | null>(settings);
  const [saving, setSaving] = useState(false);
  const [savedTick, setSavedTick] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setDraft(settings);
  }, [settings]);

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

      <ConnectionCard />

      <NotificationsSection />

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

      <UpdatesSection />

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
