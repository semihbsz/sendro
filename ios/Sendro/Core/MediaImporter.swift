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
        let ext = (fileName as NSString).pathExtension
        guard !ext.isEmpty, let type = UTType(filenameExtension: ext.lowercased()) else {
            return nil
        }
        if type.conforms(to: .movie) { return .video }
        if type.conforms(to: .image) { return .photo }
        return nil
    }

    /// Import a verified file into the photo library.
    /// - Parameter moveFile: when true PhotoKit takes ownership of the file
    ///   (it is moved, not copied) — maps to "Delete temp after import".
    static func importToPhotos(fileURL: URL,
                               kind: MediaKind,
                               moveFile: Bool,
                               addToAlbum: Bool) async throws {
        let addStatus = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard addStatus == .authorized || addStatus == .limited else {
            throw MediaImportError.notAuthorized
        }

        var album: PHAssetCollection?
        if addToAlbum {
            let rwStatus = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
            if rwStatus == .authorized {
                album = try? await fetchOrCreateAlbum(named: albumName)
            }
        }

        let library = PHPhotoLibrary.shared()
        try await library.performChanges {
            let creation = PHAssetCreationRequest.forAsset()
            let options = PHAssetResourceCreationOptions()
            options.shouldMoveFile = moveFile
            options.originalFilename = fileURL.lastPathComponent
            creation.addResource(with: kind.resourceType, fileURL: fileURL, options: options)
            if let album,
               let placeholder = creation.placeholderForCreatedAsset,
               let albumRequest = PHAssetCollectionChangeRequest(for: album) {
                albumRequest.addAssets([placeholder] as NSArray)
            }
        }
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
