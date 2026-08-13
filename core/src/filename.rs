//! Filename handling: sanitization for the local filesystem, RFC 5987
//! `filename*` encoding/decoding, and duplicate ` (n)` suffixing.
//!
//! PROTOCOL.md §8: filenames are arbitrary UTF-8; receivers sanitize path
//! separators and reserved characters but preserve everything else,
//! including case and spaces.

use std::path::{Path, PathBuf};

use percent_encoding::{percent_decode_str, utf8_percent_encode, AsciiSet, NON_ALPHANUMERIC};

/// RFC 5987 `attr-char` = ALPHA / DIGIT / "!" / "#" / "$" / "&" / "+" /
/// "-" / "." / "^" / "_" / "`" / "|" / "~". Everything else is
/// percent-encoded (UTF-8 bytes, uppercase hex).
const RFC5987_ENCODE_SET: &AsciiSet = &NON_ALPHANUMERIC
    .remove(b'!')
    .remove(b'#')
    .remove(b'$')
    .remove(b'&')
    .remove(b'+')
    .remove(b'-')
    .remove(b'.')
    .remove(b'^')
    .remove(b'_')
    .remove(b'`')
    .remove(b'|')
    .remove(b'~');

/// Windows reserved base names (case-insensitive).
const WINDOWS_RESERVED: &[&str] = &[
    "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8",
    "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
];

/// Sanitize an arbitrary UTF-8 filename for the local filesystem.
///
/// Replaces path separators and reserved characters with `_`, strips
/// control characters, trims trailing dots/spaces (Windows), and preserves
/// case, spaces and all other Unicode.
pub fn sanitize(name: &str) -> String {
    let mut out = String::with_capacity(name.len());
    for c in name.chars() {
        match c {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => out.push('_'),
            c if (c as u32) < 0x20 || c == '\u{7f}' => out.push('_'),
            c => out.push(c),
        }
    }
    // Windows rejects trailing dots and spaces; leading/trailing whitespace
    // is trimmed for safety. A leading dot (hidden file) is preserved.
    let trimmed = out.trim().trim_end_matches(['.', ' ']).to_string();
    if trimmed.is_empty() || trimmed.chars().all(|c| c == '.') {
        return "file".to_string();
    }
    // Guard Windows reserved device names (match on the stem).
    let stem = trimmed.split('.').next().unwrap_or("");
    if WINDOWS_RESERVED
        .iter()
        .any(|r| r.eq_ignore_ascii_case(stem))
    {
        return format!("_{trimmed}");
    }
    trimmed
}

/// RFC 5987 encode a filename value (the part after `UTF-8''`).
pub fn rfc5987_encode(name: &str) -> String {
    utf8_percent_encode(name, RFC5987_ENCODE_SET).to_string()
}

/// Full `Content-Disposition` value per PROTOCOL.md §6.4.
pub fn content_disposition(name: &str) -> String {
    format!("attachment; filename*=UTF-8''{}", rfc5987_encode(name))
}

/// Decode an RFC 5987 value. Accepts either the full `UTF-8''<pct>` form
/// (charset prefix, optional language tag) or a bare percent-encoded string.
pub fn rfc5987_decode(value: &str) -> Option<String> {
    // ext-value = charset "'" [ language ] "'" value-chars (RFC 5987 §3.2).
    // A bare percent-encoded value never contains a raw `'` (it is not an
    // attr-char), so the presence of one implies the full form.
    let encoded = match value.find('\'') {
        Some(first) => {
            let charset = &value[..first];
            if !charset.eq_ignore_ascii_case("utf-8") {
                return None;
            }
            let rest = &value[first + 1..];
            let second = rest.find('\'')?;
            &rest[second + 1..]
        }
        None => value,
    };
    percent_decode_str(encoded)
        .decode_utf8()
        .ok()
        .map(|s| s.into_owned())
}

/// Split `name` into (stem, extension-with-dot). `".bashrc"` → `(".bashrc", "")`.
fn split_ext(name: &str) -> (&str, &str) {
    match name.rfind('.') {
        Some(idx) if idx > 0 => (&name[..idx], &name[idx..]),
        _ => (name, ""),
    }
}

/// Return a path in `dir` for `name` that does not collide with an existing
/// file: `name.ext`, then `name (2).ext`, `name (3).ext`, ...
pub fn unique_path(dir: &Path, name: &str) -> PathBuf {
    let candidate = dir.join(name);
    if !candidate.exists() {
        return candidate;
    }
    let (stem, ext) = split_ext(name);
    for n in 2u32.. {
        let candidate = dir.join(format!("{stem} ({n}){ext}"));
        if !candidate.exists() {
            return candidate;
        }
    }
    unreachable!("u32 range exhausted finding a unique filename")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sanitize_preserves_unicode() {
        assert_eq!(
            sanitize("Çekmeköy Reşadiye Drone.MOV"),
            "Çekmeköy Reşadiye Drone.MOV"
        );
    }

    #[test]
    fn sanitize_strips_separators() {
        assert_eq!(sanitize("a/b\\c:d*e?f\"g<h>i|j"), "a_b_c_d_e_f_g_h_i_j");
    }

    #[test]
    fn sanitize_reserved() {
        assert_eq!(sanitize("CON.txt"), "_CON.txt");
        assert_eq!(sanitize("..."), "file");
        assert_eq!(sanitize(""), "file");
    }

    #[test]
    fn rfc5987_roundtrip() {
        let name = "final gerçekten final 5.mp4";
        let enc = rfc5987_encode(name);
        assert!(!enc.contains(' '));
        assert_eq!(rfc5987_decode(&enc).unwrap(), name);
        assert_eq!(
            rfc5987_decode(&format!("UTF-8''{enc}")).unwrap(),
            name
        );
    }
}
