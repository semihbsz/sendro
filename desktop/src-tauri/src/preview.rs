//! Desktop-only file preview support.
//!
//! Two jobs:
//!
//! 1. **Classification** — [`preview_file`] answers "what is this file, does
//!    it exist, how big is it" so the webview can pick between an inline
//!    preview and "Open in default app". Kind is decided by extension plus an
//!    existence check (CORE_API.md).
//!
//! 2. **Scope** — the webview may only ever read files the local user has
//!    already touched. There is deliberately **no blanket filesystem grant**:
//!    `tauri.conf.json` ships an *empty* `assetProtocol.scope`, and the only
//!    way a path ever enters the runtime asset scope is a successful
//!    [`preview_file`] call, which first validates it against
//!    [`is_previewable`]:
//!
//!    * anything inside the configured receive folder (that is where every
//!      incoming transfer and every guest upload lands), or
//!    * a path the user explicitly handed to Sendro in this session — files
//!      offered with `offer_files`, files shared into a Sendro Link session,
//!      and paths reported by the core's own `WatchFileDetected` /
//!      `TransferUpdated` events, or
//!    * the clipboard-paste scratch directory (`%TEMP%/sendro-paste`).
//!
//!    Everything else is refused before any metadata is read, so a compromised
//!    webview cannot use the preview commands to walk the disk.

use std::collections::{HashSet, VecDeque};
use std::io::Read as _;
use std::path::{Component, Path, PathBuf};

use serde::Serialize;
use tauri::{AppHandle, Manager, State};
use tauri_plugin_opener::OpenerExt as _;

use crate::AppState;

/// Largest text prefix ever read into the webview (§ "never load a multi-GB
/// file into the webview"). 200 KiB is ~4000 lines of subtitles or JSON.
pub const MAX_TEXT_PREVIEW_BYTES: u64 = 200 * 1024;

/// How many explicitly-touched paths are remembered. FIFO — a long editing
/// session can produce a lot of transfers, and the registry must not grow
/// without bound.
const REGISTRY_CAP: usize = 4096;

// ---------------------------------------------------------------------------
// Registry of paths the local user explicitly handed to Sendro
// ---------------------------------------------------------------------------

/// Bounded, de-duplicated FIFO of absolute paths.
#[derive(Debug, Default)]
pub struct PathRegistry {
    set: HashSet<PathBuf>,
    order: VecDeque<PathBuf>,
}

impl PathRegistry {
    pub fn insert(&mut self, path: PathBuf) {
        if path.as_os_str().is_empty() || self.set.contains(&path) {
            return;
        }
        while self.order.len() >= REGISTRY_CAP {
            if let Some(old) = self.order.pop_front() {
                self.set.remove(&old);
            }
        }
        self.order.push_back(path.clone());
        self.set.insert(path);
    }

    pub fn contains(&self, path: &Path) -> bool {
        self.set.contains(path)
    }
}

// ---------------------------------------------------------------------------
// Wire types
// ---------------------------------------------------------------------------

/// CORE_API.md: `{ kind, mimeType, sizeBytes, exists }`.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PreviewInfo {
    /// "image" | "video" | "audio" | "pdf" | "text" | "other"
    pub kind: String,
    pub mime_type: String,
    pub size_bytes: u64,
    pub exists: bool,
}

/// A bounded UTF-8 prefix of a text file.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TextPreview {
    pub text: String,
    /// True when the file is longer than the slice returned here.
    pub truncated: bool,
    pub total_bytes: u64,
}

// ---------------------------------------------------------------------------
// Extension → (kind, mime) table
// ---------------------------------------------------------------------------

pub const KIND_IMAGE: &str = "image";
pub const KIND_VIDEO: &str = "video";
pub const KIND_AUDIO: &str = "audio";
pub const KIND_PDF: &str = "pdf";
pub const KIND_TEXT: &str = "text";
pub const KIND_OTHER: &str = "other";

