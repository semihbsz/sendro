//
//  SendView.swift
//  Sendro
//
//  The Send tab — a first-class surface, not a sheet. Header with the target
//  PC chip, four big actions (Photos & Videos, Files, Send Text, Paste), then
//  the outgoing queue: per-file progress, speed, ETA, cancel, retry and the
//  "Landed on <PC>" done state. Backed by UploadEngine (PROTOCOL.md §7) and,
//  for text, by the ephemeral message path (§11.2).
//

import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct SendView: View {

    @EnvironmentObject private var uploader: UploadEngine
    @EnvironmentObject private var pairedHosts: PairedHostStore
    @EnvironmentObject private var engine: TransferEngine

    /// Owned by RootView so the choice survives tab switches.
    @Binding var targetHostId: String?
    let openDevices: () -> Void

    @State private var showPhotoPicker = false
    @State private var showDocPicker = false
    @State private var showTargetPicker = false
    @State private var showComposer = false
    @State private var composerPrefill = ""
    @State private var pickFailures: [String] = []
    @State private var hint: String?

    // MARK: Target resolution

    private var onlineHosts: [PairedHost] {
        pairedHosts.hosts.filter { engine.hostOnline[$0.deviceId] == true }
    }

    /// The PC that will actually receive: the explicit pick while it is
    /// online, else the first online one.
    private var targetHost: PairedHost? {
        if let id = targetHostId,
           let host = pairedHosts.host(id: id),
           engine.hostOnline[host.deviceId] == true {
            return host
        }
        return onlineHosts.first
    }

    /// What the header chip names, even when nothing is online.
    private var chipHost: PairedHost? {
        if let host = targetHost { return host }
        if let id = targetHostId, let host = pairedHosts.host(id: id) { return host }
        return pairedHosts.hosts.first
    }

    private var canSend: Bool { targetHost != nil }

    var body: some View {
        VStack(spacing: 0) {
            header
                .padding(.horizontal, 20)
                .padding(.top, 12)

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if pairedHosts.hosts.isEmpty {
                        noticeCard(title: "No paired PC yet",
                                   message: "Pair a computer first — open Sendro on your PC, then tap the chip above.",
                                   tint: Theme.irisSoft,
                                   actionTitle: "Pair a PC",
                                   action: openDevices)
                            .padding(.top, 20)
                    } else if !canSend {
                        noticeCard(title: "Nothing online to send to",
                                   message: "\(chipHost?.name ?? "Your PC") looks offline. Open Sendro on your computer on this Wi-Fi and these actions light up by themselves.",
                                   tint: Theme.warn,
                                   actionTitle: nil,
                                   action: nil)
                            .padding(.top, 20)
                    }

                    actionsGrid
                        .padding(.top, 22)

                    if let hint = hint {
                        hintRow(hint)
                            .padding(.top, 14)
                    }

                    if !pickFailures.isEmpty {
                        failuresCard
                            .padding(.top, 14)
                    }

                    if uploader.items.isEmpty {
                        emptyQueueState
                            .padding(.top, 34)
                    } else {
                        queueSection
                            .padding(.top, 30)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 130)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .scrollIndicators(.hidden)
        }
        .sheet(isPresented: $showPhotoPicker) {
            PhotoPicker { picked in
                handlePicked(picked)
            }
            .ignoresSafeArea()
        }
        .sheet(isPresented: $showDocPicker) {
            DocumentPicker { picked in
                handlePicked(picked)
            }
            .ignoresSafeArea()
        }
        .sheet(isPresented: $showComposer) {
            MessageComposerSheet(initialText: composerPrefill,
                                 hostName: targetHost?.name ?? chipHost?.name ?? "your PC",
                                 hostId: targetHost?.deviceId)
        }
        .confirmationDialog("Send to which computer?",
                            isPresented: $showTargetPicker,
                            titleVisibility: .visible) {
            ForEach(pairedHosts.hosts) { host in
                Button(engine.hostOnline[host.deviceId] == true
                       ? host.name
                       : "\(host.name) (offline)") {
                    targetHostId = host.deviceId
                }
            }
            Button("Manage Computers…") { openDevices() }
            Button("Cancel", role: .cancel) {}
        }
    }

    // MARK: Header

    private var header: some View {
        HStack(alignment: .center, spacing: 10) {
            Text("Send")
                .font(Theme.sans(34, .semibold))
                .foregroundColor(Theme.textPrimary)
                .lineLimit(1)

            Spacer(minLength: 8)

            Button {
                if pairedHosts.hosts.count > 1 {
                    showTargetPicker = true
                } else {
                    openDevices()
                }
            } label: {
                HStack(spacing: 8) {
                    if let host = chipHost {
                        PulseDot(color: Theme.teal,
                                 active: engine.hostOnline[host.deviceId] == true)
                        Text(host.name.uppercased())
                            .font(Theme.mono(11.5, .medium))
                            .foregroundColor(Theme.textBase.opacity(0.8))
                            .lineLimit(1)
                            .truncationMode(.tail)
                    } else {
                        Circle()
                            .fill(Theme.iris)
                            .frame(width: 7, height: 7)
                        Text("PAIR A PC")
                            .font(Theme.mono(11.5, .medium))
                            .foregroundColor(Theme.irisSoft)
                            .lineLimit(1)
                    }
                    Image(systemName: pairedHosts.hosts.count > 1
                          ? "chevron.up.chevron.down"
                          : "chevron.right")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundColor(Theme.textBase.opacity(0.5))
                }
                .padding(.leading, 11)
                .padding(.trailing, 12)
                .frame(height: 32)
                .glassRow(cornerRadius: 16, fillOpacity: 0.06, borderOpacity: 0.12)
            }
            .buttonStyle(PressableButtonStyle())
            .accessibilityLabel("Target computer")
        }
    }

    // MARK: Actions

    private var actionsGrid: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionTag(text: "Send")
                .padding(.leading, 2)

            HStack(alignment: .top, spacing: 12) {
                actionCard(title: "Photos & Videos",
                           subtitle: "Original bytes",
                           systemImage: "photo.on.rectangle.angled",
                           prominent: true) {
                    showPhotoPicker = true
                }
                actionCard(title: "Files",
                           subtitle: "Anything on this iPhone",
                           systemImage: "folder",
                           prominent: false) {
                    showDocPicker = true
                }
            }

            HStack(alignment: .top, spacing: 12) {
                actionCard(title: "Send Text",
                           subtitle: "A link, a code — vanishes after",
                           systemImage: "text.bubble",
                           prominent: false) {
                    composerPrefill = ""
                    showComposer = true
                }
                actionCard(title: "Paste",
                           subtitle: "Whatever's on the clipboard",
                           systemImage: "doc.on.clipboard",
                           prominent: false) {
                    handlePaste()
                }
            }
        }
        .disabled(!canSend)
        .opacity(canSend ? 1 : 0.45)
    }

    private func actionCard(title: String,
                            subtitle: String,
                            systemImage: String,
                            prominent: Bool,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 13, style: .continuous)
                        .fill(Theme.iris.opacity(prominent ? 0.22 : 0.13))
                    Image(systemName: systemImage)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(prominent ? Theme.irisBright : Theme.irisSoft)
                }
                .frame(width: 40, height: 40)

                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(Theme.sans(15.5, .semibold))
                        .foregroundColor(Theme.textPrimary)
                        .lineLimit(2)
                        .minimumScaleFactor(0.85)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(subtitle)
                        .font(Theme.mono(10))
                        .foregroundColor(Theme.textTertiary)
                        .lineLimit(2)
                        .minimumScaleFactor(0.85)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .multilineTextAlignment(.leading)
            .padding(16)
            .frame(maxWidth: .infinity, minHeight: 132, alignment: .topLeading)
            .glassCard(cornerRadius: 22)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressableButtonStyle())
    }

    // MARK: Notices

    private func noticeCard(title: String,
                            message: String,
                            tint: Color,
                            actionTitle: String?,
                            action: (() -> Void)?) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(Theme.sans(16, .semibold))
                .foregroundColor(Theme.textPrimary)
            Text(message)
                .font(Theme.sans(13))
                .foregroundColor(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if let actionTitle, let action {
                Button(action: action) {
                    Text(actionTitle)
                        .font(Theme.sans(13, .semibold))
                        .foregroundColor(Theme.onAccent)
                        .padding(.horizontal, 16)
                        .frame(height: 34)
                        .background(RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(tint))
                }
                .buttonStyle(PressableButtonStyle())
                .padding(.top, 2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .glassRow(cornerRadius: 22, fillOpacity: 0.05, borderOpacity: 0.09)
    }

    private func hintRow(_ text: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "info.circle")
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(Theme.irisSoft)
            Text(text)
                .font(Theme.sans(12.5))
                .foregroundColor(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassRow(cornerRadius: 16, fillOpacity: 0.05, borderOpacity: 0.08)
        .transition(.opacity)
    }

    private var failuresCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(Theme.warn)
                Text("Some items couldn't be added")
                    .font(Theme.sans(13, .semibold))
                    .foregroundColor(Theme.warn)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                Spacer(minLength: 0)
                Button {
                    pickFailures = []
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(Theme.textBase.opacity(0.5))
                        .frame(width: 24, height: 24)
                }
                .buttonStyle(PressableButtonStyle())
            }
            ForEach(pickFailures, id: \.self) { failure in
                Text(failure)
                    .font(Theme.sans(12))
                    .foregroundColor(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .glassRow(cornerRadius: 16, fillOpacity: 0.05, borderOpacity: 0.09)
    }

    // MARK: Empty queue

    private var emptyQueueState: some View {
        VStack(alignment: .leading, spacing: 0) {
            SendBeamEmblem()
                .frame(maxWidth: .infinity)
                .frame(height: 150)

            Text("Nothing in flight")
                .font(Theme.sans(22, .semibold))
                .foregroundColor(Theme.textPrimary)
                .padding(.top, 8)

            Text(canSend
                 ? "Pick photos, files or text above and it beams straight to \(targetHost?.name ?? "your PC") — original bytes, verified on arrival."
                 : "Anything you queue here beams straight to your PC over Wi-Fi — original bytes, verified on arrival.")
                .font(Theme.sans(14))
                .foregroundColor(Theme.textBase.opacity(0.5))
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 10)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: Queue

    private var queueSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                SectionTag(text: "Outgoing")
                    .padding(.leading, 2)
                Spacer(minLength: 8)
                if uploader.hasFinished {
                    Button {
                        uploader.clearFinished()
                    } label: {
                        Text("Clear done")
                            .font(Theme.sans(12, .medium))
                            .foregroundColor(Theme.irisSoft)
                            .lineLimit(1)
                    }
                    .buttonStyle(PressableButtonStyle())
                }
            }

            VStack(spacing: 8) {
                ForEach(uploader.items) { item in
                    UploadRow(item: item,
                              onCancel: { uploader.cancel(itemId: item.id) },
                              onRetry: { uploader.retry(itemId: item.id) })
                }
            }
        }
    }

    // MARK: Picker completion

    private func handlePicked(_ picked: PickedFiles) {
        // The pickers dismiss themselves UIKit-side; keep the bindings in
        // sync so they can be presented again.
        showPhotoPicker = false
        showDocPicker = false
        pickFailures = picked.failures
        guard !picked.urls.isEmpty else { return }
        guard let host = targetHost else {
            for url in picked.urls {
                SendStaging.remove(url)
            }
            pickFailures.append("The PC went offline — nothing was queued.")
            return
        }
        uploader.enqueue(fileURLs: picked.urls, hostId: host.deviceId, hostName: host.name)
    }

    // MARK: Paste

    /// Preference order for pasted image data: take the pasteboard's own
    /// bytes so nothing is re-encoded.
    private static let preferredImageTypes = [
        "public.png", "public.jpeg", "public.heic", "public.heif",
        "public.tiff", "com.compuserve.gif", "com.adobe.pdf"
    ]

    private static let stampFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH-mm-ss"
        return formatter
    }()

    /// iOS 16 shows a system paste banner the first time we read the
    /// pasteboard's contents; a denial simply yields nil everywhere, which
    /// lands in the "couldn't read" hint rather than a crash.
    private func handlePaste() {
        let pasteboard = UIPasteboard.general
        if pasteboard.hasImages {
            pasteImage(from: pasteboard)
        } else if pasteboard.hasStrings {
            guard let text = pasteboard.string, !text.isEmpty else {
                showHint("Couldn't read the clipboard text.")
                return
            }
            composerPrefill = text
            showComposer = true
        } else {
            showHint("Clipboard is empty.")
        }
    }

    private func pasteImage(from pasteboard: UIPasteboard) {
        guard let host = targetHost else {
            showHint("No PC online — nothing was queued.")
            return
        }
        let available = pasteboard.types
        var chosenType = Self.preferredImageTypes.first { available.contains($0) }
        if chosenType == nil {
            chosenType = available.first { UTType($0)?.conforms(to: .image) == true }
        }

        var bytes: Data?
        var ext = "png"
        if let type = chosenType,
           let raw = pasteboard.data(forPasteboardType: type), !raw.isEmpty {
            bytes = raw
            ext = UTType(type)?.preferredFilenameExtension ?? "png"
        } else if let image = pasteboard.image, let png = image.pngData() {
            // Only re-encode when no raw representation was retrievable.
            bytes = png
            ext = "png"
        }
        guard let data = bytes, !data.isEmpty else {
            showHint("Couldn't read the image on the clipboard.")
            return
        }

        let name = FileStore.sanitize(
            fileName: "Pasted \(Self.stampFormatter.string(from: Date())).\(ext)")
        let url = FileStore.availableURL(in: SendStaging.newBatchDirectory(), name: name)
        do {
            try data.write(to: url, options: .atomic)
        } catch {
            showHint("Couldn't stage the pasted image: \(error.localizedDescription)")
            return
        }
        uploader.enqueue(fileURLs: [url], hostId: host.deviceId, hostName: host.name)
    }

    private func showHint(_ text: String) {
        withAnimation(.easeOut(duration: 0.18)) { hint = text }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if hint == text {
                withAnimation(.easeOut(duration: 0.18)) { hint = nil }
            }
        }
    }
}

