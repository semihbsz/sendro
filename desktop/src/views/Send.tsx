import { open } from "@tauri-apps/plugin-dialog";
import { useAppDispatch, useAppState } from "../store";
import { IconDrop, IconShieldCheck } from "../icons";
import { formatBytes, isOnline } from "../format";
import { TransferCard } from "../components/TransferCard";
import { isTerminal } from "../types";

async function pickFiles(): Promise<string[] | null> {
  const selected = await open({ multiple: true, title: "Send files" });
  if (selected === null) return null;
  return Array.isArray(selected) ? selected : [selected];
}

async function pickFolder(): Promise<string[] | null> {
  const selected = await open({ directory: true, title: "Send a folder" });
  if (selected === null) return null;
  return Array.isArray(selected) ? selected : [selected];
}

export function Send() {
  const { devices, queue, history, dragging } = useAppState();
  const dispatch = useAppDispatch();

  const choose = async (picker: () => Promise<string[] | null>) => {
    try {
      const paths = await picker();
      if (paths && paths.length > 0) {
        dispatch({ type: "set-pending", paths });
      }
    } catch (err) {
      console.error("file picker failed", err);
    }
  };

  const onlineDevices = devices.filter((d) => isOnline(d.lastSeenMs));
  const active = queue.filter((t) => !isTerminal(t.state));

  const heroSub = dragging
    ? "Release to send"
    : devices.length === 0
      ? "Pair your iPhone first — open Sendro on it and tap this PC"
      : onlineDevices.length > 0
        ? `${
            onlineDevices.length === 1
              ? (onlineDevices[0]?.deviceName ?? "1 device")
              : `${onlineDevices.length} devices`
          } ${onlineDevices.length === 1 ? "is" : "are"} online · drop anywhere in this window`
        : "Paired devices pick transfers up when they come online";

  // Aside stats: bytes completed today + verified share of completed sends.
  const dayStart = new Date();
  dayStart.setHours(0, 0, 0, 0);
  const todayBytes = history
    .filter(
      (h) => h.finalState === "completed" && h.endedAtMs >= dayStart.getTime(),
    )
    .reduce((sum, h) => sum + h.sizeBytes, 0);
  const completed = history.filter((h) => h.finalState === "completed");
  const verifiedPct =
    completed.length > 0
      ? Math.round(
          (completed.filter((h) => h.verified).length / completed.length) *
            100,
        )
      : null;

  return (
    <div className="page" key="send">
      <div className="page-title">Send</div>
      <div className="page-sub">original bytes · sha-256 verified · lan only</div>

      <div
        className={`hero-drop${dragging ? " dragging" : ""}`}
        onClick={() => void choose(pickFiles)}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => {
          if (e.key === "Enter") void choose(pickFiles);
        }}
      >
        <div className="hero-particles" aria-hidden="true">
          <span className="hp hp-1" />
          <span className="hp hp-2" />
          <span className="hp hp-3" />
        </div>
        <div className="hero-rings">
          <span className="ring-a" />
          <span className="ring-b" />
          <IconDrop size={26} />
        </div>
        <div className="hero-copy">
          <div className="hero-headline">Drop it. It's on your phone.</div>
          <div className="hero-sub">{heroSub}</div>
        </div>
        <div className="hero-actions">
          <button
            className="btn-solid"
            onClick={(e) => {
              e.stopPropagation();
              void choose(pickFiles);
            }}
          >
            Choose files
          </button>
          <button
            className="btn-glass"
            onClick={(e) => {
              e.stopPropagation();
              void choose(pickFolder);
            }}
          >
            Choose folder
          </button>
        </div>
      </div>

      <div className="send-cols">
        <div className="send-left">
          <div className="strip-label">In flight</div>
          <div className="mini-list">
            {active.length > 0 ? (
              active.map((t) => <TransferCard key={t.transferId} t={t} />)
            ) : (
              <div className="dashed-empty">
                Nothing in flight. Drop a file and it lands here, live.
              </div>
            )}
          </div>
        </div>

        <div className="aside-card">
          <div className="aside-head">
            <IconShieldCheck size={15} />
            <span>Private by design</span>
          </div>
          <div className="aside-body">
            Transfers never leave your local network. Every file is verified
            with SHA-256 on arrival — a mismatch is surfaced, not skipped.
          </div>
          <div className="aside-stats">
            <div>
              <div className="stat-label">Today</div>
              <div className={`stat-value${todayBytes === 0 ? " dim" : ""}`}>
                {todayBytes > 0 ? formatBytes(todayBytes) : "0 B"}
              </div>
            </div>
            <div>
              <div className="stat-label">Verified</div>
              <div
                className={`stat-value${verifiedPct === null ? " dim" : " verify"}`}
              >
                {verifiedPct === null ? "—" : `${verifiedPct}%`}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
