//
//  NoteStore.swift
//  Sendro
//
//  The 24-hour notes shelf (PROTOCOL.md §11.3).
//
//  A §11 text message is ephemeral ON THE WIRE and on the host: the PC keeps
//  it in RAM until it is delivered or dismissed and never writes it down.
//  That is still true and this file does not change it.
//
//  What this file adds is a LOCAL, opt-out-free convenience on the phone: a
//  copy of the text you sent or received, kept on this device only, for 24
//  hours, so you can look at that Wi-Fi password again after the card has
//  gone. The rules, deliberately narrow:
//
//  - Local only. Nothing here is ever uploaded, synced or put in history.
//  - Time-boxed. Every note carries its own expiry; anything past it is
//    deleted on load, on every write, and once a minute while the app is
//    open. There is no "keep forever" and no way to extend a note.
//  - Deletable. One swipe removes a note; "Clear all" removes the shelf.
//  - Protected at rest. The file is written with
//    `.completeFileProtection`, so it is unreadable while the phone is
//    locked, and it lives in Application Support (excluded from backups is
//    NOT needed — a 24 h file that expires on read is harmless — but it is
//    never in Documents, so it cannot show up in the Files app).
//

import Foundation
import Combine

/// One kept line of text. `Codable` here is intentional and is the ONLY
/// place in the app where message text is allowed to be serialized.
struct Note: Identifiable, Codable, Equatable {

    enum Direction: String, Codable {
        case incoming       // arrived from a paired computer
        case outgoing       // typed here and sent
    }

    let id: String
    let text: String
    /// The other end of the exchange — the PC's name.
    let peerName: String
    let direction: Direction
    let createdAt: Date
    /// Hard deletion time. Stored rather than derived so a change to the
    /// TTL can never silently extend notes that already exist.
    let expiresAt: Date

    var isExpired: Bool { Date() >= expiresAt }
}

@MainActor
final class NoteStore: ObservableObject {

    /// How long a note survives. Fixed; not a setting.
    static let ttl: TimeInterval = 24 * 60 * 60

    /// Upper bound on the shelf, oldest dropped first. Bounds the file and
    /// keeps the list readable.
    static let capacity = 200

    @Published private(set) var notes: [Note] = []

    private let fileURL: URL
    private var pruneTimer: Task<Void, Never>?

    nonisolated init(fileURL: URL? = nil) {
        self.fileURL = fileURL
            ?? AppPaths.appSupport.appendingPathComponent("notes.json", isDirectory: false)
        Task { @MainActor in self.load() }
    }

    // MARK: Lifecycle

    /// Start the once-a-minute expiry sweep. Called when the app becomes
    /// active; cheap enough to call repeatedly.
    func startPruning() {
        guard pruneTimer == nil else { return }
        pruneTimer = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 60_000_000_000)
                if Task.isCancelled { return }
                // The store went away with the app graph — end the loop
                // rather than spinning against a dead reference.
                guard let self else { return }
                self.prune()
            }
        }
    }

    func stopPruning() {
        pruneTimer?.cancel()
        pruneTimer = nil
    }

    // MARK: Reading

    /// Newest first — the order the shelf is read in.
    var newestFirst: [Note] { notes.sorted { $0.createdAt > $1.createdAt } }

    var isEmpty: Bool { notes.isEmpty }

    /// Whole hours (rounded up, minimum 1) left before `note` is deleted.
    /// Used for the "23 h left" caption; deliberately coarse.
    static func hoursLeft(for note: Note) -> Int {
        let seconds = note.expiresAt.timeIntervalSinceNow
        guard seconds > 0 else { return 0 }
        return max(1, Int(ceil(seconds / 3600)))
    }

    // MARK: Writing

    /// Record text that arrived from a computer. Deduped by message id, so a
    /// resend of the same §11 message never stacks twice.
    func addIncoming(_ messages: [Message]) {
        guard !messages.isEmpty else { return }
        var changed = false
        for message in messages {
            let id = "in:\(message.messageId)"
            guard !notes.contains(where: { $0.id == id }) else { continue }
            let created = Date(timeIntervalSince1970: Double(message.sentAtMs) / 1000)
            // A host clock that is wrong must not create a note that is
            // already expired (or one that outlives the TTL): the shelf
            // clock is ours.
            notes.append(Note(id: id,
                              text: message.text,
                              peerName: message.senderName,
                              direction: .incoming,
                              createdAt: min(created, Date()),
                              expiresAt: Date().addingTimeInterval(Self.ttl)))
            changed = true
        }
        if changed { commit() }
    }

    /// Record text this phone sent. Called only after the host accepted it.
    func addOutgoing(_ text: String, peerName: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        notes.append(Note(id: "out:\(UUID().uuidString)",
                          text: text,
                          peerName: peerName,
                          direction: .outgoing,
                          createdAt: Date(),
                          expiresAt: Date().addingTimeInterval(Self.ttl)))
        commit()
    }

    func remove(id: String) {
        notes.removeAll { $0.id == id }
        commit()
    }

    func clearAll() {
        notes.removeAll()
        commit()
    }

    /// Drop everything past its expiry. Safe to call at any time.
    func prune() {
        let before = notes.count
        notes.removeAll { $0.isExpired }
        if notes.count != before { commit() }
    }

    // MARK: Persistence

    private func commit() {
        notes.removeAll { $0.isExpired }
        if notes.count > Self.capacity {
            notes.sort { $0.createdAt < $1.createdAt }
            notes.removeFirst(notes.count - Self.capacity)
        }
        save()
    }

    /// Read the shelf off disk. MERGES rather than replaces: `init` schedules
    /// this on a later main-actor turn, and the poll loop can land a message
    /// first — a straight assignment would silently throw that note away.
    private func load() {
        guard let data = try? Data(contentsOf: fileURL) else { return }
        guard let decoded = try? JSONDecoder().decode([Note].self, from: data) else {
            // A corrupt shelf is not worth a recovery path: it is a 24-hour
            // cache. Start clean and overwrite it on the next write.
            try? FileManager.default.removeItem(at: fileURL)
            return
        }
        let known = Set(notes.map(\.id))
        let restored = decoded.filter { !$0.isExpired && !known.contains($0.id) }
        guard !restored.isEmpty else { return }
        notes.append(contentsOf: restored)
        commit()
    }

    private func save() {
        if notes.isEmpty {
            try? FileManager.default.removeItem(at: fileURL)
            return
        }
        guard let data = try? JSONEncoder().encode(notes) else { return }
        try? data.write(to: fileURL, options: [.atomic, .completeFileProtection])
    }
}
