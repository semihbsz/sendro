//! HTTP API (axum) — PROTOCOL.md §4–§7.

use std::io::SeekFrom;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};
use std::time::{Duration, Instant};

use axum::body::{Body, Bytes};
use axum::extract::{DefaultBodyLimit, Path, Query, State};
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::middleware;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Extension, Json, Router};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use futures::Stream;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
use tokio_util::io::ReaderStream;
use uuid::Uuid;

use crate::auth::AuthedDevice;
use crate::filename;
use crate::hashing::CHUNK_SIZE;
use crate::pairing::{ConfirmOutcome, StartError};
use crate::range::{parse_range, RangeParse};
use crate::state::StoredDevice;
use crate::transfers::TransferError;
use crate::types::*;
use crate::{Core, CoreEvent};

const MAX_OUTBOX_WAIT_SECS: u64 = 30;

/// JSON envelope slack on top of the 32 KiB text limit (§11): escaping can
/// inflate the encoded text, so the body is allowed to be a little larger
/// before it is refused outright.
const MAX_MESSAGE_BODY_BYTES: usize = crate::messages::MAX_MESSAGE_BYTES * 8;

pub fn build_router(core: Arc<Core>) -> Router {
    let open = Router::new()
        .route("/api/v1/info", get(info))
        .route("/api/v1/pair/start", post(pair_start))
        .route("/api/v1/pair/confirm", post(pair_confirm));

    let authed = Router::new()
        .route("/api/v1/ping", get(ping))
        .route("/api/v1/outbox", get(outbox))
        .route("/api/v1/transfers/:id/accept", post(accept_transfer))
        .route("/api/v1/transfers/:id/reject", post(reject_transfer))
        .route("/api/v1/transfers/:id/status", post(report_status))
        .route("/api/v1/transfers/:id/file", get(download_file))
        .route("/api/v1/messages", post(post_message))
        .route(
            "/api/v1/upload",
            post(upload).layer(DefaultBodyLimit::disable()),
        )
        .route_layer(middleware::from_fn_with_state(
            core.clone(),
            crate::auth::require_auth,
        ));

    // §14 guest routes are mounted on the SAME router but deliberately
    // OUTSIDE the bearer-auth layer: the unguessable token in the path is
    // the credential. See link.rs for the checks that replace it.
    open.merge(authed)
        .merge(crate::link::guest_router())
        .fallback(|| async { api_error(StatusCode::NOT_FOUND, "not_found", None) })
        .with_state(core)
}

/// Build a protocol error response: status + `{"error":"code","message":..}`.
pub fn api_error(status: StatusCode, code: &str, message: Option<&str>) -> Response {
    let body = ErrorBody {
        error: code.to_string(),
        message: message.map(str::to_string),
    };
    (status, Json(body)).into_response()
}

fn transfer_error(e: TransferError) -> Response {
    match e {
        TransferError::NotFound => api_error(StatusCode::NOT_FOUND, "not_found", None),
        TransferError::Conflict => {
            api_error(StatusCode::CONFLICT, "conflict", Some("invalid state"))
        }
        TransferError::BadRequest => api_error(StatusCode::BAD_REQUEST, "bad_request", None),
    }
}

// ---------------------------------------------------------------------------
// §5 info
// ---------------------------------------------------------------------------

async fn info(State(core): State<Arc<Core>>) -> Json<InfoResponse> {
    let host = core.info();
    Json(InfoResponse {
        app: APP_NAME.to_string(),
        protocol_version: PROTOCOL_VERSION,
        device_id: host.device_id,
        device_name: host.device_name,
        platform: host.platform,
        api_port: host.api_port,
    })
}

// ---------------------------------------------------------------------------
// §4 pairing
// ---------------------------------------------------------------------------

