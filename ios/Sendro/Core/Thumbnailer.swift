//
//  Thumbnailer.swift
//  Sendro
//
//  Lazy, memory-safe thumbnails for received files, plus the file-kind
//  classification the preview screen routes on.
//
//  RULES (they are the whole point of this file):
//  - A full-size image is NEVER decoded. Stills go through ImageIO's
//    CGImageSourceCreateThumbnailAtIndex with kCGImageSourceThumbnailMaxPixelSize,
//    which decodes straight to the requested pixel size.
//  - Video frames come from AVAssetImageGenerator with a maximumSize cap.
//  - Nothing runs on the main thread: every entry point is async and does its
//    work on a detached utility task.
//  - Results are cached in an NSCache keyed by path + modification time + size
//    + requested pixel size, so a rewritten file never serves a stale bitmap
//    and memory pressure evicts automatically.
//  - Photos-resident media (imported, temp deleted) is fetched through
//    PHImageManager, but ONLY when read authorization already exists — this
//    file never triggers a permission prompt. The preview screen asks.
//

import Foundation
import UIKit
import ImageIO
import AVFoundation
import Photos
import UniformTypeIdentifiers

// MARK: - Kinds

/// How a received file should be previewed.
enum PreviewKind: Equatable {
    case image
    case video
    case other
}

enum PreviewMedia {

    /// Extensions we hand to the in-app image viewer. Deliberately explicit —
    /// UTType lookups can miss (webp on older systems, camera raws), and a
    /// wrong guess here means a blank viewer instead of QuickLook.
    static let imageExtensions: Set<String> = [
        "jpg", "jpeg", "png", "heic", "heif", "tiff", "tif",
        "dng", "gif", "webp", "bmp"
    ]

    /// Extensions we hand to AVKit's VideoPlayer.
    static let videoExtensions: Set<String> = ["mov", "mp4", "m4v"]

    static func kind(forFileName fileName: String) -> PreviewKind {
        let ext = (fileName as NSString).pathExtension.lowercased()
        if imageExtensions.contains(ext) { return .image }
        if videoExtensions.contains(ext) { return .video }
        // Secondary guess for anything not in the tables above.
        if let type = UTType(filenameExtension: ext) {
            if type.conforms(to: .movie) { return .video }
            if type.conforms(to: .image) { return .image }
        }
        return .other
    }
}

// MARK: - Cache

/// Shared thumbnail cache. Thread-safe: NSCache is, and this type holds no
/// other mutable state.
final class Thumbnailer {

    static let shared = Thumbnailer()

    /// Row thumbnails; ~200px is enough for a 44pt badge at 3× (132px).
    static let rowMaxPixel: CGFloat = 200

