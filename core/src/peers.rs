//! The PC as a **client** — PROTOCOL.md §4 (pairing), §7 (upload),
//! §11.2 (text) against a peer that is itself a host (§15).
//!
//! Everything else in this crate is the host half: we advertise, other
//! devices pair *to* us and pull from our outbox. This module is the mirror
//! image, and the two are deliberately kept apart at rest:
//!
//! | direction | who holds what | file |
//! |---|---|---|
//! | device → PC (inbound trust) | we are the host, we keep `SHA-256(token)` | `trusted_devices.json` |
//! | PC → peer (outbound trust) | we are the client, we keep the **raw** token | `peers.json` |
//!
//! See [`crate::state::StoredPeer`] for why the second one is as sensitive as
//! a password store, and what is done about it.
//!
//! §15.3 note: when a peer exposes an outbox, offering (host mode) is the
//! better path — it resumes and keeps our queue authoritative. This module is
//! the other one: a plain §7 push, which is the *only* path to a receiver-only
//! peer like a TV. A peer that answers `404` on the outbox is receive-only;
//! that is expected and is never surfaced as an error.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use anyhow::{anyhow, Context};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use futures::TryStreamExt;
use serde::{Deserialize, Serialize};
use tokio::io::AsyncReadExt;
use tokio_util::io::ReaderStream;
use uuid::Uuid;

use crate::hashing::CHUNK_SIZE;
use crate::state::{self, StoredPeer};
use crate::transfers::{expand_paths, Direction, SpeedWindow, TransferRecord};
use crate::types::{
    now_ms, ErrorBody, InfoResponse, PairConfirmResponse, PairStartResponse, TransferState,
    TransferSummary, UploadResponse, PLATFORM, PROTOCOL_VERSION,
};
use crate::Core;

/// A peer pairing session has the same 120 s life as the host-side one (§4.1);
/// we drop ours slightly later so the peer's expiry is what the user hits.
pub const PEER_PAIRING_TTL: Duration = Duration::from_secs(150);

/// How often the upload progress ticker refreshes speed/ETA (~5/sec, then
/// throttled to ~4/sec by `emit_transfer`).
const PROGRESS_TICK: Duration = Duration::from_millis(200);

/// Timeout for the small JSON round trips (info / pair / ping / messages).
/// Never applied to an upload — that one is bounded by the read timeout of
/// the connection, not by the size of the file.
const CONTROL_TIMEOUT: Duration = Duration::from_secs(8);

/// A peer we have paired with — the public projection of
/// [`crate::state::StoredPeer`]. **Deliberately has no token field**: this is
/// what crosses the Tauri IPC boundary and lands in the webview.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairedPeer {
    pub device_id: Uuid,
    pub device_name: String,
    pub platform: String,
    pub address: String,
    pub port: u16,
    pub paired_at_ms: i64,
    pub last_seen_ms: Option<i64>,
    /// The peer answers `404` on the outbox (§15.1): it can only receive.
    pub receive_only: bool,
}

/// An outbound pairing session waiting for the user to type the peer's code.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PeerPairingSession {
    pub pairing_id: Uuid,
    /// Who we are pairing with, from its `GET /api/v1/info` (§5).
    pub device_id: Uuid,
    pub device_name: String,
    pub platform: String,
    pub address: String,
    pub port: u16,
    pub expires_in_seconds: u64,
}

/// The RAM-only half of an in-flight outbound pairing.
///
/// The 6-digit code is **not** in here: it arrives in
/// [`Core::confirm_peer_pairing`], is used to derive the §4.2 proof, and is
/// dropped at the end of that call. Nothing about a code is ever written to
/// disk on either side.
pub(crate) struct PendingPeerPairing {
    pub salt: Vec<u8>,
    pub address: String,
    pub port: u16,
    pub device_id: Uuid,
    pub device_name: String,
    pub platform: String,
    pub created_at: Instant,
}

fn base_url(address: &str, port: u16) -> String {
    format!("http://{address}:{port}/api/v1")
}

/// Turn a non-2xx peer response into an error carrying the §9 body when it
/// has one, so the UI can say something better than "HTTP 403".
async fn peer_error(context: &str, response: reqwest::Response) -> anyhow::Error {
    let status = response.status();
    let body: Option<ErrorBody> = response.json().await.ok();
    match body {
        Some(body) => {
            let detail = body.message.unwrap_or(body.error);
            anyhow!("{context}: {} ({detail})", status.as_u16())
        }
        None => anyhow!("{context}: HTTP {}", status.as_u16()),
    }
}