async fn pair_start(
    State(core): State<Arc<Core>>,
    Json(req): Json<PairStartRequest>,
) -> Response {
    if req.protocol_version != PROTOCOL_VERSION {
        return api_error(
            StatusCode::BAD_REQUEST,
            "bad_request",
            Some("unsupported protocolVersion"),
        );
    }
    if req.device_name.trim().is_empty() {
        return api_error(StatusCode::BAD_REQUEST, "bad_request", Some("deviceName"));
    }
    match core
        .pairings
        .start(req.device_id, req.device_name.clone(), req.platform)
    {
        Ok(session) => {
            core.emit(CoreEvent::PairingStarted {
                pairing_id: session.pairing_id,
                code: session.code.clone(),
                device_name: session.device_name.clone(),
            });
            Json(PairStartResponse {
                pairing_id: session.pairing_id,
                salt: URL_SAFE_NO_PAD.encode(session.salt),
                expires_in_seconds: crate::pairing::PAIRING_TTL.as_secs(),
            })
            .into_response()
        }
        Err(StartError::TooManySessions) => api_error(
            StatusCode::TOO_MANY_REQUESTS,
            "rate_limited",
            Some("too many concurrent pairing sessions"),
        ),
    }
}

async fn pair_confirm(
    State(core): State<Arc<Core>>,
    Json(req): Json<PairConfirmRequest>,
) -> Response {
    match core
        .pairings
        .confirm(req.pairing_id, req.device_id, &req.proof)
    {
        ConfirmOutcome::Success {
            device_id,
            device_name,
            platform,
        } => {
            // A QR session (§13) carries no client identity — the scanning
            // device introduces itself here instead. A typed session already
            // has one from pair/start and keeps it.
            let device_name = if device_name.trim().is_empty() {
                req.device_name
                    .map(|n| n.trim().to_string())
                    .filter(|n| !n.is_empty())
                    .unwrap_or_else(|| crate::qr::QR_FALLBACK_DEVICE_NAME.to_string())
            } else {
                device_name
            };
            let platform = if platform.trim().is_empty() {
                req.platform
                    .map(|p| p.trim().to_string())
                    .filter(|p| !p.is_empty())
                    .unwrap_or_else(|| "ios".to_string())
            } else {
                platform
            };
            let token = crate::pairing::mint_device_token();
            let stored = StoredDevice {
                device_id,
                device_name,
                platform,
                paired_at_ms: now_ms(),
                last_seen_ms: Some(now_ms()),
                token_sha256: crate::auth::token_hash(&token),
            };
            let public = stored.public();
            core.add_trusted_device(stored);
            core.emit(CoreEvent::PairingCompleted {
                device: public,
            });
            let host = core.info();
            Json(PairConfirmResponse {
                device_token: token,
                host: PairHostInfo {
                    device_id: host.device_id,
                    device_name: host.device_name,
                    platform: host.platform,
                },
            })
            .into_response()
        }
        ConfirmOutcome::BadSession => api_error(
            StatusCode::BAD_REQUEST,
            "expired",
            Some("unknown or expired pairing session"),
        ),
        ConfirmOutcome::WrongProof { .. } => {
            api_error(StatusCode::FORBIDDEN, "bad_request", Some("wrong proof"))
        }
        ConfirmOutcome::TooManyAttempts { pairing_id } => {
            core.emit(CoreEvent::PairingFailed { pairing_id });
            api_error(
                StatusCode::TOO_MANY_REQUESTS,
                "rate_limited",
                Some("too many attempts"),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// §4.3 ping
// ---------------------------------------------------------------------------

async fn ping(State(core): State<Arc<Core>>) -> Json<PingResponse> {
    Json(PingResponse {
        ok: true,
        device_name: core.settings.read().device_name.clone(),
    })
}

// ---------------------------------------------------------------------------
// §6.2 outbox long-poll
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct OutboxQuery {
    #[serde(default)]
    wait_seconds: Option<u64>,
}

async fn outbox(
    State(core): State<Arc<Core>>,
    Extension(device): Extension<AuthedDevice>,
    Query(q): Query<OutboxQuery>,
) -> Json<OutboxResponse> {
    let wait = Duration::from_secs(q.wait_seconds.unwrap_or(0).min(MAX_OUTBOX_WAIT_SECS));
    let deadline = Instant::now() + wait;
    let notify = core.notify_handle(device.device_id);
    loop {
        // Draining an empty inbox is a no-op, so it is safe to do this on
        // every pass; a non-empty drain means we must return right away
        // (delivery is at-most-once — §11.1).
        let offers = core.pending_offers_for(device.device_id);
        let messages = core.drain_messages_for(device.device_id);
        if !offers.is_empty() || !messages.is_empty() {
            return Json(OutboxResponse { offers, messages });
        }
        let now = Instant::now();
        if now >= deadline {
            return Json(OutboxResponse {
                offers: Vec::new(),
                messages: Vec::new(),
            });
        }
        // Arm the waiter *before* re-checking so a publish between the check
        // above and the await below still wakes us.
        let notified = notify.notified();
        tokio::select! {
            _ = notified => {}
            _ = tokio::time::sleep(deadline - now) => {
                let offers = core.pending_offers_for(device.device_id);
                let messages = core.drain_messages_for(device.device_id);
                return Json(OutboxResponse { offers, messages });
            }
        }
    }
}

// ---------------------------------------------------------------------------
// §11.2 messages (iPhone → Windows), ephemeral
// ---------------------------------------------------------------------------

/// `POST /api/v1/messages` — accept a short text payload from a paired
/// device. The body is parsed by hand so the 32 KiB limit answers with the
/// protocol's `413 {"error":"bad_request","message":"message too long"}`
/// instead of a generic extractor rejection. Contents are never logged.
async fn post_message(
    State(core): State<Arc<Core>>,
    Extension(device): Extension<AuthedDevice>,
    body: Bytes,
) -> Response {
    if body.len() > MAX_MESSAGE_BODY_BYTES {
        return api_error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "bad_request",
            Some("message too long"),
        );
    }
    let Ok(req) = serde_json::from_slice::<SendMessageRequest>(&body) else {
        return api_error(
            StatusCode::BAD_REQUEST,
            "bad_request",
            Some("expected {\"text\": \"...\"}"),
        );
    };
    if req.text.len() > crate::messages::MAX_MESSAGE_BYTES {
        return api_error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "bad_request",
            Some("message too long"),
        );
    }
    core.receive_message(device.device_name.clone(), req.text);
    Json(OkResponse { ok: true }).into_response()
}

// ---------------------------------------------------------------------------
// §6.3 accept / reject, §6.5 status
// ---------------------------------------------------------------------------

async fn accept_transfer(
    State(core): State<Arc<Core>>,
    Extension(device): Extension<AuthedDevice>,
    Path(id): Path<Uuid>,
) -> Response {
    match core.accept_transfer(id, device.device_id) {
        Ok(()) => Json(OkResponse { ok: true }).into_response(),
        Err(e) => transfer_error(e),
    }
}

async fn reject_transfer(
    State(core): State<Arc<Core>>,
    Extension(device): Extension<AuthedDevice>,
    Path(id): Path<Uuid>,
) -> Response {
    match core.reject_transfer(id, device.device_id) {
        Ok(()) => Json(OkResponse { ok: true }).into_response(),
        Err(e) => transfer_error(e),
    }
}

async fn report_status(
    State(core): State<Arc<Core>>,
    Extension(device): Extension<AuthedDevice>,
    Path(id): Path<Uuid>,
    Json(report): Json<StatusReport>,
) -> Response {
    match core.report_status(
        id,
        device.device_id,
        &report.state,
        report.bytes_received,
        report.error,
    ) {
        Ok(()) => Json(OkResponse { ok: true }).into_response(),
        Err(e) => transfer_error(e),
    }
}

// ---------------------------------------------------------------------------
// §6.4 download
// ---------------------------------------------------------------------------

/// Who to report streaming progress to. `None` for a §14 guest download:
/// nothing a guest reads touches the transfer state machine.
pub(crate) struct TransferTracking {
    pub core: Arc<Core>,
    pub id: Uuid,
}

/// Body stream that counts served bytes, feeds progress/speed to the core,
/// and flips the transfer to Interrupted if the client goes away mid-stream.
struct CountingStream {
    inner: ReaderStream<tokio::io::Take<tokio::fs::File>>,
    tracking: Option<TransferTracking>,
    /// Guest-connection slot (§14.2). Held here — not by the handler — so it
    /// is released when the body really finishes, not when the handler
    /// returns.
    _guest_slot: Option<crate::link::GuestSlot>,
    start_offset: u64,
    served: u64,
    expected: u64,
    ended: bool,
}

impl CountingStream {
    fn finish(&mut self, completed: bool) {
        if !self.ended {
            self.ended = true;
            if let Some(t) = &self.tracking {
                t.core.note_stream_end(t.id, completed);
            }
        }
    }
}

impl Stream for CountingStream {
    type Item = std::io::Result<Bytes>;

    fn poll_next(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        match Pin::new(&mut self.inner).poll_next(cx) {
            Poll::Ready(Some(Ok(chunk))) => {
                self.served += chunk.len() as u64;
                let pos = self.start_offset + self.served;
                if let Some(t) = &self.tracking {
                    t.core.note_bytes_served(t.id, pos, chunk.len() as u64);
                }
                Poll::Ready(Some(Ok(chunk)))
            }
            Poll::Ready(Some(Err(e))) => {
                self.finish(false);
                Poll::Ready(Some(Err(e)))
            }
            Poll::Ready(None) => {
                let completed = self.served >= self.expected;
                self.finish(completed);
                Poll::Ready(None)
            }
            Poll::Pending => Poll::Pending,
        }
    }
}

impl Drop for CountingStream {
    fn drop(&mut self) {
        // Dropped before the body was fully polled → client disconnected.
        self.finish(false);
    }
}

/// RAII guard for the concurrency slot in case we bail before the stream
/// takes ownership of the accounting.
struct SlotGuard {
    core: Arc<Core>,
    id: Uuid,
    armed: bool,
}

impl SlotGuard {
    fn disarm(mut self) {
        self.armed = false;
    }
}

impl Drop for SlotGuard {
    fn drop(&mut self) {
        if self.armed {
            self.core.note_stream_end(self.id, false);
        }
    }
}

async fn download_file(
    State(core): State<Arc<Core>>,
    Extension(device): Extension<AuthedDevice>,
    Path(id): Path<Uuid>,
    headers: HeaderMap,
) -> Response {
    // Global pause gate: new chunk requests are refused while paused; the
    // iOS client backs off per Retry-After and resumes with a ranged
    // request once unpaused.
    if core.is_paused() {
        let mut resp = api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "rate_limited",
            Some("transfers paused"),
        );
        resp.headers_mut()
            .insert(header::RETRY_AFTER, HeaderValue::from_static("5"));
        return resp;
    }

    // Lookup + authorization: only the device the transfer was offered to,
    // only in Accepted / Transferring / Interrupted.
    let (source_path, size, sha256, mime_type, file_name) = {
        let transfers = core.transfers.read();
        // `!is_peer`: a §7 push of ours to this same device is not something
        // it may download from us (peers.rs / transfers.rs `is_peer`).
        let Some(rec) = transfers
            .get(&id)
            .filter(|r| r.device_id == device.device_id && !r.is_peer)
        else {
            return api_error(StatusCode::NOT_FOUND, "not_found", None);
        };
        match rec.state {
            TransferState::Accepted
            | TransferState::Transferring
            | TransferState::Interrupted => {}
            TransferState::Expired => {
                return api_error(StatusCode::GONE, "gone", Some("offer expired"))
            }
            _ => {
                return api_error(
                    StatusCode::CONFLICT,
                    "conflict",
                    Some("transfer not downloadable in its current state"),
                )
            }
        }
        let (Some(path), Some(sha)) = (rec.source_path.clone(), rec.sha256.clone()) else {
            return api_error(StatusCode::CONFLICT, "conflict", Some("not ready"));
        };
        (
            path,
            rec.size_bytes,
            sha,
            rec.mime_type.clone(),
            rec.file_name.clone(),
        )
    };

    // Concurrency gate: at most `concurrency` transfers streaming at once.
    // The iOS client retries on 503 + Retry-After.
    if !core.try_acquire_stream(id) {
        let mut resp = api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "rate_limited",
            Some("transfer slots busy"),
        );
        resp.headers_mut()
            .insert(header::RETRY_AFTER, HeaderValue::from_static("2"));
        return resp;
    }
    let slot = SlotGuard {
        core: core.clone(),
        id,
        armed: true,
    };

    let spec = FileServe {
        path: source_path,
        size,
        sha256: Some(sha256),
        mime_type,
        file_name,
    };
    match serve_file(
        spec,
        &headers,
        Some(TransferTracking {
            core: core.clone(),
            id,
        }),
        None,
    )
    .await
    {
        Ok(resp) => {
            // The body stream now owns the slot accounting (note_stream_end).
            slot.disarm();
            resp
        }
        Err(failure) => {
            drop(slot); // releases the concurrency slot
            if let Some(msg) = failure.source_error {
                core.mark_failed(id, &msg);
            }
            failure.response
        }
    }
}

