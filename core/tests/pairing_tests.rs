use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use sendro_core::pairing::{
    compute_proof, ConfirmOutcome, PairingManager, MAX_ATTEMPTS, MAX_CONCURRENT_SESSIONS,
};
use sha2::Sha256;
use uuid::Uuid;

fn start_session(mgr: &PairingManager) -> sendro_core::pairing::PairingSession {
    mgr.start(Uuid::new_v4(), "Semih's iPhone".into(), "ios".into())
        .expect("session")
}

/// Full HKDF/HMAC round trip: an independently computed proof (spelled out
/// from PROTOCOL.md §4.2, not via compute_proof) must be accepted.
#[test]
fn pairing_round_trip_success() {
    let mgr = PairingManager::new();
    let session = start_session(&mgr);

    // K = HKDF-SHA256(ikm=UTF8(code), salt, info="sendro-pair-v1", len=32)
    let hk = Hkdf::<Sha256>::new(Some(&session.salt), session.code.as_bytes());
    let mut key = [0u8; 32];
    hk.expand(b"sendro-pair-v1", &mut key).unwrap();
    // proof = base64url(HMAC-SHA256(K, "{pairingId}:{deviceId}"))
    let mut mac = Hmac::<Sha256>::new_from_slice(&key).unwrap();
    mac.update(format!("{}:{}", session.pairing_id, session.device_id).as_bytes());
    let proof = URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes());

    // Sanity: the helper agrees with the hand-rolled derivation.
    assert_eq!(
        proof,
        compute_proof(
            &session.code,
            &session.salt,
            session.pairing_id,
            session.device_id
        )
    );

    match mgr.confirm(session.pairing_id, session.device_id, &proof) {
        ConfirmOutcome::Success { device_id, .. } => assert_eq!(device_id, session.device_id),
        other => panic!("expected success, got {other:?}"),
    }
    // Session is consumed — replay fails.
    assert!(matches!(
        mgr.confirm(session.pairing_id, session.device_id, &proof),
        ConfirmOutcome::BadSession
    ));
}

#[test]
fn wrong_code_fails() {
    let mgr = PairingManager::new();
    let session = start_session(&mgr);
    let wrong_code = if session.code == "000000" { "000001" } else { "000000" };
    let proof = compute_proof(
        wrong_code,
        &session.salt,
        session.pairing_id,
        session.device_id,
    );
    assert!(matches!(
        mgr.confirm(session.pairing_id, session.device_id, &proof),
        ConfirmOutcome::WrongProof { .. }
    ));
    // The right code still works afterwards (attempt counted, not burned).
    let good = compute_proof(
        &session.code,
        &session.salt,
        session.pairing_id,
        session.device_id,
    );
    assert!(matches!(
        mgr.confirm(session.pairing_id, session.device_id, &good),
        ConfirmOutcome::Success { .. }
    ));
}

#[test]
fn attempt_limit_burns_session() {
    let mgr = PairingManager::new();
    let session = start_session(&mgr);
    let bad = "not-a-valid-proof";
    for attempt in 1..MAX_ATTEMPTS {
        assert!(
            matches!(
                mgr.confirm(session.pairing_id, session.device_id, bad),
                ConfirmOutcome::WrongProof { .. }
            ),
            "attempt {attempt} should count but not burn"
        );
    }
    assert!(matches!(
        mgr.confirm(session.pairing_id, session.device_id, bad),
        ConfirmOutcome::TooManyAttempts { .. }
    ));
    // Burned: even the correct proof is now rejected.
    let good = compute_proof(
        &session.code,
        &session.salt,
        session.pairing_id,
        session.device_id,
    );
    assert!(matches!(
        mgr.confirm(session.pairing_id, session.device_id, good.as_str()),
        ConfirmOutcome::BadSession
    ));
}

#[test]
fn concurrent_session_cap() {
    let mgr = PairingManager::new();
    for _ in 0..MAX_CONCURRENT_SESSIONS {
        start_session(&mgr);
    }
    assert!(mgr
        .start(Uuid::new_v4(), "One Too Many".into(), "ios".into())
        .is_err());
}

#[test]
fn code_is_six_digits_and_salt_is_16_bytes() {
    let mgr = PairingManager::new();
    let session = start_session(&mgr);
    assert_eq!(session.code.len(), 6);
    assert!(session.code.bytes().all(|b| b.is_ascii_digit()));
    assert_eq!(session.salt.len(), 16);
}
