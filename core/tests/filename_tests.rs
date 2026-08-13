use sendro_core::filename::{
    content_disposition, rfc5987_decode, rfc5987_encode, sanitize, unique_path,
};

#[test]
fn rfc5987_encoding_correctness() {
    assert_eq!(
        rfc5987_encode("Çekmeköy Reşadiye Drone.MOV"),
        "%C3%87ekmek%C3%B6y%20Re%C5%9Fadiye%20Drone.MOV"
    );
    assert_eq!(
        rfc5987_encode("final gerçekten final 5.mp4"),
        "final%20ger%C3%A7ekten%20final%205.mp4"
    );
    // attr-chars survive unencoded.
    assert_eq!(rfc5987_encode("a-b_c.d!e#f$g&h+i^j`k|l~m"), "a-b_c.d!e#f$g&h+i^j`k|l~m");
    // Reserved URL characters do not.
    assert_eq!(rfc5987_encode("a b'c%d\"e"), "a%20b%27c%25d%22e");
}

#[test]
fn content_disposition_header_shape() {
    assert_eq!(
        content_disposition("Çekmeköy Reşadiye Drone.MOV"),
        "attachment; filename*=UTF-8''%C3%87ekmek%C3%B6y%20Re%C5%9Fadiye%20Drone.MOV"
    );
}

#[test]
fn rfc5987_decode_roundtrip_and_prefix() {
    for name in ["Çekmeköy Reşadiye Drone.MOV", "final gerçekten final 5.mp4", "IMG_4822.HEIC"] {
        let enc = rfc5987_encode(name);
        assert_eq!(rfc5987_decode(&enc).as_deref(), Some(name));
        assert_eq!(rfc5987_decode(&format!("UTF-8''{enc}")).as_deref(), Some(name));
        assert_eq!(rfc5987_decode(&format!("utf-8'en'{enc}")).as_deref(), Some(name));
    }
    assert_eq!(rfc5987_decode("iso-8859-1''a%20b"), None);
}

#[test]
fn sanitize_preserves_unicode_and_strips_reserved() {
    assert_eq!(sanitize("final gerçekten final 5.mp4"), "final gerçekten final 5.mp4");
    assert_eq!(sanitize("a/b\\c.mp4"), "a_b_c.mp4");
    assert_eq!(sanitize("clip:v2?.mov"), "clip_v2_.mov");
    assert_eq!(sanitize("  spaced.mp4  "), "spaced.mp4");
    assert_eq!(sanitize("trailingdots..."), "trailingdots");
    assert_eq!(sanitize(""), "file");
}

#[test]
fn duplicate_suffixing() {
    let dir = tempfile::tempdir().unwrap();

    // First copy keeps the name.
    let p1 = unique_path(dir.path(), "final gerçekten final 5.mp4");
    assert_eq!(
        p1.file_name().unwrap().to_str().unwrap(),
        "final gerçekten final 5.mp4"
    );
    std::fs::write(&p1, b"x").unwrap();

    // Second → " (2)" before the extension.
    let p2 = unique_path(dir.path(), "final gerçekten final 5.mp4");
    assert_eq!(
        p2.file_name().unwrap().to_str().unwrap(),
        "final gerçekten final 5 (2).mp4"
    );
    std::fs::write(&p2, b"x").unwrap();

    // Third → " (3)".
    let p3 = unique_path(dir.path(), "final gerçekten final 5.mp4");
    assert_eq!(
        p3.file_name().unwrap().to_str().unwrap(),
        "final gerçekten final 5 (3).mp4"
    );

    // HEIC case from the spec.
    let h1 = unique_path(dir.path(), "IMG_4822.HEIC");
    std::fs::write(&h1, b"x").unwrap();
    let h2 = unique_path(dir.path(), "IMG_4822.HEIC");
    assert_eq!(h2.file_name().unwrap().to_str().unwrap(), "IMG_4822 (2).HEIC");

    // Files without an extension get the suffix at the end.
    let n1 = unique_path(dir.path(), "README");
    std::fs::write(&n1, b"x").unwrap();
    let n2 = unique_path(dir.path(), "README");
    assert_eq!(n2.file_name().unwrap().to_str().unwrap(), "README (2)");
}
