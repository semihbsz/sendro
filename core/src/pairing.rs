//! Pairing per PROTOCOL.md §4.
//!
//! 6-digit crypto-random code → HKDF-SHA256(ikm = code, salt, info =
//! "sendro-pair-v1") → 32-byte key; proof = base64url-no-pad(
//! HMAC-SHA256(key, "{pairingId}:{deviceId}")). Constant-time compare,
//! 120 s expiry, max 5 attempts per session, max 3 concurrent sessions.

use std::collections::HashMap;
use std::time::{Duration, Instant};

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use parking_lot::Mutex;
use rand::rngs::OsRng;
use rand::{Rng, RngCore};
use sha2::Sha256;
use subtle::ConstantTimeEq;
use uuid::Uuid;

pub const PAIRING_TTL: Duration = Duration::from_secs(120);
pub const MAX_ATTEMPTS: u32 = 5;
pub const MAX_CONCURRENT_SESSIONS: usize = 3;
pub const HKDF_INFO: &[u8] = b"sendro-pair-v1";

#[derive(Debug, Clone)]
pub struct PairingSession {
    pub pairing_id: Uuid,
    pub code: String,
    pub salt: [u8; 16],
    /// The client that started the session; `Uuid::nil()` for a QR session
    /// (§13), where the client only shows up at confirm time.
    pub device_id: Uuid,
    /// Empty for a QR session — the confirming client supplies its own name.
    pub device_name: String,
    /// Empty for a QR session.
    pub platform: String,
    pub created_at: Instant,
    pub attempts: u32,
}

impl PairingSession {
    fn expired(&self) -> bool {
        self.created_at.elapsed() > PAIRING_TTL
    }
}

#[derive(Debug)]
pub enum StartError {
    /// Already at the concurrent-session cap → HTTP 429.
    TooManySessions,
}

#[derive(Debug)]
pub enum ConfirmOutcome {
    /// Proof verified; session consumed. Carries the client device metadata.
    Success {
        device_id: Uuid,
        device_name: String,
        platform: String,
    },
    /// Unknown or expired session → HTTP 400.
    BadSession,
    /// Wrong proof; attempt counted, session still alive → HTTP 403.
    WrongProof { pairing_id: Uuid },
    /// Attempt limit reached; session burned → HTTP 429.
    TooManyAttempts { pairing_id: Uuid },
}

#[derive(Default)]
pub struct PairingManager {
    sessions: Mutex<HashMap<Uuid, PairingSession>>,
}

impl PairingManager {
    pub fn new() -> Self {
        Self::default()
    }

    /// Start a pairing session. Returns the new session (the caller shows
    /// `code` in the UI and returns `salt` to the client).
    pub fn start(
        &self,
        device_id: Uuid,
        device_name: String,
        platform: String,
    ) -> Result<PairingSession, StartError> {
        let mut sessions = self.sessions.lock();
        sessions.retain(|_, s| !s.expired());
        if sessions.len() >= MAX_CONCURRENT_SESSIONS {
            return Err(StartError::TooManySessions);
        }
        Ok(insert_session(&mut sessions, device_id, device_name, platform))
    }

    /// Start a session for QR display (§13).
    ///
    /// Identical machinery to [`PairingManager::start`] — same TTL, same
    /// attempt limit, same code space — except for two things: the client is
    /// not known yet (it identifies itself in `POST /api/v1/pair/confirm`;
    /// empty `device_name`/`platform` here mean "take it from the confirm
    /// request"), and this never fails. The concurrency cap exists to bound
    /// *network-initiated* pairing attempts; a local user pressing the QR
    /// button instead evicts the oldest session.
    pub fn start_qr(&self) -> PairingSession {
        let mut sessions = self.sessions.lock();
        sessions.retain(|_, s| !s.expired());
        while sessions.len() >= MAX_CONCURRENT_SESSIONS {
            let Some(oldest) = sessions
                .values()
                .min_by_key(|s| s.created_at)
                .map(|s| s.pairing_id)
            else {
                break;
            };
            sessions.remove(&oldest);
        }
        insert_session(&mut sessions, Uuid::nil(), String::new(), String::new())
    }

