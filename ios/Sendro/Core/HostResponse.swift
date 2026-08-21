//
//  HostResponse.swift
//  Sendro
//
//  One place where an HTTP status coming back from a Sendro host turns into
//  either (a) backpressure — "come back in N seconds", never an error — or
//  (b) a sentence a human can act on.
//
//  Why this file exists: the download path, the upload path and the typed
//  API client each used to invent their own wording, and each of them ended
//  up printing "Host returned HTTP 503." for the host's perfectly ordinary
//  "all my transfer slots are busy, try again in 2 seconds". The user must
//  never see a bare status code, and a 503 must never be a failure.
//
//  Host side (core/src/server.rs, core/src/link.rs) for reference:
//    503 rate_limited "transfers paused"          Retry-After: 5
//    503 rate_limited "transfer slots busy"       Retry-After: 2
//    503 rate_limited "too many guest connections" Retry-After: 2
//    409 conflict     "not ready" / bad state
//    410 gone         source file moved/changed, offer expired
//    416              our Range is stale for the host's file
//
//  Everything here is pure and stateless, so it is safe to call from a
//  URLSession delegate queue as well as from the main actor.
//

import Foundation

// MARK: - Why a host asked us to wait

/// The *reason* behind a retryable answer. Both 503 flavours are ordinary
/// backpressure; they differ only in what the user should be told.
enum HostBusyReason: Equatable {
    /// 503 "transfer slots busy" / "too many guest connections" — the host
    /// is already streaming its maximum number of files.
    case slotsBusy
    /// 503 "transfers paused" — someone hit Pause on the computer.
    case paused
    /// 409 — the offer exists but the host has not finished preparing it.
    case notReady
    /// 429 or any other 5xx — a hiccup worth retrying.
    case hostProblem

    /// Chip-sized word for dense rows.
    var shortLabel: String {
        switch self {
        case .slotsBusy:   return "Busy"
        case .paused:      return "Paused"
        case .notReady:    return "Waiting"
        case .hostProblem: return "Retrying"
        }
    }

    /// Screen headline, no countdown in it.
    func headline(hostName: String) -> String {
        switch self {
        case .slotsBusy:   return "\(hostName) is busy"
        case .paused:      return "Paused on \(hostName)"
        case .notReady:    return "\(hostName) is getting this ready"
        case .hostProblem: return "\(hostName) hit a snag"
        }
    }

    /// Calm explanatory line with the live countdown. Never red.
    func waitingLine(hostName: String, seconds: Int) -> String {
        switch self {
        case .slotsBusy:
            return "\(hostName) is already sending other files. Retrying in \(seconds)s — your place in line is kept and nothing was lost."
        case .paused:
            return "Transfers are paused on \(hostName). This picks up by itself the moment they resume — checking again in \(seconds)s."
        case .notReady:
            return "\(hostName) is still preparing this file. Retrying in \(seconds)s."
        case .hostProblem:
            return "\(hostName) answered with an error. Retrying in \(seconds)s — the bytes already here are kept."
        }
    }

    /// Only after a very long stretch of backpressure. Still resumable.
    func giveUpMessage(hostName: String) -> String {
        switch self {
        case .slotsBusy:
            return "\(hostName) stayed busy for a long time, so this is parked. Resume to try again — every byte received so far is kept."
        case .paused:
            return "Transfers have been paused on \(hostName) for a long time. Resume once they're running again — nothing was lost."
        case .notReady:
            return "\(hostName) never finished preparing this file. Resume to try again."
        case .hostProblem:
            return "\(hostName) kept answering with an error, so this is parked. Resume to try again — the bytes already here are kept."
        }
    }
}

/// A retryable answer: why, and how long to wait.
struct HostBackpressure: Equatable {
    let reason: HostBusyReason
    /// Already clamped to `HostStatus.minRetryAfter ... maxRetryAfter`.
    let retryAfterSeconds: Int
}

// MARK: - Status → meaning

enum HostStatus {

    /// Which side of a transfer a status came back on. The same 404 means
    /// "the sender cancelled it" when pulling and "this computer doesn't
    /// accept files" when pushing.
    enum Direction {
        case incoming       // §6.4 download
        case outgoing       // §7 upload
    }

    /// Never hammer, never sleep for minutes on a 2-second problem.
    static let minRetryAfter = 1
    static let maxRetryAfter = 30
    /// Used when Retry-After is absent or unparsable.
    static let defaultRetryAfter = 3

    static func clampRetryAfter(_ seconds: Int) -> Int {
        min(maxRetryAfter, max(minRetryAfter, seconds))
    }

    /// RFC 9110 Retry-After: delta-seconds, or an HTTP-date. Anything we
    /// cannot read falls back to `defaultRetryAfter` rather than to zero.
    static func retryAfterSeconds(_ raw: String?) -> Int {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else {
            return defaultRetryAfter
        }
        // delta-seconds. Double first so "2" and a sloppy "2.5" both land.
        if let value = Double(trimmed), value.isFinite {
            return clampRetryAfter(Int(value.rounded(.up)))
        }
        if let date = httpDate(trimmed) {
            return clampRetryAfter(Int(date.timeIntervalSinceNow.rounded(.up)))
        }
        return defaultRetryAfter
    }

    /// The three date formats RFC 9110 says a client must accept.
    static func httpDate(_ raw: String) -> Date? {
        for formatter in httpDateFormatters {
            if let date = formatter.date(from: raw) { return date }
        }
        return nil
    }

