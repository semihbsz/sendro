import { useCallback, useEffect, useState } from "react";
import { useAppDispatch, useAppState } from "../store";
import * as api from "../api";
import { kindLabel } from "../qr";
import { QrCode } from "./QrCode";

/**
 * The QR half of pairing (PROTOCOL.md §13).
 *
 * The code is drawn here in the webview from a bundled generator — nothing is
 * fetched, nothing is uploaded, and the secret only ever travels over the
 * optical channel (this screen → your camera).
 *
 * The session is an ordinary §4 pairing session, so the 6-digit code stays
 * valid underneath as the typed fallback, and both expire together after
 * 120 s. When it expires we mint a fresh one so the screen is never showing a
 * code that silently stopped working.
 */
export function QrPairPanel() {
  const { qrPairing } = useAppState();
  const dispatch = useAppDispatch();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState(0);
  const [secondsLeft, setSecondsLeft] = useState(0);

  const start = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      const qr = await api.startQrPairing();
      dispatch({ type: "set-qr-pairing", qr });
      setSelected(0);
      setSecondsLeft(qr.expiresInSeconds);
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  }, [dispatch]);

  // Open a session as soon as the tab is shown.
  useEffect(() => {
    if (!qrPairing) void start();
    else setSecondsLeft((s) => (s > 0 ? s : qrPairing.expiresInSeconds));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Countdown, and a fresh session the moment this one dies.
  useEffect(() => {
    if (!qrPairing) return;
    const timer = window.setInterval(() => {
      setSecondsLeft((s) => Math.max(0, s - 1));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [qrPairing]);

  useEffect(() => {
    if (qrPairing && secondsLeft === 0 && !busy) void start();
  }, [qrPairing, secondsLeft, busy, start]);

  if (error) {
    return (
      <div className="qr-panel">
        <div className="error-note">{error}</div>
        <button className="btn-glass btn-sm" onClick={() => void start()}>
          Try again
        </button>
      </div>
    );
  }

  if (!qrPairing) {
    return (
      <div className="qr-panel">
        <div className="qr-placeholder" />
        <div className="qr-foot">opening a pairing session…</div>
      </div>
    );
  }

  const urls = qrPairing.urls;
  const active = urls[Math.min(selected, urls.length - 1)];
  const digits = qrPairing.code.split("");

  return (
    <div className="qr-panel">
      {active ? (
        <QrCode
          data={active.url}
          size={288}
          label={`Pairing code for ${active.address}`}
        />
      ) : (
        <div className="qr-tile qr-tile-failed" style={{ width: 288, height: 288 }}>
          <span>This PC has no network address to advertise</span>
        </div>
      )}

      {urls.length > 1 ? (
        <div className="qr-addr-row" role="group" aria-label="Host address">
          {urls.map((u, i) => (
            <button
              key={u.address}
              className={`qr-addr${i === Math.min(selected, urls.length - 1) ? " active" : ""}`}
              onClick={() => setSelected(i)}
              title={u.address}
            >
              <span className={`kind-badge ${u.kind}`}>{kindLabel(u.kind)}</span>
              <span className="qr-addr-ip mono">{u.address}</span>
            </button>
          ))}
        </div>
      ) : active ? (
        <div className="qr-addr-single">
          <span className={`kind-badge ${active.kind}`}>
            {kindLabel(active.kind)}
          </span>
          <span className="mono">{active.address}</span>
        </div>
      ) : null}

      <div className="qr-fallback">
        <span className="qr-fallback-label">or type this code</span>
        <div className="pairing-code compact">
          {digits.map((d, i) => (
            <span key={i} className="pairing-digit">
              {d}
            </span>
          ))}
        </div>
      </div>

      <div className="qr-foot">
        {busy
          ? "refreshing…"
          : secondsLeft > 0
            ? `expires in ${secondsLeft}s · nothing leaves your network`
            : "expired — opening a new code…"}
      </div>
    </div>
  );
}
