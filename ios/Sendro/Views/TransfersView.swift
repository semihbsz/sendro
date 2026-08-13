//
//  TransfersView.swift
//  Sendro
//
//  Incoming offers (Accept / Reject), active transfers with live progress,
//  and local history.
//

import SwiftUI

struct TransfersView: View {

    @EnvironmentObject private var engine: TransferEngine
    @EnvironmentObject private var history: HistoryStore

    var body: some View {
        NavigationStack {
            List {
                if !engine.incomingOffers.isEmpty {
                    Section("Incoming") {
                        ForEach(engine.incomingOffers) { incoming in
                            IncomingOfferRow(incoming: incoming)
                        }
                    }
                }

                if !engine.active.isEmpty {
                    Section("Active") {
                        ForEach(engine.active) { transfer in
                            ActiveTransferRow(transfer: transfer)
                        }
                    }
                }

                historySection

                if engine.incomingOffers.isEmpty && engine.active.isEmpty && history.entries.isEmpty {
                    Section {
                        VStack(spacing: 10) {
                            Image(systemName: "tray.and.arrow.down")
                                .font(.system(size: 36))
                                .foregroundColor(.secondary)
                            Text("Nothing here yet")
                                .font(.headline)
                            Text("Send a file from Sendro on your PC and it will show up here.")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 24)
                    }
                    .listRowBackground(Color.clear)
                }
            }
            .navigationTitle("Transfers")
        }
    }

    @ViewBuilder
    private var historySection: some View {
        if !history.entries.isEmpty {
            Section {
                ForEach(history.entries) { entry in
                    HistoryRow(entry: entry)
                }
            } header: {
                HStack {
                    Text("History")
                    Spacer()
                    Button("Clear") {
                        history.clear()
                    }
                    .font(.caption)
                }
            }
        }
    }
}

// MARK: - Incoming offer

private struct IncomingOfferRow: View {

