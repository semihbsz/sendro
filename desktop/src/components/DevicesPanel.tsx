/**
 * The Devices surface (inside Settings).
 *
 * Sendro moves files in two directions and, until now, the PC only understood
 * one of them. Both are on this screen, named in plain language:
 *
 *   "Devices that send to this PC" — they pair to us and pull from our outbox
 *                                    (PROTOCOL.md §4/§6). We show them a code.
 *   "Devices this PC can send to"  — we pair to them and push (§4/§7/§15).
 *                                    They show *us* a code.
 *
 * A device can be in both lists; that is not a mistake, it just means trust
 * was established in both directions.
 */
import { useEffect, useRef, useState } from "react";
import { open } from "@tauri-apps/plugin-dialog";
import { useAppDispatch, useAppState } from "../store";
import * as api from "../api";
import { formatDate, formatRelative, isOnline } from "../format";
import { IconPhone, IconRadar, IconWifi } from "../icons";
import { platformIcon, platformLabel } from "../targets";
import { PeerPairDialog } from "./PeerPairDialog";
import type { DiscoveredPeer, PairedPeer, PeerPairingSession } from "../types";

const DEFAULT_PORT = 48800;

/** "192.168.1.42" or "192.168.1.42:48801" → address + port. */
function parseTarget(raw: string): { address: string; port: number } | null {
  const trimmed = raw.trim();
  if (trimmed === "") return null;
  const match = /^([^:\s]+)(?::(\d{1,5}))?$/.exec(trimmed);
  if (!match) return null;
  const port = match[2] ? Number(match[2]) : DEFAULT_PORT;
  if (!Number.isInteger(port) || port < 1 || port > 65535) return null;
  return { address: match[1] ?? "", port };
}

/* ------------------------------------------------------------------ *
 * Inbound: devices that pair to this PC
 * ------------------------------------------------------------------ */

