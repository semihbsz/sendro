//
//  TransferEngine.swift
//  Sendro
//
//  The heart of the client: per-host outbox long-poll loops, offer intake,
//  auto-accept, streaming downloads with resume + progressive SHA-256,
//  verification, save routing (Photos / Files) and status reporting.
//
//  Threading model:
//  - TransferEngine is @MainActor; all published state mutates on main.
//  - Each DownloadTask owns a private URLSession with a serial delegate
//    queue; chunks are written to disk and hashed there, and progress is
//    hopped onto the main actor (throttled) for UI.
//

import Foundation
import Combine
import UIKit

// MARK: - UI-facing state

enum TransferPhase: Equatable {
    case preparing                      // preflight + prefix re-hash
    case downloading
    case verifying
    case awaitingSaveChoice             // media + "Ask" setting
    case saving
    case failed(message: String, resumable: Bool)
    case interrupted                    // restored from a previous launch

    var label: String {
        switch self {
        case .preparing:         return "Preparing…"
        case .downloading:       return "Downloading"
        case .verifying:         return "Verifying…"
        case .awaitingSaveChoice: return "Where should this go?"
        case .saving:            return "Saving…"
        case .failed:            return "Failed"
        case .interrupted:       return "Paused"
        }
    }
}

struct ActiveTransfer: Identifiable {
    let offer: TransferOffer
    let hostId: String
    var phase: TransferPhase
    var bytesReceived: Int64
    var speedBytesPerSecond: Double
    var etaSeconds: Int?

    var id: String { offer.transferId }

    var fractionComplete: Double {
        guard offer.sizeBytes > 0 else { return 0 }
        return min(1.0, Double(bytesReceived) / Double(offer.sizeBytes))
    }
}

struct IncomingOffer: Identifiable {
    let offer: TransferOffer
    let hostId: String
    let receivedAt: Date

    var id: String { offer.transferId }
}

/// Persisted so a relaunch can resume in-flight (accepted) transfers.
private struct InFlightRecord: Codable {
    let offer: TransferOffer
    let hostId: String
}

// MARK: - Engine

@MainActor
final class TransferEngine: ObservableObject {

    @Published var incomingOffers: [IncomingOffer] = []
    @Published var active: [ActiveTransfer] = []
    @Published var hostOnline: [String: Bool] = [:]

    /// 200 MB safety margin on top of the file size for storage preflight.
    static let storageMargin: Int64 = 200 * 1024 * 1024

    private let settings: Settings
    private let paired: PairedHostStore
    private let history: HistoryStore
    private let fileStore: FileStore
    private let discovery: DiscoveryService

    private var pollTasks: [String: Task<Void, Never>] = [:]
    private var downloads: [String: DownloadTask] = [:]
    private var processedOfferIds: Set<String> = []
    private var autoResumed: Set<String> = []
    private var cancellables: Set<AnyCancellable> = []
    private var started = false

    nonisolated init(settings: Settings,
                     paired: PairedHostStore,
                     history: HistoryStore,
                     fileStore: FileStore,
                     discovery: DiscoveryService) {
        self.settings = settings
        self.paired = paired
        self.history = history
        self.fileStore = fileStore
        self.discovery = discovery
    }

    // MARK: Lifecycle

