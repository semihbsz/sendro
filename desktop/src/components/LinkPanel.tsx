import { useCallback, useEffect, useState } from "react";
import { open } from "@tauri-apps/plugin-dialog";
import { writeText } from "@tauri-apps/plugin-clipboard-manager";
import { useAppDispatch, useAppState } from "../store";
import * as api from "../api";
import { baseName, formatBytes } from "../format";
import { kindLabel } from "../qr";
import { IconCheck, IconFile, IconPlus, IconWifi, IconX } from "../icons";
import { QrCode } from "./QrCode";
import { Toggle } from "./common";
import { Modal } from "./Modal";
import { LINK_DURATIONS, type LinkOptions } from "../types";

/** mm:ss / h:mm:ss left, or "expired". */
function countdown(ms: number): string {
  if (ms <= 0) return "expired";
  const total = Math.floor(ms / 1000);
  const s = total % 60;
  const m = Math.floor(total / 60) % 60;
  const h = Math.floor(total / 3600);
  const pad = (n: number) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
}

/**
 * Sendro Link (PROTOCOL.md §14) — a temporary guest web session for someone
 * on the same Wi-Fi who doesn't have Sendro.
 *
 * Lives as a card on SEND rather than a fourth rail tab: the rail stays three
 * tabs, and the feature reads as "another way to send", which is what it is.
 *
 * Everything here is ephemeral by construction. The core keeps the session in
 * RAM only, so quitting Sendro ends it — the UI says so, and never implies
 * anything is still standing when the app is closed.
 */
