/**
 * "Send text" composer (PROTOCOL.md §11.1) — a short UTF-8 payload pushed to
 * a paired device over the existing outbox long-poll. Nothing typed here is
 * stored: the core keeps it in RAM until the phone drains it, and this
 * component throws its state away on close.
 */
import { useEffect, useRef, useState } from "react";
import { Modal } from "./Modal";
import { useAppDispatch, useAppState } from "../store";
import { IconPhone } from "../icons";
import { EmptyState } from "./common";
import { sendTargets, sendTextTo, type SendTarget } from "../targets";
import { MAX_MESSAGE_BYTES } from "../types";

const encoder = new TextEncoder();

/** The 32 KiB limit is on encoded UTF-8 bytes, not characters. */
function byteLength(text: string): number {
  return encoder.encode(text).length;
}

export function TextComposer() {
  const { composerText, composerTarget, devices, peers, chipDeviceId } =
    useAppState();
  const dispatch = useAppDispatch();
  const open = composerText !== null;

  if (!open) return null;
  return (
    <Composer
      initialText={composerText}
      // Both directions of trust are valid targets for text (§11.1 rides the
      // outbox for a device; §11.2 posts straight to a peer).
      targets={sendTargets(devices, peers)}
      preferredId={composerTarget ?? chipDeviceId}
      onClose={() => dispatch({ type: "close-composer" })}
    />
  );
}

function Composer({
  initialText,
  targets,
  preferredId,
  onClose,
}: {
  initialText: string;
  targets: SendTarget[];
  preferredId: string | null;
  onClose: () => void;
}) {
  const [text, setText] = useState(initialText);
  // Same target the file-send flow would use: the top-bar chip's device when
  // it is still paired, else the first online one, else the first paired one.
  const [targetId, setTargetId] = useState<string>(() => {
    if (preferredId && targets.some((t) => t.deviceId === preferredId)) {
      return preferredId;
    }
    return (
      targets.find((t) => t.online)?.deviceId ?? targets[0]?.deviceId ?? ""
    );
  });
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const areaRef = useRef<HTMLTextAreaElement | null>(null);
  const sentTimer = useRef<number | null>(null);

  useEffect(() => {
    areaRef.current?.focus();
    const area = areaRef.current;
    if (area) area.selectionStart = area.selectionEnd = area.value.length;
    return () => {
      if (sentTimer.current !== null) window.clearTimeout(sentTimer.current);
    };
  }, []);

  const bytes = byteLength(text);
  const tooLong = bytes > MAX_MESSAGE_BYTES;
  const target = targets.find((t) => t.deviceId === targetId) ?? null;
  const canSend = !sending && text.length > 0 && !tooLong && target !== null;

  const send = async () => {
    if (!canSend) return;
    setSending(true);
    setError(null);
    try {
      await sendTextTo(target, text);
      setText("");
      setSent(true);
      areaRef.current?.focus();
      if (sentTimer.current !== null) window.clearTimeout(sentTimer.current);
      sentTimer.current = window.setTimeout(() => setSent(false), 1800);
    } catch (err) {
      setError(String(err));
    } finally {
      setSending(false);
    }
  };

  return (
    <Modal title="Send text" onClose={onClose} cancelLabel="Close">
      {targets.length === 0 ? (
        <EmptyState
          icon={<IconPhone size={22} />}
          title="No paired devices"
          subtitle="Pair a phone with this PC, or pair this PC with a phone or TV from Settings › Devices."
        />
      ) : (
        <>
          <textarea
            ref={areaRef}
            className="composer-input"
            value={text}
            placeholder="Paste a link, a caption, a path…"
            spellCheck={false}
            onChange={(e) => {
              setText(e.target.value);
              setSent(false);
            }}
            onKeyDown={(e) => {
              if ((e.metaKey || e.ctrlKey) && e.key === "Enter") {
                e.preventDefault();
                void send();
              }
            }}
          />

          <div className="composer-foot">
            <span className={`composer-count${tooLong ? " over" : ""}`}>
              {bytes.toLocaleString()} / {MAX_MESSAGE_BYTES.toLocaleString()} B
            </span>
            <span className="composer-foot-spacer" />
            <select
              className="target-select"
              value={targetId}
              title="Send to"
              onChange={(e) => setTargetId(e.target.value)}
            >
              {targets.map((t) => (
                <option key={t.deviceId} value={t.deviceId}>
                  {t.deviceName}
                  {t.online ? "" : " (offline)"}
                </option>
              ))}
            </select>
            <button
              className="btn-solid btn-sm"
              disabled={!canSend}
              onClick={() => void send()}
            >
              {sending ? "Sending…" : "Send"}
            </button>
          </div>

          <div className="composer-hint">
            <span>
              nothing is stored · delivered once, then gone ·{" "}
              <kbd>Ctrl</kbd>
              <kbd>Enter</kbd> to send
            </span>
            {sent ? <span className="save-note">Sent</span> : null}
          </div>
        </>
      )}

      {error ? <div className="error-note">{error}</div> : null}
    </Modal>
  );
}
