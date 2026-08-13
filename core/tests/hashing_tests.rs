mod common;

use sendro_core::hashing::sha256_file;
use sha2::{Digest, Sha256};

#[tokio::test]
async fn sha256_matches_known_vector() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("abc.txt");
    std::fs::write(&path, b"abc").unwrap();
    let (hash, len) = sha256_file(&path, |_| {}).await.unwrap();
    assert_eq!(len, 3);
    assert_eq!(
        hash,
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    );
}

#[tokio::test]
async fn sha256_streaming_matches_direct_digest_of_3mib_file() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("random.bin");
    let data = common::write_random_file(&path, 3 * 1024 * 1024 + 137);

    let direct = hex::encode(Sha256::digest(&data));

    let mut progress = Vec::new();
    let (streamed, len) = sha256_file(&path, |p| progress.push(p)).await.unwrap();

    assert_eq!(streamed, direct);
    assert_eq!(len, data.len() as u64);
    // Progress must be monotonically increasing, chunked (~1 MiB), and end
    // at the file size — proof there is no single whole-file read.
    assert!(progress.len() >= 3, "expected multiple chunks, got {progress:?}");
    assert!(progress.windows(2).all(|w| w[0] < w[1]));
    assert_eq!(*progress.last().unwrap(), len);
}
