import { useAppState } from "../store";
import * as api from "../api";
import { formatBytes, formatEta, formatPercent, formatSpeed } from "../format";
import { IconRetry, IconX } from "../icons";
import { PhasePill, ProgressRing, Sparkline, ringFraction } from "./transfer";
import { FileName } from "./common";
import { targetForTransfer } from "../preview";
import type { TransferSummary } from "../types";

export const RETRYABLE = new Set(["failed", "interrupted", "expired"]);
export const CANCELABLE = new Set([
  "queued",
  "hashing",
  "offered",
  "accepted",
  "transferring",
  "verifying",
  "saving",
]);

function metaLine(t: TransferSummary, paused: boolean): string {
  const peer = `${t.direction === "outgoing" ? "to" : "from"} ${t.deviceName}`;
  switch (t.state) {
    case "queued":
      return `${formatBytes(t.sizeBytes)} · ${peer} · waiting for a slot`;
    case "hashing":
      return `${formatBytes(t.sizeBytes)} · preparing sha-256…`;
    case "offered":
      return `${formatBytes(t.sizeBytes)} · waiting for ${t.deviceName}…`;
    case "accepted":
      return `${formatBytes(t.sizeBytes)} · ${peer} · starting`;
    case "transferring":
      if (paused) {
        return `${formatBytes(t.bytesTransferred)} / ${formatBytes(t.sizeBytes)} · paused`;
      }
      return `${formatBytes(t.bytesTransferred)} / ${formatBytes(t.sizeBytes)} · ${formatSpeed(t.speedBps)} · ETA ${formatEta(t.etaSeconds)}`;
    case "verifying":
      return `${formatBytes(t.sizeBytes)} · matching sha-256 on ${t.deviceName}`;
    case "saving":
      return `${formatBytes(t.sizeBytes)} · saving on ${t.deviceName}`;
    case "completed":
      return `${formatBytes(t.sizeBytes)} · ${peer} · byte-for-byte`;
    default:
      return `${formatBytes(t.sizeBytes)} · ${peer}`;
  }
}

/** Compact in-flight card: ring, phase pill, live meta, sparkline, actions. */
export function TransferCard({ t }: { t: TransferSummary }) {
  const { paused, speedSamples, settings } = useAppState();
  const pct = Math.round(ringFraction(t) * 100);
  const showPct =
    t.state === "transferring"
      ? Math.round(formatPercent(t.bytesTransferred, t.sizeBytes))
      : pct;
  const samples = speedSamples[t.transferId] ?? [];

  return (
    <div className="mini-card">
      <div className="mini-row">
        <div className="ring-wrap">
          <ProgressRing t={t} paused={paused} size={46} stroke={4} />
          <div className="ring-pct">
            {t.state === "hashing" ? "…" : showPct}
          </div>
        </div>
        <div className="mini-main">
          <div className="mini-title-row">
            <FileName
              className="mini-name"
              label={t.fileName}
              target={targetForTransfer(t, settings?.receiveDir ?? null)}
            />
            <PhasePill t={t} paused={paused} />
          </div>
          <div className="mini-meta">
            {metaLine(t, paused)}
            {t.error ? <span className="err"> — {t.error}</span> : null}
          </div>
        </div>
        <Sparkline samples={samples} maxHeight={26} />
        {RETRYABLE.has(t.state) ? (
          <button
            className="icon-btn verify"
            title="Retry"
            onClick={() => void api.retryTransfer(t.transferId)}
          >
            <IconRetry size={13} />
          </button>
        ) : null}
        {CANCELABLE.has(t.state) ? (
          <button
            className="icon-btn danger"
            title="Cancel"
            onClick={() => void api.cancelTransfer(t.transferId)}
          >
            <IconX size={11} strokeWidth={2.4} />
          </button>
        ) : null}
      </div>
    </div>
  );
}
