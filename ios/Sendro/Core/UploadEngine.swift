//
//  UploadEngine.swift
//  Sendro
//
//  iPhone → PC sending (PROTOCOL.md §7, v1-lite). Sequential queue: each
//  picked file is hashed with streaming SHA-256 (1 MiB chunks), then POSTed
//  raw to /api/v1/upload via URLSession.uploadTask(with:fromFile:) — the
//  body streams from disk, the file is never loaded into memory.
//
//  §7 has NO ranged upload: a retry after failure or an integrity reject
//  always restarts the stream from byte 0. Acceptable for v1 on a LAN.
//
//  Backpressure: a receiving host can answer 503 (Retry-After) when it is
//  paused or out of connection/guest slots — see core/src/link.rs. That is
//  never surfaced as a failure here; the item parks with a countdown and the
//  (still strictly sequential) queue holds for that host until it expires.
//
//  Threading model mirrors TransferEngine: the engine is @MainActor and all
//  published state mutates on main; hashing runs on a detached utility task;
//  each UploadRunner owns a private URLSession with a serial delegate queue
//  and hops results back onto the main actor.
//

import Foundation
import Combine

// MARK: - UI-facing state

enum UploadPhase: Equatable {
    case queued
    /// The receiving host answered with backpressure (503 / 429 / 409 /
    /// other 5xx). Counting down to the next attempt — NOT a failure.
    case waitingForHost(reason: HostBusyReason, secondsRemaining: Int)
    case hashing                    // streaming SHA-256 before the POST
    case uploading
    case done                       // server answered 200 → hash verified
    case failed(message: String)

    var label: String {
        switch self {
        case .queued:         return "Waiting"
        case .waitingForHost: return "Waiting"
        case .hashing:        return "Hashing"
        case .uploading:      return "Sending"
        case .done:           return "Landed"
        case .failed:         return "Failed"
        }
    }
}

struct UploadItem: Identifiable {
    let id: String                  // UUID string; doubles as history transferId
    let fileURL: URL                // our staged temp copy (original bytes)
    let fileName: String
    let sizeBytes: Int64
    let hostId: String
    let hostName: String
    var phase: UploadPhase
    var bytesSent: Int64
    var speedBytesPerSecond: Double
    var etaSeconds: Int?
    var savedPath: String?          // from the 200 response
    var sha256: String?             // cached so Retry skips re-hashing

    var fractionComplete: Double {
        guard sizeBytes > 0 else { return 0 }
        return min(1.0, Double(bytesSent) / Double(sizeBytes))
    }
}

/// Server's 200 body: {"ok":true,"savedPath":"C:\\...\\name.ext"}.
struct UploadResult: Codable {
    let ok: Bool
    let savedPath: String?
}

// MARK: - Staging (temp copies of picked files)

/// Picked files are copied here (original bytes, real names) so the upload
/// outlives the picker's short-lived URLs. The engine deletes each file when
/// its item finishes or is removed.
enum SendStaging {