impl Core {
    // ------------------------------------------------------------------
    // Trust: pairing with a peer (§4, as the client)
    // ------------------------------------------------------------------

    /// Step 1 of §4 from the client side.
    ///
    /// `GET /api/v1/info` first (is this Sendro, and can we speak its
    /// protocol version?), then `POST /api/v1/pair/start` with *our* identity.
    /// The peer shows a 6-digit code on its own screen; the returned session
    /// is what the PC's "type the code" dialog is built from.
    pub async fn pair_with_peer(
        &self,
        address: String,
        port: u16,
    ) -> anyhow::Result<PeerPairingSession> {
        let address = address.trim().to_string();
        if address.is_empty() {
            anyhow::bail!("enter the peer's address");
        }
        let base = base_url(&address, port);

        let info: InfoResponse = self
            .http
            .get(format!("{base}/info"))
            .timeout(CONTROL_TIMEOUT)
            .send()
            .await
            .with_context(|| format!("{address}:{port} did not answer"))?
            .error_for_status()
            .with_context(|| format!("{address}:{port} is not answering /info"))?
            .json()
            .await
            .context("that address answered, but not with Sendro's /info")?;

        if info.app != crate::types::APP_NAME {
            anyhow::bail!("{address}:{port} is not a Sendro device");
        }
        // §10: an unknown major version means "update Sendro", not "try anyway".
        if info.protocol_version != PROTOCOL_VERSION {
            anyhow::bail!(
                "{} speaks Sendro protocol v{}, this PC speaks v{PROTOCOL_VERSION} — update the older side",
                info.device_name,
                info.protocol_version
            );
        }
        if info.device_id == self.device_id {
            anyhow::bail!("that is this PC");
        }

        // Read the name *before* the request expression: a `parking_lot`
        // guard created inside a statement that contains `.await` stays alive
        // across it, which would make this future `!Send` — and every Tauri
        // command is spawned on a multi-threaded runtime.
        let our_name = self.settings.read().device_name.clone();
        let start: PairStartResponse = {
            let response = self
                .http
                .post(format!("{base}/pair/start"))
                .timeout(CONTROL_TIMEOUT)
                .json(&serde_json::json!({
                    "deviceId": self.device_id,
                    "deviceName": our_name,
                    "platform": PLATFORM,
                    "protocolVersion": PROTOCOL_VERSION,
                }))
                .send()
                .await
                .with_context(|| format!("could not start pairing with {}", info.device_name))?;
            if !response.status().is_success() {
                return Err(peer_error("pairing was refused", response).await);
            }
            response.json().await.context("malformed pair/start reply")?
        };

        let salt = URL_SAFE_NO_PAD
            .decode(start.salt.as_bytes())
            .context("malformed pairing salt")?;

        self.pending_peer_pairings.lock().insert(
            start.pairing_id,
            PendingPeerPairing {
                salt,
                address: address.clone(),
                port,
                device_id: info.device_id,
                device_name: info.device_name.clone(),
                platform: info.platform.clone(),
                created_at: Instant::now(),
            },
        );

        Ok(PeerPairingSession {
            pairing_id: start.pairing_id,
            device_id: info.device_id,
            device_name: info.device_name,
            platform: info.platform,
            address,
            port,
            expires_in_seconds: start.expires_in_seconds,
        })
    }