    func start() {
        guard !started else { return }
        started = true
        restoreInflight()
        paired.$hosts
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                Task { @MainActor in self?.reconcileLoops() }
            }
            .store(in: &cancellables)
        discovery.$hosts
            .receive(on: DispatchQueue.main)
            .sink { [weak self] hosts in
                Task { @MainActor in self?.syncEndpoints(hosts) }
            }
            .store(in: &cancellables)
        reconcileLoops()
    }

    // MARK: Pairing management

    func unpair(hostId: String) {
        pollTasks[hostId]?.cancel()
        pollTasks[hostId] = nil
        hostOnline[hostId] = nil
        for transfer in active where transfer.hostId == hostId {
            downloads[transfer.id]?.cancel()
        }
        incomingOffers.removeAll { $0.hostId == hostId }
        KeychainStore.deleteToken(forHost: hostId)
        paired.remove(id: hostId)
    }

    func pingHost(_ hostId: String) async -> String {
        guard let client = client(for: hostId) else { return "No stored endpoint / token" }
        let started = Date()
        do {
            let pong = try await client.ping()
            let ms = Int(Date().timeIntervalSince(started) * 1000)
            return "OK — \(pong.deviceName) (\(ms) ms)"
        } catch {
            return "Unreachable — \(error.localizedDescription)"
        }
    }

    // MARK: Offers

    func accept(_ incoming: IncomingOffer) {
        incomingOffers.removeAll { $0.id == incoming.id }
        processedOfferIds.insert(incoming.id)
        Task { await self.acceptAndStart(offer: incoming.offer, hostId: incoming.hostId) }
    }

    func reject(_ incoming: IncomingOffer) {
        incomingOffers.removeAll { $0.id == incoming.id }
        processedOfferIds.insert(incoming.id)
        history.add(transferId: incoming.offer.transferId,
                    fileName: incoming.offer.fileName,
                    sizeBytes: incoming.offer.sizeBytes,
                    senderName: incoming.offer.senderName,
                    outcome: "rejected")
        if let client = client(for: incoming.hostId) {
            let transferId = incoming.offer.transferId
            Task.detached { try? await client.reject(transferId: transferId) }
        }
    }

    // MARK: Transfer control (UI)

    func cancel(transferId: String) {
        if let download = downloads[transferId] {
            download.cancel()            // outcome .cancelled flows through handleOutcome
            return
        }
        // Not currently running (failed / interrupted / awaiting choice).
        guard let idx = active.firstIndex(where: { $0.id == transferId }) else { return }
        let transfer = active[idx]
        try? FileManager.default.removeItem(at: AppPaths.partFileURL(transferId: transferId))
        removeRecord(transferId)
        active.remove(at: idx)
        sendStatus(transfer.hostId, transferId, StatusReport(state: "cancelled"))
        history.add(transferId: transferId,
                    fileName: transfer.offer.fileName,
                    sizeBytes: transfer.offer.sizeBytes,
                    senderName: transfer.offer.senderName,
                    outcome: "cancelled")
        updateIdleTimer()
    }

    /// Resume or retry a failed / interrupted transfer.
    func resume(transferId: String) {
        guard let transfer = active.first(where: { $0.id == transferId }) else { return }
        switch transfer.phase {
        case .failed, .interrupted:
            if hasRecord(transferId) {
                // Already accepted on the host — just download again (ranged).
                startDownload(offer: transfer.offer, hostId: transfer.hostId)
            } else {
                // Never accepted (e.g. storage preflight blocked it).
                Task { await self.acceptAndStart(offer: transfer.offer, hostId: transfer.hostId) }
            }
        default:
            break
        }
    }

    /// User answered the "save to Photos or keep in Files?" question.
    func resolveSaveChoice(transferId: String, saveToPhotos: Bool) {
        guard let transfer = active.first(where: { $0.id == transferId }),
              transfer.phase == .awaitingSaveChoice else { return }
        let fileURL = AppPaths.partFileURL(transferId: transferId)
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            setPhase(transferId, .failed(message: "Temp file went missing.", resumable: true))
            return
        }
        if saveToPhotos, let kind = MediaImporter.mediaKind(forFileName: transfer.offer.fileName) {
            Task {
                await self.saveToPhotos(offer: transfer.offer, hostId: transfer.hostId,
                                        fileURL: fileURL, kind: kind)
            }
        } else {
            saveToFilesStore(offer: transfer.offer, hostId: transfer.hostId, fileURL: fileURL)
        }
    }

    // MARK: Diagnostics helpers

    static func freeDiskSpace() -> Int64? {
        let url = URL(fileURLWithPath: NSHomeDirectory())
        let values = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage
    }

    // MARK: - Poll loops

    private func reconcileLoops() {
        let pairedIds = Set(paired.hosts.map { $0.deviceId })
        for (id, task) in pollTasks where !pairedIds.contains(id) {
            task.cancel()
            pollTasks[id] = nil
            hostOnline[id] = nil
        }
        for id in pairedIds where pollTasks[id] == nil {
            pollTasks[id] = Task { [weak self] in
                guard let self else { return }
                await self.pollLoop(hostId: id)
            }
        }
    }

    private func syncEndpoints(_ discovered: [DiscoveredHost]) {
        for host in discovered {
            guard let ip = host.ipAddress, let port = host.port else { continue }
            if paired.host(id: host.deviceId) != nil {
                paired.updateEndpoint(id: host.deviceId, host: ip, port: port, name: host.name)
            }
        }
    }

    private func pollLoop(hostId: String) async {
        var backoffSeconds: UInt64 = 2
        while !Task.isCancelled {
            guard let client = client(for: hostId) else {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                continue
            }
            do {
                let response = try await client.outboxLongPoll(waitSeconds: 25)
                if Task.isCancelled { break }
                markOnline(hostId, true)
                backoffSeconds = 2
                for offer in response.offers {
                    handleOffer(offer, hostId: hostId)
                }
                resumeInterrupted(hostId: hostId)
            } catch {
                if Task.isCancelled { break }
                markOnline(hostId, false)
                try? await Task.sleep(nanoseconds: backoffSeconds * 1_000_000_000)
                backoffSeconds = min(backoffSeconds * 2, 30)
            }
        }
    }

    private func markOnline(_ hostId: String, _ online: Bool) {
        if hostOnline[hostId] != online {
            hostOnline[hostId] = online
        }
    }

    private func handleOffer(_ offer: TransferOffer, hostId: String) {
        let id = offer.transferId
        guard !processedOfferIds.contains(id),
              !incomingOffers.contains(where: { $0.id == id }),
              !active.contains(where: { $0.id == id }) else { return }

        if offer.autoAccept && settings.autoAcceptFromTrusted {
            processedOfferIds.insert(id)
            Task { await self.acceptAndStart(offer: offer, hostId: hostId) }
        } else {
            incomingOffers.append(IncomingOffer(offer: offer, hostId: hostId, receivedAt: Date()))
        }
    }

    private func resumeInterrupted(hostId: String) {
        for transfer in active where transfer.hostId == hostId {
            if transfer.phase == .interrupted, !autoResumed.contains(transfer.id) {
                autoResumed.insert(transfer.id)
                startDownload(offer: transfer.offer, hostId: hostId)
            }
        }
    }

    // MARK: - Accept & download

    private func acceptAndStart(offer: TransferOffer, hostId: String) async {
        // Storage preflight (PROTOCOL.md §9 insufficient_storage).
        if let free = Self.freeDiskSpace(), free < offer.sizeBytes + Self.storageMargin {
            sendStatus(hostId, offer.transferId,
                       StatusReport(state: "failed", error: "insufficient_storage"))
            upsertActive(offer: offer, hostId: hostId,
                         phase: .failed(message: "Not enough free space — need \(ByteFormat.string(offer.sizeBytes + Self.storageMargin)) free.",
                                        resumable: true))
            return
        }

        upsertActive(offer: offer, hostId: hostId, phase: .preparing)

        guard let client = client(for: hostId) else {
            setPhase(offer.transferId, .failed(message: "Host is not reachable.", resumable: true))
            return
        }
        do {
            try await client.accept(transferId: offer.transferId)
        } catch {
            setPhase(offer.transferId,
                     .failed(message: "Could not accept: \(error.localizedDescription)", resumable: true))
            return
        }
        addRecord(offer: offer, hostId: hostId)
        startDownload(offer: offer, hostId: hostId)
    }

    private func startDownload(offer: TransferOffer, hostId: String) {
        let transferId = offer.transferId
        guard downloads[transferId] == nil else { return }
        guard let client = client(for: hostId) else {
            setPhase(transferId, .failed(message: "Host is not reachable.", resumable: true))
            return
        }

        upsertActive(offer: offer, hostId: hostId, phase: .preparing)

        let partURL = AppPaths.partFileURL(transferId: transferId)
        let sha256 = offer.sha256
        let download = DownloadTask(offer: offer, partURL: partURL) { rangeStart in
            client.makeFileRequest(transferId: transferId, rangeStart: rangeStart, sha256: sha256)
        }

        download.onBegan = { [weak self] resumedFrom in
            Task { @MainActor in
                guard let self else { return }
                self.setPhase(transferId, .downloading)
                self.setBytes(transferId, resumedFrom)
                self.sendStatus(hostId, transferId,
                                StatusReport(state: "downloading", bytesReceived: resumedFrom))
            }
        }
        download.onProgress = { [weak self] update in
            Task { @MainActor in
                self?.applyProgress(transferId, update)
            }
        }
        download.onStatusTick = { [weak self] bytes in
            Task { @MainActor in
                self?.sendStatus(hostId, transferId,
                                 StatusReport(state: "downloading", bytesReceived: bytes))
            }
        }
        download.onVerifying = { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.setPhase(transferId, .verifying)
                self.sendStatus(hostId, transferId, StatusReport(state: "verifying"))
            }
        }
        download.onCompletion = { [weak self] outcome in
            Task { @MainActor in
                self?.handleOutcome(outcome, offer: offer, hostId: hostId)
            }
        }

        downloads[transferId] = download
        updateIdleTimer()
        download.start()
    }

    private func handleOutcome(_ outcome: DownloadTask.Outcome,
                               offer: TransferOffer,
                               hostId: String) {
        let transferId = offer.transferId
        downloads[transferId] = nil
        updateIdleTimer()

        switch outcome {
        case .verified(let fileURL):
            setBytes(transferId, offer.sizeBytes)
            sendStatus(hostId, transferId,
                       StatusReport(state: "verified", bytesReceived: offer.sizeBytes))
            routeSave(offer: offer, hostId: hostId, fileURL: fileURL)

        case .integrityMismatch:
            try? FileManager.default.removeItem(at: AppPaths.partFileURL(transferId: transferId))
            removeRecord(transferId)
            sendStatus(hostId, transferId, StatusReport(state: "failed", error: "integrity"))
            setPhase(transferId,
                     .failed(message: "Integrity check failed — received bytes don't match the sender's SHA-256. Retry to download again.",
                             resumable: true))
            history.add(transferId: transferId,
                        fileName: offer.fileName,
                        sizeBytes: offer.sizeBytes,
                        senderName: offer.senderName,
                        outcome: "failed",
                        errorMessage: "integrity")

        case .failed(let message, let resumable):
            sendStatus(hostId, transferId, StatusReport(state: "failed", error: message))
            setPhase(transferId, .failed(message: message, resumable: resumable))

        case .cancelled:
            try? FileManager.default.removeItem(at: AppPaths.partFileURL(transferId: transferId))
            removeRecord(transferId)
            sendStatus(hostId, transferId, StatusReport(state: "cancelled"))
            active.removeAll { $0.id == transferId }
            history.add(transferId: transferId,
                        fileName: offer.fileName,
                        sizeBytes: offer.sizeBytes,
                        senderName: offer.senderName,
                        outcome: "cancelled")
        }
    }

    // MARK: - Save routing

    private func routeSave(offer: TransferOffer, hostId: String, fileURL: URL) {
        if let kind = MediaImporter.mediaKind(forFileName: offer.fileName) {
            switch settings.saveMediaToPhotos {
            case .always:
                Task {
                    await self.saveToPhotos(offer: offer, hostId: hostId,
                                            fileURL: fileURL, kind: kind)
                }
            case .ask:
                setPhase(offer.transferId, .awaitingSaveChoice)
            case .never:
                saveToFilesStore(offer: offer, hostId: hostId, fileURL: fileURL)
            }
        } else {
            saveToFilesStore(offer: offer, hostId: hostId, fileURL: fileURL)
        }
    }

    private func saveToPhotos(offer: TransferOffer, hostId: String,
                              fileURL: URL, kind: MediaKind) async {
        setPhase(offer.transferId, .saving)
        sendStatus(hostId, offer.transferId, StatusReport(state: "saving"))
        let keepCopy = !settings.deleteTempAfterImport
        do {
            try await MediaImporter.importToPhotos(fileURL: fileURL,
                                                   kind: kind,
                                                   moveFile: !keepCopy,
                                                   addToAlbum: settings.addToSendroAlbum)
            if keepCopy {
                try? fileStore.moveIn(from: fileURL, preferredName: offer.fileName)
            }
            finishCompleted(offer: offer, hostId: hostId, savedTo: "photos")
        } catch {
            // Never lose verified bytes: fall back to the Files store.
            do {
                try fileStore.moveIn(from: fileURL, preferredName: offer.fileName)
                finishCompleted(offer: offer, hostId: hostId, savedTo: "files")
            } catch {
                sendStatus(hostId, offer.transferId,
                           StatusReport(state: "failed", error: "save failed: \(error.localizedDescription)"))
                setPhase(offer.transferId,
                         .failed(message: "Could not save: \(error.localizedDescription)", resumable: false))
            }
        }
    }

    private func saveToFilesStore(offer: TransferOffer, hostId: String, fileURL: URL) {
        setPhase(offer.transferId, .saving)
        sendStatus(hostId, offer.transferId, StatusReport(state: "saving"))
        do {
            try fileStore.moveIn(from: fileURL, preferredName: offer.fileName)
            finishCompleted(offer: offer, hostId: hostId, savedTo: "files")
        } catch {
            sendStatus(hostId, offer.transferId,
                       StatusReport(state: "failed", error: "save failed: \(error.localizedDescription)"))
            setPhase(offer.transferId,
                     .failed(message: "Could not save: \(error.localizedDescription)", resumable: false))
        }
    }

    private func finishCompleted(offer: TransferOffer, hostId: String, savedTo: String) {
        sendStatus(hostId, offer.transferId,
                   StatusReport(state: "completed", bytesReceived: offer.sizeBytes, savedTo: savedTo))
        history.add(transferId: offer.transferId,
                    fileName: offer.fileName,
                    sizeBytes: offer.sizeBytes,
                    senderName: offer.senderName,
                    outcome: "completed",
                    savedTo: savedTo)
        removeRecord(offer.transferId)
        active.removeAll { $0.id == offer.transferId }
        updateIdleTimer()
    }

    // MARK: - Active list plumbing

    private func upsertActive(offer: TransferOffer, hostId: String, phase: TransferPhase) {
        if let idx = active.firstIndex(where: { $0.id == offer.transferId }) {
            active[idx].phase = phase
        } else {
            active.append(ActiveTransfer(offer: offer,
                                         hostId: hostId,
                                         phase: phase,
                                         bytesReceived: 0,
                                         speedBytesPerSecond: 0,
                                         etaSeconds: nil))
        }
    }

    private func setPhase(_ transferId: String, _ phase: TransferPhase) {
        guard let idx = active.firstIndex(where: { $0.id == transferId }) else { return }
        active[idx].phase = phase
        if case .failed = phase {
            active[idx].speedBytesPerSecond = 0
            active[idx].etaSeconds = nil
        }
    }

    private func setBytes(_ transferId: String, _ bytes: Int64) {
        guard let idx = active.firstIndex(where: { $0.id == transferId }) else { return }
        active[idx].bytesReceived = bytes
    }

    private func applyProgress(_ transferId: String, _ update: DownloadTask.ProgressUpdate) {
        guard let idx = active.firstIndex(where: { $0.id == transferId }) else { return }
        active[idx].bytesReceived = update.bytesReceived
        active[idx].speedBytesPerSecond = update.speedBytesPerSecond
        active[idx].etaSeconds = update.etaSeconds
    }

    private func updateIdleTimer() {
        UIApplication.shared.isIdleTimerDisabled = !downloads.isEmpty
    }

    // MARK: - Status reports (client → host, fire and forget)

    private func sendStatus(_ hostId: String, _ transferId: String, _ report: StatusReport) {
        guard let client = client(for: hostId) else { return }
        Task.detached {
            try? await client.reportStatus(transferId: transferId, report)
        }
    }

    private func client(for hostId: String) -> SendroClient? {
        guard let host = paired.host(id: hostId),
              let token = KeychainStore.token(forHost: hostId) else { return nil }
        return SendroClient(host: host.lastHost, port: host.lastPort, token: token)
    }

    // MARK: - In-flight persistence

    private func restoreInflight() {
        for record in loadRecords() {
            let partURL = AppPaths.partFileURL(transferId: record.offer.transferId)
            let size = (try? FileManager.default.attributesOfItem(atPath: partURL.path)[.size] as? NSNumber)?.int64Value ?? 0
            active.append(ActiveTransfer(offer: record.offer,
                                         hostId: record.hostId,
                                         phase: .interrupted,
                                         bytesReceived: size,
                                         speedBytesPerSecond: 0,
                                         etaSeconds: nil))
            processedOfferIds.insert(record.offer.transferId)
        }
    }

    private func loadRecords() -> [InFlightRecord] {
        guard let data = try? Data(contentsOf: AppPaths.inflightStateURL) else { return [] }
        return (try? JSONDecoder().decode([InFlightRecord].self, from: data)) ?? []
    }

    private func saveRecords(_ records: [InFlightRecord]) {
        guard let data = try? JSONEncoder().encode(records) else { return }
        try? data.write(to: AppPaths.inflightStateURL, options: .atomic)
    }

    private func addRecord(offer: TransferOffer, hostId: String) {
        var records = loadRecords().filter { $0.offer.transferId != offer.transferId }
        records.append(InFlightRecord(offer: offer, hostId: hostId))
        saveRecords(records)
    }

    private func removeRecord(_ transferId: String) {
        saveRecords(loadRecords().filter { $0.offer.transferId != transferId })
    }

    private func hasRecord(_ transferId: String) -> Bool {
        loadRecords().contains { $0.offer.transferId == transferId }
    }
}

