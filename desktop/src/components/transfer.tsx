/** Shared transfer visuals: ring progress, sparkline, phase pills. */
import type { TransferState, TransferSummary } from "../types";
import { formatPercent } from "../format";

/* ---------- Phase labels & pill tone ---------- */

export type PhaseTone = "accent" | "verify" | "fail" | "warn" | "plain";

const PHASE: Record<TransferState, { label: string; tone: PhaseTone }> = {
  queued: { label: "queued", tone: "plain" },
  hashing: { label: "preparing", tone: "accent" },
  offered: { label: "waiting", tone: "accent" },
  accepted: { label: "accepted", tone: "accent" },
  transferring: { label: "sending", tone: "accent" },
  verifying: { label: "verifying", tone: "verify" },
  saving: { label: "saving", tone: "verify" },
  completed: { label: "verified", tone: "verify" },
  rejected: { label: "rejected", tone: "fail" },
  cancelled: { label: "cancelled", tone: "plain" },
  failed: { label: "failed", tone: "fail" },
  interrupted: { label: "interrupted", tone: "warn" },
  expired: { label: "expired", tone: "warn" },
};

export function phaseOf(
  t: TransferSummary,
  paused: boolean,
): { label: string; tone: PhaseTone } {
  if (t.state === "transferring") {
    if (paused) return { label: "paused", tone: "plain" };
    return {
      label: t.direction === "incoming" ? "receiving" : "sending",
      tone: "accent",
    };
  }
  return PHASE[t.state];
}

export function PhasePill({
  t,
  paused,
}: {
  t: TransferSummary;
  paused: boolean;
}) {
  const { label, tone } = phaseOf(t, paused);
  const cls =
    tone === "accent" ? "" : tone === "verify" ? " verify" : ` ${tone}`;
  return <span className={`phase-pill${cls}`}>{label}</span>;
}

/* ---------- Ring progress ---------- */

/** 0..1 data fraction for the accent ring.
 *
 *  The ring reports *bytes actually moved*, so it can never contradict the
 *  "moved" figure next to it. In particular a failed or interrupted transfer
 *  shows how far it really got — a red row sitting at a confident 100% was
 *  the single most misleading thing in the old flight list. Only a finished
 *  transfer is allowed to read full. */
export function ringFraction(t: TransferSummary): number {
  const moved = formatPercent(t.bytesTransferred, t.sizeBytes) / 100;
  switch (t.state) {
    case "queued":
    case "hashing":
      return 0;
    // A re-offered or resumed transfer keeps the bytes it already moved.
    case "offered":
    case "accepted":
    case "transferring":
    case "interrupted":
    case "failed":
      return moved;
    // Nothing was delivered, and nothing will be.
    case "rejected":
    case "cancelled":
    case "expired":
      return 0;
    // verifying / saving / completed — all bytes are across.
    default:
      return 1;
  }
}

const VERIFY_STATES: readonly TransferState[] = [
  "verifying",
  "saving",
  "completed",
];

/** Circular progress with the design's glow; a teal verify ring sweeps
 *  on top during verifying/saving and locks full when completed. */
export function ProgressRing({
  t,
  paused,
  size,
  stroke,
}: {
  t: TransferSummary;
  paused: boolean;
  size: number;
  stroke: number;
}) {
  const r = size / 2 - stroke / 2 - 2;
  const c = 2 * Math.PI * r;
  const frac = ringFraction(t);
  const indeterminate = t.state === "hashing";
  const showVerify = VERIFY_STATES.includes(t.state);
  const verifySweep = t.state === "verifying";
  const center = size / 2;

  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
      <circle
        className="ring-track"
        cx={center}
        cy={center}
        r={r}
        fill="none"
        strokeWidth={stroke}
      />
      <circle
        className={`ring-fill${paused && t.state === "transferring" ? " paused" : ""}${indeterminate ? " spin" : ""}`}
        cx={center}
        cy={center}
        r={r}
        fill="none"
        strokeWidth={stroke}
        strokeLinecap="round"
        strokeDasharray={c}
        strokeDashoffset={indeterminate ? c * 0.85 : c * (1 - frac)}
      />
      {showVerify ? (
        <circle
          className={`ring-verify${verifySweep ? " spin" : ""}`}
          cx={center}
          cy={center}
          r={r}
          fill="none"
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={c}
          strokeDashoffset={verifySweep ? c * 0.65 : 0}
        />
      ) : null}
    </svg>
  );
}

/* ---------- Throughput sparkline ---------- */

export function Sparkline({
  samples,
  bars = 18,
  maxHeight,
  large,
}: {
  samples: number[];
  bars?: number;
  maxHeight: number;
  large?: boolean;
}) {
  const peak = samples.length > 0 ? Math.max(...samples) : 0;
  const min = large ? 4 : 3;
  const items: number[] = [];
  for (let i = 0; i < bars; i++) {
    const v = samples[samples.length - bars + i];
    const n = v !== undefined && peak > 0 ? v / peak : 0;
    items.push(Math.max(min, Math.round(n * maxHeight)));
  }
  return (
    <div className={large ? "spark-lg" : "spark"} aria-hidden="true">
      {items.map((h, i) => (
        <div key={i} className="spark-bar" style={{ height: h }} />
      ))}
    </div>
  );
}
