//! QR pairing — PROTOCOL.md §13.
//!
//! The QR session must be an ordinary §4 session (same expiry, same attempt
//! limits, same event) whose parameters happen to be rendered as a URL, so
//! these tests drive a simulated scan all the way to a real
//! `POST /api/v1/pair/confirm`.

mod common;

use std::time::Duration;

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use common::start_core_with;
use sendro_core::net::{classify, KIND_HOTSPOT, KIND_LAN, KIND_OTHER};
use sendro_core::qr::parse_pair_url;
use sendro_core::CoreEvent;
use uuid::Uuid;

const TURKISH_NAME: &str = "Semih'in Bilgisayarı & Ofis PC";

#[tokio::test]
async fn qr_url_round_trips_with_turkish_characters_and_spaces() {
    let ctx = start_core_with(|cfg| cfg.device_name = TURKISH_NAME.to_string()).await;

    let qr = ctx.core.start_qr_pairing();
    assert_eq!(qr.expires_in_seconds, 120, "§4.1 TTL, unchanged for QR");
    assert_eq!(qr.code.len(), 6);
    assert!(qr.code.bytes().all(|b| b.is_ascii_digit()));
    assert!(
        !qr.urls.is_empty(),
        "expected at least one routable address on this host"
    );

    for entry in &qr.urls {
        // Every value percent-encoded → the whole URL is ASCII and has no
        // raw space or stray separator.
        assert!(entry.url.is_ascii(), "{}", entry.url);
        assert!(!entry.url.contains(' '));
        assert!([KIND_LAN, KIND_HOTSPOT, KIND_OTHER].contains(&entry.kind.as_str()));

        let parsed = parse_pair_url(&entry.url).expect("parses back");
        assert_eq!(parsed.version, 1);
        assert_eq!(parsed.host, entry.address);
        assert_eq!(parsed.port, ctx.core.info().api_port);
        assert_eq!(parsed.device_id, ctx.core.info().device_id);
        // The interesting bit: spaces, an apostrophe, "&" and Turkish
        // characters survive the round trip byte for byte.
        assert_eq!(parsed.device_name, TURKISH_NAME);
        assert_eq!(parsed.pairing_id, qr.pairing_id);
        assert_eq!(parsed.salt, qr.salt);
        assert_eq!(parsed.code, qr.code);
    }

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn scanning_a_qr_url_pairs_over_the_normal_endpoint() {
    let ctx = start_core_with(|cfg| cfg.device_name = TURKISH_NAME.to_string()).await;
    let mut events = ctx.core.subscribe();

    let qr = ctx.core.start_qr_pairing();

    // The typed code still reaches the UI, so the fallback keeps working.
    let event_code = tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            if let CoreEvent::PairingStarted {
                pairing_id, code, ..
            } = events.recv().await.expect("event stream")
            {
                if pairing_id == qr.pairing_id {
                    break code;
                }
            }
        }
    })
    .await
    .expect("PairingStarted event");
    assert_eq!(event_code, qr.code);

    // What the phone does after a scan: parse, sanity-check /info, prove.
    let scanned = parse_pair_url(&qr.urls[0].url).expect("scan parses");
    let info: serde_json::Value = ctx
        .client
        .get(ctx.url("/api/v1/info"))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(info["app"], "sendro");
    assert_eq!(info["deviceId"].as_str().unwrap(), scanned.device_id.to_string());

    let device_id = Uuid::new_v4();
    let salt = URL_SAFE_NO_PAD.decode(scanned.salt.as_bytes()).unwrap();
    let proof =
        sendro_core::pairing::compute_proof(&scanned.code, &salt, scanned.pairing_id, device_id);
    let resp = ctx
        .client
        .post(ctx.url("/api/v1/pair/confirm"))
        .json(&serde_json::json!({
            "pairingId": scanned.pairing_id,
            "deviceId": device_id,
            "proof": proof,
            "deviceName": "Semih's iPhone",
            "platform": "ios",
        }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let body: serde_json::Value = resp.json().await.unwrap();
    assert_eq!(body["deviceToken"].as_str().unwrap().len(), 43);

    // The scanned session produced a normal trusted device.
    let devices = ctx.core.trusted_devices();
    assert_eq!(devices.len(), 1);
    assert_eq!(devices[0].device_id, device_id);
    assert_eq!(devices[0].device_name, "Semih's iPhone");

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn qr_session_keeps_the_attempt_limit() {
    let ctx = start_core_with(|_| {}).await;
    let qr = ctx.core.start_qr_pairing();

    // Same 5-attempt budget as a typed session (§4.2): 4 × 403, then 429.
    for _ in 0..4 {
        let resp = ctx
            .client
            .post(ctx.url("/api/v1/pair/confirm"))
            .json(&serde_json::json!({
                "pairingId": qr.pairing_id,
                "deviceId": Uuid::new_v4(),
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
            "pairingId": qr.pairing_id,
            "deviceId": Uuid::new_v4(),
            "proof": "bm9wZQ"
        }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 429);

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn addresses_are_classified_and_ordered() {
    // Pure classification (mirrors the unit tests, asserted from outside the
    // crate because the desktop renders on these exact strings).
    assert_eq!(classify("192.168.137.9".parse().unwrap()), KIND_HOTSPOT);
    assert_eq!(classify("172.20.10.5".parse().unwrap()), KIND_HOTSPOT);
    assert_eq!(classify("192.168.1.20".parse().unwrap()), KIND_LAN);
    assert_eq!(classify("10.1.2.3".parse().unwrap()), KIND_LAN);
    assert_eq!(classify("172.20.11.5".parse().unwrap()), KIND_LAN);
    assert_eq!(classify("203.0.113.7".parse().unwrap()), KIND_OTHER);
    assert_eq!(classify("169.254.4.4".parse().unwrap()), KIND_OTHER);

    let ctx = start_core_with(|_| {}).await;
    let ifaces = ctx.core.network_interfaces();
    assert!(
        ifaces.iter().all(|i| i.address != "127.0.0.1" && i.is_up),
        "loopback must never be offered as a way in"
    );
    let rank = |kind: &str| match kind {
        KIND_LAN => 0,
        KIND_HOTSPOT => 1,
        _ => 2,
    };
    let ranks: Vec<u8> = ifaces.iter().map(|i| rank(&i.kind)).collect();
    assert!(
        ranks.windows(2).all(|w| w[0] <= w[1]),
        "lan first, then hotspot, then other: {ranks:?}"
    );
    // The QR list is built from the same enumeration, in the same order.
    let qr = ctx.core.start_qr_pairing();
    let qr_addrs: Vec<String> = qr.urls.iter().map(|u| u.address.clone()).collect();
    let if_addrs: Vec<String> = ifaces.iter().map(|i| i.address.clone()).collect();
    assert_eq!(qr_addrs, if_addrs);

    ctx.core.shutdown().await;
}
