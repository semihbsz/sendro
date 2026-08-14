//
//  DevicesSheet.swift
//  Sendro
//
//  Bottom sheet behind the header device chip: paired computers (online
//  state, unpair), nearby Bonjour hosts (resolve / pair / version mismatch),
//  local-network permission explainer, manual connect by IP, and the
//  6-digit pairing flow.
//

import SwiftUI
import UIKit

/// A host the user is about to pair with (from discovery or manual entry).
struct PairTarget: Identifiable, Equatable {
    let name: String
    let host: String
    let port: UInt16

    var id: String { "\(host):\(port)" }
}

struct DevicesSheet: View {

    enum Stage {
        case list
        case manual
        case pairing(PairTarget)
    }

    @EnvironmentObject private var discovery: DiscoveryService
    @EnvironmentObject private var pairedHosts: PairedHostStore
    @EnvironmentObject private var engine: TransferEngine
    @Environment(\.dismiss) private var dismiss

    @State private var stage: Stage = .list
    @State private var unpairCandidate: PairedHost?

    var body: some View {
        ZStack(alignment: .top) {
            LinearGradient(colors: [Color(red: 0x1C / 255, green: 0x1E / 255, blue: 0x2C / 255),
                                    Color(red: 0x0E / 255, green: 0x0F / 255, blue: 0x16 / 255)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()

            ScrollView {
                Group {
                    switch stage {
                    case .list:
                        listStage
                    case .manual:
                        ManualConnectPane(
                            onBack: { withAnimation(.easeOut(duration: 0.2)) { stage = .list } },
                            onFound: { target in
                                withAnimation(.easeOut(duration: 0.2)) { stage = .pairing(target) }
                            })
                    case .pairing(let target):
                        PairingPane(target: target,
                                    onFinished: { dismiss() },
                                    onCancel: { withAnimation(.easeOut(duration: 0.2)) { stage = .list } })
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 24)
                .padding(.bottom, 40)
            }
            .scrollIndicators(.hidden)
        }
        .preferredColorScheme(.dark)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .confirmationDialog("Unpair this computer?",
                            isPresented: Binding(
                                get: { unpairCandidate != nil },
                                set: { if !$0 { unpairCandidate = nil } }),
                            titleVisibility: .visible) {
            Button("Unpair", role: .destructive) {
                if let host = unpairCandidate {
                    engine.unpair(hostId: host.deviceId)
                }
                unpairCandidate = nil
            }
            Button("Cancel", role: .cancel) {
                unpairCandidate = nil
            }
        }
    }

    // MARK: List stage

    private var listStage: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Devices")
                .font(Theme.sans(24, .semibold))
                .foregroundColor(Theme.textPrimary)

            if discovery.status == .permissionDenied {
                permissionCard
                    .padding(.top, 16)
            }

            if !pairedHosts.hosts.isEmpty {
                VStack(spacing: 10) {
                    ForEach(pairedHosts.hosts) { host in
                        pairedRow(host)
                    }
                }
                .padding(.top, 18)
            }

            SectionTag(text: "Nearby")
                .padding(.top, 24)
                .padding(.leading, 2)

            nearbyList
                .padding(.top, 12)

            Button {
                withAnimation(.easeOut(duration: 0.2)) { stage = .manual }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "number")
                        .font(.system(size: 12, weight: .semibold))
                    Text("Connect by IP address")
                        .font(Theme.sans(14, .medium))
                }
                .foregroundColor(Theme.irisSoft)
                .frame(maxWidth: .infinity)
                .frame(height: 46)
                .glassRow(cornerRadius: 16, fillOpacity: 0.05, borderOpacity: 0.09)
            }
            .buttonStyle(PressableButtonStyle())
            .padding(.top, 16)

            Text("Found over Bonjour. If discovery is blocked, connect by IP address — the Sendro window on your PC shows it.")
                .font(Theme.sans(12))
                .foregroundColor(Theme.textFaint)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 14)
        }
    }

    private var permissionCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "wifi.exclamationmark")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(Theme.warn)
                Text("Local Network access is off")
                    .font(Theme.sans(14, .semibold))
                    .foregroundColor(Theme.textPrimary)
            }
            Text("Discovery is blocked. Enable it under Settings › Apps › Sendro › Local Network, or connect by IP below.")
                .font(Theme.sans(12.5))
                .foregroundColor(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Button {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            } label: {
                Text("Open Settings")
                    .font(Theme.sans(12.5, .semibold))
                    .foregroundColor(Theme.onAccent)
                    .padding(.horizontal, 14)
                    .frame(height: 32)
                    .background(RoundedRectangle(cornerRadius: 11, style: .continuous)
                        .fill(Theme.warn))
            }
            .buttonStyle(PressableButtonStyle())
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassRow(cornerRadius: 20, fillOpacity: 0.06, borderOpacity: 0.1)
    }

    private func pairedRow(_ host: PairedHost) -> some View {
        let online = engine.hostOnline[host.deviceId] == true
        return HStack(spacing: 13) {
            ZStack {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(Theme.teal.opacity(online ? 0.14 : 0.06))
                PulseDot(color: Theme.teal, active: online, side: 9)
            }
            .frame(width: 42, height: 42)

            VStack(alignment: .leading, spacing: 3) {
                Text(host.name)
                    .font(Theme.sans(16, .semibold))
                    .foregroundColor(Theme.textBase.opacity(0.95))
                    .lineLimit(1)
                Text("\(host.lastHost):\(String(host.lastPort)) · \(online ? "online" : "offline")")
                    .font(Theme.mono(10.5))
                    .foregroundColor(Theme.textBase.opacity(0.45))
                    .lineLimit(1)
            }
            Spacer()
            Text("PAIRED")
                .font(Theme.mono(10, .medium))
                .tracking(1.0)
                .foregroundColor(online ? Theme.teal : Theme.textBase.opacity(0.4))
        }
        .padding(16)
        .glassRow(cornerRadius: 22, fillOpacity: 0.06, borderOpacity: 0.1)
        .contextMenu {
            Button(role: .destructive) {
                unpairCandidate = host
            } label: {
                Label("Unpair", systemImage: "trash")
            }
        }
    }

    @ViewBuilder
    private var nearbyList: some View {
        let unpaired = discovery.hosts.filter { pairedHosts.host(id: $0.deviceId) == nil }
        if unpaired.isEmpty {
            HStack(spacing: 10) {
                if discovery.status == .browsing {
                    ProgressView()
                        .tint(Theme.textBase.opacity(0.6))
                    Text("Looking for Sendro on your Wi-Fi…")
                        .font(Theme.sans(13.5))
                        .foregroundColor(Theme.textSecondary)
                } else {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 13))
                        .foregroundColor(Theme.textSecondary)
                    Text("No computers found yet.")
                        .font(Theme.sans(13.5))
                        .foregroundColor(Theme.textSecondary)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .glassRow(cornerRadius: 20, fillOpacity: 0.04, borderOpacity: 0.08)
        } else {
            VStack(spacing: 9) {
                ForEach(unpaired) { host in
                    nearbyRow(host)
                }
            }
        }
    }

    private func nearbyRow(_ host: DiscoveredHost) -> some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .fill(Color.white.opacity(0.06))
                Circle()
                    .fill(Theme.textBase.opacity(0.3))
                    .frame(width: 8, height: 8)
            }
            .frame(width: 38, height: 38)

            VStack(alignment: .leading, spacing: 2) {
                Text(host.name)
                    .font(Theme.sans(15, .medium))
                    .foregroundColor(Theme.textBase.opacity(0.93))
                    .lineLimit(1)
                if let ip = host.ipAddress, let port = host.port {
                    Text("\(ip):\(String(port))")
                        .font(Theme.mono(10.5))
                        .foregroundColor(Theme.textTertiary)
                        .lineLimit(1)
                } else {
                    Text("Resolving…")
                        .font(Theme.mono(10.5))
                        .foregroundColor(Theme.textTertiary)
                }
            }
            Spacer()

            if host.protocolVersion != sendroProtocolVersion {
                Text("UPDATE SENDRO")
                    .font(Theme.mono(9.5, .medium))
                    .tracking(0.8)
                    .foregroundColor(Theme.warn)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 5)
                    .background(Capsule().fill(Theme.warn.opacity(0.13)))
            } else if let ip = host.ipAddress, let port = host.port {
                Button {
                    withAnimation(.easeOut(duration: 0.2)) {
                        stage = .pairing(PairTarget(name: host.name, host: ip, port: port))
                    }
                } label: {
                    Text("Pair")
                        .font(Theme.sans(13, .semibold))
                        .foregroundColor(Theme.onAccent)
                        .padding(.horizontal, 16)
                        .frame(height: 34)
                        .background(RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(Theme.iris))
                }
                .buttonStyle(PressableButtonStyle())
            } else {
                ProgressView()
                    .tint(Theme.textBase.opacity(0.5))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .glassRow(cornerRadius: 20, fillOpacity: 0.04, borderOpacity: 0.08)
    }
}