    static var root: URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("sendro-outgoing", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Fresh subdirectory per picker batch (keeps same-named files apart).
    static func newBatchDirectory() -> URL {
        let dir = root.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Remove a staged file, and its batch directory once empty.
    static func remove(_ url: URL) {
        let fm = FileManager.default
        try? fm.removeItem(at: url)
        let dir = url.deletingLastPathComponent()
        if dir.path.hasPrefix(root.path), dir.path != root.path,
           let contents = try? fm.contentsOfDirectory(atPath: dir.path), contents.isEmpty {
            try? fm.removeItem(at: dir)
        }
    }
}

// MARK: - Engine

@MainActor
final class UploadEngine: ObservableObject {

    @Published var items: [UploadItem] = []

    private let paired: PairedHostStore
    private let history: HistoryStore

    private var runner: UploadRunner?
    private var currentId: String?

    /// Per-host cooldown after backpressure. The queue stays sequential —
    /// this just stops it walking to the next file for the same host only to
    /// collect the same 503.
    private var hostBusyUntil: [String: Date] = [:]
    private var hostBusyReason: [String: HostBusyReason] = [:]
    private var backpressureAttempts: [String: Int] = [:]
    private var backpressureSince: [String: Date] = [:]
    /// One 1 Hz tick driving every countdown, alive only while one exists.
    private var ticker: Task<Void, Never>?

    /// Same patience as the receive side: ten minutes of "the host is busy"
    /// before an upload is parked as a (retryable) failure.
    static let backpressureGiveUpSeconds: TimeInterval = 10 * 60

    nonisolated init(paired: PairedHostStore, history: HistoryStore) {
        self.paired = paired
        self.history = history
    }

    var hasFinished: Bool {
        items.contains { $0.phase == .done }
    }

    // MARK: Queue control (UI)

    func enqueue(fileURLs: [URL], hostId: String, hostName: String) {
        for url in fileURLs {
            let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
            items.append(UploadItem(id: UUID().uuidString,
                                    fileURL: url,
                                    fileName: url.lastPathComponent,
                                    sizeBytes: size,
                                    hostId: hostId,
                                    hostName: hostName,
                                    phase: .queued,
                                    bytesSent: 0,
                                    speedBytesPerSecond: 0,
                                    etaSeconds: nil,
                                    savedPath: nil,
                                    sha256: nil))
        }
        pump()
    }

    /// Cancel / remove one item. A live upload is torn down through its
    /// runner (outcome path cleans up); everything else is removed directly.
    func cancel(itemId: String) {
        guard let idx = items.firstIndex(where: { $0.id == itemId }) else { return }
        if currentId == itemId {
            if runner != nil {
                runner?.cancel()        // → .cancelled outcome removes the row
                return
            }
            // Currently hashing: drop the row now; the hash callback sees the
            // missing item and deletes the staged copy + advances the queue.
            items.remove(at: idx)
            return
        }
        let url = items[idx].fileURL
        items.remove(at: idx)
        SendStaging.remove(url)
    }

    /// §7 has no ranged upload — a retry restarts the stream from byte 0.
    /// Also serves as "try now" on a row that is counting down a Retry-After.
    func retry(itemId: String) {
        guard let idx = items.firstIndex(where: { $0.id == itemId }) else { return }
        switch items[idx].phase {
        case .failed, .waitingForHost:
            break
        default:
            return
        }
        // An explicit tap means now: drop the accumulated delay and the
        // host's cooldown.
        backpressureAttempts[itemId] = nil
        backpressureSince[itemId] = nil
        hostBusyUntil[items[idx].hostId] = nil
        hostBusyReason[items[idx].hostId] = nil
        items[idx].phase = .queued
        items[idx].bytesSent = 0
        items[idx].speedBytesPerSecond = 0
        items[idx].etaSeconds = nil
        ensureTicker()          // siblings of the same host still need a tick
        pump()
    }

    func clearFinished() {
        for item in items where item.phase == .done {
            SendStaging.remove(item.fileURL)
        }
        items.removeAll { $0.phase == .done }
    }

    // MARK: Sequential pump

    private func pump() {
        guard currentId == nil else { return }
        let now = Date()
        guard let idx = items.firstIndex(where: { item in
            guard item.phase == .queued else { return false }
            // Host asked us to come back later — leave every one of its
            // files parked instead of collecting a 503 per file.
            if let until = hostBusyUntil[item.hostId], until > now { return false }
            return true
        }) else {
            ensureTicker()
            return
        }
        let item = items[idx]
        currentId = item.id
        if let sha = item.sha256 {
            beginUpload(itemId: item.id, sha256: sha)
        } else {
            items[idx].phase = .hashing
            let url = item.fileURL
            let itemId = item.id
            Task.detached(priority: .utility) { [weak self] in
                do {
                    let hex = try Self.sha256Hex(of: url)
                    await self?.hashFinished(itemId: itemId, url: url, sha256: hex)
                } catch {
                    await self?.hashFailed(itemId: itemId,
                                           message: "Could not read file: \(error.localizedDescription)")
                }
            }
        }
    }

    private func hashFinished(itemId: String, url: URL, sha256: String) {
        guard items.contains(where: { $0.id == itemId }) else {
            // Cancelled while hashing.
            SendStaging.remove(url)
            finishCurrent()
            return
        }
        beginUpload(itemId: itemId, sha256: sha256)
    }

    private func hashFailed(itemId: String, message: String) {
        if let idx = items.firstIndex(where: { $0.id == itemId }) {
            items[idx].phase = .failed(message: message)
        }
        finishCurrent()
    }

    private func beginUpload(itemId: String, sha256: String) {
        guard let idx = items.firstIndex(where: { $0.id == itemId }) else {
            finishCurrent()
            return
        }
        guard let host = paired.host(id: items[idx].hostId),
              let token = KeychainStore.token(forHost: items[idx].hostId),
              let client = SendroClient(host: host.lastHost, port: host.lastPort, token: token) else {
            items[idx].phase = .failed(message: "\(items[idx].hostName) is not reachable — check pairing.")
            finishCurrent()
            return
        }
        items[idx].sha256 = sha256
        items[idx].phase = .uploading
        items[idx].bytesSent = 0

        let request = client.makeUploadRequest(fileName: items[idx].fileName,
                                               sha256Hex: sha256,
                                               contentLength: items[idx].sizeBytes)
        let uploadRunner = UploadRunner(request: request,
                                        fileURL: items[idx].fileURL,
                                        totalBytes: items[idx].sizeBytes,
                                        hostName: items[idx].hostName)
        uploadRunner.onProgress = { [weak self] sent, speed, eta in
            Task { @MainActor in
                self?.applyProgress(itemId: itemId, sent: sent, speed: speed, eta: eta)
            }
        }
        uploadRunner.onCompletion = { [weak self] outcome in
            Task { @MainActor in
                self?.handleOutcome(itemId: itemId, outcome: outcome)
            }
        }
        runner = uploadRunner
        uploadRunner.start()
    }

    private func applyProgress(itemId: String, sent: Int64, speed: Double, eta: Int?) {
        guard let idx = items.firstIndex(where: { $0.id == itemId }) else { return }
        // Bytes are moving: the host is not busy any more, so the next
        // backpressure (if any) starts from a short delay again.
        if backpressureAttempts[itemId] != nil {
            backpressureAttempts[itemId] = nil
            backpressureSince[itemId] = nil
        }
        items[idx].bytesSent = sent
        items[idx].speedBytesPerSecond = speed
        items[idx].etaSeconds = eta
    }

    private func handleOutcome(itemId: String, outcome: UploadRunner.Outcome) {
        runner = nil
        guard let idx = items.firstIndex(where: { $0.id == itemId }) else {
            finishCurrent()
            return
        }
        let item = items[idx]
        switch outcome {
        case .success(let savedPath):
            items[idx].phase = .done
            items[idx].bytesSent = item.sizeBytes
            items[idx].speedBytesPerSecond = 0
            items[idx].etaSeconds = nil
            items[idx].savedPath = savedPath
            // verified == server 200 (the host hashed while writing).
            history.add(transferId: item.id,
                        fileName: item.fileName,
                        sizeBytes: item.sizeBytes,
                        senderName: item.hostName,
                        outcome: "completed",
                        direction: "outgoing")
            SendStaging.remove(item.fileURL)

        case .integrityRejected:
            items[idx].phase = .failed(message: "The PC's SHA-256 check failed — bytes changed in flight. Retry uploads the whole file again.")
            items[idx].speedBytesPerSecond = 0
            items[idx].etaSeconds = nil

        case .backpressure(let info):
            // Never a failure: park this one with a countdown and hold the
            // whole (sequential) queue for that host until it expires.
            noteBackpressure(itemId: itemId, idx: idx, info: info)

        case .failed(let message):
            items[idx].phase = .failed(message: message)
            items[idx].speedBytesPerSecond = 0
            items[idx].etaSeconds = nil

        case .cancelled:
            items.remove(at: idx)
            SendStaging.remove(item.fileURL)
        }
        finishCurrent()
    }

    private func finishCurrent() {
        currentId = nil
        pump()
    }

    // MARK: Host backpressure (§7 has no Range, so a retry is a fresh POST)

    private func noteBackpressure(itemId: String, idx: Int, info: HostBackpressure) {
        let hostId = items[idx].hostId
        let hostName = items[idx].hostName
        let attempts = (backpressureAttempts[itemId] ?? 0) + 1
        backpressureAttempts[itemId] = attempts
        let since = backpressureSince[itemId] ?? Date()
        backpressureSince[itemId] = since

        items[idx].bytesSent = 0
        items[idx].speedBytesPerSecond = 0
        items[idx].etaSeconds = nil

        // Give up only after a long stretch, and only into a retryable state.
        if Date().timeIntervalSince(since) > Self.backpressureGiveUpSeconds {
            backpressureAttempts[itemId] = nil
            backpressureSince[itemId] = nil
            items[idx].phase = .failed(message: info.reason.giveUpMessage(hostName: hostName))
            return
        }

        // Exponential-ish, floored by the host's Retry-After, capped at 30 s.
        let multiplier = 1 << min(max(attempts - 1, 0), 3)
        let delay = HostStatus.clampRetryAfter(info.retryAfterSeconds * multiplier)
        let until = Date().addingTimeInterval(TimeInterval(delay))
        if (hostBusyUntil[hostId] ?? .distantPast) < until { hostBusyUntil[hostId] = until }
        hostBusyReason[hostId] = info.reason
        items[idx].phase = .waitingForHost(reason: info.reason, secondsRemaining: delay)
        ensureTicker()
    }

    /// True while any row is showing a countdown. Used for ticker liveness:
    /// clearing a host cooldown by hand must not strand that host's other
    /// waiting rows with no tick left to release them.
    private var hasWaitingItems: Bool {
        items.contains { item in
            if case .waitingForHost = item.phase { return true }
            return false
        }
    }

    private func ensureTicker() {
        guard ticker == nil, !hostBusyUntil.isEmpty || hasWaitingItems else { return }
        ticker = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if Task.isCancelled { return }
                guard let self else { return }
                self.tick()
                if self.hostBusyUntil.isEmpty && !self.hasWaitingItems {
                    self.ticker = nil
                    return
                }
            }
        }
    }