/// Extension → (kind, mime type).
///
/// Formats a WebView2 surface cannot decode at all (HEIC, TIFF, PSD, RAW) are
/// deliberately classified `other` with their real mime type: the UI then
/// shows the honest "No preview" card with "Open in default app" instead of a
/// broken `<img>`. Container formats that *might* play (mkv, avi) stay
/// `video`; the player falls back on its own `error` event.
const TABLE: &[(&str, &str, &str)] = &[
    // images the webview can actually render
    ("png", KIND_IMAGE, "image/png"),
    ("jpg", KIND_IMAGE, "image/jpeg"),
    ("jpeg", KIND_IMAGE, "image/jpeg"),
    ("jfif", KIND_IMAGE, "image/jpeg"),
    ("gif", KIND_IMAGE, "image/gif"),
    ("webp", KIND_IMAGE, "image/webp"),
    ("bmp", KIND_IMAGE, "image/bmp"),
    ("avif", KIND_IMAGE, "image/avif"),
    ("svg", KIND_IMAGE, "image/svg+xml"),
    ("ico", KIND_IMAGE, "image/x-icon"),
    // images it cannot — real mime, no inline preview
    ("heic", KIND_OTHER, "image/heic"),
    ("heif", KIND_OTHER, "image/heif"),
    ("tif", KIND_OTHER, "image/tiff"),
    ("tiff", KIND_OTHER, "image/tiff"),
    ("psd", KIND_OTHER, "image/vnd.adobe.photoshop"),
    ("cr2", KIND_OTHER, "image/x-canon-cr2"),
    ("cr3", KIND_OTHER, "image/x-canon-cr3"),
    ("nef", KIND_OTHER, "image/x-nikon-nef"),
    ("arw", KIND_OTHER, "image/x-sony-arw"),
    ("dng", KIND_OTHER, "image/x-adobe-dng"),
    // video
    ("mp4", KIND_VIDEO, "video/mp4"),
    ("m4v", KIND_VIDEO, "video/x-m4v"),
    ("mov", KIND_VIDEO, "video/quicktime"),
    ("webm", KIND_VIDEO, "video/webm"),
    ("mkv", KIND_VIDEO, "video/x-matroska"),
    ("avi", KIND_VIDEO, "video/x-msvideo"),
    ("mpg", KIND_VIDEO, "video/mpeg"),
    ("mpeg", KIND_VIDEO, "video/mpeg"),
    ("mts", KIND_VIDEO, "video/mp2t"),
    ("m2ts", KIND_VIDEO, "video/mp2t"),
    ("wmv", KIND_VIDEO, "video/x-ms-wmv"),
    // audio
    ("mp3", KIND_AUDIO, "audio/mpeg"),
    ("m4a", KIND_AUDIO, "audio/mp4"),
    ("aac", KIND_AUDIO, "audio/aac"),
    ("wav", KIND_AUDIO, "audio/wav"),
    ("flac", KIND_AUDIO, "audio/flac"),
    ("ogg", KIND_AUDIO, "audio/ogg"),
    ("oga", KIND_AUDIO, "audio/ogg"),
    ("opus", KIND_AUDIO, "audio/opus"),
    ("aif", KIND_AUDIO, "audio/aiff"),
    ("aiff", KIND_AUDIO, "audio/aiff"),
    ("wma", KIND_AUDIO, "audio/x-ms-wma"),
    // documents
    ("pdf", KIND_PDF, "application/pdf"),
    // text the mono pane can show
    ("txt", KIND_TEXT, "text/plain"),
    ("log", KIND_TEXT, "text/plain"),
    ("md", KIND_TEXT, "text/markdown"),
    ("markdown", KIND_TEXT, "text/markdown"),
    ("srt", KIND_TEXT, "application/x-subrip"),
    ("vtt", KIND_TEXT, "text/vtt"),
    ("ass", KIND_TEXT, "text/plain"),
    ("ssa", KIND_TEXT, "text/plain"),
    ("json", KIND_TEXT, "application/json"),
    ("csv", KIND_TEXT, "text/csv"),
    ("tsv", KIND_TEXT, "text/tab-separated-values"),
    ("xml", KIND_TEXT, "application/xml"),
    ("yml", KIND_TEXT, "application/yaml"),
    ("yaml", KIND_TEXT, "application/yaml"),
    ("toml", KIND_TEXT, "application/toml"),
    ("ini", KIND_TEXT, "text/plain"),
    ("cfg", KIND_TEXT, "text/plain"),
    ("edl", KIND_TEXT, "text/plain"),
    ("fcpxml", KIND_TEXT, "application/xml"),
];