    /// Step 2 of §4: derive the HKDF/HMAC proof from the code the user read
    /// off the peer's screen and `POST /api/v1/pair/confirm`.
    ///
    /// The code never crosses the network — only the proof does — and it is
    /// dropped when this function returns.
    pub async fn confirm_peer_pairing(
        &self,
        pairing_id: String,
        code: String,
    ) -> anyhow::Result<PairedPeer> {
        let pairing_id: Uuid = pairing_id
            .parse()
            .context("that pairing session id is not valid")?;
        let code = code.trim().to_string();
        if code.len() != 6 || !code.bytes().all(|b| b.is_ascii_digit()) {
            anyhow::bail!("the code is six digits");
        }

        let (salt, address, port, peer_id, peer_name, peer_platform) = {
            let pending = self.pending_peer_pairings.lock();
            let session = pending
                .get(&pairing_id)
                .ok_or_else(|| anyhow!("that pairing session is gone — start again"))?;
            if session.created_at.elapsed() > PEER_PAIRING_TTL {
                anyhow::bail!("that pairing session expired — start again");
            }
            (
                session.salt.clone(),
                session.address.clone(),
                session.port,
                session.device_id,
                session.device_name.clone(),
                session.platform.clone(),
            )
        };

        let proof = crate::pairing::compute_proof(&code, &salt, pairing_id, self.device_id);
        drop(code);

        // Hoisted for the same `!Send` reason as in `pair_with_peer`.
        let our_name = self.settings.read().device_name.clone();
        let response = self
            .http
            .post(format!("{}/pair/confirm", base_url(&address, port)))
            .timeout(CONTROL_TIMEOUT)
            .json(&serde_json::json!({
                "pairingId": pairing_id,
                "deviceId": self.device_id,
                "proof": proof,
                // Optional per §4.2 — harmless for a typed session, and what a
                // QR-style peer needs to know who just confirmed.
                "deviceName": our_name,
                "platform": PLATFORM,
            }))
            .send()
            .await
            .with_context(|| format!("could not reach {peer_name}"))?;

        let status = response.status();
        if !status.is_success() {
            // A wrong code leaves the session alive (§4.2), so the dialog can
            // let the user try again; anything else burns it.
            let message = match status.as_u16() {
                403 => "that code is not right — check the digits on the other screen".to_string(),
                400 => {
                    self.pending_peer_pairings.lock().remove(&pairing_id);
                    "the pairing session expired — start again".to_string()
                }
                429 => {
                    self.pending_peer_pairings.lock().remove(&pairing_id);
                    "too many attempts — start pairing again".to_string()
                }
                _ => return Err(peer_error("pairing failed", response).await),
            };
            anyhow::bail!(message);
        }

        let confirm: PairConfirmResponse =
            response.json().await.context("malformed confirm reply")?;
        self.pending_peer_pairings.lock().remove(&pairing_id);

        // The confirm body carries the peer's own identity (§4.2); prefer it
        // over what /info said, and over the mDNS TXT record.
        let device_id = if confirm.host.device_id.is_nil() {
            peer_id
        } else {
            confirm.host.device_id
        };
        let mut stored = StoredPeer {
            device_id,
            device_name: if confirm.host.device_name.trim().is_empty() {
                peer_name
            } else {
                confirm.host.device_name
            },
            platform: if confirm.host.platform.trim().is_empty() {
                peer_platform
            } else {
                confirm.host.platform
            },
            address,
            port,
            paired_at_ms: now_ms(),
            last_seen_ms: Some(now_ms()),
            receive_only: false,
            token: confirm.device_token,
        };
        // §15.1: capability comes from a 404 on the outbox, never from `pf`.
        stored.receive_only = self.probe_receive_only(&stored).await;

        let public = stored.public();
        {
            let mut peers = self.peers.write();
            peers.retain(|p| p.device_id != stored.device_id);
            peers.push(stored);
        }
        self.save_peers();
        // The "On this network" list shows a Paired chip for it now.
        self.emit_peers_changed();
        tracing::info!(
            "paired with peer {} ({})",
            public.device_name,
            public.device_id
        );
        Ok(public)
    }

    /// Ask the peer for its outbox. A `404` means "receiver host" (§15.1) —
    /// expected, not an error. Anything else (including a network failure)
    /// leaves the flag as-is/false, because we must never *invent* a
    /// capability we have not seen.
    async fn probe_receive_only(&self, peer: &StoredPeer) -> bool {
        let url = format!("{}/outbox?waitSeconds=0", base_url(&peer.address, peer.port));
        match self
            .http
            .get(url)
            .bearer_auth(&peer.token)
            .timeout(CONTROL_TIMEOUT)
            .send()
            .await
        {
            Ok(response) => response.status() == reqwest::StatusCode::NOT_FOUND,
            Err(_) => false,
        }
    }

    // ------------------------------------------------------------------
    // The peer list
    // ------------------------------------------------------------------

