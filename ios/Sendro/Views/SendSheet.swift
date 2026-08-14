//
//  SendSheet.swift
//  Sendro
//
//  iPhone → PC send flow: pick a target (auto-selects the single online
//  paired host), add Photos or Files (original bytes), then watch the
//  outgoing queue — per-file progress, speed, ETA, cancel, retry, and the
//  "Landed on <PC>" done state. Backed by UploadEngine.
//

import SwiftUI

struct SendSheet: View {

    @EnvironmentObject private var uploader: UploadEngine
    @EnvironmentObject private var pairedHosts: PairedHostStore
    @EnvironmentObject private var engine: TransferEngine
    @Environment(\.dismiss) private var dismiss

    @State private var selectedHostId: String?
    @State private var showPhotoPicker = false
    @State private var showDocPicker = false
    @State private var pickFailures: [String] = []

    // MARK: Target resolution

    private var onlineHosts: [PairedHost] {
        pairedHosts.hosts.filter { engine.hostOnline[$0.deviceId] == true }
    }

    /// Explicit selection if still online, else the single sensible default.
    private var targetHost: PairedHost? {
        if let id = selectedHostId,
           let host = pairedHosts.host(id: id),
           engine.hostOnline[host.deviceId] == true {
            return host
        }
        return onlineHosts.first
    }