    /// Verify a proof for `pairing_id` from `device_id`.
    pub fn confirm(&self, pairing_id: Uuid, device_id: Uuid, proof: &str) -> ConfirmOutcome {
        let mut sessions = self.sessions.lock();
        let Some(session) = sessions.get_mut(&pairing_id) else {
            return ConfirmOutcome::BadSession;
        };
        if session.expired() {
            sessions.remove(&pairing_id);
            return ConfirmOutcome::BadSession;
        }
        // The proof binds the deviceId; a different deviceId than the one
        // that started the session simply produces a different expected MAC,
        // but we recompute against the *claimed* deviceId per §4.2.
        let expected = compute_proof_bytes(&session.code, &session.salt, pairing_id, device_id);
        let provided = URL_SAFE_NO_PAD.decode(proof.as_bytes()).unwrap_or_default();
        let ok = provided.len() == expected.len()
            && bool::from(provided.as_slice().ct_eq(expected.as_slice()));
        if ok {
            let session = sessions.remove(&pairing_id).expect("session present");
            return ConfirmOutcome::Success {
                device_id,
                device_name: session.device_name,
                platform: session.platform,
            };
        }
        session.attempts += 1;
        if session.attempts >= MAX_ATTEMPTS {
            sessions.remove(&pairing_id);
            return ConfirmOutcome::TooManyAttempts { pairing_id };
        }
        ConfirmOutcome::WrongProof { pairing_id }
    }

    /// Drop expired sessions (called from the maintenance sweep).
    pub fn purge_expired(&self) -> Vec<Uuid> {
        let mut sessions = self.sessions.lock();
        let expired: Vec<Uuid> = sessions
            .iter()
            .filter(|(_, s)| s.expired())
            .map(|(id, _)| *id)
            .collect();
        for id in &expired {
            sessions.remove(id);
        }
        expired
    }
}

/// Build a session with a fresh code + salt and insert it. Called with the
/// session map already locked so the cap check and the insert cannot race.
fn insert_session(
    sessions: &mut HashMap<Uuid, PairingSession>,
    device_id: Uuid,
    device_name: String,
    platform: String,
) -> PairingSession {
    let mut salt = [0u8; 16];
    OsRng.fill_bytes(&mut salt);
    let session = PairingSession {
        pairing_id: Uuid::new_v4(),
        code: generate_code(),
        salt,
        device_id,
        device_name,
        platform,
        created_at: Instant::now(),
        attempts: 0,
    };
    sessions.insert(session.pairing_id, session.clone());
    session
}

/// 6-digit crypto-random numeric code (leading zeros preserved).
fn generate_code() -> String {
    let n: u32 = OsRng.gen_range(0..1_000_000);
    format!("{n:06}")
}

/// K = HKDF-SHA256(ikm = UTF8(code), salt, info = "sendro-pair-v1", len = 32)
pub fn derive_key(code: &str, salt: &[u8]) -> [u8; 32] {
    let hk = Hkdf::<Sha256>::new(Some(salt), code.as_bytes());
    let mut okm = [0u8; 32];
    hk.expand(HKDF_INFO, &mut okm)
        .expect("32 bytes is a valid HKDF-SHA256 output length");
    okm
}

fn compute_proof_bytes(code: &str, salt: &[u8], pairing_id: Uuid, device_id: Uuid) -> Vec<u8> {
    let key = derive_key(code, salt);
    let mut mac =
        Hmac::<Sha256>::new_from_slice(&key).expect("HMAC accepts any key length");
    mac.update(format!("{pairing_id}:{device_id}").as_bytes());
    mac.finalize().into_bytes().to_vec()
}

/// proof = base64url-no-pad(HMAC-SHA256(K, "{pairingId}:{deviceId}")).
/// Public so tests (and a future desktop-side client) can act as the client.
pub fn compute_proof(code: &str, salt: &[u8], pairing_id: Uuid, device_id: Uuid) -> String {
    URL_SAFE_NO_PAD.encode(compute_proof_bytes(code, salt, pairing_id, device_id))
}

/// Mint a fresh 32-byte device token, returned as base64url no-pad (43 chars).
pub fn mint_device_token() -> String {
    let mut bytes = [0u8; 32];
    OsRng.fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}