    /// Expire finished cooldowns, refresh every visible countdown, then pump.
    private func tick() {
        let now = Date()
        // Snapshot the keys: the dictionary is mutated inside the loop.
        let expired = hostBusyUntil.compactMap { $0.value <= now ? $0.key : nil }
        for hostId in expired {
            hostBusyUntil[hostId] = nil
            hostBusyReason[hostId] = nil
        }
        for idx in items.indices {
            guard case .waitingForHost = items[idx].phase else { continue }
            let hostId = items[idx].hostId
            if let until = hostBusyUntil[hostId], until > now {
                let seconds = max(1, Int(until.timeIntervalSince(now).rounded(.up)))
                let phase = UploadPhase.waitingForHost(reason: hostBusyReason[hostId] ?? .hostProblem,
                                                       secondsRemaining: seconds)
                if items[idx].phase != phase { items[idx].phase = phase }
            } else {
                items[idx].phase = .queued          // its turn comes round again
            }
        }
        pump()
    }

    // MARK: Streaming hash (off-main)

    nonisolated private static func sha256Hex(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let hasher = StreamingSHA256()
        while true {
            guard let chunk = try handle.read(upToCount: 1_048_576), !chunk.isEmpty else { break }
            hasher.update(chunk)
        }
        return hasher.finalizeHex()
    }
}

