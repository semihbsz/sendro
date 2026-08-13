//! iPhone → Windows uploads (PROTOCOL.md §7).

mod common;

use common::{pair_device, sha256_hex, start_core};
use sendro_core::filename::rfc5987_encode;

fn upload_name_header(name: &str) -> String {
    format!("UTF-8''{}", rfc5987_encode(name))
}

#[tokio::test]
async fn upload_success_streams_verifies_and_saves() {
    let ctx = start_core().await;
    let (_device_id, token) = pair_device(&ctx).await;

    let data: Vec<u8> = (0..(2 * 1024 * 1024 + 77)).map(|i| (i % 251) as u8).collect();
    let sha = sha256_hex(&data);

    let resp = ctx
        .client
        .post(ctx.url("/api/v1/upload"))
        .bearer_auth(&token)
        .header("X-Sendro-File-Name", upload_name_header("IMG_4822.HEIC"))
        .header("X-Sendro-Sha256", &sha)
        .body(data.clone())
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let body: serde_json::Value = resp.json().await.unwrap();
    assert_eq!(body["ok"], true);
    let saved_path = std::path::PathBuf::from(body["savedPath"].as_str().unwrap());
    assert_eq!(saved_path.file_name().unwrap().to_str().unwrap(), "IMG_4822.HEIC");
    assert_eq!(std::fs::read(&saved_path).unwrap(), data);

    // Second upload with the same name gets " (2)".
    let resp = ctx
        .client
        .post(ctx.url("/api/v1/upload"))
        .bearer_auth(&token)
        .header("X-Sendro-File-Name", upload_name_header("IMG_4822.HEIC"))
        .header("X-Sendro-Sha256", &sha)
        .body(data.clone())
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let body: serde_json::Value = resp.json().await.unwrap();
    let saved2 = std::path::PathBuf::from(body["savedPath"].as_str().unwrap());
    assert_eq!(saved2.file_name().unwrap().to_str().unwrap(), "IMG_4822 (2).HEIC");
    assert_eq!(std::fs::read(&saved2).unwrap(), data);

    // History recorded both incoming transfers as verified.
    let history = ctx.core.history();
    assert_eq!(history.len(), 2);
    assert!(history.iter().all(|h| h.direction == "incoming" && h.verified));

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn upload_unicode_name_is_preserved() {
    let ctx = start_core().await;
    let (_device_id, token) = pair_device(&ctx).await;

    let data = b"drone footage".to_vec();
    let resp = ctx
        .client
        .post(ctx.url("/api/v1/upload"))
        .bearer_auth(&token)
        .header(
            "X-Sendro-File-Name",
            upload_name_header("Çekmeköy Reşadiye Drone.MOV"),
        )
        .header("X-Sendro-Sha256", sha256_hex(&data))
        .body(data)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let body: serde_json::Value = resp.json().await.unwrap();
    let saved = std::path::PathBuf::from(body["savedPath"].as_str().unwrap());
    assert_eq!(
        saved.file_name().unwrap().to_str().unwrap(),
        "Çekmeköy Reşadiye Drone.MOV"
    );
    ctx.core.shutdown().await;
}

#[tokio::test]
async fn upload_integrity_mismatch_is_422_and_partial_removed() {
    let ctx = start_core().await;
    let (_device_id, token) = pair_device(&ctx).await;

    let data: Vec<u8> = vec![42u8; 1024 * 1024 + 5];
    let wrong_sha = sha256_hex(b"something else entirely");

    let resp = ctx
        .client
        .post(ctx.url("/api/v1/upload"))
        .bearer_auth(&token)
        .header("X-Sendro-File-Name", upload_name_header("corrupt.bin"))
        .header("X-Sendro-Sha256", &wrong_sha)
        .body(data)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 422);
    let body: serde_json::Value = resp.json().await.unwrap();
    assert_eq!(body["error"], "integrity");

    // No partials, no final file — the receive dir must be empty.
    let leftovers: Vec<_> = std::fs::read_dir(ctx.recv_dir.path())
        .unwrap()
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .collect();
    assert!(leftovers.is_empty(), "receive dir not clean: {leftovers:?}");

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn upload_requires_headers() {
    let ctx = start_core().await;
    let (_device_id, token) = pair_device(&ctx).await;

    // Missing file name.
    let resp = ctx
        .client
        .post(ctx.url("/api/v1/upload"))
        .bearer_auth(&token)
        .header("X-Sendro-Sha256", sha256_hex(b"x"))
        .body(b"x".to_vec())
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 400);

    // Malformed sha.
    let resp = ctx
        .client
        .post(ctx.url("/api/v1/upload"))
        .bearer_auth(&token)
        .header("X-Sendro-File-Name", upload_name_header("x.bin"))
        .header("X-Sendro-Sha256", "nothex")
        .body(b"x".to_vec())
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 400);

    // No auth at all.
    let resp = ctx
        .client
        .post(ctx.url("/api/v1/upload"))
        .header("X-Sendro-File-Name", upload_name_header("x.bin"))
        .header("X-Sendro-Sha256", sha256_hex(b"x"))
        .body(b"x".to_vec())
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 401);

    ctx.core.shutdown().await;
}
