//
//  MediaImporter.swift
//  Sendro
//
//  PhotoKit import of received photos/videos, optionally filed into a
//  "Sendro" album.
//
//  Authorization notes:
//  - Plain save needs only .addOnly authorization.
//  - Fetching/creating the "Sendro" album requires .readWrite; if the user
//    grants only add access we still save, just without the album.
//

import Foundation
import Photos
import UniformTypeIdentifiers

enum MediaKind {
    case photo
    case video

    var resourceType: PHAssetResourceType {
        switch self {
        case .photo: return .photo
        case .video: return .video
        }
    }
}

enum MediaImportError: Error, LocalizedError {
    case notAuthorized
    case albumCreateFailed

    var errorDescription: String? {
        switch self {
        case .notAuthorized:    return "Photos access was not granted."
        case .albumCreateFailed: return "Could not create the Sendro album."
        }
    }
}

enum MediaImporter {

    static let albumName = "Sendro"

    /// nil = not an importable media type (keep it in Files instead).
    static func mediaKind(forFileName fileName: String) -> MediaKind? {
        let ext = (fileName as NSString).pathExtension.lowercased()
        guard !ext.isEmpty else { return nil }
        if let type = UTType(filenameExtension: ext) {
            if type.conforms(to: .movie) { return .video }
            if type.conforms(to: .image) { return .photo }
        }
        // Fallback table: UTType lookup can miss, or return a dynamic type
        // conforming to neither, for perfectly importable extensions.
        switch ext {
        case "jpg", "jpeg", "png", "heic", "heif", "tiff", "tif", "dng", "gif", "webp", "bmp":
            return .photo
        case "mov", "mp4", "m4v":
            return .video
        default:
            return nil
        }
    }

    /// Import a verified file into the photo library. Original bytes only —
    /// no re-encode, no transformation.
    /// - Parameter originalFilename: the real (offer) file name; the staged
    ///   on-disk name may carry a transferId prefix, so pass this explicitly.
    /// - Parameter moveFile: when true PhotoKit takes ownership of the file
    ///   (it is moved, not copied) — maps to "Delete temp after import".
    /// - Returns: the created asset's `localIdentifier`, when PhotoKit gave us
    ///   a placeholder. Stored in history so the in-app preview can pull the
    ///   image back out of Photos once the local temp is gone. nil is not an
    ///   error — the import still succeeded.
    @discardableResult
    static func importToPhotos(fileURL: URL,
                               kind: MediaKind,
                               originalFilename: String,
                               moveFile: Bool,
                               addToAlbum: Bool) async throws -> String? {
        // Add-only access is requested up front — the very first import
        // triggers the system prompt.
        let addStatus = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard addStatus == .authorized || addStatus == .limited else {
            throw MediaImportError.notAuthorized
        }

        var album: PHAssetCollection?
        if addToAlbum {
            // Album filing needs .readWrite. If that's not granted we STILL
            // import the asset — just without the album; album auth must
            // never fail the whole import.
            let rwStatus = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
            if rwStatus == .authorized {
                album = try? await fetchOrCreateAlbum(named: albumName)
            }
        }

        let options = PHAssetResourceCreationOptions()
        options.shouldMoveFile = moveFile
        options.originalFilename = originalFilename
        // Name the concrete type when the system knows the extension, so
        // PhotoKit never has to guess from the (possibly prefixed) URL.
        let ext = (originalFilename as NSString).pathExtension.lowercased()
        if !ext.isEmpty, let type = UTType(filenameExtension: ext) {
            options.uniformTypeIdentifier = type.identifier
        }

        var createdIdentifier: String?
        let library = PHPhotoLibrary.shared()
        try await library.performChanges {
            let creation = PHAssetCreationRequest.forAsset()
            creation.addResource(with: kind.resourceType, fileURL: fileURL, options: options)
            if let placeholder = creation.placeholderForCreatedAsset {
                createdIdentifier = placeholder.localIdentifier
                if let album, let albumRequest = PHAssetCollectionChangeRequest(for: album) {
                    albumRequest.addAssets([placeholder] as NSArray)
                }
            }
        }
        return createdIdentifier
    }

    private static func fetchOrCreateAlbum(named name: String) async throws -> PHAssetCollection {
        let fetchOptions = PHFetchOptions()
        fetchOptions.predicate = NSPredicate(format: "title = %@", name)
        if let existing = PHAssetCollection.fetchAssetCollections(with: .album,
                                                                  subtype: .albumRegular,
                                                                  options: fetchOptions).firstObject {
            return existing
        }

        var placeholderIdentifier = ""
        try await PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: name)
            placeholderIdentifier = request.placeholderForCreatedAssetCollection.localIdentifier
        }
        guard let created = PHAssetCollection.fetchAssetCollections(
            withLocalIdentifiers: [placeholderIdentifier], options: nil
        ).firstObject else {
            throw MediaImportError.albumCreateFailed
        }
        return created
    }
}
