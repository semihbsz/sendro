//! HTTP `Range` header parsing (PROTOCOL.md §6.4).
//!
//! Only single byte-ranges are supported. Multi-range requests are rejected
//! with `416` (we deliberately do NOT fall back to a full `200` for a
//! malformed/multi range — the client must know its range was not honored).

/// Result of parsing a `Range` header against a resource of `size` bytes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RangeParse {
    /// No `Range` header present → serve the whole file with `200`.
    None,
    /// A satisfiable single range: inclusive `[start, end]`.
    Satisfiable { start: u64, end: u64 },
    /// Invalid or unsatisfiable → respond `416 Range Not Satisfiable`.
    Unsatisfiable,
}

/// Parse an optional `Range` header value against a resource of `size` bytes.
///
/// Accepted forms (single range only):
/// - `bytes=<start>-`          → `[start, size-1]`
/// - `bytes=<start>-<end>`     → `[start, min(end, size-1)]`
/// - `bytes=-<suffix>`         → last `suffix` bytes
///
/// Rejected with [`RangeParse::Unsatisfiable`]: multi-range (contains `,`),
/// non-`bytes` units, `start >= size`, `start > end`, empty/garbage specs,
/// and any range against a zero-length resource.
pub fn parse_range(header: Option<&str>, size: u64) -> RangeParse {
    let Some(raw) = header else {
        return RangeParse::None;
    };
    let raw = raw.trim();
    let Some(spec) = raw.strip_prefix("bytes=") else {
        return RangeParse::Unsatisfiable;
    };
    // Single range only — multi-range is rejected outright.
    if spec.contains(',') {
        return RangeParse::Unsatisfiable;
    }
    let spec = spec.trim();
    let Some((start_s, end_s)) = spec.split_once('-') else {
        return RangeParse::Unsatisfiable;
    };
    let start_s = start_s.trim();
    let end_s = end_s.trim();

    if start_s.is_empty() {
        // Suffix range: bytes=-N (last N bytes).
        let Ok(suffix) = end_s.parse::<u64>() else {
            return RangeParse::Unsatisfiable;
        };
        if suffix == 0 || size == 0 {
            return RangeParse::Unsatisfiable;
        }
        let start = size.saturating_sub(suffix);
        return RangeParse::Satisfiable {
            start,
            end: size - 1,
        };
    }

    let Ok(start) = start_s.parse::<u64>() else {
        return RangeParse::Unsatisfiable;
    };
    if start >= size {
        // Also covers size == 0.
        return RangeParse::Unsatisfiable;
    }

    if end_s.is_empty() {
        // bytes=start-
        return RangeParse::Satisfiable {
            start,
            end: size - 1,
        };
    }

    let Ok(end) = end_s.parse::<u64>() else {
        return RangeParse::Unsatisfiable;
    };
    if start > end {
        return RangeParse::Unsatisfiable;
    }
    // Per RFC 9110, an end beyond the resource is clamped to the last byte.
    RangeParse::Satisfiable {
        start,
        end: end.min(size - 1),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn absent_header_is_full_body() {
        assert_eq!(parse_range(None, 100), RangeParse::None);
    }

    #[test]
    fn open_ended() {
        assert_eq!(
            parse_range(Some("bytes=10-"), 100),
            RangeParse::Satisfiable { start: 10, end: 99 }
        );
    }

    #[test]
    fn closed_range_and_clamp() {
        assert_eq!(
            parse_range(Some("bytes=0-49"), 100),
            RangeParse::Satisfiable { start: 0, end: 49 }
        );
        assert_eq!(
            parse_range(Some("bytes=50-1000"), 100),
            RangeParse::Satisfiable { start: 50, end: 99 }
        );
    }

    #[test]
    fn invalid_cases() {
        for h in [
            "bytes=",
            "bytes=-",
            "bytes=abc-",
            "bytes=5-2",
            "bytes=100-",
            "bytes=100-200",
            "items=0-1",
            "bytes=0-1,5-9",
            "",
        ] {
            assert_eq!(parse_range(Some(h), 100), RangeParse::Unsatisfiable, "{h}");
        }
    }

    #[test]
    fn suffix_range() {
        assert_eq!(
            parse_range(Some("bytes=-10"), 100),
            RangeParse::Satisfiable { start: 90, end: 99 }
        );
        assert_eq!(
            parse_range(Some("bytes=-1000"), 100),
            RangeParse::Satisfiable { start: 0, end: 99 }
        );
        assert_eq!(parse_range(Some("bytes=-0"), 100), RangeParse::Unsatisfiable);
    }

    #[test]
    fn zero_length_resource() {
        assert_eq!(parse_range(Some("bytes=0-"), 0), RangeParse::Unsatisfiable);
        assert_eq!(parse_range(None, 0), RangeParse::None);
    }
}
