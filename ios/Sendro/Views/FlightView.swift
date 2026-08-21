//
//  FlightView.swift
//  Sendro
//
//  Full-screen live transfer: progress ring with orbiting spark, phase rail
//  (Queue / Prep / Stream / Verify / Save), throughput meter, and
//  phase-specific actions — including queued, host-backpressure, verify,
//  save-choice, photos-denied, failure and paused states. Backed 1:1 by
//  TransferEngine's ActiveTransfer.
//

import SwiftUI
import UIKit
import Combine

struct FlightView: View {

    let flightRef: FlightRef

    @EnvironmentObject private var engine: TransferEngine
    @EnvironmentObject private var history: HistoryStore
    @EnvironmentObject private var fileStore: FileStore
    @Environment(\.dismiss) private var dismiss

    /// Set when the user taps Preview on the completed state.
    @State private var preview: PreviewRequest?

    /// Raw throughput samples (bytes/s, newest last) for the bar meter.
    @State private var speedSamples: [Double] = Array(repeating: 0, count: 28)

    private let meterTimer = Timer.publish(every: 0.2, on: .main, in: .common).autoconnect()

    // MARK: Derived state

    private var liveTransfer: ActiveTransfer? {
        engine.active.first { $0.id == flightRef.id }
    }

    private var completedEntry: HistoryEntry? {
        history.entries.first { $0.transferId == flightRef.id && $0.outcome == "completed" }
    }

    /// Gone from `active` with no completed record → cancelled somewhere else.
    private var vanished: Bool {
        liveTransfer == nil && completedEntry == nil
    }

    private var isDone: Bool {
        liveTransfer == nil && completedEntry != nil
    }

    private var ringFraction: Double {
        guard let transfer = liveTransfer else { return isDone ? 1 : 0 }
        switch transfer.phase {
        case .queued, .waitingForHost, .preparing, .downloading, .interrupted, .failed:
            return transfer.fractionComplete
        case .verifying, .awaitingSaveChoice, .saving, .photosDenied:
            return 1
        }
    }

    private var showsTealRing: Bool {
        if isDone { return true }
        guard let transfer = liveTransfer else { return false }
        switch transfer.phase {
        case .awaitingSaveChoice, .saving, .photosDenied:
            return true
        default:
            return false
        }
    }

    private var isVerifying: Bool {
        liveTransfer?.phase == .verifying
    }

    private var showsOrbit: Bool {
        guard let phase = liveTransfer?.phase else { return false }
        return phase == .preparing || phase == .downloading
    }

    private var showsMeter: Bool {
        guard let phase = liveTransfer?.phase else { return false }
        switch phase {
        case .preparing, .downloading, .verifying:
            return true
        default:
            return false
        }
    }

    // MARK: Body

    var body: some View {
        ZStack {
            SendroBackground()

            if showsOrbit {
                RisingStreams()
            }

            VStack(spacing: 0) {
                header
                    .padding(.horizontal, 18)
                    .padding(.top, 8)

                ScrollView {
                    VStack(spacing: 0) {
                        ringArea
                            .frame(height: 250)
                            .padding(.top, 22)

                        titleBlock
                            .padding(.top, 14)
                            .padding(.horizontal, 24)

                        PhaseRail(index: railIndex)
                            .padding(.top, 22)
                            .padding(.horizontal, 20)

                        if showsMeter {
                            meter
                                .padding(.top, 18)
                                .padding(.horizontal, 20)
                        }

                        statusBlock
                            .padding(.top, 18)
                            .padding(.horizontal, 20)

                        Spacer(minLength: 24)
                    }
                }
                .scrollIndicators(.hidden)

                bottomControls
                    .padding(.horizontal, 20)
                    .padding(.bottom, 12)
            }
        }
        .preferredColorScheme(.dark)
        .onChange(of: vanished) { gone in
            if gone { dismiss() }
        }
        .onReceive(meterTimer) { _ in
            sampleSpeed()
        }
        .fullScreenCover(item: $preview) { request in
            FilePreviewScreen(request: request)
        }
    }

