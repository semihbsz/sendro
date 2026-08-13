//! Shared helpers for integration tests: boot an in-process Core on an
//! ephemeral port and pair a fake iPhone over real HTTP.

#![allow(dead_code)]

use std::sync::Arc;
use std::time::Duration;

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use sendro_core::{Core, CoreConfig, CoreEvent};
use tempfile::TempDir;
use uuid::Uuid;

pub struct TestCtx {
    pub core: Arc<Core>,
    pub base: String,
    pub client: reqwest::Client,
    // Kept alive so the temp dirs outlive the test.
    pub data_dir: TempDir,
    pub recv_dir: TempDir,
}

impl TestCtx {
    pub fn url(&self, path: &str) -> String {
        format!("{}{}", self.base, path)
    }
}

pub async fn start_core() -> TestCtx {
    start_core_with(|_| {}).await
}

pub async fn start_core_with(tweak: impl FnOnce(&mut CoreConfig)) -> TestCtx {
    let data_dir = TempDir::new().expect("tempdir");
    let recv_dir = TempDir::new().expect("tempdir");
    let mut cfg = CoreConfig::new("Semih-PC", data_dir.path(), recv_dir.path());
    cfg.preferred_port = 0; // ephemeral
    cfg.mdns_enabled = false;
    tweak(&mut cfg);
    let core = Core::start(cfg).await.expect("core start");
    let base = format!("http://127.0.0.1:{}", core.info().api_port);
    let client = reqwest::Client::builder()
        .no_proxy()
        .timeout(Duration::from_secs(60))
        .build()
        .expect("reqwest client");
    TestCtx {
        core,
        base,
        client,
        data_dir,
        recv_dir,
    }
}

/// Pair a simulated iPhone over real HTTP (§4), reading the 6-digit code
/// from the PairingStarted event exactly like the Windows UI would show it.
pub async fn pair_device(ctx: &TestCtx) -> (Uuid, String) {
    let device_id = Uuid::new_v4();
    let mut events = ctx.core.subscribe();

    let start: serde_json::Value = ctx
        .client
        .post(ctx.url("/api/v1/pair/start"))
        .json(&serde_json::json!({
            "deviceId": device_id,
            "deviceName": "Semih's iPhone",
            "platform": "ios",
            "protocolVersion": 1
        }))
        .send()
        .await
        .expect("pair/start request")
        .json()
        .await
        .expect("pair/start json");

    let pairing_id: Uuid = start["pairingId"]
        .as_str()
        .expect("pairingId")
        .parse()
        .expect("uuid");
    let salt = URL_SAFE_NO_PAD
        .decode(start["salt"].as_str().expect("salt"))
        .expect("salt base64url");
    assert_eq!(start["expiresInSeconds"].as_u64(), Some(120));

    let code = tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            match events.recv().await.expect("event stream") {
                CoreEvent::PairingStarted {
                    pairing_id: pid,
                    code,
                    ..
                } if pid == pairing_id => break code,
                _ => {}
            }
        }
    })
    .await
    .expect("PairingStarted event");

    let proof = sendro_core::pairing::compute_proof(&code, &salt, pairing_id, device_id);
    let confirm = ctx
        .client
        .post(ctx.url("/api/v1/pair/confirm"))
        .json(&serde_json::json!({
            "pairingId": pairing_id,
            "deviceId": device_id,
            "proof": proof
        }))
        .send()
        .await
        .expect("pair/confirm request");
    assert_eq!(confirm.status(), 200, "pair/confirm should succeed");
    let confirm: serde_json::Value = confirm.json().await.expect("confirm json");
    let token = confirm["deviceToken"].as_str().expect("deviceToken").to_string();
    assert_eq!(token.len(), 43, "32 bytes base64url no-pad is 43 chars");
    (device_id, token)
}

/// Write `len` pseudo-random bytes to `path` and return them.
pub fn write_random_file(path: &std::path::Path, len: usize) -> Vec<u8> {
    use rand::RngCore;
    let mut data = vec![0u8; len];
    rand::rngs::OsRng.fill_bytes(&mut data);
    std::fs::write(path, &data).expect("write test file");
    data
}

pub fn sha256_hex(data: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    hex::encode(Sha256::digest(data))
}