    pub fn paired_peers(&self) -> Vec<PairedPeer> {
        let mut peers: Vec<PairedPeer> = self.peers.read().iter().map(|p| p.public()).collect();
        peers.sort_by(|a, b| {
            a.device_name
                .to_lowercase()
                .cmp(&b.device_name.to_lowercase())
                .then_with(|| a.device_id.cmp(&b.device_id))
        });
        peers
    }

    /// Drop a peer and its token. The peer keeps its own record of us until
    /// *it* revokes — there is no protocol message for "forget me" in v1.
    pub fn forget_peer(&self, device_id: Uuid) -> bool {
        let removed = {
            let mut peers = self.peers.write();
            let before = peers.len();
            peers.retain(|p| p.device_id != device_id);
            peers.len() != before
        };
        if removed {
            self.save_peers();
            self.emit_peers_changed();
        }
        removed
    }

    /// `GET /api/v1/ping` with our bearer token (§4.3).
    ///
    /// Also self-heals the stored address: if the peer moved (DHCP, Wi-Fi ⇄
    /// hotspot) the mDNS browser already knows the new one, so we retry there
    /// and persist whichever answered.
    pub async fn ping_peer(&self, device_id: Uuid) -> bool {
        let Some(peer) = self.stored_peer(device_id) else {
            return false;
        };

        let mut candidates = vec![(peer.address.clone(), peer.port)];
        if let Some(seen) = self
            .discovered_peers()
            .into_iter()
            .find(|p| p.device_id == device_id)
        {
            if (seen.address.clone(), seen.port) != candidates[0] {
                candidates.push((seen.address, seen.port));
            }
        }

        for (address, port) in candidates {
            let ok = self
                .http
                .get(format!("{}/ping", base_url(&address, port)))
                .bearer_auth(&peer.token)
                .timeout(CONTROL_TIMEOUT)
                .send()
                .await
                .map(|r| r.status().is_success())
                .unwrap_or(false);
            if !ok {
                continue;
            }
            let mut receive_only = peer.receive_only;
            if receive_only {
                // Cheap and drain-free (a 404 consumes nothing): a peer that
                // grew an outbox stops being marked receive-only.
                let mut probe = peer.clone();
                probe.address = address.clone();
                probe.port = port;
                receive_only = self.probe_receive_only(&probe).await;
            }
            {
                let mut peers = self.peers.write();
                if let Some(stored) = peers.iter_mut().find(|p| p.device_id == device_id) {
                    stored.last_seen_ms = Some(now_ms());
                    stored.address = address;
                    stored.port = port;
                    stored.receive_only = receive_only;
                }
            }
            self.save_peers();
            return true;
        }
        false
    }

    pub(crate) fn stored_peer(&self, device_id: Uuid) -> Option<StoredPeer> {
        self.peers
            .read()
            .iter()
            .find(|p| p.device_id == device_id)
            .cloned()
    }

    pub(crate) fn load_peers(data_dir: &Path) -> Vec<StoredPeer> {
        state::load_json(&data_dir.join(state::PEERS_FILE)).unwrap_or_default()
    }

    pub(crate) fn save_peers(&self) {
        let snapshot = self.peers.read().clone();
        // Private write — this file holds raw bearer tokens.
        if let Err(e) = state::atomic_write_json_private(
            &self.data_dir.join(state::PEERS_FILE),
            &snapshot,
        ) {
            tracing::error!("failed to persist peers: {e}");
        }
    }

    /// Drop outbound pairing sessions nobody confirmed (maintenance sweep).
    pub(crate) fn purge_peer_pairings(&self) {
        self.pending_peer_pairings
            .lock()
            .retain(|_, s| s.created_at.elapsed() <= PEER_PAIRING_TTL);
    }

    // ------------------------------------------------------------------
    // Sending to a peer (§7 upload)
    // ------------------------------------------------------------------