// MARK: - UploadRunner

/// One streaming upload. URLSession reads the file from disk itself
/// (uploadTask fromFile), so memory stays flat regardless of file size.
final class UploadRunner: NSObject, URLSessionDataDelegate {

    enum Outcome {
        case success(savedPath: String?)
        case integrityRejected          // 422 {"error":"integrity"}
        /// The receiving host said "later" (503 / 429 / 409 / other 5xx).
        case backpressure(HostBackpressure)
        case failed(message: String)
        case cancelled
    }

    var onProgress: ((Int64, Double, Int?) -> Void)?    // bytesSent, speed, eta
    var onCompletion: ((Outcome) -> Void)?

    private let request: URLRequest
    private let fileURL: URL
    private let totalBytes: Int64
    /// Only used for wording — a person needs "SEMIH-PC is busy", not "503".
    private let hostName: String

    // State — touched only on `delegateQueue` (or before the task starts)
    private var session: URLSession?
    private var task: URLSessionUploadTask?
    private var responseBody = Data()
    private var userCancelled = false
    private var speedSamples: [(time: TimeInterval, bytes: Int64)] = []
    private var lastProgressEmit: TimeInterval = 0

    private let delegateQueue: OperationQueue = {
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        queue.name = "sendro.upload.delegate"
        return queue
    }()

