import { useCallback, useEffect, useState } from "react";
import { writeText } from "@tauri-apps/plugin-clipboard-manager";
import { useAppDispatch, useAppState } from "../store";
import * as api from "../api";
import { kindLabel } from "../qr";
import { IconWifi } from "../icons";
import type { NetIface } from "../types";

/**
 * Which address should the phone actually use?
 *
 * A plain LAN address first — that is the normal, fast case. Otherwise a
 * hotspot address, which is the whole point of the no-router setups below.
 */
function preferred(ifaces: readonly NetIface[]): NetIface | null {
  return (
    ifaces.find((i) => i.kind === "lan") ??
    ifaces.find((i) => i.kind === "hotspot") ??
    ifaces[0] ??
    null
  );
}

/**
 * The "Connection" card: every address this PC answers on, which one the
 * phone should use, and how to get the two talking with no router at all.
 */
export function ConnectionCard() {
  const { info } = useAppState();
  const dispatch = useAppDispatch();
  const [ifaces, setIfaces] = useState<NetIface[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      // Re-enumerated in the core on every call: a Windows Mobile Hotspot
      // adapter usually appears *after* Sendro started.
      setIfaces(await api.networkInterfaces());
      setError(null);
    } catch (err) {
      setError(String(err));
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    if (copied === null) return;
    const timer = window.setTimeout(() => setCopied(null), 1600);
    return () => window.clearTimeout(timer);
  }, [copied]);

  const port = info?.apiPort ?? null;
  const best = ifaces ? preferred(ifaces) : null;

  const copy = async (address: string) => {
    try {
      await writeText(port ? `${address}:${port}` : address);
      setCopied(address);
    } catch (err) {
      console.error("copy failed", err);
    }
  };

  const showQr = async () => {
    try {
      const qr = await api.startQrPairing();
      dispatch({ type: "set-qr-pairing", qr });
    } catch (err) {
      setError(String(err));
    }
  };

  return (
    <div className="settings-section">
      <span className="strip-label">Connection</span>
      <div className="settings-panel">
        <div className="conn-head">
          <div>
            <div className="field-label">This PC answers on</div>
            <div className="field-hint">
              Your iPhone needs one of these. Highlighted is the one to try
              first.
            </div>
          </div>
          <div className="settings-control">
            <button className="btn-glass btn-sm" onClick={() => void refresh()}>
              Rescan
            </button>
            <button className="btn-solid btn-sm" onClick={() => void showQr()}>
              Show pairing QR
            </button>
          </div>
        </div>

        {error ? <div className="error-note">{error}</div> : null}

        {ifaces === null ? (
          <div className="conn-empty">looking at your adapters…</div>
        ) : ifaces.length === 0 ? (
          <div className="conn-empty">
            No usable network address. Connect to Wi-Fi, plug in Ethernet, or
            start a hotspot — then hit Rescan.
          </div>
        ) : (
          <div className="conn-list">
            {ifaces.map((iface) => {
              const isBest = best?.address === iface.address;
              return (
                <div
                  className={`conn-row${isBest ? " best" : ""}`}
                  key={`${iface.name}-${iface.address}`}
                >
                  <span className={`kind-badge ${iface.kind}`}>
                    {kindLabel(iface.kind)}
                  </span>
                  <span className="conn-addr mono">
                    {iface.address}
                    {port ? `:${port}` : ""}
                  </span>
                  <span className="conn-name" title={iface.name}>
                    {iface.name}
                  </span>
                  {isBest ? (
                    <span className="conn-best-tag">use this one</span>
                  ) : null}
                  <button
                    className="btn-glass btn-sm"
                    onClick={() => void copy(iface.address)}
                  >
                    {copied === iface.address ? "Copied" : "Copy"}
                  </button>
                </div>
              );
            })}
          </div>
        )}
      </div>

      <div className="pair-hint">
        <IconWifi size={17} />
        <div>
          <div className="pair-hint-title">No router? Still works.</div>
          <div className="pair-hint-body">
            Sendro only needs the two devices on the same link — a router is
            just the usual way to get one.
            <ol>
              <li>
                <b>Windows Mobile Hotspot</b> — turn it on in Windows Settings
                and join it from the iPhone. Your PC's address will be on{" "}
                <span className="mono">192.168.137.x</span>.
              </li>
              <li>
                <b>iPhone Personal Hotspot</b> — turn it on and join it from
                the PC. Your PC's address will be on{" "}
                <span className="mono">172.20.10.x</span>.
              </li>
            </ol>
            On an iPhone Personal Hotspot, Bonjour discovery is unreliable —
            the PC often will not appear by itself. Scan the QR, or type the
            address above into the iPhone by hand. That path always works.
          </div>
        </div>
      </div>
    </div>
  );
}
