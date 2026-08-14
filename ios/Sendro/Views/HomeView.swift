//
//  HomeView.swift
//  Sendro
//
//  The receive surface. An incoming file becomes the screen: offer cards up
//  top, otherwise the breathing "listening" radar. Active transfers show as
//  tappable rows (opening the Flight screen), recent history sits at the
//  bottom, and the device chip in the header opens the Devices sheet.
//

import SwiftUI
import UIKit

struct HomeView: View {

    @EnvironmentObject private var engine: TransferEngine
    @EnvironmentObject private var discovery: DiscoveryService
    @EnvironmentObject private var pairedHosts: PairedHostStore
    @EnvironmentObject private var history: HistoryStore

    let openDevices: () -> Void
    let openSettings: () -> Void
    let openSend: () -> Void
    let openFlight: (FlightRef) -> Void
    let goLibrary: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            header
                .padding(.horizontal, 20)
                .padding(.top, 8)

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if discovery.status == .permissionDenied {
                        permissionCard
                            .padding(.top, 24)
                    }

                    if !engine.incomingOffers.isEmpty {
                        incomingSection
                            .padding(.top, 26)
                    } else if engine.active.isEmpty {
                        listeningSection
                            .padding(.top, 30)
                    }

                    sendRow
                        .padding(.top, 26)

                    if !engine.active.isEmpty {
                        activeSection
                            .padding(.top, 26)
                    }