    /// Push files to a paired peer with §7 uploads.
    ///
    /// They join the same queue and history as everything else, with
    /// `direction: "outgoing"` and the peer's name, so the Flow tab shows
    /// progress/speed/ETA/cancel without knowing anything about peers.
    ///
    /// One file at a time up to the user's concurrency setting. Folders are
    /// expanded recursively, exactly like `offer_files`.
    pub async fn send_files_to_peer(
        &self,
        device_id: Uuid,
        paths: Vec<PathBuf>,
    ) -> anyhow::Result<Vec<TransferSummary>> {
        use futures::stream::StreamExt;

        let peer = self
            .stored_peer(device_id)
            .ok_or_else(|| anyhow!("device {device_id} is not a paired peer"))?;
        let files = expand_paths(&paths)?;
        if files.is_empty() {
            anyhow::bail!("no files to send");
        }

        // Queue the whole batch first so the UI sees it at once.
        let batch_id = Uuid::new_v4();
        let ids: Vec<Uuid> = files
            .iter()
            .map(|path| self.queue_peer_transfer(&peer, batch_id, path))
            .collect();

        let limit = self.concurrency().max(1);
        let peer = &peer;
        // Owned `(id, path)` pairs, not `(id, &path)`: a closure that takes a
        // borrowed argument here needs a higher-ranked `FnOnce` the compiler
        // will not infer, and the resulting future stops being `Send` — which
        // the Tauri command layer requires.
        let jobs: Vec<(Uuid, PathBuf)> = ids.iter().copied().zip(files).collect();
        futures::stream::iter(jobs)
            .map(|(id, path)| async move {
                if let Err(e) = self.upload_to_peer(peer, id, &path).await {
                    // Already recorded on the transfer; the batch continues so
                    // one bad file cannot abort the rest (§12's spirit).
                    tracing::warn!("upload to {} failed: {e:#}", peer.device_name);
                }
            })
            .buffer_unordered(limit)
            .collect::<Vec<()>>()
            .await;

        let transfers = self.transfers.read();
        Ok(ids
            .iter()
            .filter_map(|id| transfers.get(id).map(|r| r.summary()))
            .collect())
    }

    fn queue_peer_transfer(&self, peer: &StoredPeer, batch_id: Uuid, path: &Path) -> Uuid {
        let file_name = crate::filename::sanitize(
            path.file_name().and_then(|n| n.to_str()).unwrap_or("file"),
        );
        let size_bytes = std::fs::metadata(path).map(|m| m.len()).unwrap_or(0);
        let rec = TransferRecord {
            transfer_id: Uuid::new_v4(),
            batch_id,
            file_id: Uuid::new_v4(),
            file_name: file_name.clone(),
            extension: Path::new(&file_name)
                .extension()
                .and_then(|e| e.to_str())
                .unwrap_or("")
                .to_string(),
            mime_type: mime_guess::from_path(path)
                .first_raw()
                .unwrap_or("application/octet-stream")
                .to_string(),
            size_bytes,
            sha256: None,
            created_at_ms: now_ms(),
            modified_at_ms: now_ms(),
            offered_at_ms: 0,
            auto_accept: false,
            device_id: peer.device_id,
            device_name: peer.device_name.clone(),
            direction: Direction::Outgoing,
            state: TransferState::Queued,
            error: None,
            bytes_transferred: 0,
            started_at_ms: None,
            source_path: Some(path.to_path_buf()),
            verified: false,
            seq: self.seq.fetch_add(1, Ordering::Relaxed),
            speed: SpeedWindow::default(),
            last_emit: None,
            is_peer: true,
        };
        let id = rec.transfer_id;
        self.transfers.write().insert(id, rec);
        self.emit_transfer(id, true);
        id
    }