// MARK: - DownloadTask

/// One streaming, resumable, hash-as-you-go download.
///
/// Never holds more than the arriving URLSession chunk in memory: every
/// chunk is appended to the .part file and fed to the incremental hasher
/// on the session's serial delegate queue.
final class DownloadTask: NSObject, URLSessionDataDelegate {

    enum Outcome {
        case verified(fileURL: URL)
        case integrityMismatch
        case failed(message: String, resumable: Bool)
        case cancelled
    }

    struct ProgressUpdate {
        let bytesReceived: Int64
        let totalBytes: Int64
        let speedBytesPerSecond: Double
        let etaSeconds: Int?
    }

    // Configuration
    private let offer: TransferOffer
    private let partURL: URL
    private let makeRequest: (Int64?) -> URLRequest

    // Callbacks (invoked on internal queues — callers hop to MainActor)
    var onBegan: ((Int64) -> Void)?
    var onProgress: ((ProgressUpdate) -> Void)?
    var onStatusTick: ((Int64) -> Void)?
    var onVerifying: (() -> Void)?
    var onCompletion: ((Outcome) -> Void)?

    // State — touched only on `delegateQueue` (or before the task starts)
    private var session: URLSession?
    private var dataTask: URLSessionDataTask?
    private var fileHandle: FileHandle?
    private var hasher = StreamingSHA256()
    private var bytesOnDisk: Int64 = 0
    private var expectedOffset: Int64 = 0
    private var pendingFailure: Outcome?
    private var userCancelled = false
    private var speedSamples: [(time: TimeInterval, bytes: Int64)] = []
    private var lastProgressEmit: TimeInterval = 0
    private var lastStatusEmit: TimeInterval = 0