    var body: some View {
        ZStack(alignment: .top) {
            LinearGradient(colors: [Color(red: 0x1C / 255, green: 0x1E / 255, blue: 0x2C / 255),
                                    Color(red: 0x0E / 255, green: 0x0F / 255, blue: 0x16 / 255)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Send to PC")
                        .font(Theme.sans(24, .semibold))
                        .foregroundColor(Theme.textPrimary)

                    Text("Original bytes, straight over your Wi-Fi. The PC verifies every file with SHA-256 as it lands.")
                        .font(Theme.sans(13))
                        .foregroundColor(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 8)

                    if pairedHosts.hosts.isEmpty {
                        emptyPairCard
                            .padding(.top, 20)
                    } else {
                        targetSection
                            .padding(.top, 22)
                        addSection
                            .padding(.top, 22)
                    }

                    if !pickFailures.isEmpty {
                        failuresCard
                            .padding(.top, 16)
                    }

                    if !uploader.items.isEmpty {
                        queueSection
                            .padding(.top, 26)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 24)
                .padding(.bottom, 40)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .scrollIndicators(.hidden)
        }
        .preferredColorScheme(.dark)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
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
    }

    // MARK: No paired PC

    private var emptyPairCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("No paired PC yet")
                .font(Theme.sans(16, .semibold))
                .foregroundColor(Theme.textPrimary)
            Text("Pair a computer first — tap the device chip on the home screen while Sendro is open on your PC.")
                .font(Theme.sans(13))
                .foregroundColor(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .glassRow(cornerRadius: 20, fillOpacity: 0.05, borderOpacity: 0.09)
    }

    // MARK: Target

    private var targetSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionTag(text: "To")
                .padding(.leading, 2)

            VStack(spacing: 8) {
                ForEach(pairedHosts.hosts) { host in
                    hostRow(host)
                }
            }

            if onlineHosts.isEmpty {
                Text("No PC online — open Sendro on your computer and it will light up here.")
                    .font(Theme.sans(12))
                    .foregroundColor(Theme.warn)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 2)
            }
        }
    }

    private func hostRow(_ host: PairedHost) -> some View {
        let online = engine.hostOnline[host.deviceId] == true
        let isTarget = targetHost?.deviceId == host.deviceId
        return Button {
            selectedHostId = host.deviceId
        } label: {
            HStack(spacing: 12) {
                PulseDot(color: Theme.teal, active: online, side: 8)
                VStack(alignment: .leading, spacing: 2) {
                    Text(host.name)
                        .font(Theme.sans(15, .semibold))
                        .foregroundColor(online ? Theme.textPrimary : Theme.textBase.opacity(0.45))
                        .lineLimit(1)
                    Text(online ? "\(host.lastHost):\(String(host.lastPort))" : "offline")
                        .font(Theme.mono(10.5))
                        .foregroundColor(Theme.textTertiary)
                        .lineLimit(1)
                }
                Spacer()
                if isTarget {
                    Image(systemName: "checkmark")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(Theme.irisSoft)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .glassRow(cornerRadius: 18,
                      fillOpacity: isTarget ? 0.08 : 0.045,
                      borderOpacity: isTarget ? 0.2 : 0.08)
        }
        .buttonStyle(PressableButtonStyle())
        .disabled(!online)
    }

    // MARK: Add

    private var addSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionTag(text: "Add")
                .padding(.leading, 2)

            HStack(spacing: 10) {
                Button {
                    showPhotoPicker = true
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "photo.on.rectangle")
                            .font(.system(size: 14, weight: .semibold))
                        Text("Photos")
                            .font(Theme.sans(15, .semibold))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    .foregroundColor(Theme.onAccent)
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
                    .background(RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(Theme.iris))
                    .shadow(color: Theme.iris.opacity(0.4), radius: 14, x: 0, y: 8)
                }
                .buttonStyle(PressableButtonStyle())

                Button {
                    showDocPicker = true
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "folder")
                            .font(.system(size: 14, weight: .medium))
                        Text("Files")
                            .font(Theme.sans(15, .medium))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    .foregroundColor(Theme.textBase.opacity(0.8))
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
                    .glassRow(cornerRadius: 16, fillOpacity: 0.07, borderOpacity: 0.1)
                }
                .buttonStyle(PressableButtonStyle())
            }
            .disabled(targetHost == nil)
            .opacity(targetHost == nil ? 0.45 : 1)

            Text("Photos and videos are sent exactly as shot — HEIC and ProRes stay untouched.")
                .font(Theme.sans(12))
                .foregroundColor(Theme.textFaint)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 2)
        }
    }

    // MARK: Picker failures

    private var failuresCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(Theme.warn)
                Text("Some items couldn't be added")
                    .font(Theme.sans(13, .semibold))
                    .foregroundColor(Theme.warn)
                Spacer()
                Button {
                    pickFailures = []
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(Theme.textBase.opacity(0.5))
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

    // MARK: Queue

    private var queueSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                SectionTag(text: "Outgoing")
                    .padding(.leading, 2)
                Spacer()
                if uploader.hasFinished {
                    Button {
                        uploader.clearFinished()
                    } label: {
                        Text("Clear done")
                            .font(Theme.sans(12, .medium))
                            .foregroundColor(Theme.irisSoft)
                    }
                    .buttonStyle(PressableButtonStyle())
                }
            }

            VStack(spacing: 8) {
                ForEach(uploader.items) { item in
                    uploadRow(item)
                }
            }
        }
    }

    private func uploadRow(_ item: UploadItem) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 12) {
                FileBadge(fileName: item.fileName, side: 36, cornerRadius: 11)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.fileName)
                        .font(Theme.sans(14, .medium))
                        .foregroundColor(Theme.textBase.opacity(0.93))
                        .lineLimit(1)
                        .truncationMode(.middle)
                    statusLine(item)
                }
                Spacer()
                trailingControl(item)
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
    private func statusLine(_ item: UploadItem) -> some View {
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
            Text(doneLine(item))
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
    private func doneLine(_ item: UploadItem) -> String {
        var line = "Landed on \(item.hostName)"
        if let path = item.savedPath, !path.isEmpty,
           let base = path.split(whereSeparator: { $0 == "\\" || $0 == "/" }).last {
            line += " · \(String(base))"
        }
        return line
    }

    @ViewBuilder
    private func trailingControl(_ item: UploadItem) -> some View {
        switch item.phase {
        case .queued, .hashing, .uploading:
            Button {
                uploader.cancel(itemId: item.id)
            } label: {
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
                Button {
                    uploader.retry(itemId: item.id)
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(Theme.irisSoft)
                        .frame(width: 30, height: 30)
                        .glassRow(cornerRadius: 15, fillOpacity: 0.06, borderOpacity: 0.1)
                }
                .buttonStyle(PressableButtonStyle())
                .accessibilityLabel("Retry upload")

                Button {
                    uploader.cancel(itemId: item.id)
                } label: {
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

    // MARK: Picker completion

    private func handlePicked(_ picked: PickedFiles) {
        // The pickers dismiss themselves UIKit-side; make sure the bindings
        // agree so the sheets can be presented again.
        showPhotoPicker = false
        showDocPicker = false
        pickFailures = picked.failures
        guard !picked.urls.isEmpty else { return }
        guard let host = targetHost else {
            // Target went offline while picking — drop the staged copies.
            for url in picked.urls {
                SendStaging.remove(url)
            }
            pickFailures.append("The PC went offline — nothing was queued.")
            return
        }
        uploader.enqueue(fileURLs: picked.urls, hostId: host.deviceId, hostName: host.name)
    }
}