/// Everything needed to stream one file to a client, from either the §6.4
/// paired path or the §14.2 guest path.
pub(crate) struct FileServe {
    pub path: std::path::PathBuf,
    pub size: u64,
    /// ETag / `X-Sendro-Sha256` source. `None` only for a Link file whose
    /// background hash has not finished — we then omit both headers rather
    /// than publish a validator we cannot stand behind.
    pub sha256: Option<String>,
    pub mime_type: String,
    pub file_name: String,
}

/// A refusal from [`serve_file`], plus the message the caller should record
/// against its transfer (guests have nothing to record).
pub(crate) struct ServeFailure {
    pub response: Response,
    pub source_error: Option<String>,
}

/// The one and only file-serving implementation (PROTOCOL.md §6.4): identity
/// encoding, streamed from disk in [`CHUNK_SIZE`] chunks (never buffered),
/// mandatory single-range support with `If-Range`, `ETag` = sha256 and an
/// RFC 5987 `Content-Disposition`. §14.2 guest downloads go through here too.
pub(crate) async fn serve_file(
    spec: FileServe,
    headers: &HeaderMap,
    tracking: Option<TransferTracking>,
    guest_slot: Option<crate::link::GuestSlot>,
) -> Result<Response, ServeFailure> {
    let FileServe {
        path,
        size,
        sha256,
        mime_type,
        file_name,
    } = spec;

    // Open + validate the source file.
    let mut file = match tokio::fs::File::open(&path).await {
        Ok(f) => f,
        Err(e) => {
            return Err(ServeFailure {
                response: api_error(StatusCode::GONE, "gone", Some("source file unavailable")),
                source_error: Some(format!("source file unavailable: {e}")),
            })
        }
    };
    let size = match file.metadata().await {
        Ok(meta) if meta.len() == size => size,
        Ok(_) => {
            return Err(ServeFailure {
                response: api_error(StatusCode::GONE, "gone", Some("source file changed")),
                source_error: Some("source file changed on disk".to_string()),
            })
        }
        Err(e) => {
            return Err(ServeFailure {
                response: api_error(StatusCode::GONE, "gone", None),
                source_error: Some(format!("stat failed: {e}")),
            })
        }
    };

    let etag = sha256.as_ref().map(|sha| format!("\"{sha}\""));

    // If-Range: only honor the Range header when the validator matches the
    // current ETag; otherwise fall back to the full representation (RFC 9110).
    let mut range_header = headers
        .get(header::RANGE)
        .and_then(|v| v.to_str().ok());
    if let Some(if_range) = headers.get(header::IF_RANGE).and_then(|v| v.to_str().ok()) {
        // With no validator of our own (hash still pending) an If-Range can
        // never match, so the full representation is the correct answer.
        let matches = match (&etag, &sha256) {
            (Some(etag), Some(sha)) => if_range == etag || if_range == sha,
            _ => false,
        };
        if !matches {
            range_header = None;
        }
    }

    let (status, start, end) = match parse_range(range_header, size) {
        RangeParse::None => (StatusCode::OK, 0, size.saturating_sub(1)),
        RangeParse::Satisfiable { start, end } => (StatusCode::PARTIAL_CONTENT, start, end),
        RangeParse::Unsatisfiable => {
            let mut resp = api_error(
                StatusCode::RANGE_NOT_SATISFIABLE,
                "bad_request",
                Some("unsatisfiable range"),
            );
            if let Ok(v) = HeaderValue::from_str(&format!("bytes */{size}")) {
                resp.headers_mut().insert(header::CONTENT_RANGE, v);
            }
            return Err(ServeFailure {
                response: resp,
                source_error: None,
            });
        }
    };
    // Zero-byte file, no Range: serve an empty 200.
    let content_len = if size == 0 { 0 } else { end - start + 1 };

    if content_len > 0 {
        if let Err(e) = file.seek(SeekFrom::Start(start)).await {
            return Err(ServeFailure {
                response: api_error(StatusCode::GONE, "gone", None),
                source_error: Some(format!("seek failed: {e}")),
            });
        }
    }

    if let Some(t) = &tracking {
        t.core.mark_transferring(t.id, start);
    }

    let stream = CountingStream {
        inner: ReaderStream::with_capacity(file.take(content_len), CHUNK_SIZE),
        tracking,
        _guest_slot: guest_slot,
        start_offset: start,
        served: 0,
        expected: content_len,
        ended: false,
    };

    let mut resp = Response::new(Body::from_stream(stream));
    *resp.status_mut() = status;
    let headers_out = resp.headers_mut();
    headers_out.insert(
        header::CONTENT_LENGTH,
        HeaderValue::from_str(&content_len.to_string()).expect("numeric"),
    );
    headers_out.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_str(&mime_type)
            .unwrap_or_else(|_| HeaderValue::from_static("application/octet-stream")),
    );
    headers_out.insert(header::ACCEPT_RANGES, HeaderValue::from_static("bytes"));
    headers_out.insert(
        header::CONTENT_ENCODING,
        HeaderValue::from_static("identity"),
    );
    // Both validators are hex/quoted-hex, so they are always valid header
    // values; they are simply absent while a Link file is still hashing.
    if let (Some(etag), Some(sha)) = (&etag, &sha256) {
        if let (Ok(etag), Ok(sha)) = (HeaderValue::from_str(etag), HeaderValue::from_str(sha)) {
            headers_out.insert(header::ETAG, etag);
            headers_out.insert("x-sendro-sha256", sha);
        }
    }
    if let Ok(v) = HeaderValue::from_str(&filename::content_disposition(&file_name)) {
        headers_out.insert(header::CONTENT_DISPOSITION, v);
    }
    if status == StatusCode::PARTIAL_CONTENT {
        headers_out.insert(
            header::CONTENT_RANGE,
            HeaderValue::from_str(&format!("bytes {start}-{end}/{size}")).expect("numeric"),
        );
    }
    Ok(resp)
}

