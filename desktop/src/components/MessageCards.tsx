/**
 * Floating cards for ephemeral text received from a paired device
 * (PROTOCOL.md §11.3). Text lives in React state only — it is never written
 * to history, never persisted, and dismissing a card discards it forever.
 */
import { useEffect, useRef, useState } from "react";
import { writeText } from "@tauri-apps/plugin-clipboard-manager";
import { useAppDispatch, useAppState } from "../store";
import * as api from "../api";
import { IconCheck, IconPhone, IconX } from "../icons";
import type { IncomingMessage } from "../types";

function MessageCard({ m }: { m: IncomingMessage }) {
  const dispatch = useAppDispatch();
  const [copied, setCopied] = useState(false);
  const copyTimer = useRef<number | null>(null);

  useEffect(
    () => () => {
      if (copyTimer.current !== null) window.clearTimeout(copyTimer.current);
    },
    [],
  );

  const copy = async () => {
    try {
      await writeText(m.text);
      setCopied(true);
      if (copyTimer.current !== null) window.clearTimeout(copyTimer.current);
      copyTimer.current = window.setTimeout(() => setCopied(false), 1400);
    } catch (err) {
      console.error("clipboard write failed", err);
    }
  };

  const dismiss = () => {
    dispatch({ type: "dismiss-message", messageId: m.messageId });
    // Frees the core's copy too; failure is harmless (it is RAM-only).
    api.dismissMessage(m.messageId).catch(() => undefined);
  };

  return (
    <div className="msg-card" role="status">
      <div className="msg-head">
        <span className="msg-icon">
          <IconPhone size={14} />
        </span>
        <span className="msg-from">{m.senderName} sent you text</span>
        <span className="msg-head-spacer" />
        <button className="btn-ghost-text msg-copy" onClick={() => void copy()}>
          {copied ? (
            <>
              <IconCheck size={11} strokeWidth={2.6} />
              Copied
            </>
          ) : (
            "Copy"
          )}
        </button>
        <button className="icon-btn msg-close" title="Dismiss" onClick={dismiss}>
          <IconX size={11} strokeWidth={2.4} />
        </button>
      </div>
      <div className="msg-text">{m.text}</div>
    </div>
  );
}

/** Stack of incoming message cards, pinned near the top of the window. */
export function MessageCards() {
  const { messages } = useAppState();
  if (messages.length === 0) return null;
  return (
    <div className="msg-stack">
      {messages.map((m) => (
        <MessageCard key={m.messageId} m={m} />
      ))}
    </div>
  );
}
