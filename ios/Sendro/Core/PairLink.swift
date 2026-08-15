//
//  PairLink.swift
//  Sendro
//
//  PROTOCOL.md §13 — QR pairing over the optical channel.
//
//      sendro://pair?v=1&h=<host-ip>&p=<port>&id=<hostDeviceId>
//                   &n=<pct-encoded name>&pid=<pairingId>
//                   &s=<salt base64url>&c=<6-digit code>
//
//  The QR carries the SAME session §4.1 created, so the crypto is unchanged:
//  we still compute the §4.2 HKDF/HMAC proof locally and the code never goes
//  back over the wire.
//
//  SECURITY (§13, last paragraph): a `sendro://` URL is only ever accepted
//  from our own camera scanner or from an OS URL open (Camera app, a QR
//  reader). Nothing in the app fetches or parses page content into this type,
//  and every path ends on a confirmation screen that names the PC before a
//  single request is sent.
//

import Foundation

struct PairLink: Equatable, Identifiable {

    let hostName: String
    let host: String
    let port: UInt16
    let hostDeviceId: String
    let pairingId: String
    /// base64url, no padding — fed straight into SendroCrypto.pairingProof.
    let salt: String
    /// The six digits. Never transmitted; only used to derive the proof.
    let code: String

    var id: String { "\(pairingId)|\(host):\(port)" }

    /// Strict parse. Returns nil for anything that isn't a well-formed v1
    /// Sendro pairing URL — no partial acceptance, no defaults for the
    /// security-relevant fields.
    static func parse(_ url: URL) -> PairLink? {
        guard url.scheme?.lowercased() == "sendro" else { return nil }
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        // "sendro://pair?..." parses with host == "pair"; tolerate
        // "sendro:pair?..." (no authority), which lands in `path`.
        let action = (components.host ?? components.path)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            .lowercased()
        guard action == "pair" else { return nil }

        // queryItems values are already percent-decoded by URLComponents.
        var values: [String: String] = [:]
        for item in components.queryItems ?? [] {
            if let value = item.value, !value.isEmpty {
                values[item.name.lowercased()] = value
            }
        }

        guard values["v"] == "1" else { return nil }
        guard let host = values["h"]?.trimmingCharacters(in: .whitespaces), !host.isEmpty,
              let portText = values["p"], let port = UInt16(portText), port > 0,
              let hostDeviceId = values["id"], !hostDeviceId.isEmpty,
              let pairingId = values["pid"], !pairingId.isEmpty,
              let salt = values["s"], !salt.isEmpty,
              let code = values["c"] else { return nil }

        // The code is six digits per §4.1 — refuse anything else rather than
        // burning a pairing attempt on a malformed QR.
        guard code.count == 6, code.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        // The salt must be decodable base64url, otherwise the proof can't be
        // computed and the confirm would fail with a confusing error.
        guard Data(base64urlEncoded: salt) != nil else { return nil }

        var displayName = host
        if let raw = values["n"]?.trimmingCharacters(in: .whitespaces), !raw.isEmpty {
            displayName = raw
        }
        return PairLink(hostName: displayName,
                        host: host,
                        port: port,
                        hostDeviceId: hostDeviceId,
                        pairingId: pairingId,
                        salt: salt,
                        code: code)
    }
}

// MARK: - Flow

enum PairLinkError: LocalizedError {
    case badAddress
    case notSendro
    case wrongDevice
    case versionMismatch
    case proofFailed
    case rejected(String)

    var errorDescription: String? {
        switch self {
        case .badAddress:
            return "That QR code points at an address this iPhone can't reach."
        case .notSendro:
            return "Something answered at that address, but it isn't Sendro."
        case .wrongDevice:
            return "The computer at that address isn't the one in the QR code. Scan the code again."
        case .versionMismatch:
            return "Protocol version mismatch — update Sendro on both devices."
        case .proofFailed:
            return "Could not compute the pairing proof from that QR code."
        case .rejected(let message):
            return message
        }
    }
}

enum PairLinkFlow {

    /// §13 client flow: verify the host at h:p really is the PC named in the
    /// QR, then run the ordinary §4.2 confirm. Reuses SendroClient and
    /// SendroCrypto — no duplicated crypto, no new endpoints.
    ///
    /// - Returns: the confirm response (deviceToken + host identity).
    static func confirm(link: PairLink,
                        clientDeviceId: String,
                        deviceName: String) async throws -> PairConfirmResponse {
        guard let client = SendroClient(host: link.host, port: link.port) else {
            throw PairLinkError.badAddress
        }
        let info: InfoResponse
        do {
            info = try await client.info()
        } catch {
            throw PairLinkError.rejected(
                "Could not reach Sendro at \(link.host):\(link.port). "
                + "Make sure both devices are on the same Wi-Fi. (\(error.localizedDescription))")
        }
        guard info.app == "sendro" else { throw PairLinkError.notSendro }
        guard info.deviceId.caseInsensitiveCompare(link.hostDeviceId) == .orderedSame else {
            throw PairLinkError.wrongDevice
        }
        guard info.protocolVersion == sendroProtocolVersion else {
            throw PairLinkError.versionMismatch
        }
        guard let proof = SendroCrypto.pairingProof(code: link.code,
                                                    saltBase64url: link.salt,
                                                    pairingId: link.pairingId,
                                                    deviceId: clientDeviceId) else {
            throw PairLinkError.proofFailed
        }
        // deviceName / platform are mandatory on this path: the QR flow never
        // calls pair/start, so without them the host has no name for us.
        let request = PairConfirmRequest(pairingId: link.pairingId,
                                         deviceId: clientDeviceId,
                                         proof: proof,
                                         deviceName: deviceName,
                                         platform: "ios")
        do {
            return try await client.pairConfirm(request)
        } catch let error as SendroClientError {
            switch error.httpStatus ?? 0 {
            case 403:
                throw PairLinkError.rejected(
                    "The PC rejected this code. The QR may have expired — show a fresh one and scan again.")
            case 400:
                throw PairLinkError.rejected(
                    "That pairing session expired (they last 120 seconds). Show a fresh QR code and scan again.")
            case 429:
                throw PairLinkError.rejected(
                    "Too many attempts. Start a new pairing on your PC.")
            default:
                throw PairLinkError.rejected(error.localizedDescription)
            }
        }
    }
}
