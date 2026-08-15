import { useEffect, useState } from "react";
import { Modal } from "./Modal";
import { QrPairPanel } from "./QrPairPanel";
import { useAppDispatch, useAppState } from "../store";

type Tab = "code" | "qr";

/** Shown app-wide when a PairingStarted core event arrives; auto-dismissed
 *  by the store on PairingCompleted / PairingFailed. Two ways in: the typed
 *  6-digit code (§4) and a scannable QR (§13) — the same session either way. */
export function PairingModal() {
  const { pairing, qrPairing } = useAppState();
  const dispatch = useAppDispatch();
  // A session opened *by* the QR button lands here with its payload already
  // in the store, so open on that tab.
  const [tab, setTab] = useState<Tab>(qrPairing ? "qr" : "code");

  useEffect(() => {
    if (qrPairing) setTab("qr");
  }, [qrPairing]);

  if (!pairing) return null;

  const digits = pairing.code.split("");

  return (
    <Modal onClose={() => dispatch({ type: "dismiss-pairing" })}>
      <div className="pairing-modal">
        <div className="pair-tabs" role="tablist" aria-label="Pairing method">
          <button
            role="tab"
            aria-selected={tab === "code"}
            className={`pair-tab${tab === "code" ? " active" : ""}`}
            onClick={() => setTab("code")}
          >
            6-digit code
          </button>
          <button
            role="tab"
            aria-selected={tab === "qr"}
            className={`pair-tab${tab === "qr" ? " active" : ""}`}
            onClick={() => setTab("qr")}
          >
            QR code
          </button>
        </div>

        {tab === "code" ? (
          <>
            <div className="pairing-hint">Enter this code on your iPhone</div>
            <div className="pairing-device">
              “{pairing.deviceName}” is trying to pair with this PC
            </div>
            <div className="pairing-code">
              {digits.map((d, i) => (
                <span key={i} className="pairing-digit">
                  {d}
                </span>
              ))}
            </div>
            <div className="pairing-foot">
              expires in ~2 minutes · nothing leaves your network
            </div>
          </>
        ) : (
          <>
            <div className="pairing-hint">Scan this with your iPhone</div>
            <div className="pairing-device">
              Point the Camera app at it — Sendro opens and pairs itself
            </div>
            <QrPairPanel />
          </>
        )}
      </div>
    </Modal>
  );
}