// ---------------------------------------------------------------------------
// §7 upload (iPhone → Windows), shared with §14.2 guest uploads
// ---------------------------------------------------------------------------

/// Who is uploading. §7 is a paired device; §14.2 is an anonymous guest of a
/// Link session (`device_id` = nil, `device_name` = "Guest (link)").
pub(crate) struct UploadPeer {
    pub device_id: Uuid,
    pub device_name: String,
    /// §7 mandates `X-Sendro-Sha256`. A §14 guest browser on a plain-http
    /// origin may have no `crypto.subtle`, so there the header is optional —
    /// and when it is absent the file is simply not marked verified.
    pub require_hash: bool,
}

/// What landed on disk when an upload succeeded.
pub(crate) struct SavedUpload {
    pub file_name: String,
    pub size_bytes: u64,
}

pub(crate) struct UploadOutcome {
    pub response: Response,
    pub saved: Option<SavedUpload>,
}

impl UploadOutcome {
    fn rejected(response: Response) -> Self {
        Self {
            response,
            saved: None,
        }
    }
}

/// `POST /api/v1/upload` — §7.
async fn upload(
    State(core): State<Arc<Core>>,
    Extension(device): Extension<AuthedDevice>,
    headers: HeaderMap,
    body: Body,
) -> Response {
    receive_upload(
        &core,
        UploadPeer {
            device_id: device.device_id,
            device_name: device.device_name.clone(),
            require_hash: true,
        },
        &headers,
        body,
    )
    .await
    .response
}