    private let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 240
        // ~24 MB of decoded thumbnails at most; NSCache evicts under pressure
        // anyway, this just keeps the ceiling sane.
        cache.totalCostLimit = 24 * 1024 * 1024
        return cache
    }()

    private init() {}

    // MARK: Local files

    /// Thumbnail for a file in our own store. Returns nil when the file is
    /// gone or has no renderable representation.
    func thumbnail(for url: URL, maxPixel: CGFloat = Thumbnailer.rowMaxPixel) async -> UIImage? {
        guard let key = Self.cacheKey(for: url, maxPixel: maxPixel) else { return nil }
        if let cached = cache.object(forKey: key as NSString) { return cached }

        let kind = PreviewMedia.kind(forFileName: url.lastPathComponent)
        let image: UIImage?
        switch kind {
        case .image:
            image = await Self.detachedDownsample(url: url, maxPixel: maxPixel)
        case .video:
            image = await Self.videoFrame(url: url, maxPixel: maxPixel)
        case .other:
            image = nil
        }
        if let image {
            cache.setObject(image, forKey: key as NSString, cost: Self.cost(of: image))
        }
        return image
    }

    /// Larger, still-downsampled decode for the full-screen viewer. Capped at
    /// ~3000px on the long edge, which covers every iPhone screen at 3× with
    /// room to pinch-zoom into, without ever holding a 48-megapixel bitmap.
    func viewerImage(for url: URL, maxPixel: CGFloat = 3000) async -> UIImage? {
        await thumbnail(for: url, maxPixel: maxPixel)
    }

    // MARK: Photos-resident media

    /// True when we may talk to PhotoKit without triggering a prompt.
    static var photosReadable: Bool {
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        return status == .authorized || status == .limited
    }

    /// Ask for read access. Returns true when granted (or already granted).
    /// Called only from an explicit user tap — never on a list render.
    static func requestPhotosRead() async -> Bool {
        if photosReadable { return true }
        let status = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        return status == .authorized || status == .limited
    }

    /// Image for an asset we imported earlier. `allowPrompt` is false on the
    /// list path (silently returns nil when access is off) and true when the
    /// user tapped preview.
    func photosImage(localIdentifier: String,
                     maxPixel: CGFloat,
                     allowPrompt: Bool) async -> UIImage? {
        let key = "phasset:\(localIdentifier):\(Int(maxPixel))" as NSString
        if let cached = cache.object(forKey: key) { return cached }

        if allowPrompt {
            guard await Self.requestPhotosRead() else { return nil }
        } else {
            guard Self.photosReadable else { return nil }
        }

        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier],
                                              options: nil).firstObject else { return nil }
        let target = CGSize(width: maxPixel, height: maxPixel)
        let image = await Self.requestImage(for: asset, targetSize: target)
        if let image {
            cache.setObject(image, forKey: key, cost: Self.cost(of: image))
        }
        return image
    }

    /// One-shot PHImageManager request. `.highQualityFormat` delivers exactly
    /// one callback, but the continuation is guarded anyway — resuming a
    /// continuation twice is a crash, and no thumbnail is worth that.
    private static func requestImage(for asset: PHAsset, targetSize: CGSize) async -> UIImage? {
        let options = PHImageRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.resizeMode = .fast
        options.isNetworkAccessAllowed = true      // iCloud-optimized originals
        options.isSynchronous = false

        return await withCheckedContinuation { (continuation: CheckedContinuation<UIImage?, Never>) in
            let box = ResumeOnce()
            PHImageManager.default().requestImage(for: asset,
                                                  targetSize: targetSize,
                                                  contentMode: .aspectFit,
                                                  options: options) { image, _ in
                if box.claim() {
                    continuation.resume(returning: image)
                }
            }
        }
    }

    /// Playable AVAsset for a video already in the photo library (used when
    /// the local copy is gone). nil when it can't be played locally.
    static func playableAsset(localIdentifier: String) async -> AVAsset? {
        guard await requestPhotosRead() else { return nil }
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier],
                                              options: nil).firstObject else { return nil }
        let options = PHVideoRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = true
        return await withCheckedContinuation { (continuation: CheckedContinuation<AVAsset?, Never>) in
            let box = ResumeOnce()
            PHImageManager.default().requestAVAsset(forVideo: asset,
                                                    options: options) { avAsset, _, _ in
                if box.claim() {
                    continuation.resume(returning: avAsset)
                }
            }
        }
    }

    // MARK: Decoding

    private static func detachedDownsample(url: URL, maxPixel: CGFloat) async -> UIImage? {
        await Task.detached(priority: .utility) {
            Thumbnailer.downsample(url: url, maxPixel: maxPixel)
        }.value
    }

    /// ImageIO downsample — decodes directly at the requested size, so a 100 MP
    /// TIFF costs the same as a small JPEG.
    static func downsample(url: URL, maxPixel: CGFloat) -> UIImage? {
        let sourceOptions: [CFString: Any] = [kCGImageSourceShouldCache: false]
        guard let source = CGImageSourceCreateWithURL(url as CFURL,
                                                      sourceOptions as CFDictionary) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(32, maxPixel)
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0,
                                                                options as CFDictionary) else { return nil }
        return UIImage(cgImage: cgImage)
    }

    private static func videoFrame(url: URL, maxPixel: CGFloat) async -> UIImage? {
        let asset = AVURLAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: maxPixel, height: maxPixel)
        generator.requestedTimeToleranceBefore = CMTime(seconds: 1, preferredTimescale: 600)
        generator.requestedTimeToleranceAfter = CMTime(seconds: 1, preferredTimescale: 600)
        let time = CMTime(seconds: 0.2, preferredTimescale: 600)
        do {
            // iOS 16+ async variant; the older copyCGImage(at:actualTime:) is
            // deprecated and blocks the caller.
            let result = try await generator.image(at: time)
            return UIImage(cgImage: result.image)
        } catch {
            return nil
        }
    }

    // MARK: Keys

    /// path + mtime + size + requested size. A file rewritten in place gets a
    /// new key, so a stale bitmap can never be served.
    private static func cacheKey(for url: URL, maxPixel: CGFloat) -> String? {
        let values = try? url.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey])
        guard let values, values.fileSize != nil else { return nil }
        let stamp = Int((values.contentModificationDate ?? .distantPast).timeIntervalSince1970)
        return "\(url.path)|\(stamp)|\(values.fileSize ?? 0)|\(Int(maxPixel))"
    }

    private static func cost(of image: UIImage) -> Int {
        guard let cgImage = image.cgImage else { return 1 }
        return cgImage.bytesPerRow * cgImage.height
    }
}

/// Tiny lock box so a multi-callback PhotoKit handler can only resume its
/// continuation once.
private final class ResumeOnce {
    private let lock = NSLock()
    private var used = false

    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if used { return false }
        used = true
        return true
    }
}
