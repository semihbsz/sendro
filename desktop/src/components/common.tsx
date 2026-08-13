import type { ReactNode } from "react";
import type { TransferState } from "../types";
import { IconArrowDown, IconArrowUp } from "../icons";

/* ---------- Toggle switch ---------- */

export function Toggle({
  on,
  onChange,
  disabled,
  label,
}: {
  on: boolean;
  onChange: (next: boolean) => void;
  disabled?: boolean;
  label?: string;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={label}
      className={`switch${on ? " on" : ""}`}
      disabled={disabled}
      onClick={() => onChange(!on)}
    />
  );
}

/* ---------- Empty state ---------- */

export function EmptyState({
  icon,
  title,
  subtitle,
  action,
}: {
  icon: ReactNode;
  title: string;
  subtitle?: string;
  action?: ReactNode;
}) {
  return (
    <div className="empty">
      <div className="empty-icon">{icon}</div>
      <div className="empty-title">{title}</div>
      {subtitle ? <div className="empty-sub">{subtitle}</div> : null}
      {action ? <div style={{ marginTop: "var(--s-3)" }}>{action}</div> : null}
    </div>
  );
}

/* ---------- Transfer state chip ---------- */

const STATE_LABEL: Record<TransferState, string> = {
  queued: "Queued",
  hashing: "Preparing",
  offered: "Waiting for iPhone",
  accepted: "Accepted",
  transferring: "Sending",
  verifying: "Verifying",
  saving: "Saving",
  completed: "Completed",
  rejected: "Rejected",
  cancelled: "Cancelled",
  failed: "Failed",
  interrupted: "Interrupted",
  expired: "Expired",
};

const ACTIVE_STATES: readonly TransferState[] = [
  "hashing",
  "transferring",
  "verifying",
  "saving",
];

export function stateLabel(state: TransferState): string {
  return STATE_LABEL[state];
}

export function StateChip({
  state,
  direction,
}: {
  state: TransferState;
  direction?: "outgoing" | "incoming";
}) {
  let cls = "chip";
  if (state === "completed") cls += " chip-accent";
  else if (state === "failed" || state === "rejected") cls += " chip-danger";
  else if (state === "interrupted" || state === "expired") cls += " chip-warn";
  else if (ACTIVE_STATES.includes(state) || state === "offered") {
    cls += " chip-accent";
  }
  const pulse = ACTIVE_STATES.includes(state) || state === "offered";
  const label =
    state === "transferring" && direction === "incoming"
      ? "Receiving"
      : STATE_LABEL[state];
  return (
    <span className={cls}>
      <span className={`chip-dot${pulse ? " pulse" : ""}`} />
      {label}
    </span>
  );
}

/* ---------- Direction arrow ---------- */

export function DirArrow({ direction }: { direction: "outgoing" | "incoming" }) {
  const out = direction === "outgoing";
  return (
    <span
      className={`dir-arrow ${out ? "out" : "in"}`}
      title={out ? "Sent to iPhone" : "Received from iPhone"}
    >
      {out ? <IconArrowUp size={13} /> : <IconArrowDown size={13} />}
    </span>
  );
}
