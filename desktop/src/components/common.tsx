import type { ReactNode } from "react";

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
