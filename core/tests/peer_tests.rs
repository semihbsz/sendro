//! The PC as a **client** (PROTOCOL.md §4/§7/§11.2/§15): discovery hygiene,
//! outbound pairing, and pushing files to a peer host.
//!
//! The centrepiece is `pc_pairs_with_a_peer_host_then_uploads_verified_bytes`,
//! which runs two real Cores in one process — one hosting, one acting as the
//! PC-side client — and moves a multi-chunk file between them over real HTTP.
//! If that passes, PC → peer works end to end: pairing proof, bearer auth,
//! RFC 5987 filename, streamed body, receiver-side SHA-256 verification, and
//! both queues.

mod common;

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use parking_lot::Mutex;
use sendro_core::{CoreEvent, TransferState};
use uuid::Uuid;

use common::{start_core, start_core_with, TestCtx};

const UNICODE_NAME: &str = "Çekmeköy Reşadiye Drone.MOV";

/// Deterministic pseudo-random payload spanning several 1 MiB chunks.
fn payload(len: usize) -> Vec<u8> {
    let mut out = Vec::with_capacity(len);
    let mut x: u32 = 0x5EED_1234;
    while out.len() < len {
        x = x.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
        out.extend_from_slice(&x.to_le_bytes());
    }
    out.truncate(len);
    out
}

fn sha256_hex(bytes: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    hex::encode(Sha256::digest(bytes))
}

/// Pair `pc` (as client) with `peer` (as host), reading the 6-digit code off
/// the peer's own event stream exactly like a user reads it off a TV screen.
async fn pair(pc: &TestCtx, peer: &TestCtx) -> sendro_core::PairedPeer {
    let mut events = peer.core.subscribe();
    let session = pc
        .core
        .pair_with_peer("127.0.0.1".to_string(), peer.core.info().api_port)
        .await
        .expect("pair_with_peer");

    let code = tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            match events.recv().await.expect("event stream") {
                CoreEvent::PairingStarted { code, .. } => break code,
                _ => continue,
            }
        }
    })
    .await
    .expect("the peer shows a code");

    pc.core
        .confirm_peer_pairing(session.pairing_id.to_string(), code)
        .await
        .expect("confirm_peer_pairing")
}

// ---------------------------------------------------------------------------
// The one that matters: PC ⇄ peer, end to end, over real HTTP
// ---------------------------------------------------------------------------