                    recentSection
                        .padding(.top, 34)
                        .padding(.bottom, 130)
                }
                .padding(.horizontal, 20)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .scrollIndicators(.hidden)
        }
    }

    // MARK: Header

    private var primaryHost: PairedHost? {
        // Prefer an online host; fall back to the first paired one.
        pairedHosts.hosts.first { engine.hostOnline[$0.deviceId] == true }
            ?? pairedHosts.hosts.first
    }

    private var header: some View {
        HStack(spacing: 10) {
            BeamMark(side: 28)

            Button(action: openSettings) {
                Image(systemName: "gearshape")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(Theme.textBase.opacity(0.6))
                    .frame(width: 32, height: 32)
                    .glassRow(cornerRadius: 16, fillOpacity: 0.06, borderOpacity: 0.12)
            }
            .buttonStyle(PressableButtonStyle())
            .accessibilityLabel("Settings")

            Spacer()

            Button(action: openDevices) {
                HStack(spacing: 8) {
                    if let host = primaryHost {
                        PulseDot(color: Theme.teal,
                                 active: engine.hostOnline[host.deviceId] == true)
                        Text(host.name.uppercased())
                            .font(Theme.mono(11.5, .medium))
                            .foregroundColor(Theme.textBase.opacity(0.8))
                            .lineLimit(1)
                    } else {
                        Circle()
                            .fill(Theme.iris)
                            .frame(width: 7, height: 7)
                        Text("PAIR A PC")
                            .font(Theme.mono(11.5, .medium))
                            .foregroundColor(Theme.irisSoft)
                    }
                    Image(systemName: "chevron.right")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundColor(Theme.textBase.opacity(0.5))
                }
                .padding(.leading, 11)
                .padding(.trailing, 12)
                .frame(height: 32)
                .glassRow(cornerRadius: 16, fillOpacity: 0.06, borderOpacity: 0.12)
            }
            .buttonStyle(PressableButtonStyle())
            .accessibilityLabel("Devices")
        }
    }

    // MARK: Local network permission

    private var permissionCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "wifi.exclamationmark")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(Theme.warn)
                Text("Local Network access needed")
                    .font(Theme.sans(15, .semibold))
                    .foregroundColor(Theme.textPrimary)
            }
            Text("Sendro can't see your PC because Local Network access is turned off. Enable it under Settings › Apps › Sendro › Local Network.")
                .font(Theme.sans(13))
                .foregroundColor(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Button {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            } label: {
                Text("Open Settings")
                    .font(Theme.sans(13, .semibold))
                    .foregroundColor(Theme.onAccent)
                    .padding(.horizontal, 16)
                    .frame(height: 34)
                    .background(RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(Theme.warn))
            }
            .buttonStyle(PressableButtonStyle())
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard(cornerRadius: 22)
    }

    // MARK: Incoming offers

    private var incomingSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            SectionTag(text: "Incoming", color: Theme.irisSoft)
                .padding(.leading, 2)

            ForEach(engine.incomingOffers) { incoming in
                offerCard(incoming)
            }
        }
    }

    private func offerCard(_ incoming: IncomingOffer) -> some View {
        VStack(spacing: 20) {
            HStack(spacing: 14) {
                FileBadge(fileName: incoming.offer.fileName, side: 52, cornerRadius: 16)
                VStack(alignment: .leading, spacing: 4) {
                    Text(incoming.offer.fileName)
                        .font(Theme.sans(19, .semibold))
                        .foregroundColor(Theme.textPrimary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Text("\(ByteFormat.string(incoming.offer.sizeBytes)) · \(incoming.offer.senderName)")
                        .font(Theme.mono(11.5))
                        .foregroundColor(Theme.textBase.opacity(0.5))
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }

            HStack(spacing: 10) {
                Button {
                    let ref = FlightRef(offer: incoming.offer, hostId: incoming.hostId)
                    engine.accept(incoming)
                    openFlight(ref)
                } label: {
                    AccentPillLabel(title: "Accept")
                }
                .buttonStyle(PressableButtonStyle())

                Button {
                    engine.reject(incoming)
                } label: {
                    GhostPillLabel(title: "Decline")
                        .frame(width: 104)
                }
                .buttonStyle(PressableButtonStyle())
            }
        }
        .padding(22)
        .glassCard(cornerRadius: 26)
        .transition(.scale(scale: 0.92).combined(with: .opacity))
    }

    // MARK: Listening radar

    private var listeningSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            ListeningRadar()
                .frame(maxWidth: .infinity)
                .frame(height: 180)

            Text(listeningTitle)
                .font(Theme.sans(30, .semibold))
                .foregroundColor(Theme.textPrimary)
                .lineSpacing(2)
                .padding(.top, 6)

            Text(listeningSubtitle)
                .font(Theme.sans(14))
                .foregroundColor(Theme.textBase.opacity(0.5))
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: 300, alignment: .leading)
                .padding(.top, 12)
        }
    }

    private var listeningTitle: String {
        if pairedHosts.hosts.isEmpty {
            return "Pair your PC\nto start"
        }
        return "Listening\non your Wi-Fi"
    }

    private var listeningSubtitle: String {
        guard let host = primaryHost else {
            return "Open Sendro on your computer, then tap the device chip above to pair. Files land here at full size, verified byte for byte."
        }
        if engine.hostOnline[host.deviceId] == true {
            return "\(host.name) is online. Anything you send lands here at full size, verified byte for byte."
        }
        return "\(host.name) looks offline right now. Open Sendro on your PC on this Wi-Fi and it will reconnect by itself."
    }

    // MARK: Send to PC

    /// Entry point for the reverse direction (§7 upload). Enabled only when
    /// a paired PC is online; otherwise shows why it's disabled.
    private var sendRow: some View {
        let online = engine.hostOnline.values.contains(true)
        return Button(action: openSend) {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(online ? Theme.iris.opacity(0.16) : Color.white.opacity(0.05))
                    Image(systemName: "arrow.up")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(online ? Theme.irisSoft : Theme.textBase.opacity(0.35))
                }
                .frame(width: 38, height: 38)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Send to PC")
                        .font(Theme.sans(15, .semibold))
                        .foregroundColor(online ? Theme.textPrimary : Theme.textBase.opacity(0.5))
                    Text(online
                         ? "Photos, videos or files — original bytes, verified."
                         : "No PC online — open Sendro on your computer.")
                        .font(Theme.mono(10.5))
                        .foregroundColor(Theme.textTertiary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(Theme.textBase.opacity(online ? 0.5 : 0.25))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .glassRow(cornerRadius: 18,
                      fillOpacity: online ? 0.06 : 0.035,
                      borderOpacity: online ? 0.1 : 0.06)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressableButtonStyle())
        .disabled(!online)
    }

    // MARK: Active transfers

    private var activeSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionTag(text: "In flight")
                .padding(.leading, 2)

            VStack(spacing: 8) {
                ForEach(engine.active) { transfer in
                    Button {
                        openFlight(FlightRef(offer: transfer.offer, hostId: transfer.hostId))
                    } label: {
                        activeRow(transfer)
                    }
                    .buttonStyle(PressableButtonStyle())
                }
            }
        }
    }

    private func activeRow(_ transfer: ActiveTransfer) -> some View {
        VStack(spacing: 10) {
            HStack(spacing: 12) {
                FileBadge(fileName: transfer.offer.fileName, side: 36, cornerRadius: 11)
                VStack(alignment: .leading, spacing: 2) {
                    Text(transfer.offer.fileName)
                        .font(Theme.sans(14, .medium))
                        .foregroundColor(Theme.textBase.opacity(0.93))
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Text("\(ByteFormat.string(transfer.bytesReceived)) / \(ByteFormat.string(transfer.offer.sizeBytes))")
                        .font(Theme.mono(10.5))
                        .foregroundColor(Theme.textTertiary)
                }
                Spacer()
                phaseChip(for: transfer.phase)
            }

            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(Color.white.opacity(0.08))
                    Capsule()
                        .fill(phaseColor(for: transfer.phase))
                        .frame(width: max(4, geo.size.width * transfer.fractionComplete))
                }
            }
            .frame(height: 3)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .glassRow(cornerRadius: 18)
        .contentShape(Rectangle())
    }

    private func phaseChip(for phase: TransferPhase) -> some View {
        let color = phaseColor(for: phase)
        return Text(shortLabel(for: phase).uppercased())
            .font(Theme.mono(9, .medium))
            .tracking(0.8)
            .foregroundColor(color)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(Capsule().fill(color.opacity(0.13)))
    }

    private func phaseColor(for phase: TransferPhase) -> Color {
        switch phase {
        case .preparing, .downloading:   return Theme.iris
        case .verifying, .saving:        return Theme.teal
        case .awaitingSaveChoice:        return Theme.irisSoft
        case .photosDenied, .interrupted: return Theme.warn
        case .failed:                    return Theme.danger
        }
    }

    private func shortLabel(for phase: TransferPhase) -> String {
        switch phase {
        case .preparing:         return "Prep"
        case .downloading:       return "Receiving"
        case .verifying:         return "Verifying"
        case .awaitingSaveChoice: return "Choose"
        case .saving:            return "Saving"
        case .photosDenied:      return "Photos?"
        case .failed:            return "Failed"
        case .interrupted:       return "Paused"
        }
    }

    // MARK: Recent

    private var recentSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                SectionTag(text: "Recent", color: Theme.textBase.opacity(0.4))
                Spacer()
                Button(action: goLibrary) {
                    Text("All")
                        .font(Theme.sans(12, .medium))
                        .foregroundColor(Theme.irisSoft)
                }
                .buttonStyle(PressableButtonStyle())
            }
            .padding(.horizontal, 2)

            if history.entries.isEmpty {
                Text("Nothing received yet. Send a file from Sendro on your PC and it will land here.")
                    .font(Theme.sans(13))
                    .foregroundColor(Theme.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 2)
            } else {
                VStack(spacing: 8) {
                    ForEach(history.entries.prefix(3)) { entry in
                        recentRow(entry)
                    }
                }
            }
        }
    }

    private func recentRow(_ entry: HistoryEntry) -> some View {
        HStack(spacing: 12) {
            FileBadge(fileName: entry.fileName, side: 36, cornerRadius: 11)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.fileName)
                    .font(Theme.sans(14, .medium))
                    .foregroundColor(Theme.textBase.opacity(0.93))
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text("\(ByteFormat.string(entry.sizeBytes)) · \(entry.senderName) · \(Self.timeString(entry.dateMs))")
                    .font(Theme.mono(10.5))
                    .foregroundColor(Theme.textTertiary)
                    .lineLimit(1)
            }
            Spacer()
            outcomeMark(entry)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .glassRow(cornerRadius: 18)
    }

    @ViewBuilder
    private func outcomeMark(_ entry: HistoryEntry) -> some View {
        switch entry.outcome {
        case "completed":
            Image(systemName: entry.direction == "outgoing" ? "arrow.up" : "checkmark")
                .font(.system(size: 13, weight: .bold))
                .foregroundColor(Theme.teal)
        case "failed":
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(Theme.danger)
        default:
            Image(systemName: "xmark")
                .font(.system(size: 11, weight: .bold))
                .foregroundColor(Theme.textBase.opacity(0.35))
        }
    }

    static func timeString(_ dateMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(dateMs) / 1000)
        if Calendar.current.isDateInToday(date) {
            return date.formatted(date: .omitted, time: .shortened)
        }
        return date.formatted(.dateTime.month(.abbreviated).day())
    }
}

// MARK: - Listening radar

/// Two breathing rings around a glowing teal core.
struct ListeningRadar: View {

    @State private var breathe = false

    var body: some View {
        ZStack {
            Circle()
                .strokeBorder(Color.white.opacity(0.07), lineWidth: 1)
                .frame(width: 150, height: 150)
                .scaleEffect(breathe ? 1.06 : 1.0)
                .opacity(breathe ? 0.75 : 0.35)
            Circle()
                .strokeBorder(Theme.iris.opacity(0.28), lineWidth: 1)
                .frame(width: 96, height: 96)
                .scaleEffect(breathe ? 1.0 : 1.06)
                .opacity(breathe ? 0.35 : 0.75)
            Circle()
                .fill(Theme.teal)
                .frame(width: 12, height: 12)
                .shadow(color: Theme.teal.opacity(0.9), radius: 12)
        }
        .onAppear {
            withAnimation(.easeInOut(duration: 4.5).repeatForever(autoreverses: true)) {
                breathe = true
            }
        }
    }
}
