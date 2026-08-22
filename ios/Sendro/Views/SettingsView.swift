//
//  SettingsView.swift
//  Sendro
//
//  Settings sheet in the new glass language + Network Diagnostics + About.
//  Binds the exact same Settings.Keys the engine reads.
//

import SwiftUI
import Network
import UIKit
import Combine

struct SettingsView: View {

    @Environment(\.dismiss) private var dismiss

    @AppStorage(Settings.Keys.deviceName)
    private var deviceName: String = UIDevice.current.name

    @AppStorage(Settings.Keys.autoAcceptFromTrusted)
    private var autoAcceptFromTrusted = false

    @AppStorage(Settings.Keys.saveMediaToPhotos)
    private var saveMediaToPhotos: Settings.SaveMediaMode = .always

    @AppStorage(Settings.Keys.deleteTempAfterImport)
    private var deleteTempAfterImport = true

    @AppStorage(Settings.Keys.addToSendroAlbum)
    private var addToSendroAlbum = true

    @AppStorage(Settings.Keys.notifyTransfers)
    private var notifyTransfers = true

    @AppStorage(Settings.Keys.notifyMessages)
    private var notifyMessages = true

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.bg.ignoresSafeArea()

                ScrollView {
                    VStack(alignment: .leading, spacing: 26) {
                        section(tag: "This iPhone") {
                            settingCard {
                                TextField("", text: $deviceName, prompt: Text("Device name")
                                    .foregroundColor(Theme.textBase.opacity(0.35)))
                                    .font(Theme.sans(15, .medium))
                                    .foregroundColor(Theme.textPrimary)
                                    .autocorrectionDisabled()
                            }
                            caption("Shown on your PC when pairing and transferring.")
                        }

                        section(tag: "Transfers") {
                            settingCard {
                                Toggle(isOn: $autoAcceptFromTrusted) {
                                    Text("Auto-Accept From Trusted Devices")
                                        .font(Theme.sans(15))
                                        .foregroundColor(Theme.textPrimary)
                                }
                                .tint(Theme.iris)
                            }
                            caption("Files from a computer you paired with start downloading straight away, with no prompt. Turn it off and every file asks first. Devices you have not paired with can never send you anything either way.")
                        }

                        section(tag: "Notifications") {
                            settingCard {
                                VStack(spacing: 16) {
                                    Toggle(isOn: $notifyTransfers) {
                                        VStack(alignment: .leading, spacing: 3) {
                                            Text("Transfers")
                                                .font(Theme.sans(15))
                                                .foregroundColor(Theme.textPrimary)
                                            Text("Files offered, saved or failed")
                                                .font(Theme.mono(10.5))
                                                .foregroundColor(Theme.textTertiary)
                                        }
                                    }
                                    .tint(Theme.iris)

                                    Divider()
                                        .overlay(Color.white.opacity(0.08))

                                    Toggle(isOn: $notifyMessages) {
                                        VStack(alignment: .leading, spacing: 3) {
                                            Text("Messages")
                                                .font(Theme.sans(15))
                                                .foregroundColor(Theme.textPrimary)
                                            Text("Sender only — never the text")
                                                .font(Theme.mono(10.5))
                                                .foregroundColor(Theme.textTertiary)
                                        }
                                    }
                                    .tint(Theme.iris)
                                }
                            }
                            caption("Local notifications only — there is no Sendro server and nothing leaves your network. They fire while Sendro is running or briefly backgrounded; iOS suspends apps after a while, so this can't be a guarantee. Message notifications never contain the message text.")
                        }

                        section(tag: "Photos & Videos") {
                            settingCard {
                                VStack(alignment: .leading, spacing: 14) {
                                    Text("Save Media to Photos")
                                        .font(Theme.sans(15))
                                        .foregroundColor(Theme.textPrimary)
                                    mediaModePicker
                                }
                            }
                            settingCard {
                                VStack(spacing: 16) {
                                    Toggle(isOn: $addToSendroAlbum) {
                                        Text("Add to “Sendro” Album")
                                            .font(Theme.sans(15))
                                            .foregroundColor(Theme.textPrimary)
                                    }
                                    .tint(Theme.iris)

                                    Divider()
                                        .overlay(Color.white.opacity(0.08))

                                    Toggle(isOn: $deleteTempAfterImport) {
                                        Text("Delete Temp After Import")
                                            .font(Theme.sans(15))
                                            .foregroundColor(Theme.textPrimary)
                                    }
                                    .tint(Theme.iris)
                                }
                            }
                            caption("With “Delete Temp After Import” off, a copy of imported media is also kept in Files. Non-media files always go to Files.")
                        }

                        section(tag: "Network") {
                            NavigationLink {
                                NetworkDiagnosticsView()
                            } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: "stethoscope")
                                        .font(.system(size: 15, weight: .medium))
                                        .foregroundColor(Theme.irisSoft)
                                    Text("Network Diagnostics")
                                        .font(Theme.sans(15))
                                        .foregroundColor(Theme.textPrimary)
                                    Spacer()
                                    Image(systemName: "chevron.right")
                                        .font(.system(size: 11, weight: .semibold))
                                        .foregroundColor(Theme.textBase.opacity(0.35))
                                }
                                .padding(16)
                                .glassRow(cornerRadius: 20, fillOpacity: 0.05, borderOpacity: 0.09)
                            }
                            .buttonStyle(PressableButtonStyle())
                        }

                        section(tag: "About") {
                            settingCard {
                                VStack(spacing: 14) {
                                    aboutRow(label: "Version", value: Self.appVersion)
                                    Divider().overlay(Color.white.opacity(0.08))
                                    aboutRow(label: "Protocol", value: "v\(sendroProtocolVersion)")
                                }
                            }
                            caption("Sendro moves files from your PC to this iPhone over your own Wi-Fi — byte for byte, verified with SHA-256. No cloud, no internet, no size limits.")
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 14)
                    .padding(.bottom, 40)
                }
                .scrollIndicators(.hidden)
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                        .tint(Theme.irisSoft)
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    // MARK: Pieces

    private func section<Content: View>(tag: String,
                                        @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionTag(text: tag)
                .padding(.leading, 4)
            content()
        }
    }

    private func settingCard<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .glassRow(cornerRadius: 20, fillOpacity: 0.05, borderOpacity: 0.09)
    }

    private func caption(_ text: String) -> some View {
        Text(text)
            .font(Theme.sans(12))
            .foregroundColor(Theme.textFaint)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 4)
    }

    private var mediaModePicker: some View {
        HStack(spacing: 6) {
            ForEach(Settings.SaveMediaMode.allCases) { mode in
                Button {
                    withAnimation(.easeOut(duration: 0.16)) { saveMediaToPhotos = mode }
                } label: {
                    Text(mode.label)
                        .font(Theme.sans(12, saveMediaToPhotos == mode ? .semibold : .medium))
                        .foregroundColor(saveMediaToPhotos == mode
                                         ? Theme.textBase
                                         : Theme.textBase.opacity(0.45))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .frame(maxWidth: .infinity)
                        .frame(height: 30)
                        .background(
                            RoundedRectangle(cornerRadius: 9, style: .continuous)
                                .fill(saveMediaToPhotos == mode
                                      ? Color.white.opacity(0.11)
                                      : Color.clear)
                        )
                }
                .buttonStyle(PressableButtonStyle())
            }
        }
        .padding(4)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color.white.opacity(0.04))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(Color.white.opacity(0.07), lineWidth: 0.5)
        )
    }

    private func aboutRow(label: String, value: String) -> some View {
        HStack {
            Text(label)
                .font(Theme.sans(15))
                .foregroundColor(Theme.textPrimary)
            Spacer()
            Text(value)
                .font(Theme.mono(13))
                .foregroundColor(Theme.textSecondary)
        }
    }

    private static var appVersion: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "\(version) (\(build))"
    }
}