#[tokio::test]
async fn pc_pairs_with_a_peer_host_then_uploads_verified_bytes() {
    let peer = start_core_with(|cfg| cfg.device_name = "Living Room TV".to_string()).await;
    let pc = start_core_with(|cfg| cfg.device_name = "Semih-PC".to_string()).await;

    // --- §4 pairing, PC as the client -------------------------------------
    let paired = pair(&pc, &peer).await;
    assert_eq!(paired.device_id, peer.core.info().device_id);
    assert_eq!(paired.device_name, "Living Room TV");
    assert_eq!(paired.port, peer.core.info().api_port);
    assert!(
        !paired.receive_only,
        "a full host serves an outbox, so it is not receive-only"
    );

    // Outbound trust is stored on the PC…
    let peers = pc.core.paired_peers();
    assert_eq!(peers.len(), 1);
    assert_eq!(peers[0].device_id, paired.device_id);
    // …and the mirror-image inbound trust on the peer, with our identity.
    let trusted = peer.core.trusted_devices();
    assert_eq!(trusted.len(), 1);
    assert_eq!(trusted[0].device_id, pc.core.info().device_id);
    assert_eq!(trusted[0].device_name, "Semih-PC");
    assert_eq!(trusted[0].platform, "windows");

    // §4.3: the stored token really authenticates.
    assert!(pc.core.ping_peer(paired.device_id).await, "ping_peer");

    // --- §7 upload --------------------------------------------------------
    // 3 MiB + a tail, so the body spans several 1 MiB chunks and does not end
    // on a chunk boundary.
    let bytes = payload(3 * 1024 * 1024 + 7777);
    let expected_sha = sha256_hex(&bytes);
    let source = pc.data_dir.path().join(UNICODE_NAME);
    std::fs::write(&source, &bytes).expect("write source file");

    let summaries = pc
        .core
        .send_files_to_peer(paired.device_id, vec![source.clone()])
        .await
        .expect("send_files_to_peer");

    assert_eq!(summaries.len(), 1);
    let sent = &summaries[0];
    assert_eq!(sent.state, TransferState::Completed, "{:?}", sent.error);
    assert_eq!(sent.direction, "outgoing");
    assert_eq!(sent.device_name, "Living Room TV");
    assert_eq!(sent.file_name, UNICODE_NAME, "§8 name survives RFC 5987");
    assert_eq!(sent.size_bytes, bytes.len() as u64);
    assert_eq!(sent.bytes_transferred, bytes.len() as u64);
    assert_eq!(sent.sha256.as_deref(), Some(expected_sha.as_str()));

    // --- byte-for-byte on the receiving side ------------------------------
    let landed = peer.recv_dir.path().join(UNICODE_NAME);
    assert!(landed.exists(), "the file landed under its original name");
    let received = std::fs::read(&landed).expect("read received file");
    assert_eq!(received.len(), bytes.len(), "size matches");
    assert!(received == bytes, "bytes are identical");
    assert_eq!(sha256_hex(&received), expected_sha, "hash matches");

    // The receiver verified it itself (§7/§15.4) and wrote history.
    let peer_history = peer.core.history();
    assert_eq!(peer_history.len(), 1);
    assert_eq!(peer_history[0].direction, "incoming");
    assert_eq!(peer_history[0].peer_name, "Semih-PC");
    assert!(peer_history[0].verified, "receiver verified the SHA-256");
    assert_eq!(peer_history[0].final_state, TransferState::Completed);

    // …and it shows up in the PC's own queue + history as an outgoing send.
    let queued = pc
        .core
        .queue()
        .into_iter()
        .find(|t| t.transfer_id == sent.transfer_id)
        .expect("in the PC queue");
    assert_eq!(queued.state, TransferState::Completed);
    let pc_history = pc.core.history();
    assert_eq!(pc_history.len(), 1);
    assert_eq!(pc_history[0].direction, "outgoing");
    assert_eq!(pc_history[0].peer_name, "Living Room TV");
    assert!(pc_history[0].verified);

    // --- §11.2 text to the same peer --------------------------------------
    pc.core
        .send_message_to_peer(paired.device_id, "one two three".to_string())
        .await
        .expect("send_message_to_peer");
    let inbox = peer.core.incoming_messages();
    assert_eq!(inbox.len(), 1);
    assert_eq!(inbox[0].text, "one two three");
    assert_eq!(inbox[0].sender_name, "Semih-PC");

    pc.core.shutdown().await;
    peer.core.shutdown().await;
}

#[tokio::test]
async fn a_peer_push_never_leaks_into_that_peers_outbox() {
    // The dangerous case: the same device is paired in *both* directions, so
    // our outgoing push carries a deviceId that also has an outbox with us.
    let peer = start_core_with(|cfg| cfg.device_name = "Semih's Pixel".to_string()).await;
    let pc = start_core().await;
    let paired = pair(&pc, &peer).await;

    // Pair the other way too: the peer becomes a trusted device of the PC.
    let (_phone_id, phone_token) = common::pair_device(&pc).await;

    let source = pc.data_dir.path().join("clip.bin");
    std::fs::write(&source, payload(64 * 1024)).expect("write");
    pc.core
        .send_files_to_peer(paired.device_id, vec![source])
        .await
        .expect("send_files_to_peer");

    // The PC's outbox must be empty: a §7 push is not an offer.
    let outbox: serde_json::Value = pc
        .client
        .get(pc.url("/api/v1/outbox?waitSeconds=0"))
        .bearer_auth(&phone_token)
        .send()
        .await
        .expect("outbox")
        .json()
        .await
        .expect("outbox json");
    assert_eq!(
        outbox["offers"].as_array().map(|a| a.len()),
        Some(0),
        "a peer upload must never be published as an offer"
    );

    // Nor is it re-offerable through the host-side retry path.
    let id = pc.core.queue()[0].transfer_id;
    assert!(!pc.core.retry_transfer(id));

    pc.core.shutdown().await;
    peer.core.shutdown().await;
}