    /// Hash, then stream one file to the peer's `POST /api/v1/upload`.
    ///
    /// §7 puts the digest in a request *header*, so the file is hashed before
    /// the body starts — one full read, then one full send. Neither pass ever
    /// holds more than [`CHUNK_SIZE`] of the file in memory.
    async fn upload_to_peer(
        &self,
        peer: &StoredPeer,
        id: Uuid,
        path: &Path,
    ) -> anyhow::Result<()> {
        // --- hash pass (state: Hashing) ---
        {
            let mut transfers = self.transfers.write();
            if let Some(rec) = transfers.get_mut(&id) {
                rec.state = TransferState::Hashing;
                rec.bytes_transferred = 0;
                rec.speed.reset();
            }
        }
        self.emit_transfer(id, true);

        let (sha256, size) = match crate::hashing::sha256_file(path, |hashed| {
            if let Some(rec) = self.transfers.write().get_mut(&id) {
                rec.bytes_transferred = hashed;
            }
            self.emit_transfer(id, false);
        })
        .await
        {
            Ok(result) => result,
            Err(e) => {
                self.fail_peer_transfer(id, &format!("could not read the file: {e}"));
                return Err(e);
            }
        };
        {
            let mut transfers = self.transfers.write();
            if let Some(rec) = transfers.get_mut(&id) {
                rec.sha256 = Some(sha256.clone());
                rec.size_bytes = size;
                rec.bytes_transferred = 0;
                rec.started_at_ms = Some(now_ms());
                rec.state = TransferState::Transferring;
                rec.speed.reset();
            }
        }
        self.emit_transfer(id, true);

        // --- body pass (state: Transferring) ---
        let file_name = self
            .transfers
            .read()
            .get(&id)
            .map(|r| r.file_name.clone())
            .unwrap_or_default();
        let file = tokio::fs::File::open(path)
            .await
            .with_context(|| format!("open {}", path.display()))?;
        // Exactly `size` bytes, whatever the file does behind our back: the
        // Content-Length we declare must match the body byte for byte.
        let sent = Arc::new(AtomicU64::new(0));
        let counter = Arc::clone(&sent);
        let stream = ReaderStream::with_capacity(file.take(size), CHUNK_SIZE)
            .inspect_ok(move |chunk| {
                counter.fetch_add(chunk.len() as u64, Ordering::Relaxed);
            });

        let request = self
            .http
            .post(format!("{}/upload", base_url(&peer.address, peer.port)))
            .bearer_auth(&peer.token)
            // §7/§8: RFC 5987 `UTF-8''<pct-encoded>` — the exact form the iOS
            // and Android clients send, so every receiver already decodes it.
            .header(
                "X-Sendro-File-Name",
                format!("UTF-8''{}", crate::filename::rfc5987_encode(&file_name)),
            )
            .header("X-Sendro-Sha256", &sha256)
            .header(reqwest::header::CONTENT_TYPE, "application/octet-stream")
            // Explicit: with a streaming body hyper would otherwise use
            // chunked framing, and §7 says Content-Length is part of the
            // request.
            .header(reqwest::header::CONTENT_LENGTH, size)
            .body(reqwest::Body::wrap_stream(stream))
            .send();

        tokio::pin!(request);
        let mut tick = tokio::time::interval(PROGRESS_TICK);
        tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        let response = loop {
            tokio::select! {
                result = &mut request => break result,
                _ = tick.tick() => {
                    if self.transfer_is_cancelled(id) {
                        // Dropping the in-flight request kills the connection,
                        // which is exactly what "Cancel" should do.
                        return Ok(());
                    }
                    self.note_peer_progress(id, sent.load(Ordering::Relaxed));
                }
            }
        };

        let response = match response {
            Ok(response) => response,
            Err(e) => {
                self.fail_peer_transfer(id, &format!("connection lost: {e}"));
                return Err(e.into());
            }
        };

        let status = response.status();
        if status == reqwest::StatusCode::UNPROCESSABLE_ENTITY {
            // §7: the receiver's own SHA-256 disagreed and it deleted the
            // partial file. Retrying means sending the whole thing again —
            // §7 has no ranged upload, so there is nothing to resume from.
            self.fail_peer_transfer(id, "IntegrityMismatch");
            anyhow::bail!("{} rejected the file as corrupt", peer.device_name);
        }
        if status == reqwest::StatusCode::NOT_FOUND {
            self.fail_peer_transfer(id, "that device does not accept files");
            anyhow::bail!("{} does not accept uploads", peer.device_name);
        }
        if !status.is_success() {
            let error = peer_error("upload was refused", response).await;
            self.fail_peer_transfer(id, &error.to_string());
            return Err(error);
        }

        // §7 only answers 200 after verifying the digest it was given, so a
        // success *is* the verification.
        let saved: UploadResponse = response.json().await.unwrap_or(UploadResponse {
            ok: true,
            saved_path: String::new(),
        });
        {
            let mut transfers = self.transfers.write();
            if let Some(rec) = transfers.get_mut(&id) {
                rec.state = TransferState::Completed;
                rec.bytes_transferred = rec.size_bytes;
                rec.verified = true;
                rec.error = None;
            }
        }
        self.record_history(id);
        self.emit_transfer(id, true);
        {
            let mut peers = self.peers.write();
            if let Some(stored) = peers.iter_mut().find(|p| p.device_id == peer.device_id) {
                stored.last_seen_ms = Some(now_ms());
            }
        }
        self.save_peers();
        tracing::info!(
            "uploaded {} bytes to {} ({})",
            size,
            peer.device_name,
            if saved.saved_path.is_empty() {
                "no path reported"
            } else {
                "saved"
            }
        );
        Ok(())
    }