// MARK: - Network diagnostics

struct NetworkDiagnosticsView: View {

    @EnvironmentObject private var engine: TransferEngine
    @EnvironmentObject private var discovery: DiscoveryService
    @EnvironmentObject private var pairedHosts: PairedHostStore
    /// One shared NWPathMonitor for the whole app (Core/NetworkWatcher.swift)
    /// — the same instance that drives discovery restarts on a hotspot switch.
    @EnvironmentObject private var pathMonitor: NetworkWatcher

    @State private var pingResults: [String: String] = [:]
    @State private var testing = false

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 26) {
                    diagSection(tag: "Wi-Fi") {
                        diagCard {
                            VStack(alignment: .leading, spacing: 10) {
                                HStack(spacing: 10) {
                                    Image(systemName: pathMonitor.isWifi ? "wifi" : "wifi.slash")
                                        .font(.system(size: 14, weight: .medium))
                                        .foregroundColor(pathMonitor.isConnected && pathMonitor.isWifi
                                                         ? Theme.teal : Theme.warn)
                                    Text(pathMonitor.statusText)
                                        .font(Theme.sans(14.5))
                                        .foregroundColor(Theme.textPrimary)
                                }
                                if pathMonitor.isConnected && !pathMonitor.isWifi {
                                    Text("Sendro needs your iPhone and PC on the same local network — normally Wi-Fi, but a hotspot works too (see below).")
                                        .font(Theme.sans(12.5))
                                        .foregroundColor(Theme.warn)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                        }

                        HotspotHelpCard(initiallyExpanded: true)
                    }

                    diagSection(tag: "Local Network Permission") {
                        diagCard {
                            VStack(alignment: .leading, spacing: 14) {
                                permissionStatus

                                if discovery.status == .permissionDenied {
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

                                Button {
                                    discovery.restart()
                                } label: {
                                    Text("Restart Discovery")
                                        .font(Theme.sans(13, .medium))
                                        .foregroundColor(Theme.irisSoft)
                                }
                                .buttonStyle(PressableButtonStyle())
                            }
                        }
                    }

                    diagSection(tag: "Storage") {
                        diagCard {
                            HStack {
                                Text("Free space")
                                    .font(Theme.sans(14.5))
                                    .foregroundColor(Theme.textPrimary)
                                Spacer()
                                Text(TransferEngine.freeDiskSpace().map { ByteFormat.string($0) } ?? "Unknown")
                                    .font(Theme.mono(13))
                                    .foregroundColor(Theme.textSecondary)
                            }
                        }
                    }

                    diagSection(tag: "Paired Computers") {
                        if pairedHosts.hosts.isEmpty {
                            diagCard {
                                Text("No paired computers yet.")
                                    .font(Theme.sans(13.5))
                                    .foregroundColor(Theme.textSecondary)
                            }
                        } else {
                            VStack(spacing: 9) {
                                ForEach(pairedHosts.hosts) { host in
                                    hostCard(host)
                                }

                                Button {
                                    Task { await runPings() }
                                } label: {
                                    HStack(spacing: 10) {
                                        if testing {
                                            ProgressView()
                                                .tint(Theme.irisSoft)
                                            Text("Testing…")
                                        } else {
                                            Text("Test Connections")
                                        }
                                    }
                                    .font(Theme.sans(14, .medium))
                                    .foregroundColor(Theme.irisSoft)
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 46)
                                    .glassRow(cornerRadius: 16, fillOpacity: 0.05, borderOpacity: 0.09)
                                }
                                .buttonStyle(PressableButtonStyle())
                                .disabled(testing)
                            }
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 14)
                .padding(.bottom, 40)
            }
            .scrollIndicators(.hidden)
        }
        .navigationTitle("Network Diagnostics")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private var permissionStatus: some View {
        switch discovery.status {
        case .permissionDenied:
            statusLine(text: "Denied — discovery is blocked",
                       systemImage: "xmark.circle.fill", color: Theme.danger)
        case .browsing:
            statusLine(text: "Granted — browsing for _sendro._tcp",
                       systemImage: "checkmark.circle.fill", color: Theme.teal)
        case .failed(let message):
            statusLine(text: "Browser error: \(message)",
                       systemImage: "exclamationmark.triangle.fill", color: Theme.warn)
        case .idle:
            statusLine(text: "Discovery idle",
                       systemImage: "pause.circle", color: Theme.textSecondary)
        }
    }

    private func statusLine(text: String, systemImage: String, color: Color) -> some View {
        HStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(color)
            Text(text)
                .font(Theme.sans(14))
                .foregroundColor(Theme.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func hostCard(_ host: PairedHost) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text(host.name)
                    .font(Theme.sans(15, .medium))
                    .foregroundColor(Theme.textPrimary)
                Spacer()
                Circle()
                    .fill((engine.hostOnline[host.deviceId] ?? false)
                          ? Theme.teal : Theme.textBase.opacity(0.3))
                    .frame(width: 9, height: 9)
            }
            Text("\(host.lastHost):\(String(host.lastPort))")
                .font(Theme.mono(11))
                .foregroundColor(Theme.textTertiary)
            if let result = pingResults[host.deviceId] {
                Text(result)
                    .font(Theme.mono(11))
                    .foregroundColor(result.hasPrefix("OK") ? Theme.teal : Theme.danger)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .glassRow(cornerRadius: 20, fillOpacity: 0.05, borderOpacity: 0.09)
    }

    private func diagSection<Content: View>(tag: String,
                                            @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionTag(text: tag)
                .padding(.leading, 4)
            content()
        }
    }

    private func diagCard<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .glassRow(cornerRadius: 20, fillOpacity: 0.05, borderOpacity: 0.09)
    }

    @MainActor
    private func runPings() async {
        testing = true
        for host in pairedHosts.hosts {
            pingResults[host.deviceId] = await engine.pingHost(host.deviceId)
        }
        testing = false
    }
}