// ---------------------------------------------------------------------------
// Storage of outbound trust
// ---------------------------------------------------------------------------

#[tokio::test]
async fn peers_json_holds_the_token_but_never_the_pairing_code() {
    let peer = start_core_with(|cfg| cfg.device_name = "Living Room TV".to_string()).await;
    let pc = start_core().await;

    // Capture the code the same way the user sees it, so we can look for it.
    let mut events = peer.core.subscribe();
    let session = pc
        .core
        .pair_with_peer("127.0.0.1".to_string(), peer.core.info().api_port)
        .await
        .expect("pair_with_peer");
    let code = tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            if let CoreEvent::PairingStarted { code, .. } =
                events.recv().await.expect("event stream")
            {
                break code;
            }
        }
    })
    .await
    .expect("code");
    pc.core
        .confirm_peer_pairing(session.pairing_id.to_string(), code.clone())
        .await
        .expect("confirm");

    let path = pc.data_dir.path().join("peers.json");
    let raw = std::fs::read_to_string(&path).expect("peers.json exists");
    assert!(
        !raw.contains(&code),
        "the 6-digit pairing code must never reach disk"
    );
    // It *does* hold the raw bearer token — that is the documented trade-off
    // of being the client (we need it to authenticate), which is why the file
    // is written owner-only.
    assert!(raw.contains("\"token\""));
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = std::fs::metadata(&path).expect("stat").permissions().mode();
        assert_eq!(mode & 0o777, 0o600, "peers.json must be owner-only");
    }

    // And the public projection carries no token at all.
    let json = serde_json::to_string(&pc.core.paired_peers()).expect("serialize");
    assert!(!json.contains("token"));

    pc.core.shutdown().await;
    peer.core.shutdown().await;
}

#[tokio::test]
async fn forget_peer_drops_it_and_survives_a_restart() {
    let peer = start_core_with(|cfg| cfg.device_name = "Living Room TV".to_string()).await;
    let pc = start_core().await;
    let paired = pair(&pc, &peer).await;

    assert_eq!(pc.core.paired_peers().len(), 1);
    assert!(pc.core.forget_peer(paired.device_id));
    assert!(pc.core.paired_peers().is_empty());
    // Idempotent.
    assert!(!pc.core.forget_peer(paired.device_id));
    // Sending to a forgotten peer is an error, not a panic.
    let source = pc.data_dir.path().join("x.bin");
    std::fs::write(&source, b"x").expect("write");
    assert!(pc
        .core
        .send_files_to_peer(paired.device_id, vec![source])
        .await
        .is_err());

    // The token is gone from disk, not just from memory.
    let raw = std::fs::read_to_string(pc.data_dir.path().join("peers.json")).expect("peers.json");
    assert!(!raw.contains("\"token\""), "{raw}");

    pc.core.shutdown().await;
    peer.core.shutdown().await;
}

#[tokio::test]
async fn pairing_rejects_a_wrong_code_and_a_non_sendro_address() {
    let peer = start_core_with(|cfg| cfg.device_name = "Living Room TV".to_string()).await;
    let pc = start_core().await;

    let session = pc
        .core
        .pair_with_peer("127.0.0.1".to_string(), peer.core.info().api_port)
        .await
        .expect("pair_with_peer");

    // Six digits that are almost certainly not the code (1 in 10^6 if they
    // are, and then the next assert would still hold for the second attempt).
    let err = pc
        .core
        .confirm_peer_pairing(session.pairing_id.to_string(), "000000".to_string())
        .await
        .err()
        .map(|e| e.to_string())
        .unwrap_or_default();
    assert!(
        err.contains("code") || err.contains("digits"),
        "wrong-code errors must be readable: {err}"
    );
    assert!(pc.core.paired_peers().is_empty());

    // Malformed input never reaches the network.
    assert!(pc
        .core
        .confirm_peer_pairing(session.pairing_id.to_string(), "12".to_string())
        .await
        .is_err());
    assert!(pc
        .core
        .confirm_peer_pairing(Uuid::new_v4().to_string(), "123456".to_string())
        .await
        .is_err());

    // Nothing is listening here — a clear failure, not a hang.
    let dead = pc.core.pair_with_peer("127.0.0.1".to_string(), 1).await;
    assert!(dead.is_err());

    pc.core.shutdown().await;
    peer.core.shutdown().await;
}

