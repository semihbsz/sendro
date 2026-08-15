import { useCallback, useEffect, useRef, useState } from "react";
import { open } from "@tauri-apps/plugin-dialog";
import { useAppDispatch, useAppState } from "../store";
import { IconDrop, IconSend, IconShieldCheck } from "../icons";
import { formatBytes, isOnline } from "../format";
import { TransferCard } from "../components/TransferCard";
import { LinkPanel } from "../components/LinkPanel";
import { readClipboardForSend } from "../paste";
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

/** True when the keystroke belongs to a field the user is typing into. */
function isEditable(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  const tag = target.tagName;
  return (
    tag === "INPUT" ||
    tag === "TEXTAREA" ||
    tag === "SELECT" ||
    target.isContentEditable
  );
}

export function Send() {
  const { devices, queue, history, dragging, pendingPaths, composerText } =
    useAppState();
  const dispatch = useAppDispatch();
  const [pasteHint, setPasteHint] = useState<string | null>(null);
  const [pasting, setPasting] = useState(false);
  const hintTimer = useRef<number | null>(null);

  const showHint = useCallback((message: string) => {
    setPasteHint(message);
    if (hintTimer.current !== null) window.clearTimeout(hintTimer.current);
    hintTimer.current = window.setTimeout(() => setPasteHint(null), 2400);
  }, []);

  useEffect(
    () => () => {
      if (hintTimer.current !== null) window.clearTimeout(hintTimer.current);
    },
    [],
  );

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

  /** Paste button / Ctrl+V: image → offer flow, text → composer. */
  const paste = useCallback(async () => {
    if (pasting) return;
    setPasting(true);
    try {
      const result = await readClipboardForSend();
      if (result.kind === "image") {
        dispatch({ type: "set-pending", paths: [result.path] });
      } else if (result.kind === "text") {
        dispatch({ type: "open-composer", text: result.text });
      } else if (result.kind === "error") {
        console.error("clipboard paste failed", result.message);
        showHint("Couldn't read the clipboard");
      } else {
        showHint("Clipboard is empty");
      }
    } catch (err) {
      // Belt and braces — readClipboardForSend already swallows failures.
      console.error("clipboard paste failed", err);
      showHint("Couldn't read the clipboard");
    } finally {
      setPasting(false);
    }
  }, [dispatch, pasting, showHint]);

  // Ctrl/Cmd+V anywhere on the SEND view does what the Paste button does —
  // unless a field has focus (native paste) or a modal already owns input.
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== "v" && e.key !== "V") return;
      if (!(e.ctrlKey || e.metaKey) || e.altKey || e.shiftKey) return;
      if (isEditable(e.target)) return;
      if (composerText !== null || pendingPaths !== null) return;
      e.preventDefault();
      void paste();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [paste, composerText, pendingPaths]);

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
          <button
            className="btn-glass"
            disabled={pasting}
            title="Paste an image or text from the clipboard (Ctrl+V)"
            onClick={(e) => {
              e.stopPropagation();
              void paste();
            }}
          >
            Paste
          </button>
        </div>
        <div className="hero-foot">
          <button
            className="btn-ghost-text hero-text-btn"
            onClick={(e) => {
              e.stopPropagation();
              dispatch({ type: "open-composer", text: "" });
            }}
          >
            <IconSend size={12} />
            Send text
          </button>
          {pasteHint ? (
            <span className="paste-hint">{pasteHint}</span>
          ) : (
            <span className="hero-foot-hint">Ctrl+V pastes here</span>
          )}
        </div>
      </div>

      <LinkPanel />

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
