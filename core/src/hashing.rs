//! Streaming SHA-256 (never loads whole files into memory).

use std::path::Path;

use anyhow::Context;
use sha2::{Digest, Sha256};
use tokio::io::AsyncReadExt;

/// Chunk size used for hashing, serving and receiving (~1 MiB).
pub const CHUNK_SIZE: usize = 1024 * 1024;

/// Stream a file through SHA-256 in [`CHUNK_SIZE`] chunks.
///
/// `on_progress` is invoked with the cumulative number of bytes hashed
/// after each chunk. Returns `(lowercase_hex_digest, total_bytes)`.
pub async fn sha256_file<F>(path: &Path, mut on_progress: F) -> anyhow::Result<(String, u64)>
where
    F: FnMut(u64),
{
    let mut file = tokio::fs::File::open(path)
        .await
        .with_context(|| format!("open {}", path.display()))?;
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; CHUNK_SIZE];
    let mut total: u64 = 0;
    loop {
        let n = file
            .read(&mut buf)
            .await
            .with_context(|| format!("read {}", path.display()))?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
        total += n as u64;
        on_progress(total);
    }
    Ok((hex::encode(hasher.finalize()), total))
}

/// SHA-256 of a byte slice, lowercase hex (used for token hashing).
pub fn sha256_hex(data: &[u8]) -> String {
    hex::encode(Sha256::digest(data))
}