// ---------------------------------------------------------------------------
// §15 receiver host: outbox 404, and a 422 integrity rejection
// ---------------------------------------------------------------------------

/// A deliberately minimal §15.1 receiver host — the endpoints a TV must have
/// (`info`, `pair/*`, `ping`, `upload`, `messages`) and a `404` outbox.
struct FakeTv {
    device_id: Uuid,
    pairings: sendro_core::pairing::PairingManager,
    /// pairingId → the 6 digits the TV would be showing on screen. The test
    /// reads them here instead of pointing a camera at it.
    codes: Mutex<std::collections::HashMap<Uuid, String>>,
    tokens: Mutex<Vec<String>>,
    received: Mutex<Vec<(String, usize)>>,
    reject_integrity: AtomicBool,
}

impl FakeTv {
    fn authed(&self, headers: &axum::http::HeaderMap) -> bool {
        headers
            .get(axum::http::header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer "))
            .map(|token| self.tokens.lock().iter().any(|t| t == token))
            .unwrap_or(false)
    }
}

/// Boots the fake TV on an ephemeral port; returns it plus its port.
async fn start_fake_tv(reject_integrity: bool) -> (Arc<FakeTv>, u16) {
    use axum::extract::State;
    use axum::http::{HeaderMap, StatusCode};
    use axum::response::IntoResponse as _;
    use axum::routing::{get, post};
    use axum::{Json, Router};

    let tv = Arc::new(FakeTv {
        device_id: Uuid::new_v4(),
        pairings: sendro_core::pairing::PairingManager::new(),
        codes: Mutex::new(std::collections::HashMap::new()),
        tokens: Mutex::new(Vec::new()),
        received: Mutex::new(Vec::new()),
        reject_integrity: AtomicBool::new(reject_integrity),
    });

    let router = Router::new()
        .route(
            "/api/v1/info",
            get(|State(tv): State<Arc<FakeTv>>| async move {
                Json(serde_json::json!({
                    "app": "sendro",
                    "protocolVersion": 1,
                    "deviceId": tv.device_id,
                    "deviceName": "Living Room TV",
                    "platform": "androidtv",
                    "apiPort": 48800,
                }))
            }),
        )
        .route(
            "/api/v1/pair/start",
            post(
                |State(tv): State<Arc<FakeTv>>, Json(body): Json<serde_json::Value>| async move {
                    let device_id: Uuid = body["deviceId"].as_str().unwrap().parse().unwrap();
                    let session = tv
                        .pairings
                        .start(
                            device_id,
                            body["deviceName"].as_str().unwrap_or_default().to_string(),
                            body["platform"].as_str().unwrap_or_default().to_string(),
                        )
                        .expect("session");
                    tv.codes
                        .lock()
                        .insert(session.pairing_id, session.code.clone());
                    Json(serde_json::json!({
                        "pairingId": session.pairing_id,
                        "salt": URL_SAFE_NO_PAD.encode(session.salt),
                        "expiresInSeconds": 120,
                    }))
                },
            ),
        )
        .route(
            "/api/v1/pair/confirm",
            post(
                |State(tv): State<Arc<FakeTv>>, Json(body): Json<serde_json::Value>| async move {
                    let pairing_id: Uuid = body["pairingId"].as_str().unwrap().parse().unwrap();
                    let device_id: Uuid = body["deviceId"].as_str().unwrap().parse().unwrap();
                    let proof = body["proof"].as_str().unwrap_or_default();
                    match tv.pairings.confirm(pairing_id, device_id, proof) {
                        sendro_core::pairing::ConfirmOutcome::Success { .. } => {
                            let token = sendro_core::pairing::mint_device_token();
                            tv.tokens.lock().push(token.clone());
                            Json(serde_json::json!({
                                "deviceToken": token,
                                "host": {
                                    "deviceId": tv.device_id,
                                    "deviceName": "Living Room TV",
                                    "platform": "androidtv",
                                }
                            }))
                            .into_response()
                        }
                        _ => (
                            StatusCode::FORBIDDEN,
                            Json(serde_json::json!({ "error": "bad_request" })),
                        )
                            .into_response(),
                    }
                },
            ),
        )
        .route(
            "/api/v1/ping",
            get(
                |State(tv): State<Arc<FakeTv>>, headers: HeaderMap| async move {
                    if !tv.authed(&headers) {
                        return StatusCode::UNAUTHORIZED.into_response();
                    }
                    Json(serde_json::json!({ "ok": true, "deviceName": "Living Room TV" }))
                        .into_response()
                },
            ),
        )
        // §15.1: a receiver never offers files.
        .route(
            "/api/v1/outbox",
            get(|| async {
                (
                    StatusCode::NOT_FOUND,
                    Json(serde_json::json!({ "error": "not_found" })),
                )
            }),
        )
        .route(
            "/api/v1/messages",
            post(|| async { Json(serde_json::json!({ "ok": true })) }),
        )
        .route(
            "/api/v1/upload",
            post(
                |State(tv): State<Arc<FakeTv>>, headers: HeaderMap, body: axum::body::Body| async move {
                    if !tv.authed(&headers) {
                        return StatusCode::UNAUTHORIZED.into_response();
                    }
                    let name = headers
                        .get("x-sendro-file-name")
                        .and_then(|v| v.to_str().ok())
                        .unwrap_or_default()
                        .to_string();
                    let declared = headers
                        .get("content-length")
                        .and_then(|v| v.to_str().ok())
                        .and_then(|v| v.parse::<usize>().ok());
                    let bytes = axum::body::to_bytes(body, usize::MAX)
                        .await
                        .unwrap_or_default();
                    // §7 framing: the sender must declare the body length.
                    assert_eq!(declared, Some(bytes.len()), "Content-Length must match");
                    tv.received.lock().push((name, bytes.len()));
                    if tv.reject_integrity.load(Ordering::SeqCst) {
                        return (
                            StatusCode::UNPROCESSABLE_ENTITY,
                            Json(serde_json::json!({ "error": "integrity" })),
                        )
                            .into_response();
                    }
                    Json(serde_json::json!({ "ok": true, "savedPath": "/sdcard/Sendro" }))
                        .into_response()
                },
            ),
        )
        .with_state(tv.clone());

    let listener = tokio::net::TcpListener::bind(("127.0.0.1", 0))
        .await
        .expect("bind fake tv");
    let port = listener.local_addr().expect("addr").port();
    tokio::spawn(async move {
        let _ = axum::serve(listener, router).await;
    });
    (tv, port)
}

/// Pair a PC with the fake TV. The code is read out of the TV's own map,
/// which is the in-process stand-in for "the TV shows it on the big screen".
async fn pair_with_fake_tv(pc: &TestCtx, tv: &Arc<FakeTv>, port: u16) -> sendro_core::PairedPeer {
    let session = pc
        .core
        .pair_with_peer("127.0.0.1".to_string(), port)
        .await
        .expect("pair_with_peer");
    let code = tv
        .codes
        .lock()
        .get(&session.pairing_id)
        .cloned()
        .expect("the TV is showing a code");
    pc.core
        .confirm_peer_pairing(session.pairing_id.to_string(), code)
        .await
        .expect("confirm_peer_pairing")
}

#[tokio::test]
async fn a_receiver_host_that_404s_the_outbox_is_receive_only_not_broken() {
    let (tv, port) = start_fake_tv(false).await;
    let pc = start_core().await;
    let paired = pair_with_fake_tv(&pc, &tv, port).await;

    assert_eq!(paired.platform, "androidtv");
    assert_eq!(paired.device_name, "Living Room TV");
    assert!(
        paired.receive_only,
        "a 404 on the outbox means receive-only (§15.1), not an error"
    );
    assert!(pc.core.ping_peer(paired.device_id).await);

    // …and it is still a perfectly good target for a §7 push.
    let source = pc.data_dir.path().join(UNICODE_NAME);
    std::fs::write(&source, payload(1_500_000)).expect("write");
    let summaries = pc
        .core
        .send_files_to_peer(paired.device_id, vec![source])
        .await
        .expect("send_files_to_peer");
    assert_eq!(summaries[0].state, TransferState::Completed);

    let received = tv.received.lock().clone();
    assert_eq!(received.len(), 1);
    assert_eq!(
        received[0].0,
        format!(
            "UTF-8''{}",
            sendro_core::filename::rfc5987_encode(UNICODE_NAME)
        ),
        "§7 sends the name RFC 5987-encoded"
    );
    assert_eq!(received[0].1, 1_500_000);

    // Text lands on the TV too (§11.2).
    pc.core
        .send_message_to_peer(paired.device_id, "hello tv".to_string())
        .await
        .expect("send_message_to_peer");

    pc.core.shutdown().await;
}

#[tokio::test]
async fn an_integrity_rejection_marks_the_transfer_failed_and_allows_a_fresh_retry() {
    let (tv, port) = start_fake_tv(true).await;
    let pc = start_core().await;
    let paired = pair_with_fake_tv(&pc, &tv, port).await;

    let source = pc.data_dir.path().join("corrupt.bin");
    std::fs::write(&source, payload(300_000)).expect("write");
    let summaries = pc
        .core
        .send_files_to_peer(paired.device_id, vec![source.clone()])
        .await
        .expect("the batch call succeeds; the individual file fails");

    assert_eq!(summaries.len(), 1);
    assert_eq!(summaries[0].state, TransferState::Failed);
    assert_eq!(
        summaries[0].error.as_deref(),
        Some("IntegrityMismatch"),
        "a 422 is surfaced as an integrity failure, not a generic HTTP error"
    );
    // §7: the receiver deleted its partial (covered receiver-side in
    // upload_tests.rs); on our side the source is untouched and nothing
    // reaches history.
    assert!(source.exists());
    assert!(pc.core.history().is_empty());

    // Retry = a whole new transfer from byte 0 — §7 has no ranged upload, so
    // the host-side re-offer path must refuse it.
    let failed_id = summaries[0].transfer_id;
    assert!(!pc.core.retry_transfer(failed_id));

    tv.reject_integrity.store(false, Ordering::SeqCst);
    let retried = pc
        .core
        .send_files_to_peer(paired.device_id, vec![source])
        .await
        .expect("retry");
    assert_ne!(retried[0].transfer_id, failed_id);
    assert_eq!(retried[0].state, TransferState::Completed);
    assert_eq!(
        tv.received.lock().last().map(|r| r.1),
        Some(300_000),
        "the retry sends the whole file again"
    );

    pc.core.shutdown().await;
}

// ---------------------------------------------------------------------------
// Discovery hygiene
// ---------------------------------------------------------------------------

#[tokio::test]
async fn discovery_starts_empty_and_never_lists_this_pc() {
    let pc = start_core().await;
    // mDNS is off in tests, so the live browser has nothing — the point here
    // is that the API is safe to call and self-exclusion holds by construction
    // (the unit test in discovery.rs covers the TXT-record path).
    assert!(pc.core.discovered_peers().is_empty());
    assert!(pc
        .core
        .discover(Duration::from_millis(50))
        .await
        .iter()
        .all(|host| host.device_id != pc.core.info().device_id.to_string()));
    pc.core.shutdown().await;
}

#[tokio::test]
async fn send_files_to_an_unknown_peer_is_an_error() {
    let pc = start_core().await;
    let file: PathBuf = pc.data_dir.path().join("nope.bin");
    std::fs::write(&file, b"x").expect("write");
    assert!(pc
        .core
        .send_files_to_peer(Uuid::new_v4(), vec![file])
        .await
        .is_err());
    assert!(pc
        .core
        .send_message_to_peer(Uuid::new_v4(), "hi".to_string())
        .await
        .is_err());
    assert!(!pc.core.ping_peer(Uuid::new_v4()).await);
    pc.core.shutdown().await;
}
