//
//  Crypto.swift
//  Sendro
//
//  Pairing proof math (PROTOCOL.md §4.2), streaming SHA-256, base64url helpers.
//

import Foundation
import CryptoKit

// MARK: - base64url (no padding)

extension Data {
    /// Decode a base64url (RFC 4648 §5) string, padding-optional.
    init?(base64urlEncoded string: String) {
        var s = string
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while s.count % 4 != 0 { s.append("=") }
        self.init(base64Encoded: s)
    }

    /// Encode as base64url with no padding.
    func base64urlEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Lowercase hex string.
    func hexEncodedString() -> String {
        map { String(format: "%02x", $0) }.joined()
    }
}

// MARK: - Pairing proof

enum SendroCrypto {

    /// PROTOCOL.md §4.2:
    ///   K     = HKDF-SHA256(ikm=UTF8(code), salt=salt, info="sendro-pair-v1", len=32)
    ///   proof = base64url( HMAC-SHA256(key=K, msg=UTF8(pairingId + ":" + deviceId)) )
    static func pairingProof(code: String,
                             saltBase64url: String,
                             pairingId: String,
                             deviceId: String) -> String? {
        guard let salt = Data(base64urlEncoded: saltBase64url) else { return nil }
        let ikm = SymmetricKey(data: Data(code.utf8))
        let key = HKDF<SHA256>.deriveKey(inputKeyMaterial: ikm,
                                         salt: salt,
                                         info: Data("sendro-pair-v1".utf8),
                                         outputByteCount: 32)
        let message = Data("\(pairingId):\(deviceId)".utf8)
        let mac = HMAC<SHA256>.authenticationCode(for: message, using: key)
        return Data(mac).base64urlEncodedString()
    }
}

// MARK: - Streaming SHA-256

/// Incremental SHA-256 wrapper. Feed arriving chunks with `update(_:)`,
/// then call `finalizeHex()` exactly once. Not thread-safe by itself —
/// callers must serialize access (the download delegate queue does).
final class StreamingSHA256 {
    private var hasher = SHA256()

    func update(_ data: Data) {
        hasher.update(data: data)
    }

    /// Returns the lowercase hex digest.
    func finalizeHex() -> String {
        let digest = hasher.finalize()
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}
