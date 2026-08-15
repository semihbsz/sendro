import { useState } from "react";
import { useAppDispatch, useAppState } from "../store";
import * as api from "../api";
import { mapLimited } from "../bulk";
import {
  formatBytes,
  formatDuration,
  formatEta,
  formatSpeed,
} from "../format";
import {
  IconArrowDown,
  IconArrowUp,
  IconCheck,
  IconFlow,
} from "../icons";
import { EmptyState, FileName } from "../components/common";
import { targetForHistory, targetForTransfer } from "../preview";
import { TransferCard, CANCELABLE, RETRYABLE } from "../components/TransferCard";
import { PhasePill, ProgressRing, Sparkline, ringFraction } from "../components/transfer";
import { isTerminal, type HistoryEntry, type TransferSummary } from "../types";

function shortSha(sha: string | null): string | null {
  if (!sha || sha.length < 12) return null;
  return `${sha.slice(0, 4)}…${sha.slice(-4)}`;
}

function hashLine(t: TransferSummary): string {
  const sha = shortSha(t.sha256);
  const peer = `${t.direction === "outgoing" ? "to" : "from"} ${t.deviceName}`;
  if (t.state === "verifying") {
    return sha ? `matching sha256 ${sha}` : "matching sha-256…";
  }
  if (t.state === "completed") {
    return sha
      ? `sha256 ${sha} · byte-for-byte · ${t.deviceName}`
      : `byte-for-byte · ${t.deviceName}`;
  }
  return `${formatBytes(t.sizeBytes)} · ${peer}`;
}

/** The big ring hero for the primary in-flight transfer. */
function FlowHero({ t }: { t: TransferSummary }) {
  const { paused, speedSamples, settings } = useAppState();
  const frac = ringFraction(t);
  const pct = Math.round(frac * 100);
  const moving = t.state === "transferring" && !paused;
  const samples = speedSamples[t.transferId] ?? [];

  return (
    <div className="flow-hero">
      <div className="hero-particles" aria-hidden="true">
        <span className="hp hp-1" />
        <span className="hp hp-2" />
      </div>
      <div className="flow-hero-inner">
        <div className="big-ring-wrap">
          <ProgressRing t={t} paused={paused} size={132} stroke={7} />
          {moving ? (
            <div
              className="orbit"
              style={{ transform: `rotate(${frac * 360}deg)` }}
              aria-hidden="true"
            >
              <div className="orbit-dot" />
            </div>
          ) : null}
          <div className="big-pct">
            <span className="big-pct-num">
              {t.state === "hashing" ? "…" : pct}
            </span>
            {t.state !== "hashing" ? (
              <span className="big-pct-sign">%</span>
            ) : null}
          </div>
        </div>
        <div className="flow-hero-main">
          <div className="flow-hero-title-row">
            <FileName
              className="flow-hero-name"
              label={t.fileName}
              target={targetForTransfer(t, settings?.receiveDir ?? null)}
            />
            <PhasePill t={t} paused={paused} />
          </div>
          <div className="flow-hash">{hashLine(t)}</div>
          <Sparkline samples={samples} maxHeight={40} large />
          <div className="flow-stats">
            <div>
              <div className="stat-label">Moved</div>
              <div className="stat-value">
                {formatBytes(t.bytesTransferred)}
              </div>
            </div>
            <div>
              <div className="stat-label">Rate</div>
              <div className="stat-value">
                {moving ? formatSpeed(t.speedBps) : "—"}
              </div>
            </div>
            <div>
              <div className="stat-label">ETA</div>
              <div className="stat-value">
                {moving ? formatEta(t.etaSeconds) : "—"}
              </div>
            </div>
            <div className="flow-stats-spacer" />
            {CANCELABLE.has(t.state) ? (
              <button
                className="btn-cancel"
                onClick={() => void api.cancelTransfer(t.transferId)}
              >
                Cancel
              </button>
            ) : null}
          </div>
        </div>
      </div>
    </div>
  );
}

function VerifiedCell({ h }: { h: HistoryEntry }) {
  if (h.finalState === "completed") {
    return h.verified ? (
      <span className="pill-verified">
        <IconCheck size={10} strokeWidth={2.6} />
        SHA-256
      </span>
    ) : (
      <span className="pill-failed">hash mismatch</span>
    );
  }
  return <span className="pill-muted">{h.finalState}</span>;
}

