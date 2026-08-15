//! Sendro Link — the guest web session of PROTOCOL.md §14.
//!
//! A temporary, unguessable-URL web page served by the host itself so
//! someone on the same Wi-Fi without Sendro installed can pull the files the
//! user explicitly shared (and, optionally, push files back). Still LAN-only,
//! still no cloud.
//!
//! Security properties, all enforced here:
//!
//! * **RAM only.** [`LinkState`] lives in [`Core::link`] and is never
//!   serialized to `data_dir`; a restart wipes it (§14.3).
//! * **Never auto-started.** Only [`Core::start_link_session`] creates one,
//!   and nothing on the network can call it.
//! * **The path token is the credential.** It is compared in constant time,
//!   and a stopped/expired token is retired so it can never be reused — its
//!   routes answer `410 gone` instead of silently 404ing.
//! * **No browsing, no traversal.** Guests address files by `fileId`; the
//!   id → path map is the only lookup, so a `..` in the URL cannot resolve
//!   to anything.
//! * **Never logged.** Only a 6-character prefix of a token is ever written
//!   to a log line, and full guest URLs never are.

use std::collections::VecDeque;
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

use axum::body::Body;
use axum::extract::{DefaultBodyLimit, Path, State};
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use rand::rngs::OsRng;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use subtle::ConstantTimeEq;
use uuid::Uuid;

use crate::qr::QrUrl;
use crate::server::{api_error, receive_upload, serve_file, FileServe, UploadPeer};
use crate::types::now_ms;
use crate::{net, Core, CoreEvent};

/// Token size in bytes; base64url-no-pad renders 24 bytes as 32 chars (§14.1).
pub const TOKEN_BYTES: usize = 24;
/// Length of the rendered token, used as a cheap shape check before the
/// constant-time compare (the length is a public protocol constant).
pub const TOKEN_CHARS: usize = 32;
/// Default session duration when the UI does not pick one (§14.1).
pub const DEFAULT_EXPIRY_MINUTES: u32 = 30;
/// Hard ceiling on session duration: 24 h (§14.1).
pub const MAX_EXPIRY_MINUTES: u32 = 24 * 60;
/// Max simultaneous guest connections across all guest routes (§14.2).
pub const MAX_GUEST_CONNECTIONS: usize = 8;
/// Max uploads accepted per session (§14.2).
pub const MAX_GUEST_UPLOADS: u32 = 200;
/// How many retired tokens are remembered so they answer `410` (not `404`).
const RETIRED_TOKENS_CAP: usize = 16;
/// Peer name used for guest transfers in the queue and history (§14.2).
pub const GUEST_PEER_NAME: &str = "Guest (link)";

/// The guest page: one self-contained document, no CDN, no external font —
/// it has to work with zero internet access.
const GUEST_PAGE: &str = include_str!("link_guest.html");

// ---------------------------------------------------------------------------
// Public API types (CORE_API.md)
// ---------------------------------------------------------------------------

/// What the user picked in the PC UI before starting a session.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LinkOptions {
    pub expires_in_minutes: u32,
    pub allow_upload: bool,
    pub paths: Vec<PathBuf>,
}

impl Default for LinkOptions {
    fn default() -> Self {
        Self {
            expires_in_minutes: DEFAULT_EXPIRY_MINUTES,
            allow_upload: false,
            paths: Vec::new(),
        }
    }
}

/// One file explicitly shared into the session.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LinkFile {
    pub file_id: Uuid,
    pub file_name: String,
    pub size_bytes: u64,
    pub mime_type: String,
    /// `None` until the background hash finishes; the guest download then
    /// gains its `ETag`/`X-Sendro-Sha256`.
    pub sha256: Option<String>,
}

/// Snapshot of the live session (CORE_API.md). Handed to the local UI only.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LinkSession {
    pub token: String,
    /// Best candidate URL, ready to render as a QR code.
    pub url: String,
    /// One URL per routable address (lan → hotspot → other).
    pub urls: Vec<QrUrl>,
    pub expires_at_ms: i64,
    pub allow_upload: bool,
    pub files: Vec<LinkFile>,
    pub guest_uploads: u32,
}

// ---------------------------------------------------------------------------
// Internal state
// ---------------------------------------------------------------------------

