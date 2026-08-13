//! Restart survival, pause gate, concurrency gate, disconnect → Interrupted.

mod common;

use std::time::Duration;

use common::{pair_device, sha256_hex, start_core, write_random_file};
use futures::StreamExt;
use sendro_core::{Core, CoreConfig, TransferState};

#[tokio::test]
async fn interrupted_offers_survive_restart_and_resume() {
    let ctx = start_core().await;
    let (device_id, token) = pair_device(&ctx).await;

    let src_dir = tempfile::tempdir().unwrap();
    let file_path = src_dir.path().join("BMW E36 Final.mp4");
    let data = write_random_file(&file_path, 1024 * 1024 + 9);
    let expected_sha = sha256_hex(&data);

    let summaries = ctx
        .core
        .offer_files(device_id, vec![file_path], false)
        .await
        .unwrap();
    let transfer_id = summaries[0].transfer_id;

    // Accept and download the first half, then "the PC restarts".
    ctx.client
        .post(ctx.url(&format!("/api/v1/transfers/{transfer_id}/accept")))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    let half = data.len() / 2;
    let first = ctx
        .client
        .get(ctx.url(&format!("/api/v1/transfers/{transfer_id}/file")))
        .bearer_auth(&token)
        .header("Range", format!("bytes=0-{}", half - 1))
        .send()
        .await
        .unwrap()
        .bytes()
        .await
        .unwrap()
        .to_vec();
    ctx.core.shutdown().await;

    // Restart on the same data_dir.
    let mut cfg = CoreConfig::new("Semih-PC", ctx.data_dir.path(), ctx.recv_dir.path());
    cfg.preferred_port = 0;
    cfg.mdns_enabled = false;
    let core2 = Core::start(cfg).await.unwrap();
    let base2 = format!("http://127.0.0.1:{}", core2.info().api_port);

    // The offer came back as Interrupted (resumable), same sha.
    let queue = core2.queue();
    let restored = queue
        .iter()
        .find(|t| t.transfer_id == transfer_id)
        .expect("offer survived restart");
    assert_eq!(restored.state, TransferState::Interrupted);
    assert_eq!(restored.sha256.as_deref(), Some(expected_sha.as_str()));

    // Same token still works (trusted_devices.json persisted) and the
    // client can resume with a ranged request against the new instance.
    let resp = ctx
        .client
        .get(format!("{base2}/api/v1/transfers/{transfer_id}/file"))
        .bearer_auth(&token)
        .header("Range", format!("bytes={half}-"))
        .header("If-Range", format!("\"{expected_sha}\""))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 206);
    let second = resp.bytes().await.unwrap().to_vec();

    let mut joined = first;
    joined.extend_from_slice(&second);
    assert_eq!(joined, data, "resume across restart must be byte-identical");

    core2.shutdown().await;
}

#[tokio::test]
async fn pause_gate_holds_outbox_and_downloads() {
    let ctx = start_core().await;
    let (device_id, token) = pair_device(&ctx).await;

    let src_dir = tempfile::tempdir().unwrap();
    let file_path = src_dir.path().join("paused.bin");
    write_random_file(&file_path, 256 * 1024);
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

    ctx.core.pause_transfers(true);

    // Outbox holds offers while paused.
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

    // Download chunk requests get 503 + Retry-After.
    let resp = ctx
        .client
        .get(ctx.url(&format!("/api/v1/transfers/{transfer_id}/file")))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 503);
    assert!(resp.headers().get("retry-after").is_some());

    // Unpause → download proceeds.
    ctx.core.pause_transfers(false);
    let resp = ctx
        .client
        .get(ctx.url(&format!("/api/v1/transfers/{transfer_id}/file")))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    ctx.core.shutdown().await;
}

#[tokio::test]
async fn concurrency_gate_returns_503_with_retry_after() {
    let ctx = common::start_core_with(|cfg| cfg.concurrency = 1).await;
    let (device_id, token) = pair_device(&ctx).await;

    let src_dir = tempfile::tempdir().unwrap();
    let path_a = src_dir.path().join("a.bin");
    let path_b = src_dir.path().join("b.bin");
    write_random_file(&path_a, 8 * 1024 * 1024);
    write_random_file(&path_b, 64 * 1024);

    let summaries = ctx
        .core
        .offer_files(device_id, vec![path_a, path_b], false)
        .await
        .unwrap();
    let (id_a, id_b) = (summaries[0].transfer_id, summaries[1].transfer_id);
    for id in [id_a, id_b] {
        ctx.client
            .post(ctx.url(&format!("/api/v1/transfers/{id}/accept")))
            .bearer_auth(&token)
            .send()
            .await
            .unwrap();
    }

    // Open a stream for A and hold it (read just the first chunk).
    let resp_a = ctx
        .client
        .get(ctx.url(&format!("/api/v1/transfers/{id_a}/file")))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    assert_eq!(resp_a.status(), 200);
    let mut stream_a = resp_a.bytes_stream();
    let _first_chunk = stream_a.next().await.unwrap().unwrap();

    // B is refused while A occupies the single slot.
    let resp_b = ctx
        .client
        .get(ctx.url(&format!("/api/v1/transfers/{id_b}/file")))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    assert_eq!(resp_b.status(), 503);
    assert_eq!(resp_b.headers().get("retry-after").unwrap(), "2");

    // Drain A completely → slot free → B succeeds.
    while let Some(chunk) = stream_a.next().await {
        chunk.unwrap();
    }
    // The slot is released when the server-side stream finishes; allow a
    // brief moment for that.
    let mut ok = false;
    for _ in 0..50 {
        let resp_b = ctx
            .client
            .get(ctx.url(&format!("/api/v1/transfers/{id_b}/file")))
            .bearer_auth(&token)
            .send()
            .await
            .unwrap();
        if resp_b.status() == 200 {
            let body = resp_b.bytes().await.unwrap();
            assert_eq!(body.len(), 64 * 1024);
            ok = true;
            break;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    assert!(ok, "transfer B should proceed after A finished");
    ctx.core.shutdown().await;
}

#[tokio::test]
async fn client_disconnect_mid_stream_marks_interrupted() {
    let ctx = start_core().await;
    let (device_id, token) = pair_device(&ctx).await;

    let src_dir = tempfile::tempdir().unwrap();
    let file_path = src_dir.path().join("big.bin");
    write_random_file(&file_path, 16 * 1024 * 1024);

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

    // Start the download, read one chunk, then drop the connection.
    {
        let resp = ctx
            .client
            .get(ctx.url(&format!("/api/v1/transfers/{transfer_id}/file")))
            .bearer_auth(&token)
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), 200);
        let mut stream = resp.bytes_stream();
        let _ = stream.next().await.unwrap().unwrap();
        // resp/stream dropped here → TCP reset mid-body.
    }

    // Host must flip the transfer to Interrupted (resumable).
    let mut interrupted = false;
    for _ in 0..100 {
        let state = ctx
            .core
            .queue()
            .into_iter()
            .find(|t| t.transfer_id == transfer_id)
            .unwrap()
            .state;
        if state == TransferState::Interrupted {
            interrupted = true;
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    assert!(interrupted, "disconnect must mark the transfer Interrupted");

    // And a ranged retry works.
    let resp = ctx
        .client
        .get(ctx.url(&format!("/api/v1/transfers/{transfer_id}/file")))
        .bearer_auth(&token)
        .header("Range", "bytes=1048576-")
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 206);
    drop(resp);
    ctx.core.shutdown().await;
}
