/**
 * Pairing *outward* (PROTOCOL.md §4, this PC as the client).
 *
 * The other pairing modal in this app — `PairingModal` — is the inbound one:
 * something asked to pair with this PC and we show it a code. This is the
 * mirror: we asked a phone/TV to pair, *it* shows the six digits, and the user
 * types them here.
 *
 * The code never touches the network: the core turns it into an HMAC proof
 * (§4.2). A wrong code leaves the session alive, so the user can just try
 * again; an expired one needs a fresh session, which the Retry button starts.
 */
import { useEffect, useRef, useState } from "react";
import { Modal } from "./Modal";
import * as api from "../api";
import { platformLabel } from "../targets";
import type { PairedPeer, PeerPairingSession } from "../types";

const CODE_LENGTH = 6;

export function PeerPairDialog({
  session,
  onDone,
  onClose,
  onRestart,
}: {
  session: PeerPairingSession;
  onDone: (peer: PairedPeer) => void;
  onClose: () => void;
  /** Start a fresh session against the same address (expired / burnt). */
  onRestart: () => void;
}) {
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [left, setLeft] = useState(session.expiresInSeconds);
  const inputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  // Same 120 s the peer is counting down (§4.1), so the two screens agree.
  useEffect(() => {
    setLeft(session.expiresInSeconds);
    const timer = window.setInterval(
      () => setLeft((s) => (s > 0 ? s - 1 : 0)),
      1000,
    );
    return () => window.clearInterval(timer);
  }, [session.pairingId, session.expiresInSeconds]);

  const expired = left <= 0;

  const confirm = async () => {
    if (code.length !== CODE_LENGTH || busy) return;
    setBusy(true);
    setError(null);
    try {
      const peer = await api.confirmPeerPairing(session.pairingId, code);
      onDone(peer);
    } catch (err) {
      setError(String(err));
      setCode("");
      inputRef.current?.focus();
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title="Pair with this device" onClose={onClose} cancelLabel="Cancel">
      <div className="peer-pair-head">
        <div className="peer-pair-name">{session.deviceName}</div>
        <div className="peer-pair-sub mono">
          {platformLabel(session.platform)} · {session.address}:{session.port}
        </div>
      </div>

      <div className="peer-pair-copy">
        {expired
          ? "That code has expired — start again and the device will show a new one."
          : `Look at ${session.deviceName}: it is showing a 6-digit code. Type it here.`}
      </div>

      <input
        ref={inputRef}
        className="input peer-code-input mono"
        inputMode="numeric"
        autoComplete="off"
        spellCheck={false}
        maxLength={CODE_LENGTH}
        placeholder="000000"
        value={code}
        disabled={busy || expired}
        onChange={(e) => {
          setCode(e.target.value.replace(/\D/g, "").slice(0, CODE_LENGTH));
          setError(null);
        }}
        onKeyDown={(e) => {
          if (e.key === "Enter") void confirm();
        }}
      />

      {error ? <div className="error-note">{error}</div> : null}

      <div className="peer-pair-foot">
        <span className="peer-pair-timer mono">
          {expired
            ? "expired"
            : `expires in ${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}`}
        </span>
        <span className="composer-foot-spacer" />
        {expired || error ? (
          <button className="btn-glass btn-sm" onClick={onRestart}>
            Start again
          </button>
        ) : null}
        <button
          className="btn-solid btn-sm"
          disabled={busy || expired || code.length !== CODE_LENGTH}
          onClick={() => void confirm()}
        >
          {busy ? "Pairing…" : "Pair"}
        </button>
      </div>

      <div className="composer-hint">
        <span>
          the code never leaves the two screens · nothing leaves your network
        </span>
      </div>
    </Modal>
  );
}