/// Classify by extension. Returns `(kind, mimeType)`.
pub fn classify(path: &Path) -> (&'static str, &'static str) {
    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or_default()
        .to_ascii_lowercase();
    for (candidate, kind, mime) in TABLE {
        if *candidate == ext {
            return (kind, mime);
        }
    }
    (KIND_OTHER, "application/octet-stream")
}

// ---------------------------------------------------------------------------
// Scope
// ---------------------------------------------------------------------------

/// Canonicalize, swallowing the error (the file may be gone).
fn canonical(path: &Path) -> Option<PathBuf> {
    std::fs::canonicalize(path).ok()
}

/// Is `child` inside `parent`?
///
/// Canonicalized comparison when both resolve (this is what defeats symlinks
/// and `..`). When the child no longer exists — a moved or deleted receive —
/// fall back to a lexical check that refuses any `..` component outright, so a
/// vanished file can still be reported as `exists: false` without opening a
/// traversal hole.
fn under(parent: &Path, child: &Path) -> bool {
    if let (Some(p), Some(c)) = (canonical(parent), canonical(child)) {
        if c.starts_with(&p) {
            return true;
        }
    }
    if child.components().any(|c| c == Component::ParentDir) {
        return false;
    }
    match canonical(parent) {
        Some(p) => child.starts_with(&p) || child.starts_with(parent),
        None => child.starts_with(parent),
    }
}

/// The clipboard-paste scratch directory used by `paste_clipboard_image`.
fn paste_dir() -> PathBuf {
    std::env::temp_dir().join("sendro-paste")
}

/// May the webview read this path? See the module docs for the rule set.
pub fn is_previewable(state: &AppState, path: &Path) -> bool {
    if path.as_os_str().is_empty() || path.is_relative() {
        return false;
    }
    {
        let registry = state
            .preview_paths
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if registry.contains(path) {
            return true;
        }
    }
    let receive_dir = PathBuf::from(state.core.settings().receive_dir);
    if !receive_dir.as_os_str().is_empty() && under(&receive_dir, path) {
        return true;
    }
    under(&paste_dir(), path)
}

