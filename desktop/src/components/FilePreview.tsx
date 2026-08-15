import { useEffect, useState } from "react";
import { convertFileSrc } from "@tauri-apps/api/core";
import { useAppDispatch, useAppState } from "../store";
import * as api from "../api";
import { formatBytes } from "../format";
import { IconFile, IconFolder, IconX } from "../icons";
import {
  MAX_TEXT_PREVIEW_BYTES,
  type PreviewInfo,
  type TextPreview,
} from "../types";

/**
 * Above this an image is not inlined without a second click. Video, audio and
 * PDF stream over the asset protocol so their size does not matter, and text
 * is capped in Rust — a still image is the one kind the webview has to decode
 * into memory whole.
 */
const BIG_IMAGE_BYTES = 96 * 1024 * 1024;

type Load =
  | { phase: "loading" }
  | { phase: "error"; message: string }
  | { phase: "ready"; info: PreviewInfo };

function KindLabel({ info }: { info: PreviewInfo }) {
  return (
    <span className="preview-meta">
      {formatBytes(info.sizeBytes)} · {info.mimeType}
    </span>
  );
}

/** Text files: a bounded prefix, read in Rust, shown in the mono pane. */
function TextPane({ path }: { path: string }) {
  const [text, setText] = useState<TextPreview | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let disposed = false;
    api
      .readTextPreview(path, MAX_TEXT_PREVIEW_BYTES)
      .then((preview) => {
        if (!disposed) setText(preview);
      })
      .catch((err) => {
        if (!disposed) setError(String(err));
      });
    return () => {
      disposed = true;
    };
  }, [path]);

  if (error) return <div className="preview-note">{error}</div>;
  if (!text) return <div className="preview-note">Reading…</div>;

  return (
    <>
      <pre className="preview-text">{text.text}</pre>
      {text.truncated ? (
        <div className="preview-trunc">
          showing the first {formatBytes(MAX_TEXT_PREVIEW_BYTES)} of{" "}
          {formatBytes(text.totalBytes)} — open it in your editor for the rest
        </div>
      ) : null}
    </>
  );
}

function ImagePane({ path, info }: { path: string; info: PreviewInfo }) {
  const [zoomed, setZoomed] = useState(false);
  const [broken, setBroken] = useState(false);
  const [forced, setForced] = useState(false);

  if (info.sizeBytes > BIG_IMAGE_BYTES && !forced) {
    return (
      <div className="preview-empty">
        <IconFile size={22} />
        <div className="preview-empty-title">
          That's a {formatBytes(info.sizeBytes)} image
        </div>
        <div className="preview-empty-sub">
          Decoding it in the app would cost real memory. Open it in your image
          app, or load it here anyway.
        </div>
        <button className="btn-glass btn-sm" onClick={() => setForced(true)}>
          Load it anyway
        </button>
      </div>
    );
  }

  if (broken) {
    return (
      <div className="preview-empty">
        <IconFile size={22} />
        <div className="preview-empty-title">This image won't decode here</div>
        <div className="preview-empty-sub">
          The file is fine — the app's renderer just doesn't know this format.
        </div>
      </div>
    );
  }

  return (
    <img
      className={`preview-image${zoomed ? " zoomed" : ""}`}
      src={convertFileSrc(path)}
      alt=""
      draggable={false}
      onError={() => setBroken(true)}
      onClick={() => setZoomed((z) => !z)}
      title={zoomed ? "Click to fit" : "Click to zoom"}
    />
  );
}

function MediaPane({ path, kind }: { path: string; kind: "video" | "audio" }) {
  const [broken, setBroken] = useState(false);
  const src = convertFileSrc(path);

  if (broken) {
    return (
      <div className="preview-empty">
        <IconFile size={22} />
        <div className="preview-empty-title">
          This {kind} can't play in the app
        </div>
        <div className="preview-empty-sub">
          The codec or container isn't one the built-in player supports. Your
          file is untouched — open it in a real player.
        </div>
      </div>
    );
  }

  // Streamed over the asset protocol: the webview range-requests it, so even
  // an 8 GB master never lands in memory.
  return kind === "video" ? (
    <video
      className="preview-video"
      src={src}
      controls
      preload="metadata"
      onError={() => setBroken(true)}
    />
  ) : (
    <div className="preview-audio-wrap">
      <audio
        className="preview-audio"
        src={src}
        controls
        preload="metadata"
        onError={() => setBroken(true)}
      />
    </div>
  );
}