pub(crate) struct LinkEntry {
    pub file: LinkFile,
    /// Absolute source path. Guests never see it; it is only ever reached
    /// through a `fileId` equality lookup.
    pub path: PathBuf,
}

pub(crate) struct LinkState {
    pub token: String,
    pub expires_at_ms: i64,
    pub allow_upload: bool,
    pub files: Vec<LinkEntry>,
    /// Completed guest uploads (surfaced in the UI).
    pub guest_uploads: u32,
    /// Accepted upload *attempts*, including ones that failed verification —
    /// this is what the 200-per-session cap counts, so a guest cannot retry
    /// forever.
    pub upload_attempts: u32,
}

impl LinkState {
    fn expired(&self, now: i64) -> bool {
        now >= self.expires_at_ms
    }

    fn snapshot(&self, core: &Core) -> LinkSession {
        let urls = guest_urls(core.port, &self.token);
        LinkSession {
            token: self.token.clone(),
            url: urls
                .first()
                .map(|u| u.url.clone())
                // No routable address (no NIC): still give the UI something
                // that works from this machine.
                .unwrap_or_else(|| guest_url("127.0.0.1", core.port, &self.token)),
            urls,
            expires_at_ms: self.expires_at_ms,
            allow_upload: self.allow_upload,
            files: self.files.iter().map(|e| e.file.clone()).collect(),
            guest_uploads: self.guest_uploads,
        }
    }
}

fn guest_url(address: &str, port: u16, token: &str) -> String {
    format!("http://{address}:{port}/link/{token}/")
}

fn guest_urls(port: u16, token: &str) -> Vec<QrUrl> {
    net::interfaces()
        .into_iter()
        .map(|iface| QrUrl {
            url: guest_url(&iface.address, port, token),
            address: iface.address,
            kind: iface.kind,
        })
        .collect()
}

