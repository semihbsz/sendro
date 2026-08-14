//
//  SendPickers.swift
//  Sendro
//
//  System pickers for the iPhone → PC send flow, wrapped for SwiftUI.
//
//  Byte preservation is the whole point of the product, so:
//  - Photos: PHPickerViewController (out-of-process, NO photo-library
//    permission needed) with preferredAssetRepresentationMode = .current and
//    loadFileRepresentation — the original container (HEIC / MOV / DNG …)
//    is copied as-is, never transcoded. loadDataRepresentation is a last
//    resort (still byte-exact but memory-bound), capped at 100 MB.
//  - Files: UIDocumentPickerViewController(forOpeningContentTypes:asCopy:)
//    — the system hands us temp copies of the original bytes.
//
//  Both copy results into a SendStaging batch directory; UploadEngine owns
//  deletion from there.
//

import SwiftUI
import UIKit
import PhotosUI
import UniformTypeIdentifiers

/// What a picker session produced: staged file URLs + per-item failures.
struct PickedFiles {
    var urls: [URL] = []
    var failures: [String] = []
}

// MARK: - Photos (PHPicker)

struct PhotoPicker: UIViewControllerRepresentable {

    /// Called on the main queue once every selected item is staged (or failed).
    let onComplete: (PickedFiles) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration()
        configuration.selectionLimit = 0                            // multi-select
        configuration.preferredAssetRepresentationMode = .current   // original bytes
        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onComplete: onComplete)
    }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {

        /// Last-resort data-representation cap — beyond this we fail the item
        /// instead of ballooning memory. (There is no way to ask an
        /// NSItemProvider for its size up front; in practice photo/video
        /// assets always offer a file representation, so this path is rare.)
        static let dataFallbackCap = 100 * 1024 * 1024

        let onComplete: (PickedFiles) -> Void

        init(onComplete: @escaping (PickedFiles) -> Void) {
            self.onComplete = onComplete
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            let providers = results.map { $0.itemProvider }
            guard !providers.isEmpty else {
                onComplete(PickedFiles())
                return
            }
            let batchDir = SendStaging.newBatchDirectory()
            let group = DispatchGroup()
            let lock = NSLock()
            var picked = PickedFiles()

            for provider in providers {
                group.enter()
                Coordinator.stageOriginal(provider, into: batchDir) { url, failure in
                    // Provider callbacks arrive on arbitrary background queues.
                    lock.lock()
                    if let url { picked.urls.append(url) }
                    if let failure { picked.failures.append(failure) }
                    lock.unlock()
                    group.leave()
                }
            }
            group.notify(queue: .main) { [onComplete] in
                onComplete(picked)
            }
        }

        /// Copy one provider's original bytes into `directory`.
        /// Exactly one of (url, failure) is non-nil in the completion.
        static func stageOriginal(_ provider: NSItemProvider,
                                  into directory: URL,
                                  completion: @escaping (URL?, String?) -> Void) {
            let displayName = provider.suggestedName ?? "photo"
            guard let typeId = provider.registeredTypeIdentifiers.first else {
                completion(nil, "\(displayName): unreadable item.")
                return
            }
            _ = provider.loadFileRepresentation(forTypeIdentifier: typeId) { tempURL, _ in
                if let tempURL {
                    // The URL is only valid until this closure returns —
                    // copy synchronously, preserving the real filename.
                    let name = Coordinator.fileName(suggested: provider.suggestedName,
                                                    typeId: typeId,
                                                    fallback: tempURL.lastPathComponent)
                    let destination = FileStore.availableURL(in: directory,
                                                             name: FileStore.sanitize(fileName: name))
                    do {
                        try FileManager.default.copyItem(at: tempURL, to: destination)
                        completion(destination, nil)
                    } catch {
                        completion(nil, "\(name): copy failed — \(error.localizedDescription)")
                    }
                    return
                }
                // No file representation — fall back to a data representation.
                // Still byte-exact, but the whole asset lands in memory, so
                // refuse anything over the cap rather than ballooning.
                _ = provider.loadDataRepresentation(forTypeIdentifier: typeId) { data, _ in
                    guard let data else {
                        completion(nil, "\(displayName): could not read the original bytes.")
                        return
                    }
                    guard data.count <= Coordinator.dataFallbackCap else {
                        completion(nil, "\(displayName): too large to send without a file representation (>100 MB).")
                        return
                    }
                    let name = Coordinator.fileName(suggested: provider.suggestedName,
                                                    typeId: typeId,
                                                    fallback: displayName)
                    let destination = FileStore.availableURL(in: directory,
                                                             name: FileStore.sanitize(fileName: name))
                    do {
                        try data.write(to: destination, options: .atomic)
                        completion(destination, nil)
                    } catch {
                        completion(nil, "\(name): could not stage — \(error.localizedDescription)")
                    }
                }
            }
        }

        /// Best real-world filename: the provider's suggestion, given the
        /// type's preferred extension when the suggestion lacks one.
        static func fileName(suggested: String?, typeId: String, fallback: String) -> String {
            let ext = UTType(typeId)?.preferredFilenameExtension ?? ""
            if let suggested, !suggested.isEmpty {
                let hasExt = !(suggested as NSString).pathExtension.isEmpty
                if hasExt || ext.isEmpty { return suggested }
                return "\(suggested).\(ext)"
            }
            let hasExt = !(fallback as NSString).pathExtension.isEmpty
            if hasExt || ext.isEmpty { return fallback }
            return "\(fallback).\(ext)"
        }
    }
}

// MARK: - Files (document picker)

struct DocumentPicker: UIViewControllerRepresentable {

    /// Called on the main queue with staged copies.
    let onComplete: (PickedFiles) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        picker.allowsMultipleSelection = true
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onComplete: onComplete)
    }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {

        let onComplete: (PickedFiles) -> Void

        init(onComplete: @escaping (PickedFiles) -> Void) {
            self.onComplete = onComplete
        }

        func documentPicker(_ controller: UIDocumentPickerViewController,
                            didPickDocumentsAt urls: [URL]) {
            // asCopy: true → these are already our own temp copies; move them
            // into a SendStaging batch dir for the unified cleanup lifecycle.
            let batchDir = SendStaging.newBatchDirectory()
            var picked = PickedFiles()
            let fm = FileManager.default
            for url in urls {
                let destination = FileStore.availableURL(in: batchDir,
                                                         name: FileStore.sanitize(fileName: url.lastPathComponent))
                do {
                    try fm.moveItem(at: url, to: destination)
                    picked.urls.append(destination)
                } catch {
                    do {
                        try fm.copyItem(at: url, to: destination)
                        picked.urls.append(destination)
                    } catch {
                        picked.failures.append("\(url.lastPathComponent): \(error.localizedDescription)")
                    }
                }
            }
            onComplete(picked)
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            onComplete(PickedFiles())
        }
    }
}