// MARK: - Outgoing queue row

/// One row of the outgoing queue. Split out of the old SendSheet so the Send
/// screen stays readable; it owns no state, just callbacks.
struct UploadRow: View {

    let item: UploadItem
    let onCancel: () -> Void
    let onRetry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 12) {
                FileBadge(fileName: item.fileName, side: 36, cornerRadius: 11)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.fileName)
                        .font(Theme.sans(14, .medium))
                        .foregroundColor(Theme.textBase.opacity(0.93))
                        .lineLimit(1)
                        .truncationMode(.middle)
                    statusLine
                }
                Spacer(minLength: 0)
                trailingControl
            }

            if item.phase == .uploading || item.phase == .hashing {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule()
                            .fill(Color.white.opacity(0.08))
                        Capsule()
                            .fill(Theme.iris)
                            .frame(width: max(4, geo.size.width * item.fractionComplete))
                    }
                }
                .frame(height: 3)
            }

            if case .failed(let message) = item.phase {
                Text(message)
                    .font(Theme.sans(12))
                    .foregroundColor(Theme.danger)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .glassRow(cornerRadius: 18)
    }

    @ViewBuilder
    private var statusLine: some View {
        switch item.phase {
        case .queued:
            Text("Waiting…")
                .font(Theme.mono(10.5))
                .foregroundColor(Theme.textTertiary)
        case .hashing:
            Text("Hashing SHA-256…")
                .font(Theme.mono(10.5))
                .foregroundColor(Theme.textTertiary)
        case .uploading:
            Text("\(ByteFormat.string(item.bytesSent)) / \(ByteFormat.string(item.sizeBytes)) · \(ByteFormat.speed(item.speedBytesPerSecond)) · ETA \(ByteFormat.eta(item.etaSeconds))")
                .font(Theme.mono(10.5))
                .foregroundColor(Theme.textTertiary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        case .done:
            Text(doneLine)
                .font(Theme.mono(10.5))
                .foregroundColor(Theme.teal)
                .lineLimit(1)
                .truncationMode(.middle)
        case .failed:
            Text("Failed")
                .font(Theme.mono(10.5))
                .foregroundColor(Theme.danger)
        }
    }

    /// "Landed on SEMIH-PC · name.ext" — basename out of the server's
    /// savedPath (Windows or POSIX separators).
    private var doneLine: String {
        var line = "Landed on \(item.hostName)"
        if let path = item.savedPath, !path.isEmpty,
           let base = path.split(whereSeparator: { $0 == "\\" || $0 == "/" }).last {
            line += " · \(String(base))"
        }
        return line
    }

    @ViewBuilder
    private var trailingControl: some View {
        switch item.phase {
        case .queued, .hashing, .uploading:
            Button(action: onCancel) {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(Theme.textBase.opacity(0.55))
                    .frame(width: 30, height: 30)
                    .glassRow(cornerRadius: 15, fillOpacity: 0.06, borderOpacity: 0.1)
            }
            .buttonStyle(PressableButtonStyle())
            .accessibilityLabel("Cancel upload")
        case .done:
            Image(systemName: "checkmark")
                .font(.system(size: 13, weight: .bold))
                .foregroundColor(Theme.teal)
        case .failed:
            HStack(spacing: 6) {
                Button(action: onRetry) {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(Theme.irisSoft)
                        .frame(width: 30, height: 30)
                        .glassRow(cornerRadius: 15, fillOpacity: 0.06, borderOpacity: 0.1)
                }
                .buttonStyle(PressableButtonStyle())
                .accessibilityLabel("Retry upload")

                Button(action: onCancel) {
                    Image(systemName: "trash")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(Theme.danger.opacity(0.85))
                        .frame(width: 30, height: 30)
                        .glassRow(cornerRadius: 15, fillOpacity: 0.06, borderOpacity: 0.1)
                }
                .buttonStyle(PressableButtonStyle())
                .accessibilityLabel("Remove upload")
            }
        }
    }
}