function InboundDevices() {
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
        <span className="strip-label">Devices that send to this PC</span>
        <span className="finished-head-spacer" />
        <button className="btn-glass btn-sm" onClick={() => void showQr()}>
          Pair with a QR code
        </button>
      </div>
      <div className="section-explainer">
        They pair with this PC and pick up whatever you send them. The 6-digit
        code appears on this screen.
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
                open Sendro on the phone and tap this PC
              </div>
            </div>
          </div>
        ) : (
          devices.map((d) => {
            const online = isOnline(d.lastSeenMs);
            return (
              <div className="device-row" key={d.deviceId}>
                <div className="device-row-icon">{platformIcon(d.platform)}</div>
                <div className="device-row-main">
                  <div className="device-row-name">{d.deviceName}</div>
                  <div className="device-row-sub">
                    {platformLabel(d.platform)} · paired {formatDate(d.pairedAtMs)}{" "}
                    ·{" "}
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
    </div>
  );
}

/* ------------------------------------------------------------------ *
 * Outbound: what this PC can send to, and what it can see
 * ------------------------------------------------------------------ */

function NetworkRow({
  peer,
  onPair,
  pairing,
}: {
  peer: DiscoveredPeer;
  onPair: (peer: DiscoveredPeer) => Promise<void>;
  pairing: boolean;
}) {
  return (
    <div className="device-row">
      <div className="device-row-icon">{platformIcon(peer.platform)}</div>
      <div className="device-row-main">
        <div className="device-row-name">{peer.deviceName}</div>
        <div className="device-row-sub">
          {platformLabel(peer.platform)} · {peer.address}:{peer.port}
        </div>
      </div>
      {peer.paired ? (
        <span className="state-chip paired">Paired</span>
      ) : peer.reachable ? (
        <button
          className="btn-solid btn-sm"
          disabled={pairing}
          title={`Ask ${peer.deviceName} to pair — it will show a 6-digit code`}
          // The dialog and the inline error are both handled upstream; this
          // one only has to not become an unhandled rejection.
          onClick={() => void onPair(peer).catch(() => undefined)}
        >
          {pairing ? "Asking…" : "Pair"}
        </button>
      ) : (
        <span
          className="state-chip off"
          title="It is advertising itself but not answering — asleep, or on another subnet"
        >
          Unreachable
        </span>
      )}
    </div>
  );
}

function OnThisNetwork({
  onPair,
  pairingId,
}: {
  onPair: (peer: DiscoveredPeer) => Promise<void>;
  pairingId: string | null;
}) {
  const { discovered, browsing } = useAppState();
  const dispatch = useAppDispatch();
  const [refreshing, setRefreshing] = useState(false);
  const [manual, setManual] = useState("");
  const [manualError, setManualError] = useState<string | null>(null);
  const [manualBusy, setManualBusy] = useState(false);
  const spinTimer = useRef<number | null>(null);

  useEffect(
    () => () => {
      if (spinTimer.current !== null) window.clearTimeout(spinTimer.current);
    },
    [],
  );

  const refresh = async () => {
    setRefreshing(true);
    try {
      await api.refreshPeers();
      const next = await api.discoveredPeers();
      dispatch({ type: "set-discovered", discovered: next });
    } catch (err) {
      console.error("refresh peers failed", err);
    } finally {
      // The browser is continuous, so there is nothing to wait for — hold the
      // spinner briefly anyway so the click visibly did something.
      spinTimer.current = window.setTimeout(() => setRefreshing(false), 700);
    }
  };

  const addByIp = async () => {
    const parsed = parseTarget(manual);
    if (!parsed) {
      setManualError("Enter an address like 192.168.1.42 (optionally :48800)");
      return;
    }
    setManualBusy(true);
    setManualError(null);
    try {
      await onPair({
        deviceId: "",
        deviceName: parsed.address,
        platform: "",
        address: parsed.address,
        port: parsed.port,
        protocolVersion: 0,
        lastSeenMs: Date.now(),
        paired: false,
        reachable: true,
      });
      setManual("");
    } catch (err) {
      setManualError(String(err));
    } finally {
      setManualBusy(false);
    }
  };

  const spinning = refreshing || browsing;

  return (
    <div className="settings-section">
      <div className="settings-section-head">
        <span className="strip-label">On this network</span>
        <span className="finished-head-spacer" />
        {spinning ? <span className="scan-spinner" aria-hidden="true" /> : null}
        <button
          className="btn-glass btn-sm"
          disabled={refreshing}
          onClick={() => void refresh()}
        >
          {spinning ? "Looking…" : "Refresh"}
        </button>
      </div>
      <div className="section-explainer">
        Everything running Sendro on your Wi-Fi right now. Pairing asks the
        device for permission — it shows a code, you type it here.
      </div>

      <div className="settings-panel">
        {discovered.length === 0 ? (
          <div className="device-row">
            <div className="device-row-icon">
              <IconRadar size={17} />
            </div>
            <div className="device-row-main">
              <div className="device-row-name">
                {spinning ? "Looking for devices…" : "Nothing found yet"}
              </div>
              <div className="device-row-sub">
                they need Sendro open and the same network — or add one by IP
                below
              </div>
            </div>
          </div>
        ) : (
          discovered.map((peer) => (
            <NetworkRow
              key={`${peer.deviceId}-${peer.address}`}
              peer={peer}
              pairing={pairingId === peer.deviceId}
              onPair={onPair}
            />
          ))
        )}

        <div className="settings-row manual-add">
          <div>
            <div className="field-label">Add by IP</div>
            <div className="field-hint">
              For a device mDNS cannot see — a guest network, a wired TV, a
              firewall in the way.
            </div>
          </div>
          <div className="settings-control">
            <input
              className="input mono"
              style={{ width: 190 }}
              placeholder="192.168.1.42"
              spellCheck={false}
              value={manual}
              onChange={(e) => {
                setManual(e.target.value);
                setManualError(null);
              }}
              onKeyDown={(e) => {
                if (e.key === "Enter") void addByIp();
              }}
            />
            <button
              className="btn-glass btn-sm"
              disabled={manualBusy || manual.trim() === ""}
              onClick={() => void addByIp()}
            >
              {manualBusy ? "Asking…" : "Pair"}
            </button>
          </div>
        </div>
      </div>
      {manualError ? <div className="error-note">{manualError}</div> : null}
    </div>
  );
}

function PeerRow({ peer }: { peer: PairedPeer }) {
  const dispatch = useAppDispatch();
  const [busy, setBusy] = useState<null | "ping" | "forget">(null);
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const noteTimer = useRef<number | null>(null);

  useEffect(
    () => () => {
      if (noteTimer.current !== null) window.clearTimeout(noteTimer.current);
    },
    [],
  );

  const sendFiles = async () => {
    setError(null);
    let paths: string[] = [];
    try {
      const selected = await open({
        multiple: true,
        title: `Send to ${peer.deviceName}`,
      });
      if (selected === null) return;
      paths = Array.isArray(selected) ? selected : [selected];
    } catch (err) {
      setError(String(err));
      return;
    }
    if (paths.length === 0) return;

    // A §7 push only resolves when the whole batch has finished, so this is
    // deliberately not awaited: the user goes to Flow and watches it move.
    // Per-file failures land there as failed cards carrying their own error;
    // a whole-call rejection (an unreadable path) can only be logged, because
    // this row is gone by then.
    dispatch({ type: "set-view", view: "flow" });
    void api
      .sendFilesToPeer(peer.deviceId, paths)
      .then(() => api.getQueue())
      .then((queue) => dispatch({ type: "set-queue", queue }))
      .catch((err) => console.error("send to peer failed", err));
  };

  const check = async () => {
    setBusy("ping");
    setError(null);
    try {
      const ok = await api.pingPeer(peer.deviceId);
      setNote(ok ? "reachable" : "no answer");
      if (noteTimer.current !== null) window.clearTimeout(noteTimer.current);
      noteTimer.current = window.setTimeout(() => setNote(null), 2500);
      const peers = await api.pairedPeers();
      dispatch({ type: "set-peers", peers });
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(null);
    }
  };

  const forget = async () => {
    setBusy("forget");
    try {
      await api.forgetPeer(peer.deviceId);
      const peers = await api.pairedPeers();
      dispatch({ type: "set-peers", peers });
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="device-row">
      <div className="device-row-icon">{platformIcon(peer.platform)}</div>
      <div className="device-row-main">
        <div className="device-row-name">{peer.deviceName}</div>
        <div className="device-row-sub">
          {platformLabel(peer.platform)} · {peer.address}:{peer.port} · paired{" "}
          {formatDate(peer.pairedAtMs)}
          {peer.receiveOnly ? " · receives only" : ""}
        </div>
        {error ? <div className="row-error">{error}</div> : null}
      </div>
      {note ? <span className="state-chip paired">{note}</span> : null}
      <button
        className="btn-glass btn-sm"
        disabled={busy !== null}
        onClick={() => void sendFiles()}
      >
        Send files…
      </button>
      <button
        className="btn-glass btn-sm"
        disabled={busy !== null}
        onClick={() =>
          dispatch({ type: "open-composer", text: "", targetId: peer.deviceId })
        }
      >
        Send text…
      </button>
      <button
        className="btn-ghost-text"
        style={{ padding: "8px 10px" }}
        disabled={busy !== null}
        title="Check that it still answers"
        onClick={() => void check()}
      >
        Check
      </button>
      <button
        className="btn-revoke"
        disabled={busy !== null}
        title="Forget this device — Sendro drops the key it uses to send"
        onClick={() => void forget()}
      >
        Forget
      </button>
    </div>
  );
}

function OutboundPeers() {
  const { peers } = useAppState();
  return (
    <div className="settings-section">
      <div className="settings-section-head">
        <span className="strip-label">Devices this PC can send to</span>
      </div>
      <div className="section-explainer">
        Phones and TVs you paired with from here. Files go straight to them —
        they don't have to ask for anything.
      </div>
      <div className="settings-panel">
        {peers.length === 0 ? (
          <div className="device-row">
            <div className="device-row-icon">
              <IconRadar size={17} />
            </div>
            <div className="device-row-main">
              <div className="device-row-name">Nothing paired this way yet</div>
              <div className="device-row-sub">
                pick a device above and hit Pair
              </div>
            </div>
          </div>
        ) : (
          peers.map((peer) => <PeerRow key={peer.deviceId} peer={peer} />)
        )}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ *
 * The whole surface
 * ------------------------------------------------------------------ */

export function DevicesPanel() {
  const dispatch = useAppDispatch();
  const [session, setSession] = useState<PeerPairingSession | null>(null);
  const [pairingId, setPairingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const lastTarget = useRef<{ address: string; port: number } | null>(null);

  /** Ask a peer to open a pairing session (§4.1) and show the code dialog. */
  const startPairing = async (peer: DiscoveredPeer): Promise<void> => {
    setError(null);
    setPairingId(peer.deviceId || peer.address);
    lastTarget.current = { address: peer.address, port: peer.port };
    try {
      const started = await api.pairWithPeer(peer.address, peer.port);
      setSession(started);
    } catch (err) {
      setError(String(err));
      throw err; // let "Add by IP" surface it inline too
    } finally {
      setPairingId(null);
    }
  };

  const restart = async () => {
    const target = lastTarget.current;
    if (!target) return;
    setSession(null);
    try {
      setSession(await api.pairWithPeer(target.address, target.port));
    } catch (err) {
      setError(String(err));
    }
  };

  return (
    <>
      <InboundDevices />

      <OnThisNetwork onPair={startPairing} pairingId={pairingId} />
      {error ? <div className="error-note">{error}</div> : null}

      <OutboundPeers />

      <div className="pair-hint">
        <IconWifi size={17} />
        <div>
          <div className="pair-hint-title">Two directions, one network</div>
          <div className="pair-hint-body">
            A phone pairs <em>to</em> this PC when it wants to pull files from
            it. This PC pairs <em>to</em> a phone or TV when it wants to push
            files at it. Same 6-digit code, same 2 minutes, same rule: it only
            ever travels from one screen to the other pair of eyes in the room.
            A device can do both.
          </div>
        </div>
      </div>

      {session ? (
        <PeerPairDialog
          session={session}
          onClose={() => setSession(null)}
          onRestart={() => void restart()}
          onDone={(peer) => {
            setSession(null);
            void api
              .pairedPeers()
              .then((peers) => dispatch({ type: "set-peers", peers }))
              .catch(() => undefined);
            void api
              .discoveredPeers()
              .then((discovered) =>
                dispatch({ type: "set-discovered", discovered }),
              )
              .catch(() => undefined);
            console.info(`paired with ${peer.deviceName}`);
          }}
        />
      ) : null}
    </>
  );
}
