//
//  FilePreview.swift
//  Sendro
//
//  In-app preview of anything Sendro received, so the user never has to leave
//  for Photos or Files just to see what landed.
//
//  Routing:
//    image  -> full-screen viewer (pinch, double-tap, swipe-to-dismiss)
//    video  -> AVKit VideoPlayer with normal transport controls
//    other  -> QLPreviewController; if QuickLook can't render it, a clean
//              "No preview available" card with Share / Save to Files
//
//  The hard case: media imported into Photos with "Delete Temp After Import"
//  ON no longer exists locally. Those rows must NOT show a broken preview —
//  they either come out of the photo library via PHAsset (asking for read
//  access at the moment of the tap, never before) or say plainly that the
//  file lives in Photos and offer to open it there.
//

import SwiftUI
import UIKit
import AVKit
import QuickLook
import Photos

// MARK: - What to preview

struct PreviewRequest: Identifiable, Equatable {

    enum Source: Equatable {
        /// Bytes are on this iPhone, at this URL.
        case file(URL)
        /// Bytes live in the photo library under this localIdentifier.
        case photosAsset(String)
        /// Bytes are not reachable from here. `wentToPhotos` distinguishes
        /// "it's in your gallery" from "it isn't on this iPhone any more".
        case gone(wentToPhotos: Bool)
    }

    let id: String
    let fileName: String
    let sizeBytes: Int64
    let date: Date
    let source: Source

    var kind: PreviewKind { PreviewMedia.kind(forFileName: fileName) }

    var localURL: URL? {
        if case .file(let url) = source { return url }
        return nil
    }
}

/// Turns a Library row into something previewable.
enum PreviewResolver {

    static func request(for file: ReceivedFile) -> PreviewRequest {
        PreviewRequest(id: file.id,
                       fileName: file.name,
                       sizeBytes: file.sizeBytes,
                       date: file.modified,
                       source: .file(file.url))
    }

    /// History rows carry provenance (`localName`, `photoAssetId`) from the
    /// engine. Entries written before those fields existed fall back to a
    /// name lookup in the Files store, then to the Photos hint.
    static func request(for entry: HistoryEntry, fileStore: FileStore) -> PreviewRequest? {
        guard entry.outcome == "completed", entry.direction != "outgoing" else { return nil }
        let date = Date(timeIntervalSince1970: TimeInterval(entry.dateMs) / 1000)

        if let url = localURL(for: entry, fileStore: fileStore) {
            return PreviewRequest(id: entry.id,
                                  fileName: entry.fileName,
                                  sizeBytes: entry.sizeBytes,
                                  date: date,
                                  source: .file(url))
        }
        if let assetId = entry.photoAssetId {
            return PreviewRequest(id: entry.id,
                                  fileName: entry.fileName,
                                  sizeBytes: entry.sizeBytes,
                                  date: date,
                                  source: .photosAsset(assetId))
        }
        return PreviewRequest(id: entry.id,
                              fileName: entry.fileName,
                              sizeBytes: entry.sizeBytes,
                              date: date,
                              source: .gone(wentToPhotos: entry.savedTo == "photos"))
    }

    /// Only the file NAME is persisted in history — the container path changes
    /// on every reinstall — so resolve it against the live store directory.
    static func localURL(for entry: HistoryEntry, fileStore: FileStore) -> URL? {
        let fm = FileManager.default
        if let name = entry.localName {
            let url = fileStore.directory.appendingPathComponent(name, isDirectory: false)
            if fm.fileExists(atPath: url.path) { return url }
        }
        // Legacy entries: try the original file name as saved.
        let fallback = fileStore.directory
            .appendingPathComponent(FileStore.sanitize(fileName: entry.fileName), isDirectory: false)
        if fm.fileExists(atPath: fallback.path) { return fallback }
        return nil
    }
}

// MARK: - Row thumbnail

/// Real thumbnail where it is cheap, the hatched extension badge otherwise.
/// Loading is lazy, off-main and cached (Thumbnailer); the full-size image is
/// never decoded, and a Photos-resident asset is only touched when read
/// access already exists — this view never triggers a permission prompt.
struct RowThumbnail: View {

    let fileName: String
    let source: PreviewRequest.Source
    var side: CGFloat = 44
    var cornerRadius: CGFloat = 13

    @State private var image: UIImage?