    private static let httpDateFormatters: [DateFormatter] = {
        ["EEE, dd MMM yyyy HH:mm:ss zzz",       // IMF-fixdate
         "EEEE, dd-MMM-yy HH:mm:ss zzz",        // obsolete RFC 850
         "EEE MMM d HH:mm:ss yyyy"]             // obsolete asctime
            .map { format in
                let formatter = DateFormatter()
                formatter.locale = Locale(identifier: "en_US_POSIX")
                formatter.timeZone = TimeZone(secondsFromGMT: 0)
                formatter.dateFormat = format
                return formatter
            }
    }()

    /// The host's `{"error":…,"message":…}` body, when there is one.
    static func apiError(in body: Data) -> ApiError? {
        guard !body.isEmpty else { return nil }
        return try? JSONDecoder().decode(ApiError.self, from: body)
    }

    /// Non-nil when this status means "later", not "no".
    ///
    /// 503 and 429 are explicit backpressure; 409 is "not ready yet"; every
    /// other 5xx is treated as a host hiccup worth retrying. 4xx (other than
    /// 409/429) is a real refusal and returns nil.
    static func backpressure(status: Int,
                             retryAfterHeader: String?,
                             hostMessage: String?) -> HostBackpressure? {
        let seconds = retryAfterSeconds(retryAfterHeader)
        switch status {
        case 409:
            return HostBackpressure(reason: .notReady, retryAfterSeconds: seconds)
        case 429:
            return HostBackpressure(reason: .hostProblem, retryAfterSeconds: seconds)
        case 503:
            // Same status, two very different things to say. The host puts
            // the difference in the JSON message.
            let reason: HostBusyReason = (hostMessage ?? "").lowercased().contains("pause")
                ? .paused : .slotsBusy
            return HostBackpressure(reason: reason, retryAfterSeconds: seconds)
        default:
            guard (500...599).contains(status) else { return nil }
            return HostBackpressure(reason: .hostProblem, retryAfterSeconds: seconds)
        }
    }

    /// Same decision, straight off the wire.
    static func backpressure(status: Int,
                             retryAfterHeader: String?,
                             body: Data) -> HostBackpressure? {
        backpressure(status: status,
                     retryAfterHeader: retryAfterHeader,
                     hostMessage: apiError(in: body)?.message)
    }

    /// Plain language for a status that is NOT backpressure. `resumable`
    /// drives whether the button reads "Resume" or "Retry"; it is never a
    /// reason to throw away bytes.
    static func failure(status: Int,
                        hostMessage: String?,
                        hostName: String,
                        direction: Direction) -> (message: String, resumable: Bool) {
        // The host's own words, when it actually said something.
        let hostSays: String? = (hostMessage?.isEmpty == false) ? hostMessage : nil
        switch (status, direction) {

        case (401, .incoming):
            return ("This iPhone isn't paired with \(hostName) any more. Pair the two again and this picks up where it stopped.", false)
        case (401, .outgoing):
            return ("This iPhone isn't paired with \(hostName) any more. Pair the two again, then send it.", false)

        case (403, .incoming):
            return ("\(hostName) wouldn't hand this file over. Pair the two devices again.", false)
        case (403, .outgoing):
            return ("\(hostName) isn't accepting files from this iPhone. Pair the two devices again.", false)

        case (404, .incoming):
            return ("\(hostName) cancelled this transfer, so there's nothing left to receive.", false)
        case (404, .outgoing):
            return ("\(hostName) isn't accepting files right now. Make sure Sendro is running on it and up to date.", false)

        case (410, .incoming):
            return ("That file is gone from \(hostName) — moved, changed, or the offer expired. Ask for it again.", false)
        case (410, .outgoing):
            return ("\(hostName) is no longer accepting this file.", false)

        case (413, _):
            return (hostSays ?? "\(hostName) says that file is too large.", false)

        case (422, .incoming):
            return ("\(hostName) rejected the bytes — the SHA-256 didn't match.", true)
        case (422, .outgoing):
            return ("\(hostName)'s SHA-256 check failed — the bytes changed in flight.", false)

        case (400, _):
            return (hostSays ?? "\(hostName) didn't understand the request.", false)

        default:
            if let hostSays { return (hostSays, false) }
            switch direction {
            case .incoming: return ("\(hostName) refused this transfer.", false)
            case .outgoing: return ("\(hostName) refused the upload.", false)
            }
        }
    }

    /// Wording for the typed API client (accept / reject / status / ping /
    /// messages), where there is no named host in hand. Same rules, softer
    /// subject.
    static func clientMessage(status: Int, code: String?, message: String?) -> String {
        let hostSays: String? = (message?.isEmpty == false) ? message : nil
        switch status {
        case 401:
            return "This iPhone isn't paired with that computer any more — pair again."
        case 403:
            return "That computer refused this — pair the two devices again."
        case 404:
            return "That transfer is gone — the sender cancelled it."
        case 409:
            return "Not ready yet — that computer is still preparing this."
        case 410:
            return "That file is no longer available on the sender's computer."
        case 413:
            return hostSays ?? "That's larger than the computer will accept."
        case 422:
            return "The computer's SHA-256 check failed — the bytes changed in flight."
        case 429:
            return "That computer is busy — trying again shortly."
        case 503:
            return (hostSays ?? "").lowercased().contains("pause")
                ? "Transfers are paused on that computer."
                : "That computer is busy — trying again shortly."
        default:
            if (500...599).contains(status) {
                return "That computer hit a problem — trying again shortly."
            }
            if let hostSays { return hostSays }
            if code == "unauthorized" {
                return "This iPhone isn't paired with that computer any more — pair again."
            }
            return "That computer refused the request."
        }
    }
}
