import { useAppDispatch, useAppState } from "../store";
import * as api from "../api";
import {
  formatBytes,
  formatEta,
  formatPercent,
  formatSpeed,
} from "../format";
import {
  IconCheck,
  IconFile,
  IconPause,
  IconPlay,
  IconQueue,
  IconRetry,
  IconX,
} from "../icons";
import { EmptyState, StateChip } from "../components/common";
import { isTerminal, type TransferSummary } from "../types";

const RETRYABLE = new Set(["failed", "interrupted", "expired"]);
const CANCELABLE = new Set([
  "queued",
  "hashing",
  "offered",
  "accepted",
  "transferring",
  "verifying",
  "saving",
]);

function TransferCard({ t, paused }: { t: TransferSummary; paused: boolean }) {
  const pct = formatPercent(t.bytesTransferred, t.sizeBytes);
  const indeterminate = t.state === "hashing";
  const showProgress = !isTerminal(t.state) && t.state !== "queued";
  const moving = t.state === "transferring";

  return (
    <div className="row" style={{ alignItems: "flex-start" }}>
      <span className="row-icon" style={{ marginTop: 2 }}>
        <IconFile size={16} />
      </span>
      <div className="row-main">
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: "var(--s-3)",
          }}
        >
          <div className="row-title" style={{ flex: 1, minWidth: 0 }}>
            {t.fileName}
          </div>
          <StateChip state={t.state} direction={t.direction} />
        </div>
        <div className="row-sub">
          {formatBytes(t.sizeBytes)} ·{" "}
          {t.direction === "outgoing" ? "to" : "from"} {t.deviceName}
          {t.error ? (
            <span style={{ color: "var(--danger)" }}> — {t.error}</span>
          ) : null}
        </div>

        {showProgress ? (
          <>
            <div className="progress">
              <div
                className={`progress-fill${indeterminate ? " indeterminate" : ""}${paused ? " paused" : ""}`}
                style={{ width: indeterminate ? undefined : `${pct}%` }}
              />
            </div>
            <div className="transfer-meta">
              {indeterminate ? (
                <span>Preparing (SHA-256)…</span>
              ) : (
                <>
                  <span>
                    {formatBytes(t.bytesTransferred)} of{" "}
                    {formatBytes(t.sizeBytes)} · {pct.toFixed(0)}%
                  </span>
                  {moving ? <span>{formatSpeed(t.speedBps)}</span> : null}
                  {moving ? <span>ETA {formatEta(t.etaSeconds)}</span> : null}
                </>
              )}
            </div>
          </>
        ) : null}
      </div>

      <div className="row-actions" style={{ marginTop: 2 }}>
        {RETRYABLE.has(t.state) ? (
          <button
            className="btn btn-icon btn-ghost"
            title="Retry"
            onClick={() => void api.retryTransfer(t.transferId)}
          >
            <IconRetry size={15} />
          </button>
        ) : null}
        {CANCELABLE.has(t.state) ? (
          <button
            className="btn btn-icon btn-danger-ghost"
            title="Cancel"
            onClick={() => void api.cancelTransfer(t.transferId)}
          >
            <IconX size={15} />
          </button>
        ) : null}
      </div>
    </div>
  );
}

export function Queue() {
  const { queue, paused } = useAppState();
  const dispatch = useAppDispatch();

  const active = queue.filter((t) => !isTerminal(t.state));
  const done = queue.filter((t) => isTerminal(t.state));
  const hasCompleted = done.length > 0;

  const togglePause = async () => {
    const next = !paused;
    dispatch({ type: "set-paused", paused: next });
    try {
      await api.pauseTransfers(next);
    } catch (err) {
      dispatch({ type: "set-paused", paused: !next });
      console.error("pause failed", err);
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

  return (
    <div className="view">
      <div className="view-header">
        <div>
          <div className="view-title">Queue</div>
          <div className="view-subtitle">
            {active.length === 0
              ? "Nothing in flight."
              : `${active.length} transfer${active.length === 1 ? "" : "s"} in flight${paused ? " — paused" : ""}.`}
          </div>
        </div>
        <div className="view-actions">
          <button
            className={paused ? "btn btn-primary" : "btn"}
            onClick={() => void togglePause()}
          >
            {paused ? <IconPlay size={14} /> : <IconPause size={14} />}
            {paused ? "Resume" : "Pause"}
          </button>
          <button
            className="btn btn-ghost"
            disabled={!hasCompleted}
            onClick={() => void clearCompleted()}
          >
            <IconCheck size={14} />
            Clear Completed
          </button>
        </div>
      </div>

      {queue.length === 0 ? (
        <div className="panel">
          <EmptyState
            icon={<IconQueue size={20} />}
            title="The queue is clear"
            subtitle="Drop files on the Home screen to start a transfer. Finished items move to History."
          />
        </div>
      ) : (
        <>
          {active.length > 0 ? (
            <div className="panel">
              <div className="list">
                {active.map((t) => (
                  <TransferCard key={t.transferId} t={t} paused={paused} />
                ))}
              </div>
            </div>
          ) : null}

          {done.length > 0 ? (
            <>
              <span className="section-label">Finished</span>
              <div className="panel" style={{ marginTop: "calc(var(--s-3) * -1 + 0px)" }}>
                <div className="list">
                  {done.map((t) => (
                    <TransferCard key={t.transferId} t={t} paused={paused} />
                  ))}
                </div>
              </div>
            </>
          ) : null}
        </>
      )}
    </div>
  );
}