    private var kind: PreviewKind { PreviewMedia.kind(forFileName: fileName) }

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: side, height: side)
                    .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                            .strokeBorder(Color.white.opacity(0.12), lineWidth: 0.5)
                    )
                    .overlay(alignment: .bottomTrailing) {
                        if kind == .video {
                            Image(systemName: "play.fill")
                                .font(.system(size: side < 40 ? 7 : 8, weight: .black))
                                .foregroundColor(.white)
                                .padding(3)
                                .background(Circle().fill(Color.black.opacity(0.55)))
                                .padding(3)
                        }
                    }
            } else {
                FileBadge(fileName: fileName, side: side, cornerRadius: cornerRadius)
            }
        }
        .frame(width: side, height: side)
        .task(id: taskKey) {
            await load()
        }
    }

    private var taskKey: String {
        switch source {
        case .file(let url):        return "f:\(url.path)"
        case .photosAsset(let id):  return "p:\(id)"
        case .gone:                 return "gone"
        }
    }

    private func load() async {
        switch source {
        case .file(let url):
            guard kind != .other else { return }
            let loaded = await Thumbnailer.shared.thumbnail(for: url)
            if !Task.isCancelled { image = loaded }
        case .photosAsset(let id):
            // Silent: no prompt on a list render.
            guard Thumbnailer.photosReadable else { return }
            let loaded = await Thumbnailer.shared.photosImage(localIdentifier: id,
                                                              maxPixel: Thumbnailer.rowMaxPixel,
                                                              allowPrompt: false)
            if !Task.isCancelled { image = loaded }
        case .gone:
            break
        }
    }
}

// MARK: - Full-screen preview

struct FilePreviewScreen: View {

    let request: PreviewRequest

    @Environment(\.dismiss) private var dismiss
    @State private var showsChrome = true

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            content
                .ignoresSafeArea(edges: .bottom)

            VStack(spacing: 0) {
                topBar
                    .opacity(showsChrome ? 1 : 0)
                    .animation(.easeOut(duration: 0.2), value: showsChrome)
                Spacer(minLength: 0)
            }
        }
        .preferredColorScheme(.dark)
        .statusBarHidden(!showsChrome)
    }

    // MARK: Chrome

    private var topBar: some View {
        HStack(alignment: .center, spacing: 12) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(Theme.textBase.opacity(0.9))
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(Color.white.opacity(0.12)))
            }
            .buttonStyle(PressableButtonStyle())
            .accessibilityLabel("Close preview")

            VStack(alignment: .leading, spacing: 2) {
                Text(request.fileName)
                    .font(Theme.sans(15, .semibold))
                    .foregroundColor(Theme.textPrimary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(subtitle)
                    .font(Theme.mono(10.5))
                    .foregroundColor(Theme.textBase.opacity(0.55))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }

            Spacer(minLength: 0)

            if let url = request.localURL {
                ShareLink(item: url) {
                    Image(systemName: "square.and.arrow.up")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(Theme.textBase.opacity(0.9))
                        .frame(width: 34, height: 34)
                        .background(Circle().fill(Color.white.opacity(0.12)))
                }
                .accessibilityLabel("Share")
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(
            LinearGradient(colors: [Color.black.opacity(0.75), Color.black.opacity(0)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea(edges: .top)
                .allowsHitTesting(false)
        )
    }

    private var subtitle: String {
        "\(ByteFormat.string(request.sizeBytes)) · \(request.date.formatted(date: .abbreviated, time: .shortened))"
    }

    // MARK: Content

    @ViewBuilder
    private var content: some View {
        switch request.source {
        case .file(let url):
            switch request.kind {
            case .image:
                LocalImagePreview(
                    url: url,
                    onToggleChrome: {
                        withAnimation(.easeOut(duration: 0.15)) { showsChrome.toggle() }
                    },
                    onDismiss: { dismiss() })
            case .video:
                VideoPreview { AVPlayer(url: url) }
            case .other:
                DocumentPreview(request: request, url: url)
            }

        case .photosAsset(let identifier):
            PhotosAssetPreview(identifier: identifier,
                               fileName: request.fileName,
                               kind: request.kind,
                               onDismiss: { dismiss() })

        case .gone(let wentToPhotos):
            UnavailableCard(fileName: request.fileName, wentToPhotos: wentToPhotos)
        }
    }
}

// MARK: - Image viewer

/// Loads a downsampled (never full-size) decode, then hands it to the zoomer.
private struct LocalImagePreview: View {

    let url: URL
    let onToggleChrome: () -> Void
    let onDismiss: () -> Void

    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        Group {
            if let image {
                ZoomableImage(image: image,
                              onToggleChrome: onToggleChrome,
                              onDismiss: onDismiss)
            } else if failed {
                UnavailableCard(fileName: url.lastPathComponent, wentToPhotos: false,
                                message: "This image couldn't be decoded on this iPhone.",
                                shareURL: url)
            } else {
                ProgressView()
                    .tint(Theme.irisSoft)
            }
        }
        .task(id: url.path) {
            let loaded = await Thumbnailer.shared.viewerImage(for: url)
            guard !Task.isCancelled else { return }
            if let loaded {
                image = loaded
            } else {
                failed = true
            }
        }
    }
}

/// Pinch-zoom, double-tap-to-zoom, drag-to-pan when zoomed, swipe-to-dismiss
/// when not. Deliberately hand-rolled: a UIScrollView wrapper buys nothing
/// here and costs a representable.
struct ZoomableImage: View {

    let image: UIImage
    let onToggleChrome: () -> Void
    let onDismiss: () -> Void

    @State private var scale: CGFloat = 1
    @State private var scaleAnchor: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var offsetAnchor: CGSize = .zero
    @State private var dismissDrag: CGSize = .zero

    private static let maxScale: CGFloat = 5
    private static let dismissDistance: CGFloat = 130

    var body: some View {
        GeometryReader { geo in
            Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: geo.size.width, height: geo.size.height)
                .scaleEffect(scale)
                .offset(x: offset.width + dismissDrag.width,
                        y: offset.height + dismissDrag.height)
                .opacity(dismissOpacity)
                .contentShape(Rectangle())
                .gesture(magnification)
                .simultaneousGesture(dragGesture)
                .onTapGesture(count: 2) {
                    withAnimation(.spring(response: 0.32, dampingFraction: 0.82)) {
                        if scale > 1.05 {
                            scale = 1
                            offset = .zero
                        } else {
                            scale = 2.5
                        }
                        scaleAnchor = scale
                        offsetAnchor = offset
                    }
                }
                .onTapGesture {
                    onToggleChrome()
                }
        }
    }

    private var dismissOpacity: Double {
        let travel = min(1, abs(dismissDrag.height) / (Self.dismissDistance * 2))
        return 1 - Double(travel) * 0.6
    }

    private var magnification: some Gesture {
        MagnificationGesture()
            .onChanged { value in
                scale = min(Self.maxScale, max(0.6, scaleAnchor * value))
            }
            .onEnded { _ in
                withAnimation(.spring(response: 0.28, dampingFraction: 0.85)) {
                    if scale < 1 {
                        scale = 1
                        offset = .zero
                    }
                }
                scaleAnchor = scale
                offsetAnchor = offset
            }
    }

    private var dragGesture: some Gesture {
        DragGesture()
            .onChanged { value in
                if scale > 1.05 {
                    offset = CGSize(width: offsetAnchor.width + value.translation.width,
                                    height: offsetAnchor.height + value.translation.height)
                } else {
                    dismissDrag = value.translation
                }
            }
            .onEnded { value in
                if scale > 1.05 {
                    offsetAnchor = offset
                    return
                }
                if abs(value.translation.height) > Self.dismissDistance {
                    onDismiss()
                } else {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) {
                        dismissDrag = .zero
                    }
                }
            }
    }
}