    fn transfer_is_cancelled(&self, id: Uuid) -> bool {
        self.transfers
            .read()
            .get(&id)
            .map(|rec| rec.state == TransferState::Cancelled)
            .unwrap_or(true)
    }

    fn note_peer_progress(&self, id: Uuid, sent: u64) {
        {
            let mut transfers = self.transfers.write();
            let Some(rec) = transfers.get_mut(&id) else {
                return;
            };
            let delta = sent.saturating_sub(rec.bytes_transferred);
            rec.bytes_transferred = sent.min(rec.size_bytes);
            if delta > 0 {
                rec.speed.push(delta);
            }
        }
        self.emit_transfer(id, false);
    }

    fn fail_peer_transfer(&self, id: Uuid, error: &str) {
        {
            let mut transfers = self.transfers.write();
            if let Some(rec) = transfers.get_mut(&id) {
                if rec.state == TransferState::Cancelled {
                    return;
                }
                rec.state = TransferState::Failed;
                rec.error = Some(error.to_string());
            }
        }
        self.emit_transfer(id, true);
    }

    // ------------------------------------------------------------------
    // Text to a peer (§11.2, as the client)
    // ------------------------------------------------------------------

    /// `POST /api/v1/messages` on the peer — the TV shows it as a card.
    /// RAM only on both sides: nothing is persisted, nothing enters history,
    /// and the text is never logged.
    pub async fn send_message_to_peer(&self, device_id: Uuid, text: String) -> anyhow::Result<()> {
        if text.is_empty() {
            anyhow::bail!("message must not be empty");
        }
        if text.len() > crate::messages::MAX_MESSAGE_BYTES {
            anyhow::bail!(
                "message too long: {} bytes (max {})",
                text.len(),
                crate::messages::MAX_MESSAGE_BYTES
            );
        }
        let peer = self
            .stored_peer(device_id)
            .ok_or_else(|| anyhow!("device {device_id} is not a paired peer"))?;

        let bytes = text.len();
        let response = self
            .http
            .post(format!("{}/messages", base_url(&peer.address, peer.port)))
            .bearer_auth(&peer.token)
            .timeout(CONTROL_TIMEOUT)
            .json(&serde_json::json!({ "text": text }))
            .send()
            .await
            .with_context(|| format!("could not reach {}", peer.device_name))?;
        if !response.status().is_success() {
            return Err(peer_error("the text was not accepted", response).await);
        }
        // Contents are never logged.
        tracing::debug!("sent {bytes} bytes of text to {}", peer.device_id);
        Ok(())
    }

    /// Re-emit `PeersChanged` on demand (the UI calls this after a manual
    /// "Add by IP", where nothing in the mDNS world changed).
    pub fn refresh_peers(&self) {
        self.emit_peers_changed();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every method here is called from a `#[tauri::command]`, which spawns
    /// it on a multi-threaded runtime — so the futures must be `Send`. The
    /// desktop shell cannot be compiled on CI (no WebView), so the constraint
    /// is asserted *here*, where it is cheap.
    ///
    /// This function is never called: it exists so the compiler has to prove
    /// the bound. It catches the classic mistake of a `parking_lot` guard
    /// created inside a statement that also contains an `.await` — clippy's
    /// `await_holding_lock` only sees bound guards, not temporaries.
    #[allow(dead_code)]
    fn peer_futures_are_send(core: &'static Core) {
        fn assert_send<T: Send>(_: &T) {}

        assert_send(&core.pair_with_peer(String::new(), 48800));
        assert_send(&core.confirm_peer_pairing(String::new(), String::new()));
        assert_send(&core.ping_peer(Uuid::nil()));
        assert_send(&core.send_files_to_peer(Uuid::nil(), Vec::new()));
        assert_send(&core.send_message_to_peer(Uuid::nil(), String::new()));
    }
}