/// Mint a fresh 24-byte token, base64url no-pad (32 chars) — §14.1.
fn mint_token() -> String {
    let mut bytes = [0u8; TOKEN_BYTES];
    OsRng.fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

/// Constant-time token comparison. The length is a public constant, so
/// comparing it up front leaks nothing.
fn token_eq(a: &str, b: &str) -> bool {
    a.len() == b.len() && bool::from(a.as_bytes().ct_eq(b.as_bytes()))
}

/// The only form of a token that may ever reach a log line (§14.3).
fn token_prefix(token: &str) -> String {
    token.chars().take(6).collect()
}

/// Outcome of resolving a `:token` path segment.
enum TokenLookup {
    Active,
    /// Known but finished (stopped or expired) → `410 gone`.
    Retired,
    /// Never existed → `404 not_found`.
    Unknown,
}

// ---------------------------------------------------------------------------
// Guest connection slots
// ---------------------------------------------------------------------------

/// RAII slot for the §14.2 "max 8 concurrent guest connections" limit. For a
/// download the guard is moved into the response body stream, so the slot is
/// only released when the last byte is out.
pub(crate) struct GuestSlot {
    counter: Arc<AtomicUsize>,
}

impl Drop for GuestSlot {
    fn drop(&mut self) {
        self.counter.fetch_sub(1, Ordering::SeqCst);
    }
}

fn acquire_guest_slot(core: &Core) -> Option<GuestSlot> {
    let counter = core.link_conns.clone();
    let mut current = counter.load(Ordering::SeqCst);
    loop {
        if current >= MAX_GUEST_CONNECTIONS {
            return None;
        }
        match counter.compare_exchange_weak(
            current,
            current + 1,
            Ordering::SeqCst,
            Ordering::SeqCst,
        ) {
            Ok(_) => return Some(GuestSlot { counter }),
            Err(actual) => current = actual,
        }
    }
}

fn too_many_guests() -> Response {
    let mut resp = api_error(
        StatusCode::SERVICE_UNAVAILABLE,
        "rate_limited",
        Some("too many guest connections"),
    );
    resp.headers_mut()
        .insert(header::RETRY_AFTER, HeaderValue::from_static("2"));
    resp
}

// ---------------------------------------------------------------------------
// Core API
// ---------------------------------------------------------------------------

impl Core {
    /// Start a guest session (§14). Always an explicit local user action —
    /// nothing on the network can reach this.
    ///
    /// An already-running session is stopped first (its token is retired and
    /// can never be revived).
    pub fn start_link_session(&self, opts: LinkOptions) -> anyhow::Result<LinkSession> {
        if opts.expires_in_minutes == 0 || opts.expires_in_minutes > MAX_EXPIRY_MINUTES {
            anyhow::bail!(
                "expires_in_minutes must be 1..={MAX_EXPIRY_MINUTES} (got {})",
                opts.expires_in_minutes
            );
        }
        let files = crate::transfers::expand_paths(&opts.paths)?;
        let entries = files
            .iter()
            .map(|p| link_entry(p))
            .collect::<anyhow::Result<Vec<LinkEntry>>>()?;

        // Retire whatever was running before minting the new token.
        self.stop_link_session();

        let token = mint_token();
        let expires_at_ms = now_ms() + opts.expires_in_minutes as i64 * 60_000;
        let snapshot = {
            let mut slot = self.link.write();
            let state = LinkState {
                token: token.clone(),
                expires_at_ms,
                allow_upload: opts.allow_upload,
                files: entries,
                guest_uploads: 0,
                upload_attempts: 0,
            };
            let snapshot = state.snapshot(self);
            *slot = Some(state);
            snapshot
        };
        tracing::info!(
            "link session {}… started: {} file(s), upload={}, {} min",
            token_prefix(&token),
            snapshot.files.len(),
            opts.allow_upload,
            opts.expires_in_minutes
        );
        self.spawn_link_hashing(token);
        self.emit(CoreEvent::LinkSessionChanged {
            session: Some(snapshot.clone()),
        });
        Ok(snapshot)
    }

    /// Stop the session. Its token is retired: the guest routes answer
    /// `410 gone` from now on, and it is never reused.
    pub fn stop_link_session(&self) -> bool {
        let stopped = { self.link.write().take() };
        match stopped {
            Some(state) => {
                self.retire_token(state.token);
                self.emit(CoreEvent::LinkSessionChanged { session: None });
                true
            }
            None => false,
        }
    }

    /// The live session, or `None` if there is none (or it just expired).
    pub fn link_session(&self) -> Option<LinkSession> {
        self.reap_expired_link();
        self.link.read().as_ref().map(|s| s.snapshot(self))
    }

    /// Add more files to the running session (folders expand recursively).
    pub fn add_link_files(&self, paths: Vec<PathBuf>) -> anyhow::Result<LinkSession> {
        let files = crate::transfers::expand_paths(&paths)?;
        let mut entries = files
            .iter()
            .map(|p| link_entry(p))
            .collect::<anyhow::Result<Vec<LinkEntry>>>()?;

        self.reap_expired_link();
        let (snapshot, token) = {
            let mut slot = self.link.write();
            let Some(state) = slot.as_mut() else {
                anyhow::bail!("no link session is running");
            };
            state.files.append(&mut entries);
            (state.snapshot(self), state.token.clone())
        };
        self.spawn_link_hashing(token);
        self.emit(CoreEvent::LinkSessionChanged {
            session: Some(snapshot.clone()),
        });
        Ok(snapshot)
    }

    /// Un-share one file. Any in-flight download of it keeps its open file
    /// handle, but no new request can reach it.
    pub fn remove_link_file(&self, file_id: Uuid) -> bool {
        let snapshot = {
            let mut slot = self.link.write();
            let Some(state) = slot.as_mut() else {
                return false;
            };
            let before = state.files.len();
            state.files.retain(|e| e.file.file_id != file_id);
            if state.files.len() == before {
                return false;
            }
            state.snapshot(self)
        };
        self.emit(CoreEvent::LinkSessionChanged {
            session: Some(snapshot),
        });
        true
    }

    /// Test hook: move the running session's deadline. Not part of the
    /// CORE_API.md contract — it exists so the §14 expiry path can be tested
    /// without waiting out the one-minute minimum duration.
    #[doc(hidden)]
    pub fn __set_link_expiry_ms(&self, expires_at_ms: i64) -> bool {
        let mut slot = self.link.write();
        match slot.as_mut() {
            Some(state) => {
                state.expires_at_ms = expires_at_ms;
                true
            }
            None => false,
        }
    }

    /// Drop the session if its deadline passed (called lazily on every guest
    /// request and from the maintenance sweep).
    pub(crate) fn reap_expired_link(&self) {
        let expired = {
            let mut slot = self.link.write();
            match slot.as_ref() {
                Some(state) if state.expired(now_ms()) => slot.take(),
                _ => None,
            }
        };
        if let Some(state) = expired {
            tracing::info!("link session {}… expired", token_prefix(&state.token));
            self.retire_token(state.token);
            self.emit(CoreEvent::LinkSessionChanged { session: None });
        }
    }

    /// Remember a finished token so its routes answer `410 gone` rather than
    /// `404`, and so it can never be resurrected. RAM only, bounded.
    fn retire_token(&self, token: String) {
        let mut retired = self.link_retired.lock();
        while retired.len() >= RETIRED_TOKENS_CAP {
            retired.pop_front();
        }
        retired.push_back(token);
    }

    fn lookup_token(&self, token: &str) -> TokenLookup {
        self.reap_expired_link();
        if let Some(state) = self.link.read().as_ref() {
            if token_eq(&state.token, token) {
                return TokenLookup::Active;
            }
        }
        if self.link_retired.lock().iter().any(|t| token_eq(t, token)) {
            return TokenLookup::Retired;
        }
        TokenLookup::Unknown
    }

    /// Hash newly added files in the background so `add_link_files` /
    /// `start_link_session` stay instant even for an 8 GB video. The session
    /// serves the file happily meanwhile; only the ETag arrives late.
    fn spawn_link_hashing(&self, token: String) {
        let Some(core) = self.self_ref.read().as_ref().and_then(|w| w.upgrade()) else {
            return;
        };
        if tokio::runtime::Handle::try_current().is_err() {
            // No async context (a bare unit test): the hashes stay None,
            // which the guest page and the download path both tolerate.
            tracing::debug!("no tokio runtime: skipping link hashing");
            return;
        }
        tokio::spawn(async move {
            loop {
                // One file per pass, re-reading the session each time: the
                // user may stop the session or remove a file mid-hash.
                let next = {
                    let slot = core.link.read();
                    let Some(state) = slot.as_ref() else { break };
                    if !token_eq(&state.token, &token) {
                        break;
                    }
                    state
                        .files
                        .iter()
                        .find(|e| e.file.sha256.is_none())
                        .map(|e| (e.file.file_id, e.path.clone()))
                };
                let Some((file_id, path)) = next else { break };
                let hashed = crate::hashing::sha256_file(&path, |_| {}).await;
                let mut changed = false;
                {
                    let mut slot = core.link.write();
                    let Some(state) = slot.as_mut() else { break };
                    if !token_eq(&state.token, &token) {
                        break;
                    }
                    if let Some(entry) = state.files.iter_mut().find(|e| e.file.file_id == file_id)
                    {
                        match &hashed {
                            Ok((hex, size)) => {
                                entry.file.sha256 = Some(hex.clone());
                                entry.file.size_bytes = *size;
                                changed = true;
                            }
                            Err(e) => {
                                tracing::warn!("link file hashing failed: {e}");
                                // Drop it: a file we cannot read is not
                                // something a guest should see in the list.
                                let id = entry.file.file_id;
                                state.files.retain(|x| x.file.file_id != id);
                                changed = true;
                            }
                        }
                    }
                }
                if changed {
                    if let Some(session) = core.link_session() {
                        core.emit(CoreEvent::LinkSessionChanged {
                            session: Some(session),
                        });
                    }
                }
            }
        });
    }
}

/// Build a session entry from a path (metadata only — no hashing here).
fn link_entry(path: &std::path::Path) -> anyhow::Result<LinkEntry> {
    let meta = std::fs::metadata(path)
        .map_err(|e| anyhow::anyhow!("cannot access {}: {e}", path.display()))?;
    if !meta.is_file() {
        anyhow::bail!("not a file: {}", path.display());
    }
    let file_name = crate::filename::sanitize(
        path.file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("file"),
    );
    Ok(LinkEntry {
        file: LinkFile {
            file_id: Uuid::new_v4(),
            file_name,
            size_bytes: meta.len(),
            mime_type: mime_guess::from_path(path)
                .first_raw()
                .unwrap_or("application/octet-stream")
                .to_string(),
            sha256: None,
        },
        path: path.to_path_buf(),
    })
}

// ---------------------------------------------------------------------------
// Guest routes (§14.2) — mounted OUTSIDE the bearer-auth layer on purpose:
// the unguessable token in the path is the credential.
// ---------------------------------------------------------------------------

pub(crate) fn guest_router() -> Router<Arc<Core>> {
    Router::new()
        // Without the trailing slash the page's relative fetches would
        // resolve one level too high, so bounce to the canonical form.
        .route("/link/:token", get(guest_index_redirect))
        .route("/link/:token/", get(guest_index))
        .route("/link/:token/api/session", get(guest_session))
        .route("/link/:token/api/file/:file_id", get(guest_file))
        .route(
            "/link/:token/api/upload",
            post(guest_upload).layer(DefaultBodyLimit::disable()),
        )
}

fn gone() -> Response {
    api_error(StatusCode::GONE, "gone", Some("this link is no longer active"))
}

fn unknown() -> Response {
    api_error(StatusCode::NOT_FOUND, "not_found", None)
}

/// Resolve the path token: `None` when the session is live, otherwise the
/// refusal to send back.
fn reject_token(core: &Core, token: &str) -> Option<Response> {
    // A wrong-length token cannot match anything; answering it exactly like
    // an unknown one keeps the two indistinguishable.
    if token.len() != TOKEN_CHARS {
        return Some(unknown());
    }
    match core.lookup_token(token) {
        TokenLookup::Active => None,
        TokenLookup::Retired => Some(gone()),
        TokenLookup::Unknown => Some(unknown()),
    }
}

async fn guest_index_redirect(Path(token): Path<String>) -> Response {
    // Same-origin path only — the token never leaves the host this way.
    match HeaderValue::from_str(&format!("/link/{token}/")) {
        Ok(location) => {
            let mut resp = StatusCode::PERMANENT_REDIRECT.into_response();
            resp.headers_mut().insert(header::LOCATION, location);
            resp
        }
        Err(_) => unknown(),
    }
}

async fn guest_index(State(core): State<Arc<Core>>, Path(token): Path<String>) -> Response {
    let Some(_slot) = acquire_guest_slot(&core) else {
        return too_many_guests();
    };
    if let Some(resp) = reject_token(&core, &token) {
        return resp;
    }
    let mut resp = Response::new(Body::from(GUEST_PAGE));
    let headers = resp.headers_mut();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("text/html; charset=utf-8"),
    );
    // The URL carries the credential: keep it out of caches and out of any
    // referrer a guest's browser might send (§14.3).
    headers.insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static("no-store, private"),
    );
    headers.insert("referrer-policy", HeaderValue::from_static("no-referrer"));
    resp
}

