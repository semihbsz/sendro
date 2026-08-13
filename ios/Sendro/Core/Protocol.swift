//
//  Protocol.swift
//  Sendro
//
//  Codable models mirroring docs/PROTOCOL.md (v1) exactly.
//  All JSON keys are camelCase — the default synthesized CodingKeys match.
//

import Foundation

/// The protocol major version this client implements.
let sendroProtocolVersion = 1

// MARK: - §5 Info

struct InfoResponse: Codable {
    let app: String
    let protocolVersion: Int
    let deviceId: String
    let deviceName: String
    let platform: String
    let apiPort: Int
}

// MARK: - §4 Pairing

struct PairStartRequest: Codable {
    let deviceId: String
    let deviceName: String
    let platform: String
    let protocolVersion: Int
}

struct PairStartResponse: Codable {
    let pairingId: String
    let salt: String            // base64url, no padding
    let expiresInSeconds: Int
}

struct PairConfirmRequest: Codable {
    let pairingId: String
    let deviceId: String
    let proof: String           // base64url(HMAC-SHA256), no padding
}

struct PairConfirmResponse: Codable {
    struct Host: Codable {
        let deviceId: String
        let deviceName: String
        let platform: String
    }
    let deviceToken: String     // base64url 32 bytes, no padding
    let host: Host
}

// MARK: - §4.3 Ping

struct PingResponse: Codable {
    let ok: Bool
    let deviceName: String
}

// MARK: - §6.1 Transfer model

/// The canonical Transfer JSON from PROTOCOL.md §6.1.
struct TransferOffer: Codable, Hashable, Identifiable {
    let transferId: String
    let batchId: String
    let fileId: String
    let fileName: String
    let extension_: String
    let mimeType: String
    let sizeBytes: Int64
    let sha256: String          // lowercase hex, 64 chars (authoritative)
    let createdAtMs: Int64
    let modifiedAtMs: Int64
    let offeredAtMs: Int64
    let senderName: String
    let autoAccept: Bool

    var id: String { transferId }

    // `extension` is a Swift keyword; map it explicitly.
    enum CodingKeys: String, CodingKey {
        case transferId, batchId, fileId, fileName
        case extension_ = "extension"
        case mimeType, sizeBytes, sha256
        case createdAtMs, modifiedAtMs, offeredAtMs
        case senderName, autoAccept
    }
}

// MARK: - §6.2 Outbox

struct OutboxResponse: Codable {
    let offers: [TransferOffer]
}

// MARK: - §6.3 Accept / reject, generic ok

struct OkResponse: Codable {
    let ok: Bool
}

// MARK: - §6.5 Status reporting (client → host)

struct StatusReport: Codable {
    /// downloading | verifying | verified | saving | completed | failed | cancelled
    let state: String
    var bytesReceived: Int64?
    var error: String?
    /// for completed: "photos" | "files" | "temp"
    var savedTo: String?

    init(state: String, bytesReceived: Int64? = nil, error: String? = nil, savedTo: String? = nil) {
        self.state = state
        self.bytesReceived = bytesReceived
        self.error = error
        self.savedTo = savedTo
    }
}

// MARK: - §9 Errors

struct ApiError: Codable, Error {
    let error: String
    var message: String?
}