/// Refusal message. Deliberately vague about *why* — it is a bug report for
/// us, not a probing oracle for a hostile page.
fn refused() -> String {
    "this file is outside Sendro's preview scope".to_string()
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

/// Classify a file for the preview modal, and — only if it exists and is in
/// scope — extend the asset-protocol scope so the webview may stream it.
#[tauri::command]
pub fn preview_file(
    app: AppHandle,
    state: State<'_, AppState>,
    path: String,
) -> Result<PreviewInfo, String> {
    let path = PathBuf::from(path);
    if !is_previewable(&state, &path) {
        return Err(refused());
    }

    let (kind, mime_type) = classify(&path);
    let meta = std::fs::metadata(&path).ok().filter(|m| m.is_file());
    let (exists, size_bytes) = match meta {
        Some(m) => (true, m.len()),
        None => (false, 0),
    };

    if exists {
        // The webview can only ever reach paths that made it through the
        // check above; nothing is allowed at startup.
        if let Err(err) = app.asset_protocol_scope().allow_file(&path) {
            log::warn!("could not extend the asset scope: {err}");
        }
    }

    Ok(PreviewInfo {
        kind: kind.to_string(),
        mime_type: mime_type.to_string(),
        size_bytes,
        exists,
    })
}

/// Read a bounded UTF-8 prefix of a text file. Never more than
/// [`MAX_TEXT_PREVIEW_BYTES`], so a 4 GB log cannot be pulled into the webview.
#[tauri::command]
pub fn read_text_preview(
    state: State<'_, AppState>,
    path: String,
    max_bytes: Option<u64>,
) -> Result<TextPreview, String> {
    let path = PathBuf::from(path);
    if !is_previewable(&state, &path) {
        return Err(refused());
    }

    let meta = std::fs::metadata(&path).map_err(|e| e.to_string())?;
    if !meta.is_file() {
        return Err("not a file".to_string());
    }
    let total_bytes = meta.len();
    let limit = max_bytes
        .unwrap_or(MAX_TEXT_PREVIEW_BYTES)
        .min(MAX_TEXT_PREVIEW_BYTES);

    let file = std::fs::File::open(&path).map_err(|e| e.to_string())?;
    let mut buf = Vec::with_capacity(limit.min(total_bytes) as usize);
    file.take(limit)
        .read_to_end(&mut buf)
        .map_err(|e| e.to_string())?;

    let truncated = total_bytes > buf.len() as u64;
    Ok(TextPreview {
        // Lossy on purpose: a UTF-16 or binary file must degrade to visible
        // replacement characters, never to an error the user cannot act on.
        text: String::from_utf8_lossy(&buf).into_owned(),
        truncated,
        total_bytes,
    })
}

/// "Open in default app" for a previewed file.
///
/// Deliberately *not* the `opener` plugin's JS `open_path` command: that one
/// is gated by an ACL path scope, and the only scope that would satisfy it for
/// arbitrary user files is a blanket one. Going through Rust means the same
/// [`is_previewable`] check guards this as guards the bytes.
#[tauri::command]
pub fn open_previewed_file(
    app: AppHandle,
    state: State<'_, AppState>,
    path: String,
) -> Result<(), String> {
    let buf = PathBuf::from(&path);
    if !is_previewable(&state, &buf) {
        return Err(refused());
    }
    if !buf.exists() {
        return Err("that file isn't there any more".to_string());
    }
    app.opener()
        .open_path(path, None::<&str>)
        .map_err(|e| e.to_string())
}

/// "Show in folder" — selects the file in Explorer. Falls back to opening the
/// containing directory when the file itself has gone.
#[tauri::command]
pub fn reveal_previewed_file(
    app: AppHandle,
    state: State<'_, AppState>,
    path: String,
) -> Result<(), String> {
    let buf = PathBuf::from(&path);
    if !is_previewable(&state, &buf) {
        return Err(refused());
    }
    if buf.exists() {
        return app
            .opener()
            .reveal_item_in_dir(&buf)
            .map_err(|e| e.to_string());
    }
    let parent = buf
        .parent()
        .filter(|p| p.exists())
        .ok_or_else(|| "that folder isn't there any more".to_string())?;
    app.opener()
        .open_path(parent.to_string_lossy().into_owned(), None::<&str>)
        .map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classifies_by_extension_case_insensitively() {
        assert_eq!(classify(Path::new("a/B.MOV")).0, KIND_VIDEO);
        assert_eq!(classify(Path::new("a/b.srt")).0, KIND_TEXT);
        assert_eq!(
            classify(Path::new("a/b.PDF")),
            (KIND_PDF, "application/pdf")
        );
        assert_eq!(classify(Path::new("a/b.heic")).0, KIND_OTHER);
        assert_eq!(classify(Path::new("a/b")).0, KIND_OTHER);
    }

    #[test]
    fn registry_dedupes_and_is_bounded() {
        let mut reg = PathRegistry::default();
        for i in 0..(REGISTRY_CAP + 10) {
            reg.insert(PathBuf::from(format!("/tmp/f{i}")));
        }
        assert!(!reg.contains(Path::new("/tmp/f0")));
        assert!(reg.contains(Path::new("/tmp/f4100")));
        reg.insert(PathBuf::from("/tmp/f4100"));
        assert!(reg.contains(Path::new("/tmp/f4100")));
    }

    #[test]
    fn parent_dir_escapes_are_refused() {
        let dir = std::env::temp_dir().join("sendro-preview-test");
        assert!(!under(
            &dir,
            Path::new("/tmp/sendro-preview-test/../../etc/passwd")
        ));
    }
}
