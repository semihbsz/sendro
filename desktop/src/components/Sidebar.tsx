import { useAppDispatch, useAppState, type View } from "../store";
import {
  IconBolt,
  IconHistory,
  IconHome,
  IconPhone,
  IconQueue,
  IconSettings,
  IconWatch,
} from "../icons";
import { isTerminal } from "../types";

const NAV: Array<{ view: View; label: string; icon: JSX.Element }> = [
  { view: "home", label: "Home", icon: <IconHome /> },
  { view: "devices", label: "Devices", icon: <IconPhone /> },
  { view: "queue", label: "Queue", icon: <IconQueue /> },
  { view: "history", label: "History", icon: <IconHistory /> },
  { view: "watch", label: "Watch Folders", icon: <IconWatch /> },
  { view: "settings", label: "Settings", icon: <IconSettings /> },
];

export function Sidebar() {
  const state = useAppState();
  const dispatch = useAppDispatch();
  const { view, info, queue, detections } = state;

  const activeCount = queue.filter((t) => !isTerminal(t.state)).length;
  const ip = info?.localIps[0] ?? null;
  const addr = info ? `${ip ?? "0.0.0.0"}:${info.apiPort}` : null;

  const badgeFor = (v: View): number => {
    if (v === "queue") return activeCount;
    if (v === "watch") return detections.length;
    return 0;
  };

  return (
    <aside className="sidebar">
      <div className="brand">
        <span className="brand-mark">
          <IconBolt size={15} />
        </span>
        <span className="brand-name">Sendro</span>
      </div>

      <nav className="nav">
        {NAV.map((item) => {
          const badge = badgeFor(item.view);
          return (
            <button
              key={item.view}
              className={`nav-item${view === item.view ? " active" : ""}`}
              onClick={() => dispatch({ type: "set-view", view: item.view })}
            >
              {item.icon}
              {item.label}
              {badge > 0 ? <span className="nav-badge">{badge}</span> : null}
            </button>
          );
        })}
      </nav>

      <div className="sidebar-footer">
        <div className="status-row">
          <span className={`status-dot${info ? "" : " off"}`} />
          <span>{info ? info.deviceName : "Starting…"}</span>
        </div>
        {addr ? <div className="status-addr">{addr}</div> : null}
      </div>
    </aside>
  );
}
