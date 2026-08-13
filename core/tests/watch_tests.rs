//! Watch-folder stabilizer tests with a short injected poll interval.

mod common;

use std::time::Duration;

use common::start_core_with;
use sendro_core::{CoreEvent, WatchFolderConfig};
use uuid::Uuid;

const POLL_MS: u64 = 50;

async fn next_detection(
    events: &mut tokio::sync::broadcast::Receiver<CoreEvent>,
    within: Duration,
) -> Option<(Uuid, String, bool)> {
    tokio::time::timeout(within, async {
        loop {
            match events.recv().await {
                Ok(CoreEvent::WatchFileDetected {
                    detection_id,
                    file_name,
                    auto,
                    ..
                }) => return (detection_id, file_name, auto),
                Ok(_) => {}
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {}
                Err(_) => panic!("event channel closed"),
            }
        }
    })
    .await
    .ok()
}

#[tokio::test]
async fn stable_file_is_detected_after_three_polls() {
    let ctx = start_core_with(|cfg| cfg.watch_poll_ms = POLL_MS).await;
    let watch_dir = tempfile::tempdir().unwrap();
    ctx.core
        .add_watch_folder(WatchFolderConfig {
            id: Uuid::new_v4(),
            path: watch_dir.path().to_string_lossy().into_owned(),
            auto_send: false,
            target_device_id: None,
            enabled: true,
        })
        .unwrap();

    let mut events = ctx.core.subscribe();
    std::fs::write(watch_dir.path().join("render_v3.mp4"), vec![7u8; 4096]).unwrap();

    let start = std::time::Instant::now();
    let (detection_id, file_name, auto) = next_detection(&mut events, Duration::from_secs(5))
        .await
        .expect("stable file should be detected");
    assert_eq!(file_name, "render_v3.mp4");
    assert!(!auto, "no auto_send configured");
    // Needs at least 3 polls to stabilize — must not fire instantly.
    assert!(
        start.elapsed() >= Duration::from_millis(2 * POLL_MS),
        "fired too early: {:?}",
        start.elapsed()
    );

    // Ignoring it is final: no re-detection while the file stays unchanged.
    ctx.core.resolve_detected_file(detection_id, false);
    assert!(
        next_detection(&mut events, Duration::from_millis(8 * POLL_MS))
            .await
            .is_none(),
        "handled file must not be re-detected"
    );
    ctx.core.shutdown().await;
}

#[tokio::test]
async fn growing_file_is_not_ready_until_it_stabilizes() {
    let ctx = start_core_with(|cfg| cfg.watch_poll_ms = POLL_MS).await;
    let watch_dir = tempfile::tempdir().unwrap();
    ctx.core
        .add_watch_folder(WatchFolderConfig {
            id: Uuid::new_v4(),
            path: watch_dir.path().to_string_lossy().into_owned(),
            auto_send: false,
            target_device_id: None,
            enabled: true,
        })
        .unwrap();

    let mut events = ctx.core.subscribe();
    let path = watch_dir.path().join("export_in_progress.mov");

    // Grow the file for ~12 poll intervals.
    let grow_path = path.clone();
    let grower = tokio::spawn(async move {
        use std::io::Write;
        let mut f = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&grow_path)
            .unwrap();
        for _ in 0..20 {
            f.write_all(&[1u8; 1024]).unwrap();
            f.flush().unwrap();
            tokio::time::sleep(Duration::from_millis(30)).await;
        }
    });

    // While growing: no detection.
    assert!(
        next_detection(&mut events, Duration::from_millis(500)).await.is_none(),
        "growing file must not be detected as ready"
    );
    grower.await.unwrap();

    // Once stable: detected.
    let (_, file_name, _) = next_detection(&mut events, Duration::from_secs(5))
        .await
        .expect("file should be detected after it stops growing");
    assert_eq!(file_name, "export_in_progress.mov");
    ctx.core.shutdown().await;
}

#[tokio::test]
async fn temp_and_hidden_files_are_ignored() {
    let ctx = start_core_with(|cfg| cfg.watch_poll_ms = POLL_MS).await;
    let watch_dir = tempfile::tempdir().unwrap();
    ctx.core
        .add_watch_folder(WatchFolderConfig {
            id: Uuid::new_v4(),
            path: watch_dir.path().to_string_lossy().into_owned(),
            auto_send: false,
            target_device_id: None,
            enabled: true,
        })
        .unwrap();

    let mut events = ctx.core.subscribe();
    for name in [
        "download.tmp",
        "video.part",
        "clip.partial",
        "chrome.crdownload",
        "safari.download",
        "scratch.temp",
        ".hidden.mp4",
        "~$word.docx",
    ] {
        std::fs::write(watch_dir.path().join(name), vec![9u8; 2048]).unwrap();
    }

    assert!(
        next_detection(&mut events, Duration::from_millis(10 * POLL_MS))
            .await
            .is_none(),
        "temp/hidden files must never be detected"
    );

    // Control: a real file next to them IS detected.
    std::fs::write(watch_dir.path().join("real.mp4"), vec![9u8; 2048]).unwrap();
    let (_, file_name, _) = next_detection(&mut events, Duration::from_secs(5))
        .await
        .expect("real file should be detected");
    assert_eq!(file_name, "real.mp4");
    ctx.core.shutdown().await;
}

#[tokio::test]
async fn zero_byte_files_are_not_ready() {
    let ctx = start_core_with(|cfg| cfg.watch_poll_ms = POLL_MS).await;
    let watch_dir = tempfile::tempdir().unwrap();
    ctx.core
        .add_watch_folder(WatchFolderConfig {
            id: Uuid::new_v4(),
            path: watch_dir.path().to_string_lossy().into_owned(),
            auto_send: false,
            target_device_id: None,
            enabled: true,
        })
        .unwrap();

    let mut events = ctx.core.subscribe();
    std::fs::write(watch_dir.path().join("empty.mp4"), b"").unwrap();
    assert!(
        next_detection(&mut events, Duration::from_millis(8 * POLL_MS))
            .await
            .is_none(),
        "zero-byte file must not be ready"
    );
    ctx.core.shutdown().await;
}
