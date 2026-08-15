import type { ReactNode } from "react";
import { useAppDispatch } from "../store";
import type { PreviewTarget } from "../types";

/* ---------- Toggle switch (design: 40×23, violet when on) ---------- */

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

/* ---------- File name that opens the in-app preview ---------- */

/**
 * A file name anywhere in the app. Clicking opens the preview modal — but
 * only when we actually know where the file is: an outgoing transfer knows
 * its source path, an incoming one is guessed inside the receive folder, and
 * a history row for a send that already left the queue knows neither. In that
 * case this renders as plain text rather than a button that would open a
 * "can't find it" card every time.
 */
export function FileName({
  target,
  label,
  className,
}: {
  target: PreviewTarget | null;
  label: string;
  className?: string;
}) {
  const dispatch = useAppDispatch();

  if (!target) {
    return (
      <span className={className} title={label}>
        {label}
      </span>
    );
  }

  return (
    <button
      type="button"
      className={`${className ?? ""} file-name-btn`.trim()}
      title={`${label} — click to preview`}
      onClick={(e) => {
        e.stopPropagation();
        dispatch({ type: "open-preview", target });
      }}
    >
      {label}
    </button>
  );
}

/* ---------- Empty state, in the redesign's breathing-rings language ---------- */

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
      <div className="empty-rings">
        <span className="ring-a" />
        <span className="ring-b" />
        {icon}
      </div>
      <div className="empty-title">{title}</div>
      {subtitle ? <div className="empty-sub">{subtitle}</div> : null}
      {action ? <div className="empty-action">{action}</div> : null}
    </div>
  );
}
