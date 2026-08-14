import { useAppDispatch, useAppState, type View } from "../store";
import { IconFlow, IconSend, IconSettings, IconWatch } from "../icons";
import { isTerminal } from "../types";

const TABS: Array<{ view: View; label: string; icon: JSX.Element }> = [
  { view: "send", label: "SEND", icon: <IconSend size={19} /> },
  { view: "flow", label: "FLOW", icon: <IconFlow size={19} /> },
  { view: "watch", label: "WATCH", icon: <IconWatch size={19} /> },
];

/** 78px icon rail: SEND / FLOW / WATCH tabs + the settings gear at the
 *  bottom. FLOW gets a glowing dot while transfers run; WATCH gets a
 *  count badge for pending detections. */
export function Rail() {
  const { view, queue, detections } = useAppState();
  const dispatch = useAppDispatch();

  const running = queue.some((t) => !isTerminal(t.state));

  return (
    <nav className="rail" aria-label="Primary">
      {TABS.map((tab) => (
        <button
          key={tab.view}
          className={`rail-tab${view === tab.view ? " active" : ""}`}
          onClick={() => dispatch({ type: "set-view", view: tab.view })}
          aria-label={tab.label}
          aria-current={view === tab.view ? "page" : undefined}
        >
          {tab.icon}
          <span className="rail-tab-label">{tab.label}</span>
          {tab.view === "flow" && running ? (
            <span className="rail-dot" aria-hidden="true" />
          ) : null}
          {tab.view === "watch" && detections.length > 0 ? (
            <span className="rail-badge">{detections.length}</span>
          ) : null}
        </button>
      ))}
      <div className="rail-spacer" />
      <button
        className={`rail-gear${view === "settings" ? " active" : ""}`}
        onClick={() => dispatch({ type: "set-view", view: "settings" })}
        aria-label="Settings"
        aria-current={view === "settings" ? "page" : undefined}
      >
        <IconSettings size={17} />
      </button>
    </nav>
  );
}