export function LinkPanel() {
  const { link, linkArmed, linkStaged } = useAppState();
  const dispatch = useAppDispatch();

  const [minutes, setMinutes] = useState(30);
  const [allowUpload, setAllowUpload] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [confirmStop, setConfirmStop] = useState(false);
  const [now, setNow] = useState(() => Date.now());

  const live = link !== null;

  // A live session needs a ticking clock for the countdown.
  useEffect(() => {
    if (!live) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [live]);

  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), 1600);
    return () => window.clearTimeout(timer);
  }, [copied]);

  // While a session is live, files dropped on the window join it directly.
  useEffect(() => {
    if (!live || linkStaged.length === 0) return;
    const paths = linkStaged;
    dispatch({ type: "clear-link-staged" });
    api
      .addLinkFiles(paths)
      .then((session) => dispatch({ type: "set-link", link: session }))
      .catch((err) => setError(String(err)));
  }, [live, linkStaged, dispatch]);

  // Arming routes window drops here instead of to the send flow.
  const arm = useCallback(
    (armed: boolean) => dispatch({ type: "arm-link", armed }),
    [dispatch],
  );

  useEffect(() => {
    if (live) arm(true);
  }, [live, arm]);

  const pick = async () => {
    try {
      const selected = await open({
        multiple: true,
        title: "Share over Sendro Link",
      });
      if (selected === null) return;
      const paths = Array.isArray(selected) ? selected : [selected];
      if (paths.length === 0) return;
      if (live) {
        const session = await api.addLinkFiles(paths);
        dispatch({ type: "set-link", link: session });
      } else {
        dispatch({ type: "stage-link-files", paths });
      }
    } catch (err) {
      setError(String(err));
    }
  };

  const start = async () => {
    setBusy(true);
    setError(null);
    try {
      const opts: LinkOptions = {
        expiresInMinutes: minutes,
        allowUpload,
        paths: linkStaged,
      };
      const session = await api.startLinkSession(opts);
      dispatch({ type: "set-link", link: session });
      dispatch({ type: "clear-link-staged" });
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  };

  const stop = async () => {
    setBusy(true);
    try {
      await api.stopLinkSession();
      dispatch({ type: "set-link", link: null });
      arm(false);
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
      setConfirmStop(false);
    }
  };

  const removeFile = async (fileId: string) => {
    try {
      await api.removeLinkFile(fileId);
      const session = await api.linkSession();
      dispatch({ type: "set-link", link: session });
    } catch (err) {
      setError(String(err));
    }
  };

  /* ---------------- Live session ---------------- */

  if (link) {
    const remaining = link.expiresAtMs - now;
    const urls = link.urls.length > 0 ? link.urls : [];

    return (
      <div className="link-card live">
        <div className="link-head">
          <span className="link-live-dot" aria-hidden="true" />
          <div className="link-head-text">
            <div className="link-title">Sendro Link is open</div>
            <div className="link-sub">
              anyone on this Wi-Fi with this link can open these files
            </div>
          </div>
          <span className="link-countdown mono" title="Time left">
            {countdown(remaining)}
          </span>
          <button
            className="btn-stop"
            disabled={busy}
            onClick={() => setConfirmStop(true)}
          >
            Stop link
          </button>
        </div>

        <div className="link-live-body">
          <div className="link-url-col">
            <div className="strip-label">Open this on the other device</div>
            <div className="link-url-row">
              <code className="link-url" title={link.url}>
                {link.url}
              </code>
              <button
                className="btn-glass btn-sm"
                onClick={() => {
                  void writeText(link.url)
                    .then(() => setCopied(true))
                    .catch(() => undefined);
                }}
              >
                {copied ? "Copied" : "Copy"}
              </button>
            </div>

            {urls.length > 1 ? (
              <div className="link-addr-note">
                also reachable on{" "}
                {urls
                  .slice(1)
                  .map((u) => `${u.address} (${kindLabel(u.kind)})`)
                  .join(", ")}
              </div>
            ) : null}

            <div className="link-stats">
              <div>
                <div className="stat-label">Shared</div>
                <div className="stat-value">
                  {link.files.length} file{link.files.length === 1 ? "" : "s"}
                </div>
              </div>
              <div>
                <div className="stat-label">Guest uploads</div>
                <div
                  className={`stat-value${link.guestUploads > 0 ? " verify" : " dim"}`}
                >
                  {link.allowUpload ? link.guestUploads : "off"}
                </div>
              </div>
            </div>

            <div className="link-warn">
              <IconWifi size={14} />
              <span>
                The link is the key — anyone you hand it to, on this network,
                gets these files. It dies when it expires, when you stop it, or
                when Sendro quits.
              </span>
            </div>
          </div>

          <div className="link-qr-col">
            <QrCode data={link.url} size={210} label="Sendro Link address" />
            <div className="link-qr-cap">point a camera at it</div>
          </div>
        </div>

        <div className="link-files">
          <div className="link-files-head">
            <span className="strip-label">Shared files</span>
            <span className="finished-head-spacer" />
            <button className="btn-ghost-text" onClick={() => void pick()}>
              Add files
            </button>
          </div>
          {link.files.length === 0 ? (
            <div className="dashed-empty">
              Nothing shared yet — add files, or leave it upload-only.
            </div>
          ) : (
            link.files.map((f) => (
              <div className="link-file-row" key={f.fileId}>
                <span className="link-file-icon">
                  <IconFile size={15} />
                </span>
                {/* The core hands the UI a fileId, not a path — guests
                    address files by id and the path never leaves Rust — so
                    a shared file is listed, not previewed, from here. */}
                <span className="link-file-name static" title={f.fileName}>
                  {f.fileName}
                </span>
                <span className="link-file-size mono">
                  {formatBytes(f.sizeBytes)}
                </span>
                {f.sha256 ? (
                  <span className="pill-verified" title={f.sha256}>
                    <IconCheck size={10} strokeWidth={2.6} />
                    SHA-256
                  </span>
                ) : (
                  <span className="pill-muted">hashing…</span>
                )}
                <button
                  className="icon-btn danger"
                  title="Stop sharing this file"
                  onClick={() => void removeFile(f.fileId)}
                >
                  <IconX size={11} strokeWidth={2.4} />
                </button>
              </div>
            ))
          )}
        </div>

        {error ? <div className="error-note">{error}</div> : null}

        {confirmStop ? (
          <Modal
            title="Stop this link?"
            onClose={() => setConfirmStop(false)}
            cancelLabel="Keep it open"
          >
            <div className="confirm-body">
              The address stops working immediately and can never be reused —
              anyone with it gets a "gone" page. Your files stay where they are.
            </div>
            <div className="confirm-actions">
              <button className="btn-cancel" onClick={() => void stop()}>
                Stop it
              </button>
            </div>
          </Modal>
        ) : null}
      </div>
    );
  }

  /* ---------------- Composer ---------------- */

  if (!linkArmed) {
    return (
      <div className="link-card">
        <div className="link-head">
          <div className="link-head-text">
            <div className="link-title">Sendro Link</div>
            <div className="link-sub">
              hand files to someone on this Wi-Fi who doesn't have Sendro
            </div>
          </div>
          <button className="btn-glass" onClick={() => arm(true)}>
            Create a link
          </button>
        </div>
      </div>
    );
  }

  const canStart = linkStaged.length > 0 || allowUpload;

  return (
    <div className="link-card">
      <div className="link-head">
        <div className="link-head-text">
          <div className="link-title">New Sendro Link</div>
          <div className="link-sub">
            a temporary web page, served by this PC, on this network only
          </div>
        </div>
        <button className="btn-ghost-text" onClick={() => arm(false)}>
          Cancel
        </button>
      </div>

      <div className="link-row">
        <div className="field-label">Expires after</div>
        <div className="link-durations">
          {LINK_DURATIONS.map((d) => (
            <button
              key={d.minutes}
              className={`chip${minutes === d.minutes ? " active" : ""}`}
              onClick={() => setMinutes(d.minutes)}
            >
              {d.label}
            </button>
          ))}
        </div>
      </div>

      <div className="link-row">
        <div>
          <div className="field-label">Allow guest uploads</div>
          <div className="field-hint">
            Lets them send files back. They land in your receive folder, marked
            “Guest (link)”.
          </div>
        </div>
        <Toggle
          on={allowUpload}
          label="Allow guest uploads"
          onChange={setAllowUpload}
        />
      </div>

      <div className="link-files">
        <div className="link-files-head">
          <span className="strip-label">Files to share</span>
          <span className="finished-head-spacer" />
          <button className="btn-ghost-text" onClick={() => void pick()}>
            <IconPlus size={12} />
            Choose files
          </button>
        </div>
        {linkStaged.length === 0 ? (
          <div className="dashed-empty">
            Drop files anywhere in this window, or choose them above.
          </div>
        ) : (
          linkStaged.map((p) => (
            <div className="link-file-row" key={p}>
              <span className="link-file-icon">
                <IconFile size={15} />
              </span>
              <span className="link-file-name static" title={p}>
                {baseName(p)}
              </span>
              <span className="finished-head-spacer" />
              <button
                className="icon-btn danger"
                title="Remove"
                onClick={() =>
                  dispatch({ type: "unstage-link-file", path: p })
                }
              >
                <IconX size={11} strokeWidth={2.4} />
              </button>
            </div>
          ))
        )}
      </div>

      {error ? <div className="error-note">{error}</div> : null}

      <div className="link-actions">
        <span className="link-note">
          Nothing is uploaded anywhere. The page is served by this PC and stops
          existing when you close Sendro.
        </span>
        <button
          className="btn-solid"
          disabled={!canStart || busy}
          title={
            canStart
              ? undefined
              : "Add at least one file, or allow guest uploads"
          }
          onClick={() => void start()}
        >
          {busy ? "Starting…" : "Start link"}
        </button>
      </div>
    </div>
  );
}
