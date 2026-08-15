//! Sendro Link guest sessions — PROTOCOL.md §14.
//!
//! Everything here talks to the real HTTP server on the guest routes, which
//! sit outside the bearer-auth layer on purpose: the path token is the
//! credential, so these tests are also the security tests for it.

mod common;

use std::path::Path;
use std::time::Duration;

use common::{sha256_hex, start_core_with, write_random_file, TestCtx};
use sendro_core::filename::rfc5987_encode;
use sendro_core::link::{
    LinkOptions, GUEST_PEER_NAME, MAX_GUEST_CONNECTIONS, MAX_GUEST_UPLOADS,
};
use sendro_core::types::now_ms;
use sendro_core::{Core, CoreConfig, CoreEvent};
use uuid::Uuid;

fn upload_name_header(name: &str) -> String {
    format!("UTF-8''{}", rfc5987_encode(name))
}

fn opts(paths: Vec<std::path::PathBuf>, allow_upload: bool) -> LinkOptions {
    LinkOptions {
        expires_in_minutes: 30,
        allow_upload,
        paths,
    }
}

/// Wait until the background hash of every shared file has landed.
async fn wait_for_hashes(ctx: &TestCtx) -> Vec<sendro_core::LinkFile> {
    for _ in 0..100 {
        if let Some(session) = ctx.core.link_session() {
            if session.files.iter().all(|f| f.sha256.is_some()) {
                return session.files;
            }
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    panic!("link files were never hashed");
}

fn walk(dir: &Path, out: &mut Vec<std::path::PathBuf>) {
    for entry in std::fs::read_dir(dir).expect("read dir") {
        let path = entry.expect("dir entry").path();
        if path.is_dir() {
            walk(&path, out);
        } else {
            out.push(path);
        }
    }
}

#[tokio::test]
async fn lifecycle_start_serve_stop_then_gone() {
    let ctx = start_core_with(|_| {}).await;
    let mut events = ctx.core.subscribe();

    // Nothing is running until the user asks for it (§14.3).
    assert!(ctx.core.link_session().is_none());

    let file = ctx.data_dir.path().join("plan.txt");
    std::fs::write(&file, b"bulusma saat 5").unwrap();
    let session = ctx
        .core
        .start_link_session(opts(vec![file.clone()], false))
        .expect("start");
    assert_eq!(session.token.len(), 32, "24 bytes base64url is 32 chars");
    assert_eq!(session.files.len(), 1);
    assert!(!session.allow_upload);
    assert!(session.expires_at_ms > now_ms());
    assert!(session.url.ends_with(&format!("/link/{}/", session.token)));
    assert!(session.urls.iter().all(|u| u.url.contains(&session.token)));

    let started = tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            if let CoreEvent::LinkSessionChanged { session } =
                events.recv().await.expect("event stream")
            {
                break session;
            }
        }
    })
    .await
    .expect("LinkSessionChanged");
    assert_eq!(started.expect("some").token, session.token);

    // The guest page is served, self-contained, and mentions nothing else.
    let page = ctx
        .client
        .get(format!("{}/link/{}/", ctx.base, session.token))
        .send()
        .await
        .unwrap();
    assert_eq!(page.status(), 200);
    assert!(page
        .headers()
        .get("content-type")
        .unwrap()
        .to_str()
        .unwrap()
        .starts_with("text/html"));
    let html = page.text().await.unwrap();
    assert!(html.contains("Sendro Link"));
    assert!(
        !html.contains("http://") && !html.contains("https://"),
        "the guest page must not reference anything off-host"
    );
    assert!(!html.contains(&session.token), "the page never embeds the token");

    // Without the trailing slash we bounce to the canonical URL.
    let redirected = ctx
        .client
        .get(format!("{}/link/{}", ctx.base, session.token))
        .send()
        .await
        .unwrap();
    assert_eq!(redirected.status(), 200);

    // §14.2 session shape.
    let body: serde_json::Value = ctx
        .client
        .get(format!("{}/link/{}/api/session", ctx.base, session.token))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(body["hostName"], "Semih-PC");
    assert_eq!(body["allowUpload"], false);
    assert_eq!(body["expiresAtMs"].as_i64().unwrap(), session.expires_at_ms);
    assert_eq!(body["files"][0]["fileName"], "plan.txt");
    assert_eq!(body["files"][0]["sizeBytes"].as_u64().unwrap(), 14);

    // Stop → every route is 410 gone, forever.
    assert!(ctx.core.stop_link_session());
    assert!(ctx.core.link_session().is_none());
    assert!(!ctx.core.stop_link_session(), "stopping twice is a no-op");

    for path in [
        format!("/link/{}/", session.token),
        format!("/link/{}/api/session", session.token),
    ] {
        let resp = ctx
            .client
            .get(format!("{}{path}", ctx.base))
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), 410, "{path}");
        let body: serde_json::Value = resp.json().await.unwrap();
        assert_eq!(body["error"], "gone");
    }

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn expired_session_is_gone() {
    let ctx = start_core_with(|_| {}).await;
    let file = ctx.data_dir.path().join("note.txt");
    std::fs::write(&file, b"x").unwrap();
    let session = ctx
        .core
        .start_link_session(opts(vec![file], false))
        .expect("start");

    // Deadline in the past (the public API's smallest unit is a minute).
    assert!(ctx.core.__set_link_expiry_ms(now_ms() - 1));

    let resp = ctx
        .client
        .get(format!("{}/link/{}/api/session", ctx.base, session.token))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 410);
    assert!(ctx.core.link_session().is_none());

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn unknown_and_malformed_tokens_are_refused() {
    let ctx = start_core_with(|_| {}).await;
    let file = ctx.data_dir.path().join("a.bin");
    std::fs::write(&file, b"a").unwrap();
    let session = ctx
        .core
        .start_link_session(opts(vec![file], true))
        .expect("start");

    // Right length, wrong token; too short; too long; traversal attempts.
    let candidates = [
        "A".repeat(32),
        "A".repeat(8),
        "A".repeat(64),
        "..".to_string(),
        format!("{}A", session.token),
    ];
    for candidate in candidates {
        let resp = ctx
            .client
            .get(format!("{}/link/{candidate}/api/session", ctx.base))
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), 404, "token {candidate} must not resolve");
    }

    // A guest cannot walk out of the session's file set either.
    let resp = ctx
        .client
        .get(format!(
            "{}/link/{}/api/file/{}",
            ctx.base,
            session.token,
            Uuid::new_v4()
        ))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 404);

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn guest_download_is_byte_for_byte_and_supports_range() {
    let ctx = start_core_with(|_| {}).await;
    let path = ctx.data_dir.path().join("Çekmeköy Reşadiye Drone.MOV");
    let data = write_random_file(&path, 3 * 1024 * 1024 + 17);
    let session = ctx
        .core
        .start_link_session(opts(vec![path], false))
        .expect("start");
    let files = wait_for_hashes(&ctx).await;
    let file = &files[0];
    assert_eq!(file.sha256.as_deref(), Some(sha256_hex(&data).as_str()));

    let url = format!(
        "{}/link/{}/api/file/{}",
        ctx.base, session.token, file.file_id
    );
    let resp = ctx.client.get(&url).send().await.unwrap();
    assert_eq!(resp.status(), 200);
    let headers = resp.headers().clone();
    assert_eq!(headers["content-encoding"], "identity");
    assert_eq!(headers["accept-ranges"], "bytes");
    assert_eq!(
        headers["etag"].to_str().unwrap(),
        format!("\"{}\"", sha256_hex(&data))
    );
    assert_eq!(headers["x-sendro-sha256"], sha256_hex(&data));
    let disposition = headers["content-disposition"].to_str().unwrap();
    assert!(disposition.starts_with("attachment; filename*=UTF-8''"));
    assert!(disposition.is_ascii(), "RFC 5987 value must be ASCII");
    let body = resp.bytes().await.unwrap();
    assert_eq!(body.as_ref(), data.as_slice(), "byte-for-byte");

    // Range (§6.4 semantics, reused verbatim).
    let resp = ctx
        .client
        .get(&url)
        .header("Range", "bytes=1000-1999")
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 206);
    assert_eq!(
        resp.headers()["content-range"],
        format!("bytes 1000-1999/{}", data.len())
    );
    let part = resp.bytes().await.unwrap();
    assert_eq!(part.as_ref(), &data[1000..2000]);

    // Unsatisfiable range still answers 416, not a silent full body.
    let resp = ctx
        .client
        .get(&url)
        .header("Range", "bytes=99999999-")
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 416);

    // Un-sharing makes it unreachable immediately.
    assert!(ctx.core.remove_link_file(file.file_id));
    let resp = ctx.client.get(&url).send().await.unwrap();
    assert_eq!(resp.status(), 404);

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn guest_upload_saves_verifies_and_collides_safely() {
    let ctx = start_core_with(|_| {}).await;
    let mut events = ctx.core.subscribe();
    let session = ctx
        .core
        .start_link_session(opts(Vec::new(), true))
        .expect("start");
    let upload_url = format!("{}/link/{}/api/upload", ctx.base, session.token);

    let data: Vec<u8> = (0..(1024 * 1024 + 3)).map(|i| (i % 251) as u8).collect();
    let sha = sha256_hex(&data);

    // 1. With a hash → verified.
    let resp = ctx
        .client
        .post(&upload_url)
        .header("X-Sendro-File-Name", upload_name_header("Ekran Görüntüsü.png"))
        .header("X-Sendro-Sha256", &sha)
        .body(data.clone())
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let body: serde_json::Value = resp.json().await.unwrap();
    let saved = std::path::PathBuf::from(body["savedPath"].as_str().unwrap());
    assert_eq!(saved.parent().unwrap(), ctx.recv_dir.path());
    assert_eq!(saved.file_name().unwrap(), "Ekran Görüntüsü.png");
    assert_eq!(std::fs::read(&saved).unwrap(), data);

    // 2. Same name again → collision-safe " (2)".
    let resp = ctx
        .client
        .post(&upload_url)
        .header("X-Sendro-File-Name", upload_name_header("Ekran Görüntüsü.png"))
        .header("X-Sendro-Sha256", &sha)
        .body(data.clone())
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let body: serde_json::Value = resp.json().await.unwrap();
    let saved2 = std::path::PathBuf::from(body["savedPath"].as_str().unwrap());
    assert_eq!(saved2.file_name().unwrap(), "Ekran Görüntüsü (2).png");

    // 3. No hash at all (no crypto.subtle on a plain-http origin) → accepted,
    //    but never reported as verified.
    let resp = ctx
        .client
        .post(&upload_url)
        .header("X-Sendro-File-Name", upload_name_header("unhashed.bin"))
        .body(b"no checksum here".to_vec())
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);

    // 4. Wrong hash → 422 and the partial file is gone.
    let resp = ctx
        .client
        .post(&upload_url)
        .header("X-Sendro-File-Name", upload_name_header("corrupt.bin"))
        .header("X-Sendro-Sha256", sha256_hex(b"something else entirely"))
        .body(vec![7u8; 512 * 1024])
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 422);
    assert_eq!(
        resp.json::<serde_json::Value>().await.unwrap()["error"],
        "integrity"
    );

    let left: Vec<String> = std::fs::read_dir(ctx.recv_dir.path())
        .unwrap()
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .collect();
    assert_eq!(left.len(), 3, "no partials left behind: {left:?}");
    assert!(!left.iter().any(|n| n.contains("corrupt")));
    assert!(!left.iter().any(|n| n.starts_with(".sendro-")));

    // History + queue show the guest, and only verified where it is true.
    let history = ctx.core.history();
    assert_eq!(history.len(), 3);
    assert!(history.iter().all(|h| h.peer_name == GUEST_PEER_NAME));
    assert!(history.iter().all(|h| h.direction == "incoming"));
    let unhashed = history
        .iter()
        .find(|h| h.file_name == "unhashed.bin")
        .expect("unhashed entry");
    assert!(!unhashed.verified, "no hash means no verified claim");
    assert!(history
        .iter()
        .filter(|h| h.file_name.starts_with("Ekran"))
        .all(|h| h.verified));

    // The session counts what actually landed.
    assert_eq!(ctx.core.link_session().unwrap().guest_uploads, 3);

    let mut seen = 0;
    while let Ok(event) = events.try_recv() {
        if let CoreEvent::GuestUpload { size_bytes, .. } = event {
            assert!(size_bytes > 0);
            seen += 1;
        }
    }
    assert_eq!(seen, 3, "one GuestUpload event per stored file");

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn upload_is_refused_when_the_session_is_read_only() {
    let ctx = start_core_with(|_| {}).await;
    let session = ctx
        .core
        .start_link_session(opts(Vec::new(), false))
        .expect("start");

    let resp = ctx
        .client
        .post(format!("{}/link/{}/api/upload", ctx.base, session.token))
        .header("X-Sendro-File-Name", upload_name_header("sneaky.bin"))
        .body(b"nope".to_vec())
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 403);
    assert_eq!(
        resp.json::<serde_json::Value>().await.unwrap()["error"],
        "unauthorized"
    );
    assert_eq!(std::fs::read_dir(ctx.recv_dir.path()).unwrap().count(), 0);
    assert_eq!(ctx.core.link_session().unwrap().guest_uploads, 0);

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn upload_cap_per_session_is_enforced() {
    let ctx = start_core_with(|_| {}).await;
    let session = ctx
        .core
        .start_link_session(opts(Vec::new(), true))
        .expect("start");
    let url = format!("{}/link/{}/api/upload", ctx.base, session.token);

    for i in 0..MAX_GUEST_UPLOADS {
        let resp = ctx
            .client
            .post(&url)
            .header("X-Sendro-File-Name", upload_name_header(&format!("f{i}.bin")))
            .body(vec![b'x'])
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), 200, "upload {i} should be accepted");
    }
    let resp = ctx
        .client
        .post(&url)
        .header("X-Sendro-File-Name", upload_name_header("one-too-many.bin"))
        .body(vec![b'x'])
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 429);
    assert_eq!(
        resp.json::<serde_json::Value>().await.unwrap()["error"],
        "rate_limited"
    );
    assert_eq!(
        ctx.core.link_session().unwrap().guest_uploads,
        MAX_GUEST_UPLOADS
    );

    ctx.core.shutdown().await;
}

