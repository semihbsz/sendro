//
//  FileStore.swift
//  Sendro
//
//  Received-files store. Final non-media (and kept-media) files live in
//  Documents/Sendro/ which is visible in the Files app thanks to
//  UIFileSharingEnabled + LSSupportsOpeningDocumentsInPlace.
//  Also owns the shared app-support paths (incoming temp dir, state files).
//

import Foundation
import Combine

// MARK: - Paths

enum AppPaths {

    /// Application Support/Sendro (created on demand).
    static var appSupport: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                            in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("Sendro", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Application Support/Sendro/incoming — .part files while downloading.
    static var incoming: URL {
        let dir = appSupport.appendingPathComponent("incoming", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Documents/Sendro — user-visible received files.
    static var documentsSendro: URL {
        let base = FileManager.default.urls(for: .documentDirectory,
                                            in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("Sendro", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func partFileURL(transferId: String) -> URL {
        incoming.appendingPathComponent("\(transferId).part", isDirectory: false)
    }

    static var inflightStateURL: URL {
        appSupport.appendingPathComponent("inflight.json", isDirectory: false)
    }

    static var historyURL: URL {
        appSupport.appendingPathComponent("history.json", isDirectory: false)
    }
}

// MARK: - Model

struct ReceivedFile: Identifiable, Equatable {
    let url: URL
    let name: String
    let sizeBytes: Int64
    let modified: Date

    var id: String { url.path }
}

// MARK: - Store

final class FileStore: ObservableObject {

    @Published private(set) var files: [ReceivedFile] = []

    var directory: URL { AppPaths.documentsSendro }

    init() {
        refresh()
    }

    func refresh() {
        let fm = FileManager.default
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey, .isRegularFileKey]
        let urls = (try? fm.contentsOfDirectory(at: directory,
                                                includingPropertiesForKeys: keys,
                                                options: [.skipsHiddenFiles])) ?? []
        var result: [ReceivedFile] = []
        for url in urls {
            guard let values = try? url.resourceValues(forKeys: Set(keys)),
                  values.isRegularFile == true else { continue }
            result.append(ReceivedFile(url: url,
                                       name: url.lastPathComponent,
                                       sizeBytes: Int64(values.fileSize ?? 0),
                                       modified: values.contentModificationDate ?? Date()))
        }
        files = result.sorted { $0.modified > $1.modified }
    }

    /// Move a verified temp file into Documents/Sendro under its (sanitized,
    /// collision-safe) original name. Returns the final URL.
    @discardableResult
    func moveIn(from source: URL, preferredName: String) throws -> URL {
        let name = FileStore.sanitize(fileName: preferredName)
        let destination = FileStore.availableURL(in: directory, name: name)
        try FileManager.default.moveItem(at: source, to: destination)
        refresh()
        return destination
    }

    /// Copy variant (used when a copy must remain at the source).
    @discardableResult
    func copyIn(from source: URL, preferredName: String) throws -> URL {
        let name = FileStore.sanitize(fileName: preferredName)
        let destination = FileStore.availableURL(in: directory, name: name)
        try FileManager.default.copyItem(at: source, to: destination)
        refresh()
        return destination
    }

    func delete(_ file: ReceivedFile) {
        try? FileManager.default.removeItem(at: file.url)
        refresh()
    }

    // MARK: Naming (PROTOCOL.md §8)

    /// Strip path separators / reserved characters, preserve everything else
    /// (case, spaces, full Unicode).
    static func sanitize(fileName: String) -> String {
        var s = fileName
        s = s.replacingOccurrences(of: "/", with: "_")
        s = s.replacingOccurrences(of: "\\", with: "_")
        s = s.replacingOccurrences(of: ":", with: "_")
        s = s.replacingOccurrences(of: "\0", with: "")
        s = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty || s == "." || s == ".." { s = "file" }
        return s
    }

    /// Duplicate names get " (n)" before the extension.
    static func availableURL(in directory: URL, name: String) -> URL {
        let fm = FileManager.default
        var candidate = directory.appendingPathComponent(name, isDirectory: false)
        guard fm.fileExists(atPath: candidate.path) else { return candidate }
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var n = 2
        while true {
            let numbered = ext.isEmpty ? "\(base) (\(n))" : "\(base) (\(n)).\(ext)"
            candidate = directory.appendingPathComponent(numbered, isDirectory: false)
            if !fm.fileExists(atPath: candidate.path) { return candidate }
            n += 1
        }
    }
}
