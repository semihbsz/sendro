//! In-process HTTP tests: auth behavior and pairing over the wire.

mod common;

use common::{pair_device, start_core};

#[tokio::test]
async fn info_is_unauthenticated_and_correctly_shaped() {
    let ctx = start_core().await;
    let resp = ctx.client.get(ctx.url("/api/v1/info")).send().await.unwrap();
    assert_eq!(resp.status(), 200);
    let body: serde_json::Value = resp.json().await.unwrap();
    assert_eq!(body["app"], "sendro");
    assert_eq!(body["protocolVersion"], 1);
    assert_eq!(body["deviceName"], "Semih-PC");
    assert_eq!(body["platform"], "windows");
    assert_eq!(
        body["apiPort"].as_u64().unwrap(),
        ctx.core.info().api_port as u64
    );
    assert!(body["deviceId"].as_str().unwrap().parse::<uuid::Uuid>().is_ok());
    ctx.core.shutdown().await;
}

#[tokio::test]
async fn missing_and_unknown_tokens_get_401() {
    let ctx = start_core().await;

    // No Authorization header.
    let resp = ctx.client.get(ctx.url("/api/v1/ping")).send().await.unwrap();
    assert_eq!(resp.status(), 401);
    let body: serde_json::Value = resp.json().await.unwrap();
    assert_eq!(body["error"], "unauthorized");

    // Unknown bearer token.
    let resp = ctx
        .client
        .get(ctx.url("/api/v1/outbox"))
        .bearer_auth("A".repeat(43))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 401);
    ctx.core.shutdown().await;
}

#[tokio::test]
async fn paired_token_gets_200() {
    let ctx = start_core().await;
    let (device_id, token) = pair_device(&ctx).await;

    let resp = ctx
        .client
        .get(ctx.url("/api/v1/ping"))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let body: serde_json::Value = resp.json().await.unwrap();
    assert_eq!(body["ok"], true);
    assert_eq!(body["deviceName"], "Semih-PC");

    // The device shows up as trusted; only the token hash is stored.
    let devices = ctx.core.trusted_devices();
    assert_eq!(devices.len(), 1);
    assert_eq!(devices[0].device_id, device_id);
    let stored = std::fs::read_to_string(ctx.data_dir.path().join("trusted_devices.json")).unwrap();
    assert!(!stored.contains(&token), "raw token must never be persisted");

    // Revoking kills the token.
    assert!(ctx.core.revoke_device(device_id));
    let resp = ctx
        .client
        .get(ctx.url("/api/v1/ping"))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 401);
    ctx.core.shutdown().await;
}

#[tokio::test]
async fn pairing_wrong_proof_and_attempt_limit_over_http() {
    let ctx = start_core().await;
    let device_id = uuid::Uuid::new_v4();

    let start: serde_json::Value = ctx
        .client
        .post(ctx.url("/api/v1/pair/start"))
        .json(&serde_json::json!({
            "deviceId": device_id,
            "deviceName": "Attacker",
            "platform": "ios",
            "protocolVersion": 1
        }))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let pairing_id = start["pairingId"].as_str().unwrap();

    // 4 wrong attempts → 403; 5th → 429 (session burned).
    for _ in 0..4 {
        let resp = ctx
            .client
            .post(ctx.url("/api/v1/pair/confirm"))
            .json(&serde_json::json!({
                "pairingId": pairing_id,
                "deviceId": device_id,
                "proof": "bm9wZQ"
            }))
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), 403);
    }
    let resp = ctx
        .client
        .post(ctx.url("/api/v1/pair/confirm"))
        .json(&serde_json::json!({
            "pairingId": pairing_id,
            "deviceId": device_id,
            "proof": "bm9wZQ"
        }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 429);

    // Burned session → 400 from now on.
    let resp = ctx
        .client
        .post(ctx.url("/api/v1/pair/confirm"))
        .json(&serde_json::json!({
            "pairingId": pairing_id,
            "deviceId": device_id,
            "proof": "bm9wZQ"
        }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 400);
    ctx.core.shutdown().await;
}

#[tokio::test]
async fn unsupported_protocol_version_is_rejected() {
    let ctx = start_core().await;
    let resp = ctx
        .client
        .post(ctx.url("/api/v1/pair/start"))
        .json(&serde_json::json!({
            "deviceId": uuid::Uuid::new_v4(),
            "deviceName": "Future iPhone",
            "platform": "ios",
            "protocolVersion": 99
        }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 400);
    ctx.core.shutdown().await;
}