    /// What the completed transfer can show, if anything (a local file, a
    /// Photos asset, or the honest "it's in your Photos library" card).
    private var completedPreview: PreviewRequest? {
        guard let entry = completedEntry else { return nil }
        return PreviewResolver.request(for: entry, fileStore: fileStore)
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: 12) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(Theme.textBase.opacity(0.8))
                    .frame(width: 34, height: 34)
                    .glassRow(cornerRadius: 12, fillOpacity: 0.07, borderOpacity: 0.1)
            }
            .buttonStyle(PressableButtonStyle())
            .accessibilityLabel("Back")

            Spacer()

            Text(phaseHeadline.uppercased())
                .font(Theme.mono(10.5, .medium))
                .tracking(2.2)
                .foregroundColor(Theme.textBase.opacity(0.5))
                .lineLimit(1)
                .minimumScaleFactor(0.7)

            Spacer()

            Color.clear.frame(width: 34, height: 34)
        }
    }

    private var phaseHeadline: String {
        if isDone { return "Verified" }
        guard let transfer = liveTransfer else { return "" }
        switch transfer.phase {
        case .queued(let position):
            return position <= 1 ? "Next in line" : "Waiting · #\(position) in line"
        case .waitingForHost(let reason, _):
            return reason.headline(hostName: flightRef.offer.senderName)
        case .preparing:          return "Preparing"
        case .downloading:        return "Receiving"
        case .verifying:          return "Verifying SHA-256"
        case .awaitingSaveChoice: return "Where should this go?"
        case .saving:             return "Saving"
        case .photosDenied:       return "Photos access needed"
        case .failed:             return "Failed"
        case .interrupted:        return "Paused"
        }
    }

    // MARK: Ring

    private var ringArea: some View {
        ZStack {
            Circle()
                .stroke(Color.white.opacity(0.08), lineWidth: 10)
                .frame(width: 200, height: 200)

            Circle()
                .trim(from: 0, to: CGFloat(ringFraction))
                .stroke(Theme.iris, style: StrokeStyle(lineWidth: 10, lineCap: .round))
                .frame(width: 200, height: 200)
                .rotationEffect(.degrees(-90))
                .shadow(color: Theme.iris.opacity(0.65), radius: 10)
                .animation(.linear(duration: 0.25), value: ringFraction)

            if showsTealRing {
                Circle()
                    .stroke(Theme.teal, style: StrokeStyle(lineWidth: 10, lineCap: .round))
                    .frame(width: 200, height: 200)
                    .shadow(color: Theme.teal.opacity(0.7), radius: 14)
                    .transition(.opacity)
            }

            if isVerifying {
                VerifyArc()
            }

            if showsOrbit {
                Circle()
                    .fill(Color.white)
                    .frame(width: 10, height: 10)
                    .shadow(color: Theme.irisBright.opacity(0.9), radius: 9)
                    .offset(y: -100)
                    .rotationEffect(.degrees(ringFraction * 360))
                    .animation(.linear(duration: 0.25), value: ringFraction)
            }

            centerContent
        }
    }

    @ViewBuilder
    private var centerContent: some View {
        if isDone {
            doneBadge(title: doneTitle)
        } else if let transfer = liveTransfer {
            switch transfer.phase {
            case .queued(let position):
                VStack(spacing: 8) {
                    Image(systemName: "line.3.horizontal.decrease")
                        .font(.system(size: 28, weight: .medium))
                        .foregroundColor(Theme.irisSoft)
                    Text(position <= 1 ? "NEXT UP" : "#\(position) IN LINE")
                        .font(Theme.mono(11, .medium))
                        .tracking(1.5)
                        .foregroundColor(Theme.textBase.opacity(0.5))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
            case .waitingForHost(let reason, let seconds):
                VStack(spacing: 8) {
                    Image(systemName: reason == .paused ? "pause.circle" : "hourglass")
                        .font(.system(size: 28, weight: .medium))
                        .foregroundColor(Theme.warn)
                    Text("\(seconds)s")
                        .font(Theme.mono(22, .medium))
                        .foregroundColor(Theme.textBase.opacity(0.72))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    Text(reason.shortLabel.uppercased())
                        .font(Theme.mono(10.5, .medium))
                        .tracking(1.5)
                        .foregroundColor(Theme.warn)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
            case .preparing, .downloading, .interrupted:
                VStack(spacing: 4) {
                    HStack(alignment: .firstTextBaseline, spacing: 2) {
                        Text("\(Int((transfer.fractionComplete * 100).rounded()))")
                            .font(Theme.mono(58, .medium))
                            .foregroundColor(Theme.textPrimary)
                        Text("%")
                            .font(Theme.mono(20, .medium))
                            .foregroundColor(Theme.textBase.opacity(0.45))
                    }
                    Text("\(ByteFormat.string(transfer.bytesReceived)) / \(ByteFormat.string(transfer.offer.sizeBytes))")
                        .font(Theme.mono(11))
                        .foregroundColor(Theme.textBase.opacity(0.5))
                }
            case .verifying:
                VStack(spacing: 8) {
                    Image(systemName: "number")
                        .font(.system(size: 30, weight: .medium))
                        .foregroundColor(Theme.teal)
                    Text("SHA-256")
                        .font(Theme.mono(11, .medium))
                        .tracking(1.5)
                        .foregroundColor(Theme.textBase.opacity(0.5))
                }
            case .awaitingSaveChoice:
                VStack(spacing: 8) {
                    Image(systemName: "arrow.triangle.branch")
                        .font(.system(size: 30, weight: .medium))
                        .foregroundColor(Theme.teal)
                    Text("VERIFIED")
                        .font(Theme.mono(11, .medium))
                        .tracking(1.5)
                        .foregroundColor(Theme.teal)
                }
            case .saving:
                VStack(spacing: 10) {
                    ProgressView()
                        .tint(Theme.teal)
                    Text("SAVING")
                        .font(Theme.mono(11, .medium))
                        .tracking(1.5)
                        .foregroundColor(Theme.textBase.opacity(0.5))
                }
            case .photosDenied:
                VStack(spacing: 8) {
                    Image(systemName: "photo.on.rectangle.angled")
                        .font(.system(size: 28, weight: .medium))
                        .foregroundColor(Theme.warn)
                    Text("NO ACCESS")
                        .font(Theme.mono(11, .medium))
                        .tracking(1.5)
                        .foregroundColor(Theme.warn)
                }
            case .failed:
                VStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.system(size: 28, weight: .medium))
                        .foregroundColor(Theme.danger)
                    Text("FAILED")
                        .font(Theme.mono(11, .medium))
                        .tracking(1.5)
                        .foregroundColor(Theme.danger)
                }
            }
        }
    }

    private func doneBadge(title: String) -> some View {
        VStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(Theme.teal.opacity(0.14))
                    .frame(width: 72, height: 72)
                Circle()
                    .strokeBorder(Theme.teal, lineWidth: 1.5)
                    .frame(width: 72, height: 72)
                Image(systemName: "checkmark")
                    .font(.system(size: 28, weight: .semibold))
                    .foregroundColor(Theme.teal)
            }
            Text(title)
                .font(Theme.sans(17, .semibold))
                .foregroundColor(Theme.textPrimary)
        }
        .transition(.scale(scale: 0.86).combined(with: .opacity))
    }

    private var doneTitle: String {
        if let entry = completedEntry {
            return entry.savedTo == "photos" ? "Saved to Photos" : "Saved to Files"
        }
        return "Saved"
    }

    // MARK: Title + hash line

    private var titleBlock: some View {
        VStack(spacing: 6) {
            Text(flightRef.offer.fileName)
                .font(Theme.sans(20, .semibold))
                .foregroundColor(Theme.textPrimary)
                .lineLimit(1)
                .truncationMode(.middle)
            Text(hashLine)
                .font(Theme.mono(11))
                .foregroundColor(Theme.textTertiary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity)
    }

    private var hashLine: String {
        let shortHash = String(flightRef.offer.sha256.prefix(8))
        if isDone {
            return "sha256 \(shortHash)… · byte-for-byte"
        }
        if isVerifying {
            return "matching sha256 \(shortHash)…"
        }
        return "\(ByteFormat.string(flightRef.offer.sizeBytes)) · \(flightRef.offer.senderName)"
    }

    // MARK: Phase rail

    /// QUEUE · PREP · STREAM · VERIFY · SAVE.
    ///
    /// The rail used to sit on STREAM for anything that was not verifying or
    /// saving, which meant a queued or parked transfer claimed to be
    /// streaming while nothing was moving. Waiting now has its own segment,
    /// and a failure lands where it actually broke.
    private var railIndex: Int {
        if isDone { return 4 }
        guard let transfer = liveTransfer else { return 0 }
        switch transfer.phase {
        case .queued, .waitingForHost:   return 0
        case .preparing:                 return 1
        case .downloading, .interrupted: return 2
        case .failed:                    return transfer.bytesReceived > 0 ? 2 : 1
        case .verifying:                 return 3
        case .awaitingSaveChoice, .saving, .photosDenied: return 4
        }
    }

    // MARK: Meter

    private var meter: some View {
        VStack(spacing: 12) {
            HStack(alignment: .bottom, spacing: 3) {
                let peak = max(speedSamples.max() ?? 1, 1)
                ForEach(Array(speedSamples.enumerated()), id: \.offset) { _, value in
                    RoundedRectangle(cornerRadius: 2, style: .continuous)
                        .fill(Theme.irisSoft.opacity(0.75))
                        .frame(height: max(3, CGFloat(value / peak) * 34))
                        .frame(maxWidth: .infinity)
                }
            }
            .frame(height: 34)
            .animation(.linear(duration: 0.15), value: speedSamples)

            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("RATE")
                        .font(Theme.mono(10, .medium))
                        .tracking(1.4)
                        .foregroundColor(Theme.textFaint)
                    Text(ByteFormat.speed(liveTransfer?.speedBytesPerSecond ?? 0))
                        .font(Theme.mono(15, .medium))
                        .foregroundColor(Theme.textBase.opacity(0.93))
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 5) {
                    Text("ETA")
                        .font(Theme.mono(10, .medium))
                        .tracking(1.4)
                        .foregroundColor(Theme.textFaint)
                    Text(etaString)
                        .font(Theme.mono(15, .medium))
                        .foregroundColor(Theme.textBase.opacity(0.93))
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .glassRow(cornerRadius: 20, fillOpacity: 0.04, borderOpacity: 0.07)
    }

    private var etaString: String {
        guard let transfer = liveTransfer, transfer.phase == .downloading else { return "—" }
        return ByteFormat.eta(transfer.etaSeconds)
    }

    private func sampleSpeed() {
        guard let transfer = liveTransfer,
              transfer.phase == .downloading || transfer.phase == .preparing else { return }
        var samples = speedSamples
        samples.append(max(0, transfer.speedBytesPerSecond))
        if samples.count > 28 { samples.removeFirst(samples.count - 28) }
        speedSamples = samples
    }

    // MARK: Status text (errors, hints)

    @ViewBuilder
    private var statusBlock: some View {
        if let transfer = liveTransfer {
            // A restart back to 0% is legitimate but looks like a bug
            // without a word of explanation.
            if let note = transfer.note, !transfer.phase.isWaiting {
                infoCard(text: note, color: Theme.textSecondary)
            }
            switch transfer.phase {
            case .queued(let position):
                infoCard(text: position <= 1
                         ? "Next in line. Sendro receives \(TransferEngine.maxConcurrentDownloads) files at a time so each one lands at full speed — this starts the moment a slot frees."
                         : "Number \(position) in line. Sendro receives \(TransferEngine.maxConcurrentDownloads) files at a time, so this starts as soon as the ones ahead of it finish.",
                         color: Theme.textSecondary)
            case .waitingForHost(let reason, let seconds):
                infoCard(text: reason.waitingLine(hostName: flightRef.offer.senderName,
                                                  seconds: seconds),
                         color: Theme.warn)
            case .photosDenied:
                infoCard(text: "Photos access is off, so this can't reach your gallery. Allow access in Settings, then retry — or keep it in Files.",
                         color: Theme.warn)
            case .failed(let message, _):
                infoCard(text: message, color: Theme.danger)
            case .interrupted:
                infoCard(text: "Paused at \(ByteFormat.string(transfer.bytesReceived)). Resume to continue from where it stopped.",
                         color: Theme.textSecondary)
            default:
                EmptyView()
            }
        }
    }

    private func infoCard(text: String, color: Color) -> some View {
        Text(text)
            .font(Theme.sans(13))
            .foregroundColor(color)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .glassRow(cornerRadius: 16, fillOpacity: 0.04, borderOpacity: 0.07)
    }

    // MARK: Bottom controls

    @ViewBuilder
    private var bottomControls: some View {
        if isDone {
            HStack(spacing: 10) {
                if let request = completedPreview {
                    Button {
                        preview = request
                    } label: {
                        AccentPillLabel(title: "Preview", height: 50)
                    }
                    .buttonStyle(PressableButtonStyle())
                }

                Button {
                    dismiss()
                } label: {
                    Text("Done")
                        .font(Theme.sans(15, .semibold))
                        .foregroundColor(Theme.teal)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .padding(.horizontal, 8)
                        .frame(maxWidth: .infinity)
                        .frame(height: 50)
                        .background(RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .fill(Theme.teal.opacity(0.16)))
                        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .strokeBorder(Theme.teal.opacity(0.35), lineWidth: 0.5))
                }
                .buttonStyle(PressableButtonStyle())
            }
        } else if let transfer = liveTransfer {
            switch transfer.phase {
            case .queued, .preparing, .downloading, .verifying, .saving:
                Button {
                    engine.cancel(transferId: transfer.id)
                    dismiss()
                } label: {
                    GhostPillLabel(title: "Cancel transfer",
                                   textColor: Theme.danger.opacity(0.9),
                                   height: 50)
                }
                .buttonStyle(PressableButtonStyle())

            case .waitingForHost:
                HStack(spacing: 10) {
                    Button {
                        engine.resume(transferId: transfer.id)
                    } label: {
                        AccentPillLabel(title: "Try now", height: 50)
                    }
                    .buttonStyle(PressableButtonStyle())

                    Button {
                        engine.cancel(transferId: transfer.id)
                        dismiss()
                    } label: {
                        GhostPillLabel(title: "Cancel",
                                       textColor: Theme.danger.opacity(0.9),
                                       height: 50)
                    }
                    .buttonStyle(PressableButtonStyle())
                }

            case .awaitingSaveChoice:
                HStack(spacing: 10) {
                    Button {
                        engine.resolveSaveChoice(transferId: transfer.id, saveToPhotos: true)
                    } label: {
                        AccentPillLabel(title: "Photos", height: 50)
                    }
                    .buttonStyle(PressableButtonStyle())

                    Button {
                        engine.resolveSaveChoice(transferId: transfer.id, saveToPhotos: false)
                    } label: {
                        GhostPillLabel(title: "Files", height: 50)
                    }
                    .buttonStyle(PressableButtonStyle())
                }

            case .photosDenied:
                HStack(spacing: 10) {
                    Button {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    } label: {
                        AccentPillLabel(title: "Open Settings", color: Theme.warn, height: 50)
                    }
                    .buttonStyle(PressableButtonStyle())

                    Button {
                        engine.resolveSaveChoice(transferId: transfer.id, saveToPhotos: true)
                    } label: {
                        GhostPillLabel(title: "Retry", height: 50)
                    }
                    .buttonStyle(PressableButtonStyle())

                    Button {
                        engine.resolveSaveChoice(transferId: transfer.id, saveToPhotos: false)
                    } label: {
                        GhostPillLabel(title: "Files", height: 50)
                    }
                    .buttonStyle(PressableButtonStyle())
                }

            case .failed(_, let resumable):
                HStack(spacing: 10) {
                    Button {
                        engine.resume(transferId: transfer.id)
                    } label: {
                        AccentPillLabel(title: resumable ? "Resume" : "Retry", height: 50)
                    }
                    .buttonStyle(PressableButtonStyle())

                    Button {
                        engine.cancel(transferId: transfer.id)
                        dismiss()
                    } label: {
                        GhostPillLabel(title: "Remove",
                                       textColor: Theme.danger.opacity(0.9),
                                       height: 50)
                    }
                    .buttonStyle(PressableButtonStyle())
                }

            case .interrupted:
                HStack(spacing: 10) {
                    Button {
                        engine.resume(transferId: transfer.id)
                    } label: {
                        AccentPillLabel(title: "Resume", height: 50)
                    }
                    .buttonStyle(PressableButtonStyle())

                    Button {
                        engine.cancel(transferId: transfer.id)
                        dismiss()
                    } label: {
                        GhostPillLabel(title: "Remove",
                                       textColor: Theme.danger.opacity(0.9),
                                       height: 50)
                    }
                    .buttonStyle(PressableButtonStyle())
                }
            }
        } else {
            Color.clear.frame(height: 50)
        }
    }
}

