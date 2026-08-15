//! Ephemeral text messages — PROTOCOL.md §11.
//!
//! Covers both directions over real HTTP: host → client via the outbox
//! drain (at-most-once, cap, long-poll wake) and client → host via
//! `POST /api/v1/messages` (auth, size limit, in-memory retention).

mod common;

use std::time::Duration;

use common::{pair_device, start_core, TestCtx};
use sendro_core::messages::{MAX_INBOX, MAX_MESSAGE_BYTES};
use sendro_core::CoreEvent;
use uuid::Uuid;

async fn poll_outbox(ctx: &TestCtx, token: &str, wait_seconds: u64) -> serde_json::Value {
    ctx.client
        .get(ctx.url(&format!("/api/v1/outbox?waitSeconds={wait_seconds}")))
        .bearer_auth(token)
        .send()
        .await
        .expect("outbox request")
        .json()
        .await
        .expect("outbox json")
}

#[tokio::test]
async fn message_round_trips_through_the_outbox_drain() {
    let ctx = start_core().await;
    let (device_id, token) = pair_device(&ctx).await;

    ctx.core
        .send_message(device_id, "https://example.com/whatever".to_string())
        .expect("send_message");
    assert_eq!(ctx.core.pending_message_count(device_id), 1);

    let body = poll_outbox(&ctx, &token, 0).await;
    let messages = body["messages"].as_array().expect("messages array");
    assert_eq!(messages.len(), 1);
    assert_eq!(messages[0]["text"], "https://example.com/whatever");
    assert_eq!(messages[0]["senderName"], "Semih-PC");
    assert!(messages[0]["messageId"]
        .as_str()
        .expect("messageId")
        .parse::<Uuid>()
        .is_ok());
    assert!(messages[0]["sentAtMs"].as_i64().expect("sentAtMs") > 0);
    // Offers and messages share the envelope; no offers here.
    assert_eq!(body["offers"].as_array().expect("offers").len(), 0);

    // Nothing about a message may reach the disk or the history.
    assert!(ctx.core.history().is_empty());
    for entry in std::fs::read_dir(ctx.data_dir.path()).expect("read data dir") {
        let path = entry.expect("dir entry").path();
        let raw = std::fs::read_to_string(&path).unwrap_or_default();
        assert!(
            !raw.contains("example.com/whatever"),
            "message text leaked into {}",
            path.display()
        );
    }

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn delivery_is_at_most_once() {
    let ctx = start_core().await;
    let (device_id, token) = pair_device(&ctx).await;

    ctx.core
        .send_message(device_id, "one two three".to_string())
        .expect("send_message");

    let first = poll_outbox(&ctx, &token, 0).await;
    assert_eq!(first["messages"].as_array().expect("messages").len(), 1);
    assert_eq!(ctx.core.pending_message_count(device_id), 0);

    // Second poll gets nothing back — not retried, not persisted.
    let second = poll_outbox(&ctx, &token, 0).await;
    assert!(
        second["messages"].is_null() || second["messages"].as_array().unwrap().is_empty(),
        "messages must be absent or empty after the drain: {second}"
    );

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn thirty_two_kib_is_accepted_and_one_byte_more_is_rejected() {
    let ctx = start_core().await;
    let (device_id, token) = pair_device(&ctx).await;

    // Host → client: the core API enforces the same limit.
    let at_limit = "a".repeat(MAX_MESSAGE_BYTES);
    assert_eq!(at_limit.len(), 32 * 1024);
    ctx.core
        .send_message(device_id, at_limit.clone())
        .expect("32 KiB exactly must be accepted");
    let over_limit = "a".repeat(MAX_MESSAGE_BYTES + 1);
    assert!(
        ctx.core.send_message(device_id, over_limit.clone()).is_err(),
        "32 KiB + 1 must be rejected"
    );
    assert_eq!(ctx.core.pending_message_count(device_id), 1);

    // Client → host: 200 at the limit, 413 one byte over (§11.2).
    let resp = ctx
        .client
        .post(ctx.url("/api/v1/messages"))
        .bearer_auth(&token)
        .json(&serde_json::json!({ "text": at_limit }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    assert_eq!(
        resp.json::<serde_json::Value>().await.unwrap()["ok"],
        true
    );

    let resp = ctx
        .client
        .post(ctx.url("/api/v1/messages"))
        .bearer_auth(&token)
        .json(&serde_json::json!({ "text": over_limit }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 413);
    let body: serde_json::Value = resp.json().await.unwrap();
    assert_eq!(body["error"], "bad_request");
    assert_eq!(body["message"], "message too long");

    // Only the accepted one is held.
    assert_eq!(ctx.core.incoming_messages().len(), 1);

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn inbox_caps_at_twenty_and_drops_the_oldest() {
    let ctx = start_core().await;
    let (device_id, token) = pair_device(&ctx).await;

    for i in 0..25 {
        ctx.core
            .send_message(device_id, format!("msg-{i}"))
            .expect("send_message");
    }
    assert_eq!(ctx.core.pending_message_count(device_id), MAX_INBOX);

    let body = poll_outbox(&ctx, &token, 0).await;
    let messages = body["messages"].as_array().expect("messages");
    assert_eq!(messages.len(), MAX_INBOX);
    // The five oldest were evicted; order is preserved.
    assert_eq!(messages[0]["text"], "msg-5");
    assert_eq!(messages[MAX_INBOX - 1]["text"], "msg-24");

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn a_pending_message_wakes_the_long_poll() {
    let ctx = start_core().await;
    let (device_id, token) = pair_device(&ctx).await;

    let url = ctx.url("/api/v1/outbox?waitSeconds=25");
    let client = ctx.client.clone();
    let token_clone = token.clone();
    let poll = tokio::spawn(async move {
        let started = std::time::Instant::now();
        let body: serde_json::Value = client
            .get(url)
            .bearer_auth(token_clone)
            .send()
            .await
            .expect("outbox request")
            .json()
            .await
            .expect("outbox json");
        (body, started.elapsed())
    });

    // Let the long poll actually park on the notifier.
    tokio::time::sleep(Duration::from_millis(300)).await;
    ctx.core
        .send_message(device_id, "wake up".to_string())
        .expect("send_message");

    let (body, elapsed) = tokio::time::timeout(Duration::from_secs(10), poll)
        .await
        .expect("long poll returned in time")
        .expect("poll task");
    assert_eq!(body["messages"][0]["text"], "wake up");
    assert!(
        elapsed < Duration::from_secs(10),
        "should return on the message, not the timeout (took {elapsed:?})"
    );

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn posting_a_message_emits_an_event_and_is_dismissable() {
    let ctx = start_core().await;
    let (_device_id, token) = pair_device(&ctx).await;
    let mut events = ctx.core.subscribe();

    let resp = ctx
        .client
        .post(ctx.url("/api/v1/messages"))
        .bearer_auth(&token)
        .json(&serde_json::json!({ "text": "ekran görüntüsü şurada" }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);

    let (message_id, text, sender) = tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            if let CoreEvent::MessageReceived {
                message_id,
                text,
                sender_name,
                received_at_ms,
            } = events.recv().await.expect("event stream")
            {
                assert!(received_at_ms > 0);
                break (message_id, text, sender_name);
            }
        }
    })
    .await
    .expect("MessageReceived event");
    assert_eq!(text, "ekran görüntüsü şurada");
    assert_eq!(sender, "Semih's iPhone");

    let held = ctx.core.incoming_messages();
    assert_eq!(held.len(), 1);
    assert_eq!(held[0].message_id, message_id);

    assert!(ctx.core.dismiss_message(message_id));
    assert!(ctx.core.incoming_messages().is_empty());
    assert!(!ctx.core.dismiss_message(message_id), "dismiss is final");

    // Received messages never enter history or the data dir.
    assert!(ctx.core.history().is_empty());
    for entry in std::fs::read_dir(ctx.data_dir.path()).expect("read data dir") {
        let path = entry.expect("dir entry").path();
        let raw = std::fs::read_to_string(&path).unwrap_or_default();
        assert!(!raw.contains("ekran görüntüsü"), "leaked into {}", path.display());
    }

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn incoming_messages_are_capped_and_clearable() {
    let ctx = start_core().await;
    let (_device_id, token) = pair_device(&ctx).await;

    for i in 0..24 {
        let resp = ctx
            .client
            .post(ctx.url("/api/v1/messages"))
            .bearer_auth(&token)
            .json(&serde_json::json!({ "text": format!("in-{i}") }))
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), 200);
    }
    let held = ctx.core.incoming_messages();
    assert_eq!(held.len(), 20);
    assert_eq!(held[0].text, "in-4");
    assert_eq!(held[19].text, "in-23");

    ctx.core.clear_messages();
    assert!(ctx.core.incoming_messages().is_empty());

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn unauthenticated_post_messages_is_401() {
    let ctx = start_core().await;

    let resp = ctx
        .client
        .post(ctx.url("/api/v1/messages"))
        .json(&serde_json::json!({ "text": "let me in" }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 401);
    let body: serde_json::Value = resp.json().await.unwrap();
    assert_eq!(body["error"], "unauthorized");

    let resp = ctx
        .client
        .post(ctx.url("/api/v1/messages"))
        .bearer_auth("A".repeat(43))
        .json(&serde_json::json!({ "text": "let me in" }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 401);

    // Nothing was retained.
    assert!(ctx.core.incoming_messages().is_empty());

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn messages_ride_alongside_offers_in_one_response() {
    let ctx = start_core().await;
    let (device_id, token) = pair_device(&ctx).await;

    let file = ctx.recv_dir.path().join("clip.mov");
    common::write_random_file(&file, 4096);
    ctx.core
        .offer_files(device_id, vec![file], false)
        .await
        .expect("offer");
    ctx.core
        .send_message(device_id, "that's the one".to_string())
        .expect("send_message");

    let body = poll_outbox(&ctx, &token, 5).await;
    assert_eq!(body["offers"].as_array().expect("offers").len(), 1);
    assert_eq!(body["messages"][0]["text"], "that's the one");

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn sending_to_an_unpaired_device_fails() {
    let ctx = start_core().await;
    assert!(ctx
        .core
        .send_message(Uuid::new_v4(), "nobody home".to_string())
        .is_err());
    ctx.core.shutdown().await;
}