/// `GET /link/<t>/api/session` — exactly the §14.2 shape.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct GuestSessionResponse {
    host_name: String,
    expires_at_ms: i64,
    allow_upload: bool,
    files: Vec<LinkFile>,
}

async fn guest_session(State(core): State<Arc<Core>>, Path(token): Path<String>) -> Response {
    let Some(_slot) = acquire_guest_slot(&core) else {
        return too_many_guests();
    };
    if let Some(resp) = reject_token(&core, &token) {
        return resp;
    }
    let Some(session) = core.link_session() else {
        return gone();
    };
    let mut resp = Json(GuestSessionResponse {
        host_name: core.settings.read().device_name.clone(),
        expires_at_ms: session.expires_at_ms,
        allow_upload: session.allow_upload,
        files: session.files,
    })
    .into_response();
    resp.headers_mut().insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static("no-store, private"),
    );
    resp
}

/// `GET /link/<t>/api/file/<fileId>` — same bytes semantics as §6.4
/// (identity encoding, Range/If-Range, RFC 5987 disposition, ETag = sha256).
/// It goes through the very same [`serve_file`] used by the paired download
/// path; there is no second file-serving implementation.
async fn guest_file(
    State(core): State<Arc<Core>>,
    Path((token, file_id)): Path<(String, String)>,
    headers: HeaderMap,
) -> Response {
    let Some(slot) = acquire_guest_slot(&core) else {
        return too_many_guests();
    };
    if let Some(resp) = reject_token(&core, &token) {
        return resp;
    }
    // fileId → path, by equality on a UUID. There is no name or path in the
    // request, so traversal is not expressible.
    let Ok(file_id) = file_id.parse::<Uuid>() else {
        return unknown();
    };
    let spec = {
        let slot = core.link.read();
        let Some(state) = slot.as_ref() else {
            return gone();
        };
        let Some(entry) = state.files.iter().find(|e| e.file.file_id == file_id) else {
            return unknown();
        };
        FileServe {
            path: entry.path.clone(),
            size: entry.file.size_bytes,
            sha256: entry.file.sha256.clone(),
            mime_type: entry.file.mime_type.clone(),
            file_name: entry.file.file_name.clone(),
        }
    };
    // The slot moves into the body stream: it is released when the transfer
    // actually finishes, not when the handler returns.
    match serve_file(spec, &headers, None, Some(slot)).await {
        Ok(resp) => resp,
        Err(failure) => failure.response,
    }
}

