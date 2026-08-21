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
    /// Accepted, waiting for one of the engine's download slots. `position`
    /// is 1-based across the whole queue ("#3 in line"). NOT an error state.
    case queued(position: Int)
    /// The host answered with backpressure (503 / 429 / 409 / other 5xx) and
    /// we are counting down to the next attempt. Also NOT an error state.
    case waitingForHost(reason: HostBusyReason, secondsRemaining: Int)
    case preparing                      // preflight + prefix re-hash
    case downloading
    case verifying
    case awaitingSaveChoice             // media + "Ask" setting
    case saving
    case photosDenied                   // Photos permission refused; bytes kept, user action needed
    case failed(message: String, resumable: Bool)
    case interrupted                    // restored from a previous launch

    var label: String {
        switch self {
        case .queued:            return "Waiting…"
        case .waitingForHost:    return "Waiting for the PC…"
        case .preparing:         return "Preparing…"
        case .downloading:       return "Downloading"
        case .verifying:         return "Verifying…"
        case .awaitingSaveChoice: return "Where should this go?"
        case .saving:            return "Saving…"
        case .photosDenied:      return "Photos Access Needed"
        case .failed:            return "Failed"
        case .interrupted:       return "Paused"
        }
    }

    /// True while the transfer is parked on purpose — queued behind other
    /// files, or waiting out host backpressure. Never render these red.
    var isWaiting: Bool {
        switch self {
        case .queued, .waitingForHost: return true
        default:                       return false
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
    /// A calm one-liner explaining something the numbers alone would make
    /// look like a bug — currently only "we had to start over from byte 0".
    /// Never an error; errors live in `.failed`.
    var note: String? = nil

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
    /// True while a bulk "Accept all" call for this offer is in flight.
    var isAccepting: Bool = false
    /// Set when a bulk accept failed for this one item (§12: a partial
    /// failure leaves the offer pending with its error surfaced).
    var errorMessage: String? = nil

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

    /// §12 — "Accept all" issues the per-transfer accept calls with bounded
    /// concurrency; never more than this many in flight.
    static let bulkAcceptConcurrency = 4

    /// How many transfers may be STREAMING at once. Matches the host's own
    /// default gate (`settings.concurrency`, 2): starting more than this only
    /// earns a 503 per extra file and splits the same LAN bandwidth into
    /// slower slices. Everything else sits in a real queue.
    static let maxConcurrentDownloads = 2

    /// How long a transfer may keep bouncing off host backpressure before it
    /// is parked as a (still resumable) failure. Ten minutes of "the PC is
    /// busy / paused" is patience; an hour is a hang.
    static let backpressureGiveUpSeconds: TimeInterval = 10 * 60

    /// A bulk accept holds one of only `bulkAcceptConcurrency` slots, so it
    /// waits out host backpressure for far less time than a single accept.
    static let bulkAcceptPatienceSeconds: TimeInterval = 30

    private let settings: Settings
    private let paired: PairedHostStore
    private let history: HistoryStore
    private let fileStore: FileStore
    private let discovery: DiscoveryService
    /// In-RAM only (§11). The engine never persists anything it routes here.
    private let messages: MessageCenter
    /// Local notifications (best-effort, foreground/briefly-backgrounded).
    private let notifier: Notifier

    private var pollTasks: [String: Task<Void, Never>] = [:]
    /// Currently STREAMING. `downloads.count` is the occupied-slot count.
    private var downloads: [String: DownloadTask] = [:]
    private var processedOfferIds: Set<String> = []
    private var autoResumed: Set<String> = []
    private var cancellables: Set<AnyCancellable> = []
    private var started = false

    // MARK: Download queue

    /// Transfer ids waiting for a slot, in the order they will start. Every
    /// id here also has a row in `active`, which is where its offer/hostId
    /// live — the queue itself stays a plain list of ids so reordering and
    /// de-duplication are trivial.
    private var queue: [String] = []

    /// Per-host cooldown after a host-WIDE refusal (503 slots busy / paused,
    /// 429, other 5xx). While `Date() < until` the pump will not start ANY
    /// transfer for that host — otherwise the queue would simply rotate
    /// through every waiting file collecting one 503 each.
    private var hostBusyUntil: [String: Date] = [:]
    private var hostBusyReason: [String: HostBusyReason] = [:]

    /// Per-transfer cooldown, for refusals that are about this file only
    /// (409 "not ready yet"). Holding the whole host back for one file that
    /// has not finished hashing would stall the rest of the batch.
    private var transferBusyUntil: [String: Date] = [:]
    private var transferBusyReason: [String: HostBusyReason] = [:]

    /// Per-transfer backpressure history, for the growing delay and the
    /// give-up clock. Cleared the moment real bytes start arriving.
    private var backpressureAttempts: [String: Int] = [:]
    private var backpressureSince: [String: Date] = [:]

    /// Re-entrancy guard for `pumpQueue()`. It is called from status
    /// callbacks, the ticker, the poll loop and the UI, so it must be safe
    /// to call at any moment, including from inside itself.
    private var isPumping = false
    private var pumpRequested = false

    /// One 1 Hz tick for the whole queue (countdowns + cooldown expiry),
    /// alive only while something is actually waiting.
    private var queueTicker: Task<Void, Never>?

    nonisolated init(settings: Settings,
                     paired: PairedHostStore,
                     history: HistoryStore,
                     fileStore: FileStore,
                     discovery: DiscoveryService,
                     messages: MessageCenter,
                     notifier: Notifier) {
        self.settings = settings
        self.paired = paired
        self.history = history
        self.fileStore = fileStore
        self.discovery = discovery
        self.messages = messages
        self.notifier = notifier
    }

    // MARK: Lifecycle

    func start() {
        guard !started else { return }
        started = true
        // A force-quit can leave a Live Activity orphaned on the Lock Screen.
        LiveActivityController.shared.endStaleActivities()
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

    /// Call when scenePhase becomes .active. iOS kills in-flight long-polls
    /// while the app is suspended, and the loops may be parked in a backoff
    /// sleep of up to 15 s. Cancel and restart every loop so each paired
    /// host is re-pinged (fresh online state) and re-polled immediately —
    /// a reachable host shows connected within ~1–2 s, no user action.
    func applicationDidBecomeActive() {
        guard started else { return }
        for task in pollTasks.values { task.cancel() }
        pollTasks.removeAll()
        // Whatever we knew before suspension is stale — clear it so each
        // fresh loop re-pings and the indicator reflects reality, not the
        // last pre-suspension failure (or success).
        hostOnline.removeAll()
        // Backpressure is a foreground concept: a stretch spent suspended
        // must not count against the give-up clock, and a host that was busy
        // ten minutes ago deserves a fresh try right now.
        clearAllBackpressure()
        reconcileLoops()   // spawns fresh loops (backoff starts at zero)
        pumpQueue()
    }

    /// Call when NWPathMonitor reports a different network (joining the PC's
    /// Mobile Hotspot, turning on Personal Hotspot, switching Wi-Fi).
    ///
    /// The old poll sockets belong to the old interface and the backoff timer
    /// may be parked for up to 15 s, so every loop is torn down and respawned:
    /// each paired host — discovered, manually typed or QR-scanned — is
    /// re-pinged immediately at its stored address. A host is never
    /// permanently written off; the loop keeps retrying forever, and discovery
    /// updates the endpoint if the same PC reappears on the new subnet.
    func networkChanged() {
        guard started else { return }
        for task in pollTasks.values { task.cancel() }
        pollTasks.removeAll()
        hostOnline.removeAll()
        clearAllBackpressure()
        reconcileLoops()
        pumpQueue()
    }

    /// Forget every host cooldown and every per-transfer retry history.
    /// Queued transfers stay queued; they just get to try again immediately.
    private func clearAllBackpressure() {
        hostBusyUntil.removeAll()
        hostBusyReason.removeAll()
        transferBusyUntil.removeAll()
        transferBusyReason.removeAll()
        backpressureAttempts.removeAll()
        backpressureSince.removeAll()
    }

    /// When does this transfer become startable, and why is it waiting?
    /// The later of its own cooldown and its host's wins.
    private func waitState(transferId: String, hostId: String) -> (until: Date, reason: HostBusyReason)? {
        var best: (until: Date, reason: HostBusyReason)?
        if let until = hostBusyUntil[hostId] {
            best = (until: until, reason: hostBusyReason[hostId] ?? .hostProblem)
        }
        if let until = transferBusyUntil[transferId],
           until > (best?.until ?? .distantPast) {
            best = (until: until, reason: transferBusyReason[transferId] ?? .notReady)
        }
        return best
    }

    /// Record a cooldown. Host-wide reasons hold the whole host back; a
    /// per-file reason holds only that file.
    private func armCooldown(transferId: String, hostId: String,
                             reason: HostBusyReason, delay: Int) {
        let until = Date().addingTimeInterval(TimeInterval(delay))
        if reason == .notReady {
            if (transferBusyUntil[transferId] ?? .distantPast) < until {
                transferBusyUntil[transferId] = until
            }
            transferBusyReason[transferId] = reason
        } else {
            if (hostBusyUntil[hostId] ?? .distantPast) < until {
                hostBusyUntil[hostId] = until
            }
            hostBusyReason[hostId] = reason
        }
    }

    private func clearCooldowns(transferId: String, hostId: String) {
        transferBusyUntil[transferId] = nil
        transferBusyReason[transferId] = nil
        hostBusyUntil[hostId] = nil
        hostBusyReason[hostId] = nil
    }

    // MARK: Pairing management

    func unpair(hostId: String) {
        pollTasks[hostId]?.cancel()
        pollTasks[hostId] = nil
        hostOnline[hostId] = nil
        hostBusyUntil[hostId] = nil
        hostBusyReason[hostId] = nil
        // Anything of theirs still waiting for a slot can never start now.
        let orphaned = queue.filter { queuedHostId($0) == hostId }
        queue.removeAll { orphaned.contains($0) }
        for transferId in orphaned {
            backpressureAttempts[transferId] = nil
            backpressureSince[transferId] = nil
            transferBusyUntil[transferId] = nil
            transferBusyReason[transferId] = nil
        }
        for transfer in active where transfer.hostId == hostId {
            downloads[transfer.id]?.cancel()
        }
        incomingOffers.removeAll { $0.hostId == hostId }
        KeychainStore.deleteToken(forHost: hostId)
        paired.remove(id: hostId)
        pumpQueue()
    }

    private func queuedHostId(_ transferId: String) -> String? {
        active.first { $0.id == transferId }?.hostId
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

    // MARK: Bulk offer actions (PROTOCOL.md §12)

    /// Accept every pending offer. There is no batch endpoint — this is a
    /// client-side loop over §6.3 with at most `bulkAcceptConcurrency`
    /// accepts in flight. A failure affects only its own item: that offer
    /// stays pending with `errorMessage` set, the rest carry on.
    func acceptAll() {
        let batch = incomingOffers.filter { !$0.isAccepting }
        guard !batch.isEmpty else { return }
        for offer in batch {
            updateIncoming(offer.id) { $0.isAccepting = true; $0.errorMessage = nil }
        }
        Task { await self.runBulkAccept(batch) }
    }

    /// Decline every pending offer (each through the normal reject path).
    func declineAll() {
        for incoming in incomingOffers where !incoming.isAccepting {
            reject(incoming)
        }
    }

    private func runBulkAccept(_ batch: [IncomingOffer]) async {
        await withTaskGroup(of: Void.self) { group in
            var next = 0
            let prime = min(Self.bulkAcceptConcurrency, batch.count)
            while next < prime {
                let incoming = batch[next]
                group.addTask { await self.bulkAcceptOne(incoming) }
                next += 1
            }
            // Refill as each slot frees up — never more than `prime` running.
            while await group.next() != nil {
                guard next < batch.count else { continue }
                let incoming = batch[next]
                group.addTask { await self.bulkAcceptOne(incoming) }
                next += 1
            }
        }
    }

    /// One item of a bulk accept. Unlike `accept(_:)` (per-card action, which
    /// optimistically moves the offer into the active list so the Flight
    /// screen can open), this keeps the offer pending until the host has
    /// actually accepted it, so a failure is visible where the user left it.
    private func bulkAcceptOne(_ incoming: IncomingOffer) async {
        let offer = incoming.offer
        let hostId = incoming.hostId
        let transferId = offer.transferId

        // Same storage preflight as the single-offer path (§9).
        if let free = Self.freeDiskSpace(), free < offer.sizeBytes + Self.storageMargin {
            sendStatus(hostId, transferId,
                       StatusReport(state: "failed", error: "insufficient_storage"))
            failBulkItem(transferId,
                         "Not enough free space — needs \(ByteFormat.string(offer.sizeBytes + Self.storageMargin)) free.")
            return
        }
        guard let client = client(for: hostId) else {
            failBulkItem(transferId, "That computer is not reachable right now.")
            return
        }
        // The row stays in its "accepting" state while this waits out any
        // host backpressure — a busy or still-hashing PC is not an error.
        // Short patience on purpose: this occupies one of only
        // `bulkAcceptConcurrency` slots, so it must never park for minutes
        // and starve the rest of the batch. On timeout the offer simply
        // stays pending with a calm note and the user can tap Accept again.
        let stillPending: () -> Bool = { [weak self] in
            self?.incomingOffers.contains { $0.id == transferId } ?? false
        }
        let outcome = await acceptWithBackpressure(client,
                                                   offer: offer,
                                                   hostId: hostId,
                                                   patience: Self.bulkAcceptPatienceSeconds,
                                                   isAlive: stillPending,
                                                   onWaiting: nil)
        switch outcome {
        case .abandoned:
            return
        case .refused(let message):
            failBulkItem(transferId, message)
            return
        case .accepted:
            break
        }
        guard incomingOffers.contains(where: { $0.id == transferId }) else {
            // Declined or accepted individually while this call was in flight.
            return
        }
        incomingOffers.removeAll { $0.id == transferId }
        processedOfferIds.insert(transferId)
        addRecord(offer: offer, hostId: hostId)
        enqueueDownload(offer: offer, hostId: hostId)
    }

    private func failBulkItem(_ transferId: String, _ message: String) {
        updateIncoming(transferId) { $0.isAccepting = false; $0.errorMessage = message }
    }

    private func updateIncoming(_ id: String, _ mutate: (inout IncomingOffer) -> Void) {
        guard let idx = incomingOffers.firstIndex(where: { $0.id == id }) else { return }
        mutate(&incomingOffers[idx])
    }

    // MARK: Text messages (§11.2, client → host)

    /// Send one ephemeral text message. Returns nil on success, or a
    /// human-readable error. The text is never stored anywhere.
    func sendMessage(_ text: String, toHostId hostId: String) async -> String? {
        guard let client = client(for: hostId) else {
            return "That computer is not reachable right now."
        }
        do {
            try await client.sendMessage(text: text)
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    // MARK: Transfer control (UI)

    func cancel(transferId: String) {
        // Leave the queue first, whatever state this is in — otherwise the
        // pump could start a transfer the user just removed.
        queue.removeAll { $0 == transferId }
        backpressureAttempts[transferId] = nil
        backpressureSince[transferId] = nil
        transferBusyUntil[transferId] = nil
        transferBusyReason[transferId] = nil

        if let download = downloads[transferId] {
            download.cancel()            // outcome .cancelled flows through handleOutcome
            return
        }
        // Not currently running (queued / waiting for host / failed /
        // interrupted / awaiting choice / photos-denied).
        guard let idx = active.firstIndex(where: { $0.id == transferId }) else {
            pumpQueue()
            return
        }
        let transfer = active[idx]
        try? FileManager.default.removeItem(at: AppPaths.partFileURL(transferId: transferId))
        try? FileManager.default.removeItem(at: Self.stagedImportURL(for: transfer.offer))
        removeRecord(transferId)
        active.remove(at: idx)
        LiveActivityController.shared.end(transferId: transferId,
                                          phase: .failed,
                                          bytesReceived: transfer.bytesReceived)
        sendStatus(transfer.hostId, transferId, StatusReport(state: "cancelled"))
        history.add(transferId: transferId,
                    fileName: transfer.offer.fileName,
                    sizeBytes: transfer.offer.sizeBytes,
                    senderName: transfer.offer.senderName,
                    outcome: "cancelled")
        updateIdleTimer()
        pumpQueue()
    }

    /// Resume / retry / "try now". Every one of these goes back through the
    /// queue — nothing bypasses the slot limit, ever.
    func resume(transferId: String) {
        guard let transfer = active.first(where: { $0.id == transferId }) else { return }
        switch transfer.phase {
        case .failed, .interrupted, .queued, .waitingForHost:
            // An explicit tap means "try now": forget the accumulated delay
            // and the host cooldown so this attempt happens immediately.
            backpressureAttempts[transferId] = nil
            backpressureSince[transferId] = nil
            clearCooldowns(transferId: transferId, hostId: transfer.hostId)
            setNote(transferId, nil)
            if hasRecord(transferId) {
                // Already accepted on the host — just download again (ranged).
                enqueueDownload(offer: transfer.offer, hostId: transfer.hostId, atFront: true)
            } else {
                // Never accepted (e.g. storage preflight blocked it).
                Task { await self.acceptAndStart(offer: transfer.offer, hostId: transfer.hostId) }
            }
        default:
            break
        }
    }

    /// User answered the "save to Photos or keep in Files?" question, or is
    /// retrying / redirecting after a Photos permission denial.
    func resolveSaveChoice(transferId: String, saveToPhotos: Bool) {
        guard let transfer = active.first(where: { $0.id == transferId }),
              transfer.phase == .awaitingSaveChoice || transfer.phase == .photosDenied else { return }
        // The verified bytes may still be the raw .part file, or already
        // staged under the real name by an earlier import attempt.
        guard let fileURL = pendingFileURL(for: transfer.offer) else {
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

    // MARK: - Download queue
    //
    // Invariants:
    // - `downloads` holds exactly the streaming transfers; its count is the
    //   occupied-slot count and never exceeds `maxConcurrentDownloads`.
    // - Every id in `queue` has a row in `active` whose phase is `.queued`
    //   or `.waitingForHost`. Nothing else is ever in `queue`.
    // - `pumpQueue()` is idempotent, re-entrant-safe and cheap; call it from
    //   anywhere a slot might have freed or a cooldown might have expired.

    /// The ONE way a download is ever started. Accept, "Accept all", Retry,
    /// Resume, auto-resume after relaunch and every backpressure requeue all
    /// funnel through here.
    ///
    /// - Parameter atFront: for requeues that already had their turn (a 503
    ///   bounce, a 416 restart) so they do not lose their place to files
    ///   accepted after them.
    private func enqueueDownload(offer: TransferOffer, hostId: String, atFront: Bool = false) {
        let transferId = offer.transferId
        // Already streaming — nothing to do, and definitely not a second task.
        guard downloads[transferId] == nil else { return }

        upsertActive(offer: offer, hostId: hostId, phase: .queued(position: queue.count + 1))

        if let existing = queue.firstIndex(of: transferId) {
            if atFront, existing != 0 {
                queue.remove(at: existing)
                queue.insert(transferId, at: 0)
            }
        } else if atFront {
            queue.insert(transferId, at: 0)
        } else {
            queue.append(transferId)
        }
        pumpQueue()
    }

    /// Start as many queued transfers as there are free slots, then refresh
    /// what every waiting row says about itself.
    private func pumpQueue() {
        if isPumping {
            // Re-entered (a start path failed synchronously and pumped
            // again). Ask the outer call to go round once more instead of
            // recursing.
            pumpRequested = true
            return
        }
        isPumping = true
        repeat {
            pumpRequested = false
            drainQueue()
        } while pumpRequested
        isPumping = false

        refreshQueuePhases()
        updateIdleTimer()
        ensureQueueTicker()
    }

    private func drainQueue() {
        guard !queue.isEmpty else { return }
        let now = Date()
        var index = 0
        while downloads.count < Self.maxConcurrentDownloads, index < queue.count {
            let transferId = queue[index]
            guard let transfer = active.first(where: { $0.id == transferId }) else {
                queue.remove(at: index)          // row vanished — drop the id
                continue                         // same index, array shrank
            }
            let hostId = transfer.hostId
            // Told to come back later — by this host (everything of its
            // waits) or about this file (only it waits).
            if let wait = waitState(transferId: transferId, hostId: hostId), wait.until > now {
                index += 1
                continue
            }
            // A host we know is unreachable: keep the place, skip the doomed
            // request. `markOnline` pumps again the moment it answers.
            if hostOnline[hostId] == false {
                index += 1
                continue
            }
            queue.remove(at: index)              // same index, array shrank
            beginStreaming(offer: transfer.offer, hostId: hostId)
        }
    }

    /// Keep every waiting row honest: its place in line, or its countdown.
    private func refreshQueuePhases() {
        let now = Date()
        for (position, transferId) in queue.enumerated() {
            guard let idx = active.firstIndex(where: { $0.id == transferId }) else { continue }
            let hostId = active[idx].hostId
            let phase: TransferPhase
            if let wait = waitState(transferId: transferId, hostId: hostId), wait.until > now {
                let seconds = max(1, Int(wait.until.timeIntervalSince(now).rounded(.up)))
                phase = .waitingForHost(reason: wait.reason, secondsRemaining: seconds)
            } else {
                phase = .queued(position: position + 1)
            }
            if active[idx].phase != phase {
                active[idx].phase = phase
                active[idx].speedBytesPerSecond = 0
                active[idx].etaSeconds = nil
            }
        }
    }

    /// A single 1 Hz tick drives every countdown and every cooldown expiry.
    /// It exists only while something is waiting and ends itself otherwise.
    private func ensureQueueTicker() {
        guard queueTicker == nil,
              !queue.isEmpty || !hostBusyUntil.isEmpty || !transferBusyUntil.isEmpty else { return }
        queueTicker = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if Task.isCancelled { return }
                guard let self else { return }
                self.expireCooldowns()
                self.pumpQueue()
                if self.queue.isEmpty && self.hostBusyUntil.isEmpty
                    && self.transferBusyUntil.isEmpty {
                    self.queueTicker = nil
                    return
                }
            }
        }
    }

    private func expireCooldowns() {
        let now = Date()
        // Snapshot the keys: the dictionaries are mutated inside the loops.
        for hostId in hostBusyUntil.compactMap({ $0.value <= now ? $0.key : nil }) {
            hostBusyUntil[hostId] = nil
            hostBusyReason[hostId] = nil
        }
        for transferId in transferBusyUntil.compactMap({ $0.value <= now ? $0.key : nil }) {
            transferBusyUntil[transferId] = nil
            transferBusyReason[transferId] = nil
        }
    }

    /// A stream just ended (verified, failed, cancelled — anything but a
    /// bounce), so a slot on the HOST just freed too. Drop a "slots busy"
    /// cooldown immediately instead of sitting out its timer for nothing.
    /// A pause or a host-side error is not cured by our stream ending, so
    /// those cooldowns are left to run out.
    private func noteStreamEnded(_ hostId: String) {
        if hostBusyReason[hostId] == .slotsBusy {
            hostBusyUntil[hostId] = nil
            hostBusyReason[hostId] = nil
        }
    }

    /// The 503 (and 429 / 409 / 5xx) landing point. This is NEVER a failure:
    /// the transfer goes straight back into the queue with a countdown, and
    /// every byte already on disk stays exactly where it is.
    private func noteBackpressure(offer: TransferOffer,
                                  hostId: String,
                                  info: HostBackpressure) {
        let transferId = offer.transferId
        let attempts = (backpressureAttempts[transferId] ?? 0) + 1
        backpressureAttempts[transferId] = attempts
        let since = backpressureSince[transferId] ?? Date()
        backpressureSince[transferId] = since

        let hostName = offer.senderName

        // Give up only after a long stretch — and even then, resumable, with
        // the partial file intact.
        if Date().timeIntervalSince(since) > Self.backpressureGiveUpSeconds {
            backpressureAttempts[transferId] = nil
            backpressureSince[transferId] = nil
            let message = info.reason.giveUpMessage(hostName: hostName)
            sendStatus(hostId, transferId, StatusReport(state: "failed", error: "host busy"))
            setPhase(transferId, .failed(message: message, resumable: true))
            LiveActivityController.shared.end(transferId: transferId,
                                              phase: .failed,
                                              bytesReceived: activeBytes(transferId))
            notifier.notifyTransferFailed(fileName: offer.fileName, reason: "the PC stayed busy")
            return
        }

        // Exponential-ish, floored by the host's own Retry-After and capped
        // at 30 s: 1x, 2x, 4x, 8x, then flat.
        let multiplier = 1 << min(max(attempts - 1, 0), 3)
        let delay = HostStatus.clampRetryAfter(info.retryAfterSeconds * multiplier)
        armCooldown(transferId: transferId, hostId: hostId, reason: info.reason, delay: delay)

        // Keep the Lock Screen honest too — "Receiving" while parked is the
        // same lie as a highlighted STREAM rail.
        LiveActivityController.shared.update(transferId: transferId,
                                             phase: .waiting,
                                             bytesReceived: activeBytes(transferId),
                                             speedBytesPerSecond: 0,
                                             etaSeconds: nil,
                                             force: true)
        // Tell the host we are parked, not gone: still "downloading" from
        // its point of view, at the offset we already hold.
        sendStatus(hostId, transferId,
                   StatusReport(state: "downloading", bytesReceived: activeBytes(transferId)))

        enqueueDownload(offer: offer, hostId: hostId, atFront: true)
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

    /// Immortal per-host loop. Invariants:
    /// - Online state comes ONLY from ping / long-poll success (manual hosts
    ///   have no mDNS presence, so discovery must never drive the indicator).
    /// - A 200 with an empty offers array is the normal long-poll timeout:
    ///   mark online and re-poll immediately, no delay.
    /// - A long-poll error alone never marks the host offline: the poll
    ///   socket dies routinely (app suspension → -999/-1001, keep-alive
    ///   reuse → -1005) while the host is perfectly reachable, so we confirm
    ///   with a fast ping first.
    /// - Real unreachability (refused / no route) backs off 1→2→4→8→15 s,
    ///   capped at 15 s, and retries forever — never a terminal stop.
    private func pollLoop(hostId: String) async {
        var backoffSeconds: Double = 0
        while !Task.isCancelled {
            guard let client = client(for: hostId) else {
                // No stored endpoint/token yet — check again shortly.
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                continue
            }
            // Fast reachability probe whenever we aren't already known-online
            // (loop start, after a failure, after foreground restart) so a
            // reachable host turns green in ~1–2 s instead of after a full
            // 25 s poll cycle.
            if hostOnline[hostId] != true, (try? await client.ping()) != nil {
                if Task.isCancelled { break }
                markOnline(hostId, true)
                backoffSeconds = 0
            }
            if Task.isCancelled { break }
            do {
                let response = try await client.outboxLongPoll(waitSeconds: 25)
                if Task.isCancelled { break }
                markOnline(hostId, true)
                backoffSeconds = 0
                var arrived: [TransferOffer] = []
                for offer in response.offers {
                    if handleOffer(offer, hostId: hostId) {
                        arrived.append(offer)
                    }
                }
                if !arrived.isEmpty {
                    // One notification per poll batch, not per file.
                    let senderName = arrived.first?.senderName
                        ?? paired.host(id: hostId)?.name
                        ?? "Your PC"
                    notifier.notifyIncomingOffers(count: arrived.count, senderName: senderName)
                }
                // §11.1 — messages ride the same poll. Straight into RAM,
                // never touched again by this loop.
                if let delivered = response.messages, !delivered.isEmpty {
                    messages.receive(delivered)
                    if let newest = delivered.last {
                        // Privacy: only the sender's name leaves this line.
                        notifier.notifyMessage(senderName: newest.senderName)
                    }
                }
                resumeInterrupted(hostId: hostId)
            } catch {
                if Task.isCancelled { break }
                // Distinguish "poll socket died" from "host actually gone".
                if (try? await client.ping()) != nil {
                    if Task.isCancelled { break }
                    markOnline(hostId, true)
                    backoffSeconds = 0
                    continue        // host is fine — re-poll immediately
                }
                if Task.isCancelled { break }
                markOnline(hostId, false)
                backoffSeconds = backoffSeconds <= 0 ? 1 : min(backoffSeconds * 2, 15)
                try? await Task.sleep(nanoseconds: UInt64(backoffSeconds * 1_000_000_000))
            }
        }
    }

    private func markOnline(_ hostId: String, _ online: Bool) {
        guard hostOnline[hostId] != online else { return }
        hostOnline[hostId] = online
        // A host coming back is the single best moment to start whatever of
        // its was parked waiting for it.
        if online { pumpQueue() }
    }

    /// - Returns: true when this offer is newly waiting for the user (an
    ///   auto-accepted offer returns false — it notifies on completion, not
    ///   on arrival).
    @discardableResult
    private func handleOffer(_ offer: TransferOffer, hostId: String) -> Bool {
        let id = offer.transferId
        guard !processedOfferIds.contains(id),
              !incomingOffers.contains(where: { $0.id == id }),
              !active.contains(where: { $0.id == id }) else { return false }

        if offer.autoAccept && settings.autoAcceptFromTrusted {
            processedOfferIds.insert(id)
            Task { await self.acceptAndStart(offer: offer, hostId: hostId) }
            return false
        }
        incomingOffers.append(IncomingOffer(offer: offer, hostId: hostId, receivedAt: Date()))
        return true
    }

    private func resumeInterrupted(hostId: String) {
        for transfer in active where transfer.hostId == hostId {
            if transfer.phase == .interrupted, !autoResumed.contains(transfer.id) {
                autoResumed.insert(transfer.id)
                enqueueDownload(offer: transfer.offer, hostId: hostId)
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
            setPhase(offer.transferId,
                     .failed(message: "\(offer.senderName) is not reachable right now.", resumable: true))
            return
        }
        let transferId = offer.transferId
        let stillWanted: () -> Bool = { [weak self] in
            self?.active.contains { $0.id == transferId } ?? false
        }
        let showCountdown: (HostBusyReason, Int) -> Void = { [weak self] reason, seconds in
            self?.setPhase(transferId, .waitingForHost(reason: reason, secondsRemaining: seconds))
        }
        let outcome = await acceptWithBackpressure(client,
                                                   offer: offer,
                                                   hostId: hostId,
                                                   patience: Self.backpressureGiveUpSeconds,
                                                   isAlive: stillWanted,
                                                   onWaiting: showCountdown)
        switch outcome {
        case .abandoned:
            return
        case .refused(let message):
            setPhase(transferId, .failed(message: message, resumable: true))
            return
        case .accepted:
            break
        }
        addRecord(offer: offer, hostId: hostId)
        enqueueDownload(offer: offer, hostId: hostId)
    }

    /// What `acceptWithBackpressure` concluded.
    private enum AcceptOutcome {
        case accepted
        /// The user removed the offer/transfer while we were waiting.
        case abandoned
        case refused(String)
    }

    /// POST §6.3 accept, treating host backpressure (503 / 429 / 409 / 5xx)
    /// as "wait, then try again" instead of as a failure. 409 in particular
    /// is a real race on "Accept all": the host has the offer but has not
    /// finished hashing the file yet.
    ///
    /// Bounded by `backpressureGiveUpSeconds`, and abandoned the moment the
    /// caller's row disappears.
    private func acceptWithBackpressure(_ client: SendroClient,
                                        offer: TransferOffer,
                                        hostId: String,
                                        patience: TimeInterval,
                                        isAlive: @escaping () -> Bool,
                                        onWaiting: ((HostBusyReason, Int) -> Void)?) async -> AcceptOutcome {
        let deadline = Date().addingTimeInterval(patience)
        var attempt = 0
        while true {
            guard isAlive() else { return .abandoned }
            do {
                try await client.accept(transferId: offer.transferId)
                // It was ready after all — do not make the download wait out
                // a cooldown that this very answer just disproved.
                transferBusyUntil[offer.transferId] = nil
                transferBusyReason[offer.transferId] = nil
                return .accepted
            } catch let error as SendroClientError {
                guard let status = error.httpStatus else {
                    return .refused(error.localizedDescription)
                }
                guard let info = HostStatus.backpressure(status: status,
                                                         retryAfterHeader: nil,
                                                         hostMessage: error.apiMessage) else {
                    return .refused(HostStatus.failure(status: status,
                                                       hostMessage: error.apiMessage,
                                                       hostName: offer.senderName,
                                                       direction: .incoming).message)
                }
                guard Date() < deadline else {
                    return .refused(info.reason.giveUpMessage(hostName: offer.senderName))
                }
                attempt += 1
                let multiplier = 1 << min(max(attempt - 1, 0), 3)
                let delay = HostStatus.clampRetryAfter(info.retryAfterSeconds * multiplier)
                // Hold the download queue back for the same window, so it
                // does not go and collect the identical refusal.
                armCooldown(transferId: offer.transferId, hostId: hostId,
                            reason: info.reason, delay: delay)
                ensureQueueTicker()

                var remaining = delay
                while remaining > 0 {
                    guard isAlive() else { return .abandoned }
                    onWaiting?(info.reason, remaining)
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                    remaining -= 1
                }
            } catch {
                // Transport-level (unreachable, timed out) — not a protocol
                // answer, so the caller decides.
                return .refused(error.localizedDescription)
            }
        }
    }

    /// Actually open the stream. ONLY `drainQueue()` may call this — it is
    /// what consumes a slot.
    private func beginStreaming(offer: TransferOffer, hostId: String) {
        let transferId = offer.transferId
        guard downloads[transferId] == nil else { return }
        guard let client = client(for: hostId) else {
            setPhase(transferId, .failed(message: "\(offer.senderName) is not reachable right now.",
                                         resumable: true))
            return
        }

        upsertActive(offer: offer, hostId: hostId, phase: .preparing)

        // Dynamic Island / Lock Screen for big ones only (≥ 200 MB). A no-op
        // below iOS 16.1 or when the user disabled Live Activities.
        LiveActivityController.shared.start(transferId: transferId,
                                            fileName: offer.fileName,
                                            totalBytes: offer.sizeBytes,
                                            senderName: offer.senderName)

        let partURL = AppPaths.partFileURL(transferId: transferId)
        let sha256 = offer.sha256
        let download = DownloadTask(offer: offer, partURL: partURL) { rangeStart in
            client.makeFileRequest(transferId: transferId, rangeStart: rangeStart, sha256: sha256)
        }

        download.onRestartedFromZero = { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.setBytes(transferId, 0)
                self.setNote(transferId, Self.restartNote(hostName: offer.senderName))
            }
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
                LiveActivityController.shared.update(transferId: transferId,
                                                     phase: .verifying,
                                                     bytesReceived: offer.sizeBytes,
                                                     speedBytesPerSecond: 0,
                                                     etaSeconds: nil,
                                                     force: true)
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
        downloads[transferId] = nil                 // slot released
        updateIdleTimer()

        // Backpressure is the one outcome that is not the end of anything:
        // it re-queues, keeps the .part file, and must not touch history,
        // notifications or the host's failure accounting.
        if case .backpressure(let info) = outcome {
            noteBackpressure(offer: offer, hostId: hostId, info: info)
            pumpQueue()
            return
        }

        // Every other outcome means our stream really ended, so a slot on
        // the host freed as well.
        noteStreamEnded(hostId)
        backpressureAttempts[transferId] = nil
        backpressureSince[transferId] = nil
        transferBusyUntil[transferId] = nil
        transferBusyReason[transferId] = nil

        switch outcome {
        case .backpressure:
            break                                   // handled above

        case .rangeRejected:
            // 416: the host says our offset is not valid for its file any
            // more (it changed, or our .part is longer than the source).
            // Throw the partial away and go round again from byte 0 — same
            // queue, front of the line, no red screen.
            try? FileManager.default.removeItem(at: AppPaths.partFileURL(transferId: transferId))
            setBytes(transferId, 0)
            setNote(transferId, Self.restartNote(hostName: offer.senderName))
            sendStatus(hostId, transferId, StatusReport(state: "downloading", bytesReceived: 0))
            enqueueDownload(offer: offer, hostId: hostId, atFront: true)

        case .httpRefused(let status, let hostMessage):
            let refusal = HostStatus.failure(status: status,
                                             hostMessage: hostMessage,
                                             hostName: offer.senderName,
                                             direction: .incoming)
            sendStatus(hostId, transferId,
                       StatusReport(state: "failed", error: "http \(status)"))
            setPhase(transferId, .failed(message: refusal.message, resumable: refusal.resumable))
            LiveActivityController.shared.end(transferId: transferId,
                                              phase: .failed,
                                              bytesReceived: activeBytes(transferId))
            notifier.notifyTransferFailed(fileName: offer.fileName, reason: refusal.message)

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
            LiveActivityController.shared.end(transferId: transferId,
                                              phase: .failed,
                                              bytesReceived: 0)
            notifier.notifyTransferFailed(fileName: offer.fileName, reason: "integrity check")

        case .failed(let message, let resumable):
            sendStatus(hostId, transferId, StatusReport(state: "failed", error: message))
            setPhase(transferId, .failed(message: message, resumable: resumable))
            LiveActivityController.shared.end(transferId: transferId,
                                              phase: .failed,
                                              bytesReceived: activeBytes(transferId))
            notifier.notifyTransferFailed(fileName: offer.fileName, reason: message)

        case .cancelled:
            try? FileManager.default.removeItem(at: AppPaths.partFileURL(transferId: transferId))
            try? FileManager.default.removeItem(at: Self.stagedImportURL(for: offer))
            removeRecord(transferId)
            sendStatus(hostId, transferId, StatusReport(state: "cancelled"))
            active.removeAll { $0.id == transferId }
            history.add(transferId: transferId,
                        fileName: offer.fileName,
                        sizeBytes: offer.sizeBytes,
                        senderName: offer.senderName,
                        outcome: "cancelled")
            LiveActivityController.shared.end(transferId: transferId,
                                              phase: .failed,
                                              bytesReceived: 0)
        }

        // A slot just freed — start whatever is next in line.
        pumpQueue()
    }

    /// Shown whenever the progress ring legitimately rewinds to zero, so a
    /// restart never reads as a bug.
    static func restartNote(hostName: String) -> String {
        "Resuming didn't line up with the copy on \(hostName), so this is downloading again from the start. Nothing is lost — it still has to match the same SHA-256."
    }

    private func activeBytes(_ transferId: String) -> Int64 {
        active.first { $0.id == transferId }?.bytesReceived ?? 0
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
        LiveActivityController.shared.update(transferId: offer.transferId,
                                             phase: .saving,
                                             bytesReceived: offer.sizeBytes,
                                             speedBytesPerSecond: 0,
                                             etaSeconds: nil,
                                             force: true)
        let keepCopy = !settings.deleteTempAfterImport

        // PhotoKit infers the asset's type from the source URL's file
        // extension, so handing it the raw "<uuid>.part" temp fails for
        // EVERY photo/video ("file format not supported") — which used to
        // silently dump all media into Files. Stage the verified bytes under
        // the real (sanitized) file name first: a same-volume rename, the
        // original bytes are untouched.
        let importURL: URL
        do {
            importURL = try stageForImport(offer: offer, from: fileURL)
        } catch {
            sendStatus(hostId, offer.transferId,
                       StatusReport(state: "failed", error: "save failed: \(error.localizedDescription)"))
            setPhase(offer.transferId,
                     .failed(message: "Could not save: \(error.localizedDescription)", resumable: false))
            return
        }

        do {
            let assetId = try await MediaImporter.importToPhotos(fileURL: importURL,
                                                                 kind: kind,
                                                                 originalFilename: offer.fileName,
                                                                 moveFile: !keepCopy,
                                                                 addToAlbum: settings.addToSendroAlbum)
            // With "Delete Temp After Import" ON the bytes are now ONLY in
            // Photos — that is exactly why the asset id is recorded: the
            // Library row has to preview from the library, not from a file
            // that no longer exists.
            var keptName: String?
            if keepCopy {
                keptName = (try? fileStore.moveIn(from: importURL,
                                                  preferredName: offer.fileName))?.lastPathComponent
            }
            finishCompleted(offer: offer, hostId: hostId, savedTo: "photos",
                            localName: keptName, photoAssetId: assetId)
        } catch MediaImportError.notAuthorized {
            // Surface it instead of silently rerouting to Files — the user
            // expects media in their gallery. Bytes stay staged for retry
            // (Open Settings → grant → Retry), or can be sent to Files.
            sendStatus(hostId, offer.transferId,
                       StatusReport(state: "failed", error: "photos permission denied"))
            setPhase(offer.transferId, .photosDenied)
        } catch {
            // Any other import failure: never lose verified bytes — fall
            // back to the Files store.
            do {
                let saved = try fileStore.moveIn(from: importURL, preferredName: offer.fileName)
                finishCompleted(offer: offer, hostId: hostId, savedTo: "files",
                                localName: saved.lastPathComponent)
            } catch {
                sendStatus(hostId, offer.transferId,
                           StatusReport(state: "failed", error: "save failed: \(error.localizedDescription)"))
                setPhase(offer.transferId,
                         .failed(message: "Could not save: \(error.localizedDescription)", resumable: false))
            }
        }
    }

    /// Where a transfer's verified bytes get staged (real name, real
    /// extension) so PhotoKit can recognize the type. transferId prefix
    /// keeps concurrent same-named transfers apart.
    private static func stagedImportURL(for offer: TransferOffer) -> URL {
        AppPaths.incoming.appendingPathComponent(
            "\(offer.transferId)-\(FileStore.sanitize(fileName: offer.fileName))",
            isDirectory: false)
    }

    /// Move the verified temp to its staged import location. Idempotent:
    /// safe to call again after a failed import attempt (file already staged).
    private func stageForImport(offer: TransferOffer, from fileURL: URL) throws -> URL {
        let staged = Self.stagedImportURL(for: offer)
        let fm = FileManager.default
        if fileURL.path == staged.path { return staged }        // already staged
        if fm.fileExists(atPath: fileURL.path) {
            if fm.fileExists(atPath: staged.path) {
                try fm.removeItem(at: staged)                   // stale leftover
            }
            try fm.moveItem(at: fileURL, to: staged)
            return staged
        }
        if fm.fileExists(atPath: staged.path) { return staged } // staged earlier
        throw NSError(domain: "Sendro", code: 2, userInfo: [
            NSLocalizedDescriptionKey: "Temp file went missing."
        ])
    }

    /// Wherever this transfer's verified bytes currently live, if anywhere.
    private func pendingFileURL(for offer: TransferOffer) -> URL? {
        let fm = FileManager.default
        let staged = Self.stagedImportURL(for: offer)
        if fm.fileExists(atPath: staged.path) { return staged }
        let part = AppPaths.partFileURL(transferId: offer.transferId)
        if fm.fileExists(atPath: part.path) { return part }
        return nil
    }

    private func saveToFilesStore(offer: TransferOffer, hostId: String, fileURL: URL) {
        setPhase(offer.transferId, .saving)
        sendStatus(hostId, offer.transferId, StatusReport(state: "saving"))
        LiveActivityController.shared.update(transferId: offer.transferId,
                                             phase: .saving,
                                             bytesReceived: offer.sizeBytes,
                                             speedBytesPerSecond: 0,
                                             etaSeconds: nil,
                                             force: true)
        do {
            let saved = try fileStore.moveIn(from: fileURL, preferredName: offer.fileName)
            finishCompleted(offer: offer, hostId: hostId, savedTo: "files",
                            localName: saved.lastPathComponent)
        } catch {
            sendStatus(hostId, offer.transferId,
                       StatusReport(state: "failed", error: "save failed: \(error.localizedDescription)"))
            setPhase(offer.transferId,
                     .failed(message: "Could not save: \(error.localizedDescription)", resumable: false))
        }
    }

    private func finishCompleted(offer: TransferOffer,
                                 hostId: String,
                                 savedTo: String,
                                 localName: String? = nil,
                                 photoAssetId: String? = nil) {
        sendStatus(hostId, offer.transferId,
                   StatusReport(state: "completed", bytesReceived: offer.sizeBytes, savedTo: savedTo))
        history.add(transferId: offer.transferId,
                    fileName: offer.fileName,
                    sizeBytes: offer.sizeBytes,
                    senderName: offer.senderName,
                    outcome: "completed",
                    savedTo: savedTo,
                    localName: localName,
                    photoAssetId: photoAssetId)
        removeRecord(offer.transferId)
        active.removeAll { $0.id == offer.transferId }
        LiveActivityController.shared.end(transferId: offer.transferId,
                                          phase: .completed,
                                          bytesReceived: offer.sizeBytes)
        notifier.notifyTransferFinished(fileName: offer.fileName, savedTo: savedTo)
        updateIdleTimer()
    }

    // MARK: - Active list plumbing

    private func upsertActive(offer: TransferOffer, hostId: String, phase: TransferPhase) {
        if let idx = active.firstIndex(where: { $0.id == offer.transferId }) {
            active[idx].phase = phase                  // `note` is deliberately kept
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

    private func setNote(_ transferId: String, _ text: String?) {
        guard let idx = active.firstIndex(where: { $0.id == transferId }) else { return }
        if active[idx].note != text { active[idx].note = text }
    }

    private func setBytes(_ transferId: String, _ bytes: Int64) {
        guard let idx = active.firstIndex(where: { $0.id == transferId }) else { return }
        active[idx].bytesReceived = bytes
    }

    private func applyProgress(_ transferId: String, _ update: DownloadTask.ProgressUpdate) {
        guard let idx = active.firstIndex(where: { $0.id == transferId }) else { return }
        // Real bytes are arriving: whatever backpressure this transfer hit
        // before is history, so the next 503 (if any) starts from a short
        // delay again and the give-up clock restarts.
        if backpressureAttempts[transferId] != nil {
            backpressureAttempts[transferId] = nil
            backpressureSince[transferId] = nil
        }
        active[idx].bytesReceived = update.bytesReceived
        active[idx].speedBytesPerSecond = update.speedBytesPerSecond
        active[idx].etaSeconds = update.etaSeconds
        // Throttled to ≤1/s inside the controller — this fires 4×/s.
        LiveActivityController.shared.update(transferId: transferId,
                                             phase: .downloading,
                                             bytesReceived: update.bytesReceived,
                                             speedBytesPerSecond: update.speedBytesPerSecond,
                                             etaSeconds: update.etaSeconds)
    }

    /// Keep the screen awake while there is anything left to do — including
    /// files that are only queued or counting down a Retry-After. Letting the
    /// device sleep mid-batch suspends the app and stalls the whole queue.
    private func updateIdleTimer() {
        UIApplication.shared.isIdleTimerDisabled = !downloads.isEmpty || !queue.isEmpty
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
        /// The host said "later", not "no" (503 / 429 / 409 / other 5xx).
        /// The .part file is untouched and the transfer is still alive.
        case backpressure(HostBackpressure)
        /// 416 — our resume offset is not valid for the host's file any
        /// more. Start over from byte 0.
        case rangeRejected
        /// A real protocol refusal. The engine words it for the user, since
        /// it is the side that knows the host's name.
        case httpRefused(status: Int, hostMessage: String?)
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
    /// The host answered 200 to a ranged request (its copy changed), so the
    /// .part file was truncated and progress genuinely went back to zero.
    var onRestartedFromZero: (() -> Void)?
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
    /// Set when the host answered with a non-2xx: from that point the body
    /// is a small JSON error, never file bytes. Bounded — an error body is
    /// tens of bytes and this must never become a buffering path.
    private var errorStatus: Int?
    private var errorRetryAfter: String?
    private var errorBody = Data()
    private static let maxErrorBodyBytes = 8 * 1024
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
            complete(.failed(message: "Couldn't get the file ready on this iPhone: \(error.localizedDescription)",
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
                    speedSamples.removeAll()
                    onRestartedFromZero?()
                } catch {
                    pendingFailure = .failed(message: "Couldn't clear the partial file to start over. Remove this transfer and ask for it again.",
                                             resumable: false)
                    completionHandler(.cancel)
                    return
                }
            }
            completionHandler(.allow)
        default:
            // Do NOT cancel here. "transfer slots busy" and "transfers
            // paused" are the same 503 and differ only in the JSON body, so
            // read the (tiny) body before deciding what this means. The
            // bytes are diverted away from the .part file and the hasher.
            errorStatus = http.statusCode
            errorRetryAfter = http.value(forHTTPHeaderField: "Retry-After")
            completionHandler(.allow)
        }
    }

    /// Turn a non-2xx answer into an outcome. Backpressure first — a 503 is
    /// never a failure — then the stale-range restart, then a real refusal.
    private static func outcome(forStatus status: Int,
                                retryAfterHeader: String?,
                                body: Data) -> Outcome {
        if status == 416 { return .rangeRejected }
        if let info = HostStatus.backpressure(status: status,
                                              retryAfterHeader: retryAfterHeader,
                                              body: body) {
            return .backpressure(info)
        }
        return .httpRefused(status: status, hostMessage: HostStatus.apiError(in: body)?.message)
    }

    func urlSession(_ session: URLSession,
                    dataTask: URLSessionDataTask,
                    didReceive data: Data) {
        if errorStatus != nil {
            // Error body, not file bytes: keep a bounded copy and let the
            // .part file and the running hash alone.
            let room = Self.maxErrorBodyBytes - errorBody.count
            if room > 0 { errorBody.append(data.prefix(room)) }
            return
        }
        guard pendingFailure == nil else { return }
        do {
            try fileHandle?.write(contentsOf: data)
        } catch {
            pendingFailure = .failed(message: "Couldn't write to storage — the iPhone may be full. \(error.localizedDescription)",
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
        if let status = errorStatus {
            // A cancel that raced the error body is still a cancel.
            complete(userCancelled ? .cancelled
                                   : Self.outcome(forStatus: status,
                                                  retryAfterHeader: errorRetryAfter,
                                                  body: errorBody))
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
            complete(.failed(message: "The connection dropped after \(ByteFormat.string(bytesOnDisk)) of \(ByteFormat.string(offer.sizeBytes)). Resume picks up from exactly there.",
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