    @EnvironmentObject private var engine: TransferEngine
    let incoming: IncomingOffer

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                Image(systemName: FileIcon.symbol(forFileName: incoming.offer.fileName))
                    .font(.title2)
                    .foregroundColor(.accentColor)
                    .frame(width: 32)
                VStack(alignment: .leading, spacing: 2) {
                    Text(incoming.offer.fileName)
                        .font(.body.weight(.medium))
                        .lineLimit(2)
                    Text("\(ByteFormat.string(incoming.offer.sizeBytes)) · from \(incoming.offer.senderName)")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            HStack(spacing: 12) {
                Button {
                    engine.accept(incoming)
                } label: {
                    Text("Accept")
                        .fontWeight(.semibold)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)

                Button(role: .destructive) {
                    engine.reject(incoming)
                } label: {
                    Text("Reject")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(.vertical, 6)
    }
}

// MARK: - Active transfer

private struct ActiveTransferRow: View {

    @EnvironmentObject private var engine: TransferEngine
    let transfer: ActiveTransfer

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                Image(systemName: FileIcon.symbol(forFileName: transfer.offer.fileName))
                    .font(.title3)
                    .foregroundColor(.accentColor)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text(transfer.offer.fileName)
                        .font(.body.weight(.medium))
                        .lineLimit(2)
                    Text("\(ByteFormat.string(transfer.offer.sizeBytes)) · from \(transfer.offer.senderName)")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                Spacer()
                phaseBadge
            }

            switch transfer.phase {
            case .preparing, .downloading:
                ProgressView(value: transfer.fractionComplete)
                    .progressViewStyle(.linear)
                HStack {
                    Text("\(ByteFormat.string(transfer.bytesReceived)) of \(ByteFormat.string(transfer.offer.sizeBytes))")
                    Spacer()
                    Text(ByteFormat.speed(transfer.speedBytesPerSecond))
                    Text("· ETA \(ByteFormat.eta(transfer.etaSeconds))")
                }
                .font(.caption)
                .foregroundColor(.secondary)
                HStack {
                    Spacer()
                    Button(role: .destructive) {
                        engine.cancel(transferId: transfer.id)
                    } label: {
                        Label("Cancel", systemImage: "xmark")
                            .font(.caption)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }

            case .verifying:
                HStack(spacing: 8) {
                    ProgressView()
                    Text("Verifying SHA-256…")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

            case .saving:
                HStack(spacing: 8) {
                    ProgressView()
                    Text("Saving…")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

            case .awaitingSaveChoice:
                HStack(spacing: 12) {
                    Button {
                        engine.resolveSaveChoice(transferId: transfer.id, saveToPhotos: true)
                    } label: {
                        Label("Photos", systemImage: "photo.on.rectangle")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)

                    Button {
                        engine.resolveSaveChoice(transferId: transfer.id, saveToPhotos: false)
                    } label: {
                        Label("Files", systemImage: "folder")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }

            case .failed(let message, let resumable):
                Text(message)
                    .font(.caption)
                    .foregroundColor(.red)
                HStack(spacing: 12) {
                    Button {
                        engine.resume(transferId: transfer.id)
                    } label: {
                        Label(resumable ? "Resume" : "Retry", systemImage: "arrow.clockwise")
                            .font(.caption)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)

                    Button(role: .destructive) {
                        engine.cancel(transferId: transfer.id)
                    } label: {
                        Label("Remove", systemImage: "trash")
                            .font(.caption)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }

            case .interrupted:
                HStack {
                    Text("Paused at \(ByteFormat.string(transfer.bytesReceived))")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Spacer()
                    Button {
                        engine.resume(transferId: transfer.id)
                    } label: {
                        Label("Resume", systemImage: "arrow.clockwise")
                            .font(.caption)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)

                    Button(role: .destructive) {
                        engine.cancel(transferId: transfer.id)
                    } label: {
                        Image(systemName: "trash")
                            .font(.caption)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
            }
        }
        .padding(.vertical, 6)
    }

    private var phaseBadge: some View {
        Text(transfer.phase.label)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Capsule().fill(badgeColor.opacity(0.15)))
            .foregroundColor(badgeColor)
    }

    private var badgeColor: Color {
        switch transfer.phase {
        case .failed:      return .red
        case .interrupted: return .orange
        case .verifying, .saving: return .blue
        default:           return .accentColor
        }
    }
}

// MARK: - History

private struct HistoryRow: View {

    let entry: HistoryEntry

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: outcomeSymbol)
                .foregroundColor(outcomeColor)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.fileName)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
                Text(subtitle)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Spacer()
            if let savedTo = entry.savedTo {
                Image(systemName: savedTo == "photos" ? "photo.on.rectangle" : "folder")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 2)
    }

    private var subtitle: String {
        let date = Date(timeIntervalSince1970: TimeInterval(entry.dateMs) / 1000)
        let when = date.formatted(date: .abbreviated, time: .shortened)
        var text = "\(ByteFormat.string(entry.sizeBytes)) · \(entry.senderName) · \(when)"
        if let error = entry.errorMessage {
            text += " · \(error)"
        }
        return text
    }

    private var outcomeSymbol: String {
        switch entry.outcome {
        case "completed": return "checkmark.circle.fill"
        case "failed":    return "exclamationmark.triangle.fill"
        case "rejected":  return "hand.raised.fill"
        case "cancelled": return "xmark.circle.fill"
        default:           return "circle"
        }
    }

    private var outcomeColor: Color {
        switch entry.outcome {
        case "completed": return .green
        case "failed":    return .red
        default:           return .secondary
        }
    }
}

// MARK: - File icons

enum FileIcon {
    static func symbol(forFileName name: String) -> String {
        let ext = (name as NSString).pathExtension.lowercased()
        switch ext {
        case "jpg", "jpeg", "png", "gif", "heic", "heif", "webp", "tiff", "bmp", "dng", "raw", "cr2", "nef":
            return "photo"
        case "mp4", "mov", "m4v", "avi", "mkv", "webm", "mts", "m2ts":
            return "film"
        case "mp3", "wav", "m4a", "flac", "aac", "ogg", "aiff":
            return "music.note"
        case "zip", "rar", "7z", "tar", "gz":
            return "archivebox"
        case "pdf":
            return "doc.richtext"
        case "txt", "md", "rtf":
            return "doc.text"
        default:
            return "doc"
        }
    }
}