#[tokio::test]
async fn concurrent_guest_connections_are_capped_at_eight() {
    let ctx = start_core_with(|_| {}).await;
    // Large enough that the body is still streaming while we hold the
    // response — so each open download really occupies its slot.
    let path = ctx.data_dir.path().join("big.bin");
    write_random_file(&path, 32 * 1024 * 1024);
    let session = ctx
        .core
        .start_link_session(opts(vec![path], false))
        .expect("start");
    let files = wait_for_hashes(&ctx).await;
    let url = format!(
        "{}/link/{}/api/file/{}",
        ctx.base, session.token, files[0].file_id
    );

    // send() returns once the headers are in; the body (and therefore the
    // slot) stays open for as long as we keep the response alive.
    let mut held = Vec::new();
    for i in 0..MAX_GUEST_CONNECTIONS {
        let resp = ctx.client.get(&url).send().await.unwrap();
        assert_eq!(resp.status(), 200, "download {i}");
        held.push(resp);
    }
    let resp = ctx.client.get(&url).send().await.unwrap();
    assert_eq!(resp.status(), 503, "the 9th guest is turned away");
    assert_eq!(resp.headers()["retry-after"], "2");
    assert_eq!(
        resp.json::<serde_json::Value>().await.unwrap()["error"],
        "rate_limited"
    );

    // Finishing the open downloads frees the slots again.
    drop(held);
    for _ in 0..50 {
        let resp = ctx.client.get(&url).send().await.unwrap();
        if resp.status() == 200 {
            ctx.core.shutdown().await;
            return;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    panic!("guest slots were never released");
}

#[tokio::test]
async fn a_link_session_never_survives_a_restart() {
    let ctx = start_core_with(|_| {}).await;
    let file = ctx.data_dir.path().join("holiday.mov");
    write_random_file(&file, 2048);
    let session = ctx
        .core
        .start_link_session(opts(vec![file], true))
        .expect("start");
    assert_eq!(
        ctx.client
            .get(format!("{}/link/{}/api/session", ctx.base, session.token))
            .send()
            .await
            .unwrap()
            .status(),
        200
    );
    ctx.core.shutdown().await;

    // Same data dir, fresh process: §14.3 — RAM only.
    let mut cfg = CoreConfig::new("Semih-PC", ctx.data_dir.path(), ctx.recv_dir.path());
    cfg.preferred_port = 0;
    cfg.mdns_enabled = false;
    let core = Core::start(cfg).await.expect("restart");
    let base = format!("http://127.0.0.1:{}", core.info().api_port);
    assert!(core.link_session().is_none());
    let resp = ctx
        .client
        .get(format!("{base}/link/{}/api/session", session.token))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 404, "a restarted host has never heard of it");
    core.shutdown().await;
}

#[tokio::test]
async fn no_link_or_pairing_secret_is_ever_written_to_disk() {
    let ctx = start_core_with(|_| {}).await;
    let file = ctx.data_dir.path().join("secret-plan.txt");
    std::fs::write(&file, b"nothing to see").unwrap();

    let session = ctx
        .core
        .start_link_session(opts(vec![file], true))
        .expect("start");
    let qr = ctx.core.start_qr_pairing();
    wait_for_hashes(&ctx).await;

    // Upload something so every write path has run at least once.
    let resp = ctx
        .client
        .post(format!("{}/link/{}/api/upload", ctx.base, session.token))
        .header("X-Sendro-File-Name", upload_name_header("guest.txt"))
        .body(b"hello from a guest".to_vec())
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    ctx.core.shutdown().await;

    let mut files = Vec::new();
    walk(ctx.data_dir.path(), &mut files);
    walk(ctx.recv_dir.path(), &mut files);
    assert!(!files.is_empty(), "expected some state files to exist");
    for path in files {
        let raw = std::fs::read(&path).unwrap_or_default();
        let text = String::from_utf8_lossy(&raw);
        for secret in [
            session.token.as_str(),
            qr.code.as_str(),
            qr.salt.as_str(),
            qr.pairing_id.to_string().as_str(),
        ] {
            assert!(
                !text.contains(secret),
                "secret leaked into {}",
                path.display()
            );
        }
    }
}