/**
 * The in-app preview modal. Opened from any file row in the app; the bytes
 * only ever reach the webview after Rust has checked the path against the
 * receive folder plus the files the user explicitly sent or shared.
 */
export function FilePreview() {
  const { preview } = useAppState();
  const dispatch = useAppDispatch();
  const [load, setLoad] = useState<Load>({ phase: "loading" });
  const [actionError, setActionError] = useState<string | null>(null);

  const path = preview?.path ?? null;

  useEffect(() => {
    if (!path) return;
    let disposed = false;
    setLoad({ phase: "loading" });
    setActionError(null);
    api
      .previewFile(path)
      .then((info) => {
        if (!disposed) setLoad({ phase: "ready", info });
      })
      .catch((err) => {
        if (!disposed) setLoad({ phase: "error", message: String(err) });
      });
    return () => {
      disposed = true;
    };
  }, [path]);

  // Esc closes, like every other overlay in the app.
  useEffect(() => {
    if (!preview) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") dispatch({ type: "close-preview" });
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [preview, dispatch]);

  if (!preview || !path) return null;

  const close = () => dispatch({ type: "close-preview" });

  const body = () => {
    if (load.phase === "loading") {
      return <div className="preview-note">Looking at the file…</div>;
    }
    if (load.phase === "error") {
      return (
        <div className="preview-empty">
          <IconFile size={22} />
          <div className="preview-empty-title">Can't preview this file</div>
          <div className="preview-empty-sub">{load.message}</div>
        </div>
      );
    }
    const { info } = load;
    if (!info.exists) {
      return (
        <div className="preview-empty">
          <IconFolder size={22} />
          <div className="preview-empty-title">This file isn't there any more</div>
          <div className="preview-empty-sub">
            It was moved, renamed or deleted since Sendro last saw it. The
            transfer itself is unaffected.
          </div>
          <button
            className="btn-glass btn-sm"
            onClick={() =>
              // The Rust side validates the *file* path (which is still in
              // scope) and falls back to opening its folder.
              void api
                .revealPreviewedFile(path)
                .catch((err) => setActionError(String(err)))
            }
          >
            Open the folder
          </button>
        </div>
      );
    }
    switch (info.kind) {
      case "image":
        return <ImagePane path={path} info={info} />;
      case "video":
        return <MediaPane path={path} kind="video" />;
      case "audio":
        return <MediaPane path={path} kind="audio" />;
      case "pdf":
        return (
          <iframe
            className="preview-pdf"
            src={convertFileSrc(path)}
            title={preview.fileName}
          />
        );
      case "text":
        return <TextPane path={path} />;
      default:
        return (
          <div className="preview-empty">
            <IconFile size={22} />
            <div className="preview-empty-title">No preview for this one</div>
            <div className="preview-empty-sub">
              Sendro can't render {info.mimeType} in the window — it opens
              perfectly well in the app that owns it.
            </div>
          </div>
        );
    }
  };

  const exists = load.phase === "ready" && load.info.exists;

  return (
    <div
      className="modal-backdrop"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) close();
      }}
    >
      <div className="preview-shell" role="dialog" aria-modal="true">
        <div className="preview-head">
          <div className="preview-head-main">
            <div className="preview-name" title={preview.fileName}>
              {preview.fileName}
            </div>
            {load.phase === "ready" ? <KindLabel info={load.info} /> : null}
          </div>
          <button
            className="icon-btn"
            onClick={close}
            title="Close preview (Esc)"
            aria-label="Close preview"
          >
            <IconX size={11} strokeWidth={2.4} />
          </button>
        </div>

        <div className="preview-body">{body()}</div>

        <div className="preview-foot">
          <div className="preview-path mono" title={actionError ?? path}>
            {actionError ?? path}
          </div>
          <button
            className="btn-glass btn-sm"
            disabled={!exists}
            onClick={() =>
              void api
                .openPreviewedFile(path)
                .catch((err) => setActionError(String(err)))
            }
          >
            Open in default app
          </button>
          <button
            className="btn-glass btn-sm"
            onClick={() =>
              void api
                .revealPreviewedFile(path)
                .catch((err) => setActionError(String(err)))
            }
          >
            Show in folder
          </button>
        </div>
      </div>
    </div>
  );
}
