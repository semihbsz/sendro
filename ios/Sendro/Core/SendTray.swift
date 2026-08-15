//
//  SendTray.swift
//  Sendro
//
//  Intake for files handed to Sendro by iOS itself — the share sheet's
//  "Copy to Sendro" / Open-In list (CFBundleDocumentTypes in Info.plist) and
//  "Open with Sendro" from the Files app.
//
//  WHY NOT A SHARE EXTENSION: a real Share Extension has to hand its files to
//  the app through an App Group container, and App Groups are NOT available to
//  free personal-team signing — adding one would break sideloading for anyone
//  without a paid developer account. Declaring document types gets Sendro into
//  the same share sheet ("Copy to Sendro") with zero entitlements. See
//  ios/README.md.
//
//  The files land in Documents/Inbox/ (our container, iOS owns the copy) or
//  arrive as security-scoped URLs for open-in-place. Either way they are moved
//  or copied into a SendStaging batch directory immediately, so the queue owns
//  stable bytes and nothing depends on a short-lived system URL.
//
//  Files are NEVER auto-sent: they sit in this tray until the user taps Send.
//

import Foundation
import Combine

@MainActor
final class SendTray: ObservableObject {

    struct Item: Identifiable, Equatable {
        let id: String
        let url: URL
        let name: String
        let sizeBytes: Int64
    }

    @Published private(set) var items: [Item] = []

    nonisolated init() {}

    var isEmpty: Bool { items.isEmpty }

    var totalBytes: Int64 { items.reduce(0) { $0 + $1.sizeBytes } }

    // MARK: Intake

    /// Take one incoming file URL. Returns true when it was staged.
    /// Repeated calls accumulate — iOS delivers a multi-file "Copy to Sendro"
    /// as several openURL calls in a row, and every one of them must queue.
    @discardableResult
    func accept(fileURL url: URL) -> Bool {
        guard url.isFileURL else { return false }
        guard let staged = Self.stage(url) else { return false }
        let size = (try? FileManager.default.attributesOfItem(atPath: staged.path)[.size] as? NSNumber)?
            .int64Value ?? 0
        items.append(Item(id: UUID().uuidString,
                          url: staged,
                          name: staged.lastPathComponent,
                          sizeBytes: size))
        return true
    }

    /// Pick up anything left in Documents/Inbox from a previous session (an
    /// open that arrived while the app was being killed, or a file we never
    /// got to consume). Cheap: the directory is normally empty.
    func drainInbox() {
        let fm = FileManager.default
        let inbox = AppPaths.documentsInbox
        guard let urls = try? fm.contentsOfDirectory(at: inbox,
                                                     includingPropertiesForKeys: [.isRegularFileKey],
                                                     options: [.skipsHiddenFiles]) else { return }
        for url in urls {
            let values = try? url.resourceValues(forKeys: [.isRegularFileKey])
            guard values?.isRegularFile == true else { continue }
            accept(fileURL: url)
        }
    }

    // MARK: Queue handoff

    /// Hand every staged URL to the caller (UploadEngine) and empty the tray.
    /// Ownership of the files transfers with them — UploadEngine deletes each
    /// staged file when its item finishes.
    func takeAll() -> [URL] {
        let urls = items.map { $0.url }
        items.removeAll()
        return urls
    }

    func remove(id: String) {
        guard let idx = items.firstIndex(where: { $0.id == id }) else { return }
        SendStaging.remove(items[idx].url)
        items.remove(at: idx)
    }

    func clear() {
        for item in items {
            SendStaging.remove(item.url)
        }
        items.removeAll()
    }

    // MARK: Staging

    /// Move (Inbox — the copy is ours) or copy (open-in-place — the user's
    /// original must survive) the file into a fresh staging batch directory.
    private static func stage(_ url: URL) -> URL? {
        let fm = FileManager.default
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        guard fm.fileExists(atPath: url.path) else { return nil }

        let batch = SendStaging.newBatchDirectory()
        let name = FileStore.sanitize(fileName: url.lastPathComponent)
        let destination = FileStore.availableURL(in: batch, name: name)

        let inbox = AppPaths.documentsInbox.standardizedFileURL.path
        let isOurInboxCopy = url.standardizedFileURL.path.hasPrefix(inbox + "/")

        if isOurInboxCopy {
            do {
                try fm.moveItem(at: url, to: destination)
                return destination
            } catch {
                // Fall through to the copy path below.
            }
        }
        do {
            try fm.copyItem(at: url, to: destination)
            if isOurInboxCopy {
                try? fm.removeItem(at: url)     // don't leave Inbox growing
            }
            return destination
        } catch {
            try? fm.removeItem(at: batch)
            return nil
        }
    }
}