// MARK: - Verify arc

/// Indeterminate teal arc that starts spinning the moment it appears.
struct VerifyArc: View {

    @State private var spin = false

    var body: some View {
        Circle()
            .trim(from: 0, to: 0.28)
            .stroke(Theme.teal, style: StrokeStyle(lineWidth: 10, lineCap: .round))
            .frame(width: 200, height: 200)
            .rotationEffect(.degrees(spin ? 270 : -90))
            .shadow(color: Theme.teal.opacity(0.7), radius: 12)
            .onAppear {
                withAnimation(.linear(duration: 1.0).repeatForever(autoreverses: false)) {
                    spin = true
                }
            }
    }
}

// MARK: - Phase rail

/// Queue · Prep · Stream · Verify · Save with a sliding iris highlight.
///
/// Queue is a first-class segment, not a gap: a transfer waiting for a slot
/// or for the host has to be somewhere on this rail, and it is not streaming.
struct PhaseRail: View {

    let index: Int
    private let labels = ["Queue", "Prep", "Stream", "Verify", "Save"]

    var body: some View {
        GeometryReader { geo in
            let innerWidth = geo.size.width - 10
            let segment = innerWidth / CGFloat(labels.count)
            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(Color.white.opacity(0.05))
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.08), lineWidth: 0.5)

                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Theme.iris.opacity(0.85))
                    .shadow(color: Theme.iris.opacity(0.45), radius: 9, x: 0, y: 4)
                    .frame(width: segment, height: 30)
                    .offset(x: 5 + segment * CGFloat(index), y: 5)
                    .animation(.spring(response: 0.42, dampingFraction: 0.8), value: index)

                HStack(spacing: 0) {
                    ForEach(labels, id: \.self) { label in
                        Text(label.uppercased())
                            .font(Theme.mono(10.5, .medium))
                            .tracking(0.8)
                            .foregroundColor(Color.white.opacity(0.85))
                            .lineLimit(1)
                            .minimumScaleFactor(0.65)
                            .frame(maxWidth: .infinity)
                    }
                }
                .padding(.horizontal, 5)
                .frame(height: 40)
            }
        }
        .frame(height: 40)
    }
}

// MARK: - Rising streams

/// Faint vertical light streams drifting upward behind the ring.
struct RisingStreams: View {

    @State private var animate = false

    var body: some View {
        GeometryReader { geo in
            ZStack {
                stream(x: geo.size.width * 0.22, height: 120, duration: 2.6, delay: 0)
                stream(x: geo.size.width * 0.50, height: 180, duration: 2.1, delay: 0.4)
                stream(x: geo.size.width * 0.74, height: 100, duration: 3.1, delay: 0.9)
            }
        }
        .allowsHitTesting(false)
        .onAppear { animate = true }
    }

    private func stream(x: CGFloat, height: CGFloat, duration: Double, delay: Double) -> some View {
        Capsule()
            .fill(LinearGradient(colors: [.clear,
                                          Theme.irisSoft.opacity(0.6),
                                          .clear],
                                 startPoint: .bottom, endPoint: .top))
            .frame(width: 2, height: height)
            .position(x: x, y: animate ? 160 : 620)
            .opacity(0.7)
            .animation(.linear(duration: duration)
                        .repeatForever(autoreverses: false)
                        .delay(delay),
                       value: animate)
    }
}
