//
//  MessageCenter.swift
//  Sendro
//
//  The in-RAM inbox for PROTOCOL.md §11 text messages.
//
//  EPHEMERALITY CONTRACT — read before touching this file:
//  - Messages live in this array and nowhere else. No UserDefaults, no
//    Keychain, no file, no HistoryStore entry, no logging.
//  - `Message` is Decodable-only by design, so there is no way to serialize
//    one even by accident.
//  - `dismiss(id:)` drops the last reference; the memory goes with it.
//  - Quitting the app takes the whole inbox with it (no restore path).
//

import Foundation
import Combine

@MainActor
final class MessageCenter: ObservableObject {

    /// Oldest first; the UI shows the newest on top of the stack.
    @Published private(set) var inbox: [Message] = []

    /// §11: "A device's in-memory inbox holds at most 20 undelivered
    /// messages; pushing past that drops the oldest."
    static let capacity = 20

    nonisolated init() {}

    var hasMessages: Bool { !inbox.isEmpty }

    /// Ingest a batch straight off the outbox long poll. Deduped by
    /// messageId (delivery is at-most-once, but a resend must never
    /// double-stack a card).
    func receive(_ messages: [Message]) {
        guard !messages.isEmpty else { return }
        var changed = false
        for message in messages {
            guard !inbox.contains(where: { $0.messageId == message.messageId }) else { continue }
            inbox.append(message)
            changed = true
        }
        guard changed else { return }
        if inbox.count > Self.capacity {
            inbox.removeFirst(inbox.count - Self.capacity)
        }
    }

    /// Permanently forget one message.
    func dismiss(id: String) {
        inbox.removeAll { $0.messageId == id }
    }

    /// Permanently forget everything.
    func clear() {
        inbox.removeAll()
    }
}