// MARK: - Video

/// The player is built ONCE and parked in @State — building it inline in a
/// ViewBuilder would hand VideoPlayer a brand-new AVPlayer on every body
/// evaluation and restart playback from zero.
private struct VideoPreview: View {

    let makePlayer: () -> AVPlayer

    @State private var player: AVPlayer?

    var body: some View {
        ZStack {
            if let player {
                VideoPlayer(player: player)
            } else {
                ProgressView()
                    .tint(Theme.irisSoft)
            }
        }
        .onAppear {
            guard player == nil else { return }
            let created = makePlayer()
            player = created
            created.play()
        }
        .onDisappear {
            player?.pause()
        }
    }
}

// MARK: - QuickLook

/// QLPreviewController for everything that isn't an image or a video. When
/// QuickLook says it can't render the file we don't show an empty grey box —
/// we show a card with the actions that still make sense.
private struct DocumentPreview: View {

    let request: PreviewRequest
    let url: URL

    var body: some View {
        Group {
            if QLPreviewController.canPreview(url as NSURL) {
                QuickLookPreview(url: url)
                    .background(Color.black)
            } else {
                UnavailableCard(fileName: request.fileName,
                                wentToPhotos: false,
                                message: "iOS has no preview for this file type. You can still share it or save it to Files — the bytes are exactly what your PC sent.",
                                shareURL: url)
            }
        }
    }
}

struct QuickLookPreview: UIViewControllerRepresentable {

    let url: URL

    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: QLPreviewController, context: Context) {
        uiViewController.reloadData()
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(url: url)
    }

    final class Coordinator: NSObject, QLPreviewControllerDataSource {

        let url: URL

        init(url: URL) {
            self.url = url
        }

        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }

        func previewController(_ controller: QLPreviewController,
                               previewItemAt index: Int) -> QLPreviewItem {
            url as NSURL
        }
    }
}

// MARK: - Photos-resident media

/// The file was imported into Photos and the local temp was deleted. Ask for
/// read access at the moment of the tap (never earlier), then render from the
/// library. A denial degrades to the "lives in your Photos" card instead of a
/// broken preview.
private struct PhotosAssetPreview: View {