// MARK: - Empty-state emblem

/// Quiet outbound counterpart to the Receive tab's radar: the beam leaving a
/// source ring for a destination node, breathing slowly.
struct SendBeamEmblem: View {

    @State private var breathe = false

    var body: some View {
        ZStack {
            Circle()
                .strokeBorder(Color.white.opacity(0.06), lineWidth: 1)
                .frame(width: 148, height: 148)
                .scaleEffect(breathe ? 1.05 : 1.0)
                .opacity(breathe ? 0.7 : 0.3)
            Circle()
                .strokeBorder(Theme.iris.opacity(0.22), lineWidth: 1)
                .frame(width: 100, height: 100)
                .scaleEffect(breathe ? 1.0 : 1.05)
                .opacity(breathe ? 0.3 : 0.7)

            ZStack {
                BeamMarkShape()
                    .stroke(LinearGradient(colors: [Theme.iris.opacity(0.85),
                                                    Theme.teal.opacity(0.9)],
                                           startPoint: .bottomLeading,
                                           endPoint: .topTrailing),
                            style: StrokeStyle(lineWidth: 116 / BeamMarkShape.canvas * 118,
                                               lineCap: .round))
                Circle()
                    .fill(Theme.teal)
                    .frame(width: 84 / BeamMarkShape.canvas * 118,
                           height: 84 / BeamMarkShape.canvas * 118)
                    .offset(x: (806 - 512) / BeamMarkShape.canvas * 118,
                            y: (316 - 512) / BeamMarkShape.canvas * 118)
                    .shadow(color: Theme.teal.opacity(0.8), radius: 10)
            }
            .frame(width: 118, height: 118)
            .opacity(breathe ? 1.0 : 0.82)
        }
        .onAppear {
            withAnimation(.easeInOut(duration: 4.5).repeatForever(autoreverses: true)) {
                breathe = true
            }
        }
    }
}