    private let delegateQueue: OperationQueue = {
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        queue.name = "sendro.download.delegate"
        return queue
    }()

    private let completionLock = NSLock()
    private var didComplete = false

    init(offer: TransferOffer, partURL: URL, makeRequest: @escaping (Int64?) -> URLRequest) {
        self.offer = offer
        self.partURL = partURL
        self.makeRequest = makeRequest
        super.init()
    }

    func start() {
        DispatchQueue.global(qos: .utility).async { [weak self] in
            self?.prepareAndBegin()
        }
    }

    func cancel() {
        userCancelled = true
        dataTask?.cancel()
        // If the task never started (still preparing), make sure we finish.
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 1) { [weak self] in
            guard let self, self.dataTask == nil else { return }
            self.complete(.cancelled)
        }
    }

    // MARK: Preparation (runs off-main, before any delegate callback)

    private func prepareAndBegin() {
        let fm = FileManager.default
        do {
            try fm.createDirectory(at: partURL.deletingLastPathComponent(),
                                   withIntermediateDirectories: true)
            if !fm.fileExists(atPath: partURL.path) {
                fm.createFile(atPath: partURL.path, contents: nil)
            }
            var existing = (try fm.attributesOfItem(atPath: partURL.path)[.size] as? NSNumber)?.int64Value ?? 0

            let handle = try FileHandle(forWritingTo: partURL)
            fileHandle = handle

            if existing > offer.sizeBytes {
                // Can't be ours — start over.
                try handle.truncate(atOffset: 0)
                existing = 0
            }

            if userCancelled {
                try? handle.close()
                fileHandle = nil
                complete(.cancelled)
                return
            }

            if existing > 0 {
                // Progressive hashing means we must re-hash what's on disk
                // (streamed, 1 MiB chunks) before appending — memory-safe.
                try rehashPrefix(byteCount: existing)
            }

            if existing == offer.sizeBytes, offer.sizeBytes > 0 {
                // Whole file already on disk — verify only.
                try handle.close()
                fileHandle = nil
                bytesOnDisk = existing
                finishAfterAllBytes()
                return
            }

            _ = try handle.seekToEnd()
            bytesOnDisk = existing
            expectedOffset = existing

            if isDone() {
                // cancel() already finished us while preparing.
                try? handle.close()
                fileHandle = nil
                return
            }

            let request = makeRequest(existing > 0 ? existing : nil)
            let config = URLSessionConfiguration.default
            config.waitsForConnectivity = true
            config.timeoutIntervalForRequest = 60          // idle timeout between chunks
            config.timeoutIntervalForResource = 7 * 24 * 3600
            config.urlCache = nil
            config.requestCachePolicy = .reloadIgnoringLocalCacheData
            let session = URLSession(configuration: config,
                                     delegate: self,
                                     delegateQueue: delegateQueue)
            self.session = session
            let task = session.dataTask(with: request)
            self.dataTask = task
            onBegan?(existing)
            task.resume()
            if userCancelled {
                task.cancel()
            }
        } catch {
            try? fileHandle?.close()
            fileHandle = nil
            complete(.failed(message: "Could not prepare download: \(error.localizedDescription)",
                             resumable: true))
        }
    }

    private func rehashPrefix(byteCount: Int64) throws {
        let readHandle = try FileHandle(forReadingFrom: partURL)
        defer { try? readHandle.close() }
        var remaining = byteCount
        while remaining > 0 {
            let chunkSize = Int(min(remaining, 1_048_576))
            guard let chunk = try readHandle.read(upToCount: chunkSize), !chunk.isEmpty else { break }
            hasher.update(chunk)
            remaining -= Int64(chunk.count)
        }
        if remaining != 0 {
            throw NSError(domain: "Sendro", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Could not re-read existing partial file."
            ])
        }
    }

    // MARK: URLSessionDataDelegate

    func urlSession(_ session: URLSession,
                    dataTask: URLSessionDataTask,
                    didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse else {
            completionHandler(.allow)
            return
        }
        switch http.statusCode {
        case 206:
            // Server honored the range — keep appending.
            completionHandler(.allow)
        case 200:
            if expectedOffset > 0 {
                // Server ignored the range (file changed?) — restart from 0.
                do {
                    try fileHandle?.truncate(atOffset: 0)
                    hasher = StreamingSHA256()
                    bytesOnDisk = 0
                    expectedOffset = 0
                } catch {
                    pendingFailure = .failed(message: "Could not reset partial file.", resumable: false)
                    completionHandler(.cancel)
                    return
                }
            }
            completionHandler(.allow)
        default:
            let resumable = http.statusCode >= 500
            pendingFailure = .failed(message: "Host returned HTTP \(http.statusCode).",
                                     resumable: resumable)
            completionHandler(.cancel)
        }
    }

    func urlSession(_ session: URLSession,
                    dataTask: URLSessionDataTask,
                    didReceive data: Data) {
        guard pendingFailure == nil else { return }
        do {
            try fileHandle?.write(contentsOf: data)
        } catch {
            pendingFailure = .failed(message: "Write failed (disk full?): \(error.localizedDescription)",
                                     resumable: true)
            dataTask.cancel()
            return
        }
        hasher.update(data)
        bytesOnDisk += Int64(data.count)

        let now = Date.timeIntervalSinceReferenceDate
        speedSamples.append((now, bytesOnDisk))
        // Rolling 3-second window.
        while let first = speedSamples.first, now - first.time > 3.0 {
            speedSamples.removeFirst()
        }

        if now - lastProgressEmit >= 0.25 || bytesOnDisk == offer.sizeBytes {
            lastProgressEmit = now
            onProgress?(makeProgressUpdate(now: now))
        }
        if now - lastStatusEmit >= 1.0 {
            lastStatusEmit = now
            onStatusTick?(bytesOnDisk)
        }
    }

    func urlSession(_ session: URLSession,
                    task: URLSessionTask,
                    didCompleteWithError error: Error?) {
        try? fileHandle?.close()
        fileHandle = nil

        if let pending = pendingFailure {
            complete(pending)
            return
        }
        if let error {
            let nsError = error as NSError
            if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled {
                complete(userCancelled ? .cancelled
                                       : .failed(message: "Transfer interrupted.", resumable: true))
            } else {
                complete(.failed(message: nsError.localizedDescription, resumable: true))
            }
            return
        }
        guard bytesOnDisk == offer.sizeBytes else {
            complete(.failed(message: "Connection closed early (\(bytesOnDisk)/\(offer.sizeBytes) bytes).",
                             resumable: true))
            return
        }
        finishAfterAllBytes()
    }

    // MARK: Verification

    private func finishAfterAllBytes() {
        onVerifying?()
        let hex = hasher.finalizeHex()
        if hex.caseInsensitiveCompare(offer.sha256) == .orderedSame {
            complete(.verified(fileURL: partURL))
        } else {
            complete(.integrityMismatch)
        }
    }

    private func makeProgressUpdate(now: TimeInterval) -> ProgressUpdate {
        var speed: Double = 0
        if let first = speedSamples.first, let last = speedSamples.last,
           last.time > first.time {
            speed = Double(last.bytes - first.bytes) / (last.time - first.time)
        }
        var eta: Int?
        if speed > 1 {
            let remaining = Double(offer.sizeBytes - bytesOnDisk)
            if remaining >= 0 {
                eta = Int(remaining / speed)
            }
        }
        return ProgressUpdate(bytesReceived: bytesOnDisk,
                              totalBytes: offer.sizeBytes,
                              speedBytesPerSecond: speed,
                              etaSeconds: eta)
    }

    private func isDone() -> Bool {
        completionLock.lock()
        defer { completionLock.unlock() }
        return didComplete
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

// MARK: - Formatting helpers (shared with views)

enum ByteFormat {
    static func string(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    static func speed(_ bytesPerSecond: Double) -> String {
        guard bytesPerSecond > 0 else { return "—" }
        return ByteCountFormatter.string(fromByteCount: Int64(bytesPerSecond), countStyle: .file) + "/s"
    }

    static func eta(_ seconds: Int?) -> String {
        guard let seconds, seconds > 0 else { return "—" }
        if seconds < 60 { return "\(seconds)s" }
        if seconds < 3600 {
            return "\(seconds / 60)m \(seconds % 60)s"
        }
        return "\(seconds / 3600)h \((seconds % 3600) / 60)m"
    }
}