/// Stream an upload body to the receive folder: dot-prefixed temp file,
/// SHA-256 computed while writing, collision-safe rename into place, queue +
/// history entries for the UI. Never buffers a whole file.
pub(crate) async fn receive_upload(
    core: &Arc<Core>,
    peer: UploadPeer,
    headers: &HeaderMap,
    body: Body,
) -> UploadOutcome {
    let Some(raw_name) = headers
        .get("x-sendro-file-name")
        .and_then(|v| v.to_str().ok())
    else {
        return UploadOutcome::rejected(api_error(
            StatusCode::BAD_REQUEST,
            "bad_request",
            Some("missing X-Sendro-File-Name"),
        ));
    };
    let Some(decoded) = filename::rfc5987_decode(raw_name) else {
        return UploadOutcome::rejected(api_error(
            StatusCode::BAD_REQUEST,
            "bad_request",
            Some("invalid X-Sendro-File-Name encoding"),
        ));
    };
    let file_name = filename::sanitize(&decoded);

    let raw_sha = headers
        .get("x-sendro-sha256")
        .and_then(|v| v.to_str().ok())
        .map(|s| s.trim().to_ascii_lowercase())
        .filter(|s| !s.is_empty());
    // A present-but-malformed hash is always a bad request; a missing one is
    // only an error for §7.
    let expected_sha = match raw_sha {
        Some(sha) if sha.len() == 64 && sha.bytes().all(|b| b.is_ascii_hexdigit()) => Some(sha),
        Some(_) => {
            return UploadOutcome::rejected(api_error(
                StatusCode::BAD_REQUEST,
                "bad_request",
                Some("missing or malformed X-Sendro-Sha256"),
            ))
        }
        None if peer.require_hash => {
            return UploadOutcome::rejected(api_error(
                StatusCode::BAD_REQUEST,
                "bad_request",
                Some("missing or malformed X-Sendro-Sha256"),
            ))
        }
        None => None,
    };
    let declared_len: u64 = headers
        .get(header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse().ok())
        .unwrap_or(0);

    let receive_dir = core.receive_dir();
    if let Err(e) = tokio::fs::create_dir_all(&receive_dir).await {
        return UploadOutcome::rejected(api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "bad_request",
            Some(&format!("receive dir unavailable: {e}")),
        ));
    }

    // Track the incoming transfer in the queue for the UI.
    let transfer_id = Uuid::new_v4();
    {
        use crate::transfers::{SpeedWindow, TransferRecord};
        let rec = TransferRecord {
            transfer_id,
            batch_id: Uuid::new_v4(),
            file_id: Uuid::new_v4(),
            file_name: file_name.clone(),
            extension: std::path::Path::new(&file_name)
                .extension()
                .and_then(|e| e.to_str())
                .unwrap_or("")
                .to_string(),
            mime_type: mime_guess::from_path(&file_name)
                .first_raw()
                .unwrap_or("application/octet-stream")
                .to_string(),
            size_bytes: declared_len,
            sha256: expected_sha.clone(),
            created_at_ms: now_ms(),
            modified_at_ms: now_ms(),
            offered_at_ms: now_ms(),
            auto_accept: false,
            device_id: peer.device_id,
            device_name: peer.device_name.clone(),
            direction: crate::transfers::Direction::Incoming,
            state: TransferState::Transferring,
            error: None,
            bytes_transferred: 0,
            started_at_ms: Some(now_ms()),
            source_path: None,
            verified: false,
            seq: core.seq.fetch_add(1, std::sync::atomic::Ordering::Relaxed),
            speed: SpeedWindow::default(),
            last_emit: None,
            is_peer: false,
        };
        core.transfers.write().insert(transfer_id, rec);
    }
    core.emit_transfer(transfer_id, true);

    // Stream the raw body to a dot-prefixed temp file (invisible to watch
    // folders), hashing while writing — never buffering the whole file.
    let tmp_path = receive_dir.join(format!(".sendro-{transfer_id}.part"));
    let result = stream_upload_to_disk(
        core,
        transfer_id,
        body,
        &tmp_path,
        expected_sha.as_deref(),
    )
    .await;

    match result {
        Ok(received) => {
            // Verified (or accepted unverified — see UploadPeer::require_hash)
            // — move into place with collision-safe naming.
            {
                let mut transfers = core.transfers.write();
                if let Some(rec) = transfers.get_mut(&transfer_id) {
                    rec.state = TransferState::Saving;
                    rec.size_bytes = received;
                    rec.bytes_transferred = received;
                }
            }
            core.emit_transfer(transfer_id, true);

            let final_path = {
                let _guard = core.fs_lock.lock();
                let final_path = filename::unique_path(&receive_dir, &file_name);
                if let Err(e) = std::fs::rename(&tmp_path, &final_path) {
                    drop(_guard);
                    let _ = std::fs::remove_file(&tmp_path);
                    core.mark_failed(transfer_id, &format!("rename failed: {e}"));
                    return UploadOutcome::rejected(api_error(
                        StatusCode::INTERNAL_SERVER_ERROR,
                        "bad_request",
                        Some("could not finalize file"),
                    ));
                }
                final_path
            };
            let saved_name = final_path
                .file_name()
                .and_then(|n| n.to_str())
                .unwrap_or(&file_name)
                .to_string();
            {
                let mut transfers = core.transfers.write();
                if let Some(rec) = transfers.get_mut(&transfer_id) {
                    rec.state = TransferState::Completed;
                    // Only a hash we could actually check counts as verified.
                    rec.verified = expected_sha.is_some();
                    rec.source_path = Some(final_path.clone());
                    rec.file_name = saved_name.clone();
                }
            }
            core.record_history(transfer_id);
            core.emit_transfer(transfer_id, true);
            let response = Json(UploadResponse {
                ok: true,
                saved_path: final_path.to_string_lossy().into_owned(),
            })
            .into_response();
            UploadOutcome {
                response,
                saved: Some(SavedUpload {
                    file_name: saved_name,
                    size_bytes: received,
                }),
            }
        }
        Err(e) => {
            let _ = tokio::fs::remove_file(&tmp_path).await;
            UploadOutcome::rejected(match e {
                UploadError::Integrity => {
                    core.mark_failed(transfer_id, "IntegrityMismatch");
                    api_error(
                        StatusCode::UNPROCESSABLE_ENTITY,
                        "integrity",
                        Some("sha256 mismatch"),
                    )
                }
                UploadError::Io(msg) => {
                    core.mark_failed(transfer_id, &msg);
                    api_error(StatusCode::INTERNAL_SERVER_ERROR, "bad_request", Some(&msg))
                }
                UploadError::Body(msg) => {
                    core.mark_failed(transfer_id, &msg);
                    api_error(StatusCode::BAD_REQUEST, "bad_request", Some(&msg))
                }
            })
        }
    }
}

