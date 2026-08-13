use sendro_core::range::{parse_range, RangeParse};

const SIZE: u64 = 1000;

#[test]
fn absent_header_serves_full_body() {
    assert_eq!(parse_range(None, SIZE), RangeParse::None);
}

#[test]
fn open_ended_start() {
    assert_eq!(
        parse_range(Some("bytes=0-"), SIZE),
        RangeParse::Satisfiable { start: 0, end: 999 }
    );
    assert_eq!(
        parse_range(Some("bytes=500-"), SIZE),
        RangeParse::Satisfiable {
            start: 500,
            end: 999
        }
    );
    assert_eq!(
        parse_range(Some("bytes=999-"), SIZE),
        RangeParse::Satisfiable {
            start: 999,
            end: 999
        }
    );
}

#[test]
fn closed_range() {
    assert_eq!(
        parse_range(Some("bytes=100-199"), SIZE),
        RangeParse::Satisfiable {
            start: 100,
            end: 199
        }
    );
    // Single byte.
    assert_eq!(
        parse_range(Some("bytes=0-0"), SIZE),
        RangeParse::Satisfiable { start: 0, end: 0 }
    );
}

#[test]
fn end_beyond_size_is_clamped() {
    assert_eq!(
        parse_range(Some("bytes=900-5000"), SIZE),
        RangeParse::Satisfiable {
            start: 900,
            end: 999
        }
    );
}

#[test]
fn start_at_or_beyond_size_is_unsatisfiable() {
    assert_eq!(
        parse_range(Some("bytes=1000-"), SIZE),
        RangeParse::Unsatisfiable
    );
    assert_eq!(
        parse_range(Some("bytes=99999-100000"), SIZE),
        RangeParse::Unsatisfiable
    );
}

#[test]
fn empty_and_garbage_are_unsatisfiable() {
    for h in [
        "",
        "bytes=",
        "bytes=-",
        "bytes=abc",
        "bytes=abc-def",
        "bytes=12x-99",
        "bytes=5-2",
        "chunks=0-10",
        "0-10",
    ] {
        assert_eq!(
            parse_range(Some(h), SIZE),
            RangeParse::Unsatisfiable,
            "header {h:?}"
        );
    }
}

#[test]
fn multi_range_is_rejected() {
    assert_eq!(
        parse_range(Some("bytes=0-10,20-30"), SIZE),
        RangeParse::Unsatisfiable
    );
    assert_eq!(
        parse_range(Some("bytes=0-10, 20-"), SIZE),
        RangeParse::Unsatisfiable
    );
}

#[test]
fn suffix_range() {
    assert_eq!(
        parse_range(Some("bytes=-100"), SIZE),
        RangeParse::Satisfiable {
            start: 900,
            end: 999
        }
    );
    // Suffix longer than the resource → whole file.
    assert_eq!(
        parse_range(Some("bytes=-4000"), SIZE),
        RangeParse::Satisfiable { start: 0, end: 999 }
    );
    assert_eq!(parse_range(Some("bytes=-0"), SIZE), RangeParse::Unsatisfiable);
}

#[test]
fn zero_length_resource() {
    assert_eq!(parse_range(Some("bytes=0-"), 0), RangeParse::Unsatisfiable);
    assert_eq!(parse_range(Some("bytes=-1"), 0), RangeParse::Unsatisfiable);
    assert_eq!(parse_range(None, 0), RangeParse::None);
}