    private let completionLock = NSLock()
    private var didComplete = false

    init(request: URLRequest, fileURL: URL, totalBytes: Int64, hostName: String) {
        self.request = request
        self.fileURL = fileURL
        self.totalBytes = totalBytes
        self.hostName = hostName
        super.init()
    }

    func start() {
        // Same posture as DownloadTask's session: survive transient blips,
        // idle-timeout between chunks, no cache.
        let config = URLSessionConfiguration.default
        config.waitsForConnectivity = true
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 7 * 24 * 3600
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: config,
                                 delegate: self,
                                 delegateQueue: delegateQueue)
        self.session = session
        let uploadTask = session.uploadTask(with: request, fromFile: fileURL)
        self.task = uploadTask
        uploadTask.resume()
        if userCancelled {
            uploadTask.cancel()
        }
    }

    func cancel() {
        userCancelled = true
        task?.cancel()
    }

    // MARK: URLSessionTaskDelegate (progress)

    func urlSession(_ session: URLSession,
                    task: URLSessionTask,
                    didSendBodyData bytesSent: Int64,
                    totalBytesSent: Int64,
                    totalBytesExpectedToSend: Int64) {
        let now = Date.timeIntervalSinceReferenceDate
        speedSamples.append((now, totalBytesSent))
        // Rolling 3-second window.
        while let first = speedSamples.first, now - first.time > 3.0 {
            speedSamples.removeFirst()
        }
        let expected = totalBytesExpectedToSend > 0 ? totalBytesExpectedToSend : totalBytes
        guard now - lastProgressEmit >= 0.25 || totalBytesSent >= expected else { return }
        lastProgressEmit = now

        var speed: Double = 0
        if let first = speedSamples.first, let last = speedSamples.last,
           last.time > first.time {
            speed = Double(last.bytes - first.bytes) / (last.time - first.time)
        }
        var eta: Int?
        if speed > 1, expected > totalBytesSent {
            eta = Int(Double(expected - totalBytesSent) / speed)
        }
        onProgress?(totalBytesSent, speed, eta)
    }

    // MARK: URLSessionDataDelegate (response body)

    func urlSession(_ session: URLSession,
                    dataTask: URLSessionDataTask,
                    didReceive data: Data) {
        // The §7 answer is a few dozen bytes of JSON either way. Bound it so
        // a misbehaving peer can never make this a buffering path.
        let room = Self.maxResponseBodyBytes - responseBody.count
        if room > 0 { responseBody.append(data.prefix(room)) }
    }

    private static let maxResponseBodyBytes = 8 * 1024

    func urlSession(_ session: URLSession,
                    task: URLSessionTask,
                    didCompleteWithError error: Error?) {
        if let error {
            let nsError = error as NSError
            if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled {
                complete(userCancelled ? .cancelled
                                       : .failed(message: "Upload interrupted."))
            } else {
                complete(.failed(message: nsError.localizedDescription))
            }
            return
        }
        guard let http = task.response as? HTTPURLResponse else {
            complete(.failed(message: "Unexpected response from host."))
            return
        }
        switch http.statusCode {
        case 200:
            let decoded = try? JSONDecoder().decode(UploadResult.self, from: responseBody)
            complete(.success(savedPath: decoded?.savedPath))
        case 422:
            complete(.integrityRejected)
        default:
            // Backpressure first — a host with its guest/connection slots
            // full, or paused, is asking us to wait, not refusing us.
            let retryAfter = http.value(forHTTPHeaderField: "Retry-After")
            if let info = HostStatus.backpressure(status: http.statusCode,
                                                  retryAfterHeader: retryAfter,
                                                  body: responseBody) {
                complete(.backpressure(info))
                return
            }
            let refusal = HostStatus.failure(status: http.statusCode,
                                             hostMessage: HostStatus.apiError(in: responseBody)?.message,
                                             hostName: hostName,
                                             direction: .outgoing)
            complete(.failed(message: refusal.message))
        }
    }

    private func complete(_ outcome: Outcome) {
        completionLock.lock()
        let alreadyDone = didComplete
        didComplete = true
        completionLock.unlock()
        guard !alreadyDone else { return }

        session?.finishTasksAndInvalidate()
        session = nil
        onCompletion?(outcome)
    }
}