enum UploadError {
    Integrity,
    Io(String),
    Body(String),
}

/// Stream the request body to `tmp_path`, hashing while writing; when
/// `expected_sha` is present, verify against it (case-insensitive) and fail
/// the upload on a mismatch. Returns bytes received.
async fn stream_upload_to_disk(
    core: &Arc<Core>,
    transfer_id: Uuid,
    body: Body,
    tmp_path: &std::path::Path,
    expected_sha: Option<&str>,
) -> Result<u64, UploadError> {
    use futures::StreamExt;

    let file = tokio::fs::File::create(tmp_path)
        .await
        .map_err(|e| UploadError::Io(format!("create temp: {e}")))?;
    let mut writer = tokio::io::BufWriter::with_capacity(CHUNK_SIZE, file);
    let mut hasher = Sha256::new();
    let mut received: u64 = 0;
    let mut stream = body.into_data_stream();

    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| UploadError::Body(format!("body read: {e}")))?;
        hasher.update(&chunk);
        received += chunk.len() as u64;
        writer
            .write_all(&chunk)
            .await
            .map_err(|e| UploadError::Io(format!("write: {e}")))?;
        {
            let mut transfers = core.transfers.write();
            if let Some(rec) = transfers.get_mut(&transfer_id) {
                rec.bytes_transferred = received;
                rec.size_bytes = rec.size_bytes.max(received);
                rec.speed.push(chunk.len() as u64);
            }
        }
        core.emit_transfer(transfer_id, false);
    }
    writer
        .flush()
        .await
        .map_err(|e| UploadError::Io(format!("flush: {e}")))?;
    writer
        .into_inner()
        .sync_all()
        .await
        .map_err(|e| UploadError::Io(format!("sync: {e}")))?;

    let actual = hex::encode(hasher.finalize());
    match expected_sha {
        Some(expected) => {
            // Verifying phase (hash already computed incrementally).
            {
                let mut transfers = core.transfers.write();
                if let Some(rec) = transfers.get_mut(&transfer_id) {
                    rec.state = TransferState::Verifying;
                }
            }
            core.emit_transfer(transfer_id, true);
            if actual != expected {
                return Err(UploadError::Integrity);
            }
        }
        None => {
            // Nothing to verify against (§14.2 guest without crypto.subtle):
            // record what we computed, but never claim it was verified.
            let mut transfers = core.transfers.write();
            if let Some(rec) = transfers.get_mut(&transfer_id) {
                rec.sha256 = Some(actual);
            }
        }
    }
    Ok(received)
}