export function Flow() {
  const { queue, history, paused, settings } = useAppState();
  const dispatch = useAppDispatch();
  const [retryingAll, setRetryingAll] = useState(false);

  const active = queue.filter((t) => !isTerminal(t.state));
  const doneInQueue = queue.filter((t) => isTerminal(t.state));
  const needsAttention = doneInQueue.filter(
    (t) => t.state !== "completed",
  );
  // Interrupted transfers stay in `active`, so scan the whole queue for
  // anything the per-item Retry button would accept.
  const retryable = queue.filter(
    (t) => t.direction === "outgoing" && RETRYABLE.has(t.state),
  );

  // Hero: prefer the transfer that's actually moving.
  const hero =
    active.find((t) => t.state === "transferring") ??
    active.find((t) => t.state === "verifying" || t.state === "saving") ??
    active[0];
  const rest = hero
    ? active.filter((t) => t.transferId !== hero.transferId)
    : active;

  const summary =
    active.length > 0
      ? `${active.length} transfer${active.length === 1 ? "" : "s"} in flight${paused ? " · paused" : ""}`
      : `nothing in flight · ${history.length} finished`;

  const clearHistory = async () => {
    try {
      await api.clearHistory();
      dispatch({ type: "set-history", history: [] });
    } catch (err) {
      console.error("clear history failed", err);
    }
  };

  const clearCompleted = async () => {
    try {
      await api.clearCompleted();
      const next = await api.getQueue();
      dispatch({ type: "set-queue", queue: next });
    } catch (err) {
      console.error("clear completed failed", err);
    }
  };

  /** Re-offer every failed/interrupted/expired send — §12's loop, not a
   *  batch call, so one stubborn file cannot block the others. */
  const retryAllFailed = async () => {
    if (retryable.length === 0) return;
    setRetryingAll(true);
    try {
      const failures = await mapLimited(retryable, 4, (t) =>
        api.retryTransfer(t.transferId),
      );
      if (failures.length > 0) {
        console.error(`${failures.length} transfers could not be retried`);
      }
      const next = await api.getQueue();
      dispatch({ type: "set-queue", queue: next });
    } catch (err) {
      console.error("retry all failed", err);
    } finally {
      setRetryingAll(false);
    }
  };

  return (
    <div className="page" key="flow">
      <div className="page-title">Flow</div>
      <div className="page-sub">{summary}</div>

      {hero ? <FlowHero t={hero} /> : null}

      {retryable.length > 1 ? (
        <div className="bulk-bar">
          <span className="bulk-count">
            {retryable.length} sends stalled — failed, interrupted or expired
          </span>
          <span className="bulk-spacer" />
          <button
            className="btn-solid btn-sm"
            disabled={retryingAll}
            title="Re-offer every stalled send, one call each"
            onClick={() => void retryAllFailed()}
          >
            Retry all failed ({retryable.length})
          </button>
        </div>
      ) : null}

      {rest.length > 0 ? (
        <div className="mini-list" style={{ marginTop: hero ? 10 : 22 }}>
          {rest.map((t) => (
            <TransferCard key={t.transferId} t={t} />
          ))}
        </div>
      ) : null}

      {needsAttention.length > 0 ? (
        <>
          <div className="finished-head">
            <span className="strip-label">Needs attention</span>
            <span className="finished-head-spacer" />
            <button
              className="btn-ghost-text"
              onClick={() => void clearCompleted()}
            >
              Dismiss all
            </button>
          </div>
          <div className="mini-list">
            {needsAttention.map((t) => (
              <TransferCard key={t.transferId} t={t} />
            ))}
          </div>
        </>
      ) : null}

      <div className="finished-head">
        <span className="strip-label">Finished</span>
        <span className="finished-head-spacer" />
        {doneInQueue.length > 0 && needsAttention.length === 0 ? (
          <button
            className="btn-ghost-text"
            onClick={() => void clearCompleted()}
          >
            Clear completed
          </button>
        ) : null}
        <button
          className="btn-ghost-text"
          disabled={history.length === 0}
          onClick={() => void clearHistory()}
        >
          Clear
        </button>
      </div>

      {history.length === 0 ? (
        <div className="glass-panel" style={{ marginTop: 12 }}>
          <EmptyState
            icon={<IconFlow size={20} />}
            title="No finished transfers yet"
            subtitle="Once a transfer finishes — sent or received — it settles here with speed and verification details. Kept on this PC only."
          />
        </div>
      ) : (
        <>
          <div className="cols-head">
            <div className="col-file">File</div>
            <div className="col-peer">Peer</div>
            <div className="col-size">Size</div>
            <div className="col-dur">Duration</div>
            <div className="col-avg">Avg speed</div>
            <div className="col-verified" style={{ justifyContent: "flex-end" }}>
              Verified
            </div>
          </div>
          <div className="finished-list">
            {history.map((h) => (
              <div className="finished-row" key={h.transferId}>
                <div className="col-file">
                  {h.direction === "outgoing" ? (
                    <IconArrowUp size={14} />
                  ) : (
                    <IconArrowDown size={14} />
                  )}
                  <FileName
                    className="col-file-name"
                    label={h.fileName}
                    target={targetForHistory(
                      h,
                      queue,
                      settings?.receiveDir ?? null,
                    )}
                  />
                </div>
                <div className="col-peer">{h.peerName}</div>
                <div className="col-size">{formatBytes(h.sizeBytes)}</div>
                <div className="col-dur">{formatDuration(h.durationMs)}</div>
                <div className="col-avg">{formatSpeed(h.avgSpeedBps)}</div>
                <div className="col-verified">
                  <VerifiedCell h={h} />
                </div>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
