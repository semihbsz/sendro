//! End-to-end in-process flows: pair → offer → outbox → accept → download
//! (full and ranged resume) → status → history.

mod common;

use common::{pair_device, sha256_hex, start_core, write_random_file};
use sendro_core::TransferState;

#[tokio::test]
async fn full_transfer_flow_with_unicode_name() {
    let ctx = start_core().await;
    let (device_id, token) = pair_device(&ctx).await;

    let src_dir = tempfile::tempdir().unwrap();
    let file_path = src_dir.path().join("Çekmeköy Reşadiye Drone.MOV");
    let data = write_random_file(&file_path, 2 * 1024 * 1024 + 333);
    let expected_sha = sha256_hex(&data);

    // Host offers the file (hashing happens before the offer is published).
    let summaries = ctx
        .core
        .offer_files(device_id, vec![file_path.clone()], false)
        .await
        .unwrap();
    assert_eq!(summaries.len(), 1);
    let offer = &summaries[0];
    assert_eq!(offer.state, TransferState::Offered);
    assert_eq!(offer.sha256.as_deref(), Some(expected_sha.as_str()));
    assert_eq!(offer.file_name, "Çekmeköy Reşadiye Drone.MOV");
    let transfer_id = offer.transfer_id;

    // Client long-polls its outbox and sees the offer.
    let outbox: serde_json::Value = ctx
        .client
        .get(ctx.url("/api/v1/outbox?waitSeconds=5"))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let offers = outbox["offers"].as_array().unwrap();
    assert_eq!(offers.len(), 1);
    let wire = &offers[0];
    assert_eq!(wire["transferId"].as_str().unwrap(), transfer_id.to_string());
    assert_eq!(wire["fileName"], "Çekmeköy Reşadiye Drone.MOV");
    assert_eq!(wire["extension"], "MOV");
    assert_eq!(wire["mimeType"], "video/quicktime");
    assert_eq!(wire["sha256"].as_str().unwrap(), expected_sha);
    assert_eq!(wire["sizeBytes"].as_u64().unwrap(), data.len() as u64);
    assert_eq!(wire["senderName"], "Semih-PC");
    assert_eq!(wire["autoAccept"], false);

    // Download before accept is refused.
    let resp = ctx
        .client
        .get(ctx.url(&format!("/api/v1/transfers/{transfer_id}/file")))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 409);

    // Accept, then download fully.
    let resp = ctx
        .client
        .post(ctx.url(&format!("/api/v1/transfers/{transfer_id}/accept")))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);

    let resp = ctx
        .client
        .get(ctx.url(&format!("/api/v1/transfers/{transfer_id}/file")))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let headers = resp.headers().clone();
    assert_eq!(
        headers.get("etag").unwrap().to_str().unwrap(),
        format!("\"{expected_sha}\"")
    );
    assert_eq!(
        headers.get("x-sendro-sha256").unwrap().to_str().unwrap(),
        expected_sha
    );
    assert_eq!(headers.get("accept-ranges").unwrap(), "bytes");
    assert_eq!(headers.get("content-type").unwrap(), "video/quicktime");
    assert_eq!(
        headers.get("content-length").unwrap().to_str().unwrap(),
        data.len().to_string()
    );
    assert_eq!(
        headers.get("content-disposition").unwrap().to_str().unwrap(),
        "attachment; filename*=UTF-8''%C3%87ekmek%C3%B6y%20Re%C5%9Fadiye%20Drone.MOV"
    );
    let body = resp.bytes().await.unwrap();
    assert_eq!(body.len(), data.len());
    assert_eq!(sha256_hex(&body), expected_sha, "downloaded bytes must hash-match");

    // Client reports its progress through the status pipeline (§6.5).
    for (state, extra) in [
        ("downloading", serde_json::json!({"bytesReceived": data.len()})),
        ("verifying", serde_json::json!({})),
        ("verified", serde_json::json!({})),
        ("saving", serde_json::json!({})),
        ("completed", serde_json::json!({"savedTo": "photos"})),
    ] {
        let mut payload = serde_json::json!({"state": state});
        payload
            .as_object_mut()
            .unwrap()
            .extend(extra.as_object().unwrap().clone());
        let resp = ctx
            .client
            .post(ctx.url(&format!("/api/v1/transfers/{transfer_id}/status")))
            .bearer_auth(&token)
            .json(&payload)
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), 200, "status {state}");
    }

    let queue = ctx.core.queue();
    let entry = queue.iter().find(|t| t.transfer_id == transfer_id).unwrap();
    assert_eq!(entry.state, TransferState::Completed);
    assert_eq!(entry.bytes_transferred, data.len() as u64);

    let history = ctx.core.history();
    assert_eq!(history.len(), 1);
    assert_eq!(history[0].transfer_id, transfer_id);
    assert_eq!(history[0].final_state, TransferState::Completed);
    assert!(history[0].verified);
    assert_eq!(history[0].direction, "outgoing");
    assert_eq!(history[0].peer_name, "Semih's iPhone");

    // Completed offers no longer appear in the outbox.
    let outbox: serde_json::Value = ctx
        .client
        .get(ctx.url("/api/v1/outbox"))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(outbox["offers"].as_array().unwrap().is_empty());

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn ranged_resume_produces_byte_identical_file() {
    let ctx = start_core().await;
    let (device_id, token) = pair_device(&ctx).await;

    let src_dir = tempfile::tempdir().unwrap();
    let file_path = src_dir.path().join("final gerçekten final 5.mp4");
    let data = write_random_file(&file_path, 3 * 1024 * 1024 + 41);
    let expected_sha = sha256_hex(&data);
    let half = data.len() / 2;

    let summaries = ctx
        .core
        .offer_files(device_id, vec![file_path], false)
        .await
        .unwrap();
    let transfer_id = summaries[0].transfer_id;

    ctx.client
        .post(ctx.url(&format!("/api/v1/transfers/{transfer_id}/accept")))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();

    // First half: closed range.
    let resp = ctx
        .client
        .get(ctx.url(&format!("/api/v1/transfers/{transfer_id}/file")))
        .bearer_auth(&token)
        .header("Range", format!("bytes=0-{}", half - 1))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 206);
    assert_eq!(
        resp.headers().get("content-range").unwrap().to_str().unwrap(),
        format!("bytes 0-{}/{}", half - 1, data.len())
    );
    let first = resp.bytes().await.unwrap().to_vec();
    assert_eq!(first.len(), half);

    // "Reconnect": resume from byte `half` with If-Range guarding the ETag.
    let resp = ctx
        .client
        .get(ctx.url(&format!("/api/v1/transfers/{transfer_id}/file")))
        .bearer_auth(&token)
        .header("Range", format!("bytes={half}-"))
        .header("If-Range", format!("\"{expected_sha}\""))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 206);
    assert_eq!(
        resp.headers().get("content-range").unwrap().to_str().unwrap(),
        format!("bytes {}-{}/{}", half, data.len() - 1, data.len())
    );
    let second = resp.bytes().await.unwrap().to_vec();

    let mut joined = first;
    joined.extend_from_slice(&second);
    assert_eq!(joined.len(), data.len());
    assert_eq!(joined, data, "resumed download must be byte-identical");
    assert_eq!(sha256_hex(&joined), expected_sha);

    // If-Range with a stale validator falls back to the full body (200).
    let resp = ctx
        .client
        .get(ctx.url(&format!("/api/v1/transfers/{transfer_id}/file")))
        .bearer_auth(&token)
        .header("Range", format!("bytes={half}-"))
        .header("If-Range", "\"deadbeef\"")
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);

    // Invalid + multi ranges → 416.
    for range in ["bytes=999999999-", "bytes=0-10,20-30", "bytes=xyz"] {
        let resp = ctx
            .client
            .get(ctx.url(&format!("/api/v1/transfers/{transfer_id}/file")))
            .bearer_auth(&token)
            .header("Range", range)
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), 416, "range {range:?}");
    }

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn download_is_scoped_to_the_offered_device() {
    let ctx = start_core().await;
    let (device_a, _token_a) = pair_device(&ctx).await;
    let (_device_b, token_b) = pair_device(&ctx).await;

    let src_dir = tempfile::tempdir().unwrap();
    let file_path = src_dir.path().join("secret.bin");
    write_random_file(&file_path, 64 * 1024);

    let summaries = ctx
        .core
        .offer_files(device_a, vec![file_path], false)
        .await
        .unwrap();
    let transfer_id = summaries[0].transfer_id;

    // Device B cannot see or fetch device A's transfer.
    let outbox: serde_json::Value = ctx
        .client
        .get(ctx.url("/api/v1/outbox"))
        .bearer_auth(&token_b)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(outbox["offers"].as_array().unwrap().is_empty());

    for path in [
        format!("/api/v1/transfers/{transfer_id}/file"),
        format!("/api/v1/transfers/{transfer_id}/accept"),
    ] {
        let req = if path.ends_with("/file") {
            ctx.client.get(ctx.url(&path))
        } else {
            ctx.client.post(ctx.url(&path))
        };
        let resp = req.bearer_auth(&token_b).send().await.unwrap();
        assert_eq!(resp.status(), 404, "{path}");
    }
    ctx.core.shutdown().await;
}

#[tokio::test]
async fn outbox_long_poll_wakes_on_new_offer() {
    let ctx = start_core().await;
    let (device_id, token) = pair_device(&ctx).await;

    let src_dir = tempfile::tempdir().unwrap();
    let file_path = src_dir.path().join("late.bin");
    write_random_file(&file_path, 128 * 1024);

    let core = ctx.core.clone();
    let offer_task = tokio::spawn(async move {
        tokio::time::sleep(std::time::Duration::from_millis(300)).await;
        core.offer_files(device_id, vec![file_path], false)
            .await
            .unwrap();
    });

    let started = std::time::Instant::now();
    let outbox: serde_json::Value = ctx
        .client
        .get(ctx.url("/api/v1/outbox?waitSeconds=10"))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let elapsed = started.elapsed();
    assert_eq!(outbox["offers"].as_array().unwrap().len(), 1);
    assert!(
        elapsed < std::time::Duration::from_secs(8),
        "long-poll should wake early, took {elapsed:?}"
    );
    offer_task.await.unwrap();
    ctx.core.shutdown().await;
}
