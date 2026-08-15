//
//  HistoryStore.swift
//  Sendro
//
//  Local JSON transfer history, newest first, capped at 500 entries.
//

import Foundation
import Combine

struct HistoryEntry: Codable, Identifiable {
    let id: String              // unique per entry (UUID)
    let transferId: String
    let fileName: String
    let sizeBytes: Int64
    let senderName: String
    let dateMs: Int64
    /// completed | failed | rejected | cancelled
    let outcome: String
    let savedTo: String?        // photos | files | temp
    let errorMessage: String?
    /// nil (legacy / incoming) | "outgoing" (iPhone → PC upload, §7).
    /// Optional so entries persisted before this field existed still decode.
    let direction: String?
    /// Where the bytes ended up on this iPhone, when they are still here:
    /// the file name inside Documents/Sendro (NOT an absolute path — the app
    /// container path changes on every reinstall, so storing one would rot).
    /// nil for media that was imported into Photos and deleted locally.
    let localName: String?
    /// PHAsset localIdentifier of the imported asset, when it went to Photos.
    /// Lets the preview fetch a thumbnail / full image straight from the
    /// library instead of showing a broken preview for a deleted temp file.
    let photoAssetId: String?
}

final class HistoryStore: ObservableObject {

    static let maxEntries = 500

    @Published private(set) var entries: [HistoryEntry] = []

    init() {
        load()
    }

    func add(transferId: String,
             fileName: String,
             sizeBytes: Int64,
             senderName: String,
             outcome: String,
             savedTo: String? = nil,
             errorMessage: String? = nil,
             direction: String? = nil,
             localName: String? = nil,
             photoAssetId: String? = nil) {
        let entry = HistoryEntry(id: UUID().uuidString,
                                 transferId: transferId,
                                 fileName: fileName,
                                 sizeBytes: sizeBytes,
                                 senderName: senderName,
                                 dateMs: Int64(Date().timeIntervalSince1970 * 1000),
                                 outcome: outcome,
                                 savedTo: savedTo,
                                 errorMessage: errorMessage,
                                 direction: direction,
                                 localName: localName,
                                 photoAssetId: photoAssetId)
        entries.insert(entry, at: 0)
        if entries.count > Self.maxEntries {
            entries = Array(entries.prefix(Self.maxEntries))
        }
        save()
    }

    func clear() {
        entries = []
        save()
    }

    private func load() {
        guard let data = try? Data(contentsOf: AppPaths.historyURL) else { return }
        if let decoded = try? JSONDecoder().decode([HistoryEntry].self, from: data) {
            entries = decoded
        }
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(entries) else { return }
        try? data.write(to: AppPaths.historyURL, options: .atomic)
    }
}