// MARK: - Manual connect

struct ManualConnectPane: View {

    let onBack: () -> Void
    let onFound: (PairTarget) -> Void

    @State private var ipText = ""
    @State private var portText = "48800"
    @State private var checking = false
    @State private var errorText: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                Button(action: onBack) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(Theme.textBase.opacity(0.8))
                        .frame(width: 32, height: 32)
                        .glassRow(cornerRadius: 12, fillOpacity: 0.07, borderOpacity: 0.1)
                }
                .buttonStyle(PressableButtonStyle())
                .accessibilityLabel("Back")

                Text("Connect by IP")
                    .font(Theme.sans(22, .semibold))
                    .foregroundColor(Theme.textPrimary)
            }

            Text("The Sendro window on your PC shows its address and port.")
                .font(Theme.sans(13))
                .foregroundColor(Theme.textSecondary)
                .padding(.top, 8)

            VStack(spacing: 10) {
                fieldRow(placeholder: "IP address (e.g. 192.168.1.20)",
                         text: $ipText,
                         keyboard: .numbersAndPunctuation)
                fieldRow(placeholder: "Port",
                         text: $portText,
                         keyboard: .numberPad)
            }
            .padding(.top, 20)

            if let errorText {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.system(size: 12, weight: .semibold))
                    Text(errorText)
                        .font(Theme.sans(12.5))
                        .fixedSize(horizontal: false, vertical: true)
                }
                .foregroundColor(Theme.danger)
                .padding(.top, 14)
            }

            Button {
                Task { await connect() }
            } label: {
                ZStack {
                    if checking {
                        HStack(spacing: 10) {
                            ProgressView()
                                .tint(Theme.onAccent)
                            Text("Checking…")
                                .font(Theme.sans(15.5, .semibold))
                                .foregroundColor(Theme.onAccent)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                        .background(RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .fill(Theme.iris.opacity(0.6)))
                    } else {
                        AccentPillLabel(title: "Connect")
                    }
                }
            }
            .buttonStyle(PressableButtonStyle())
            .disabled(checking || ipText.trimmingCharacters(in: .whitespaces).isEmpty)
            .opacity(ipText.trimmingCharacters(in: .whitespaces).isEmpty ? 0.5 : 1)
            .padding(.top, 20)
        }
    }

    private func fieldRow(placeholder: String,
                          text: Binding<String>,
                          keyboard: UIKeyboardType) -> some View {
        TextField("", text: text, prompt: Text(placeholder)
            .foregroundColor(Theme.textBase.opacity(0.35)))
            .font(Theme.mono(14))
            .foregroundColor(Theme.textPrimary)
            .keyboardType(keyboard)
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
            .padding(.horizontal, 16)
            .frame(height: 50)
            .glassRow(cornerRadius: 16, fillOpacity: 0.06, borderOpacity: 0.1)
    }

    @MainActor
    private func connect() async {
        errorText = nil
        let host = ipText.trimmingCharacters(in: .whitespaces)
        guard let portValue = UInt16(portText.trimmingCharacters(in: .whitespaces)), portValue > 0 else {
            errorText = "Enter a valid port (1–65535)."
            return
        }
        guard let client = SendroClient(host: host, port: portValue) else {
            errorText = "That address doesn't look valid."
            return
        }
        checking = true
        defer { checking = false }
        do {
            let info = try await client.info()
            guard info.app == "sendro" else {
                errorText = "That address answered, but it isn't Sendro."
                return
            }
            guard info.protocolVersion == sendroProtocolVersion else {
                errorText = "Protocol mismatch — update Sendro on this iPhone."
                return
            }
            onFound(PairTarget(name: info.deviceName, host: host, port: portValue))
        } catch {
            errorText = "Could not reach Sendro at \(host):\(portValue). \(error.localizedDescription)"
        }
    }
}