    let identifier: String
    let fileName: String
    let kind: PreviewKind
    let onDismiss: () -> Void

    @State private var image: UIImage?
    @State private var videoAsset: AVAsset?
    @State private var state: LoadState = .loading

    private enum LoadState: Equatable {
        case loading
        case ready
        case denied
        case missing
    }

    var body: some View {
        Group {
            switch state {
            case .loading:
                ProgressView()
                    .tint(Theme.irisSoft)
            case .ready:
                if let image {
                    ZoomableImage(image: image, onToggleChrome: {}, onDismiss: onDismiss)
                } else if let videoAsset {
                    VideoPreview { AVPlayer(playerItem: AVPlayerItem(asset: videoAsset)) }
                } else {
                    UnavailableCard(fileName: fileName, wentToPhotos: true)
                }
            case .denied:
                UnavailableCard(fileName: fileName,
                                wentToPhotos: true,
                                message: "This file lives in your Photos library. Sendro doesn't have permission to read it, so it can't be shown here.",
                                showsSettings: true)
            case .missing:
                UnavailableCard(fileName: fileName,
                                wentToPhotos: true,
                                message: "This file lives in your Photos library. Sendro couldn't find the asset — it may have been deleted there.")
            }
        }
        .task(id: identifier) {
            await load()
        }
    }

    private func load() async {
        guard await Thumbnailer.requestPhotosRead() else {
            state = .denied
            return
        }
        switch kind {
        case .image, .other:
            let loaded = await Thumbnailer.shared.photosImage(localIdentifier: identifier,
                                                              maxPixel: 3000,
                                                              allowPrompt: true)
            guard !Task.isCancelled else { return }
            image = loaded
            state = loaded == nil ? .missing : .ready
        case .video:
            let asset = await Thumbnailer.playableAsset(localIdentifier: identifier)
            guard !Task.isCancelled else { return }
            if let asset {
                videoAsset = asset
                state = .ready
            } else {
                state = .missing
            }
        }
    }
}

// MARK: - Fallback card

/// The honest state: no silent broken preview anywhere in this file.
struct UnavailableCard: View {

    let fileName: String
    let wentToPhotos: Bool
    var message: String? = nil
    var shareURL: URL? = nil
    var showsSettings: Bool = false

    @State private var exporting = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Image(systemName: wentToPhotos ? "photo.on.rectangle.angled" : "doc")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(wentToPhotos ? Theme.teal : Theme.irisSoft)
                Text(wentToPhotos ? "It's in your Photos library" : "No preview available")
                    .font(Theme.sans(17, .semibold))
                    .foregroundColor(Theme.textPrimary)
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
            }

            Text(fileName)
                .font(Theme.mono(12))
                .foregroundColor(Theme.textSecondary)
                .lineLimit(2)
                .truncationMode(.middle)

            Text(message ?? defaultMessage)
                .font(Theme.sans(13.5))
                .foregroundColor(Theme.textBase.opacity(0.6))
                .fixedSize(horizontal: false, vertical: true)

            actions
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard(cornerRadius: 24)
        .padding(.horizontal, 20)
        .sheet(isPresented: $exporting) {
            if let shareURL {
                DocumentExportPicker(url: shareURL)
                    .ignoresSafeArea()
            }
        }
    }

    private var defaultMessage: String {
        wentToPhotos
            ? "Sendro imported it into Photos and removed its temporary copy, so there's nothing left here to show. Open Photos to see it — look for the “Sendro” album."
            : "This file isn't on this iPhone any more."
    }

    @ViewBuilder
    private var actions: some View {
        VStack(spacing: 9) {
            if let shareURL {
                ShareLink(item: shareURL) {
                    AccentPillLabel(title: "Share", height: 46)
                }
                Button {
                    exporting = true
                } label: {
                    GhostPillLabel(title: "Save to Files…", height: 46)
                }
                .buttonStyle(PressableButtonStyle())
            }
            if wentToPhotos {
                Button {
                    openPhotosApp()
                } label: {
                    AccentPillLabel(title: "Open Photos", color: Theme.teal, height: 46)
                }
                .buttonStyle(PressableButtonStyle())
            }
            if showsSettings {
                Button {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                } label: {
                    GhostPillLabel(title: "Open Settings", height: 46)
                }
                .buttonStyle(PressableButtonStyle())
            }
        }
    }

    private func openPhotosApp() {
        // photos-redirect:// is the long-standing way to open Photos; fall
        // back to the Photos URL scheme if it isn't handled.
        let candidates = ["photos-redirect://", "photos://"]
        for candidate in candidates {
            if let url = URL(string: candidate), UIApplication.shared.canOpenURL(url) {
                UIApplication.shared.open(url)
                return
            }
        }
    }
}