/// `POST /link/<t>/api/upload` — raw body, `X-Sendro-File-Name` (RFC 5987),
/// optional `X-Sendro-Sha256`. Streams to the receive folder exactly like §7.
async fn guest_upload(
    State(core): State<Arc<Core>>,
    Path(token): Path<String>,
    headers: HeaderMap,
    body: Body,
) -> Response {
    let Some(_slot) = acquire_guest_slot(&core) else {
        return too_many_guests();
    };
    if let Some(resp) = reject_token(&core, &token) {
        return resp;
    }

    // Reserve one of the 200 upload slots before touching the body.
    {
        let mut slot = core.link.write();
        let Some(state) = slot.as_mut() else {
            return gone();
        };
        if !state.allow_upload {
            return api_error(
                StatusCode::FORBIDDEN,
                "unauthorized",
                Some("uploads are disabled for this session"),
            );
        }
        if state.upload_attempts >= MAX_GUEST_UPLOADS {
            return api_error(
                StatusCode::TOO_MANY_REQUESTS,
                "rate_limited",
                Some("upload limit reached for this session"),
            );
        }
        state.upload_attempts += 1;
    }

    let outcome = receive_upload(
        &core,
        UploadPeer {
            device_id: Uuid::nil(),
            device_name: GUEST_PEER_NAME.to_string(),
            // A browser on a plain-http origin may have no crypto.subtle, so
            // the hash header is optional here (§14.2). When it is absent we
            // simply do not claim the file was verified.
            require_hash: false,
        },
        &headers,
        body,
    )
    .await;

    if let Some(saved) = outcome.saved {
        let session = {
            let mut slot = core.link.write();
            match slot.as_mut() {
                Some(state) => {
                    state.guest_uploads += 1;
                    Some(state.snapshot(&core))
                }
                None => None,
            }
        };
        tracing::info!(
            "guest upload received via link {}…: {} bytes",
            token_prefix(&token),
            saved.size_bytes
        );
        core.emit(CoreEvent::GuestUpload {
            file_name: saved.file_name,
            size_bytes: saved.size_bytes,
        });
        if session.is_some() {
            core.emit(CoreEvent::LinkSessionChanged { session });
        }
    }
    outcome.response
}

/// Shared guest-connection counter, held by [`Core`].
pub(crate) fn new_conn_counter() -> Arc<AtomicUsize> {
    Arc::new(AtomicUsize::new(0))
}

/// Bounded FIFO of retired tokens.
pub(crate) fn new_retired_store() -> VecDeque<String> {
    VecDeque::with_capacity(RETIRED_TOKENS_CAP)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn token_shape_and_compare() {
        let a = mint_token();
        let b = mint_token();
        assert_eq!(a.len(), TOKEN_CHARS);
        assert_ne!(a, b);
        assert!(token_eq(&a, &a.clone()));
        assert!(!token_eq(&a, &b));
        assert!(!token_eq(&a, "short"));
        assert_eq!(token_prefix(&a).len(), 6);
    }
}
