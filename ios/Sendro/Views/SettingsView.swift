//
//  SettingsView.swift
//  Sendro
//
//  App settings + Network Diagnostics + About.
//

import SwiftUI
import Network
import UIKit
import Combine

struct SettingsView: View {

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

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Device name", text: $deviceName)
                        .autocorrectionDisabled()
                } header: {
                    Text("This iPhone")
                } footer: {
                    Text("Shown on your PC when pairing and transferring.")
                }

                Section {
                    Toggle("Auto-Accept From Trusted Devices", isOn: $autoAcceptFromTrusted)
                } footer: {
                    Text("Only applies to offers your PC flags as auto-send (watch-folder rules). Everything else always asks.")
                }

                Section {
                    Picker("Save Media to Photos", selection: $saveMediaToPhotos) {
                        ForEach(Settings.SaveMediaMode.allCases) { mode in
                            Text(mode.label).tag(mode)
                        }
                    }
                    Toggle("Add to “Sendro” Album", isOn: $addToSendroAlbum)
                    Toggle("Delete Temp After Import", isOn: $deleteTempAfterImport)
                } header: {
                    Text("Photos & Videos")
                } footer: {
                    Text("With “Delete Temp After Import” off, a copy of imported media is also kept in Files. Non-media files always go to Files.")
                }

                Section("Network") {
                    NavigationLink {
                        NetworkDiagnosticsView()
                    } label: {
                        Label("Network Diagnostics", systemImage: "stethoscope")
                    }
                }

                Section("About") {
                    LabeledContent("Version", value: Self.appVersion)
                    LabeledContent("Protocol", value: "v\(sendroProtocolVersion)")
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Sendro moves files from your PC to this iPhone over your own Wi-Fi — byte for byte, verified with SHA-256. No cloud, no internet, no size limits.")
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    }
                    .padding(.vertical, 2)
                }
            }
            .navigationTitle("Settings")
        }
    }

    private static var appVersion: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "\(version) (\(build))"
    }
}

// MARK: - Network diagnostics

private final class PathMonitorModel: ObservableObject {

    @Published var isConnected = false
    @Published var isWifi = false
    @Published var statusText = "Checking…"

    private let monitor = NWPathMonitor()

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                guard let self else { return }
                self.isConnected = path.status == .satisfied
                self.isWifi = path.usesInterfaceType(.wifi)
                if path.status == .satisfied {
                    self.statusText = path.usesInterfaceType(.wifi)
                        ? "Connected via Wi-Fi"
                        : "Connected (not Wi-Fi)"
                } else {
                    self.statusText = "No network connection"
                }
            }
        }
        monitor.start(queue: DispatchQueue(label: "sendro.pathmonitor"))
    }

    deinit {
        monitor.cancel()
    }
}

struct NetworkDiagnosticsView: View {

    @EnvironmentObject private var engine: TransferEngine
    @EnvironmentObject private var discovery: DiscoveryService
    @EnvironmentObject private var pairedHosts: PairedHostStore

    @StateObject private var pathMonitor = PathMonitorModel()
    @State private var pingResults: [String: String] = [:]
    @State private var testing = false

    var body: some View {
        List {
            Section("Wi-Fi") {
                HStack {
                    Image(systemName: pathMonitor.isWifi ? "wifi" : "wifi.slash")
                        .foregroundColor(pathMonitor.isConnected && pathMonitor.isWifi ? .green : .orange)
                    Text(pathMonitor.statusText)
                }
                if pathMonitor.isConnected && !pathMonitor.isWifi {
                    Text("Sendro needs your iPhone and PC on the same Wi-Fi network.")
                        .font(.caption)
                        .foregroundColor(.orange)
                }
            }

            Section("Local Network Permission") {
                switch discovery.status {
                case .permissionDenied:
                    Label("Denied — discovery is blocked", systemImage: "xmark.circle.fill")
                        .foregroundColor(.red)
                    Button("Open Settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }
                case .browsing:
                    Label("Granted — browsing for _sendro._tcp", systemImage: "checkmark.circle.fill")
                        .foregroundColor(.green)
                case .failed(let message):
                    Label("Browser error: \(message)", systemImage: "exclamationmark.triangle.fill")
                        .foregroundColor(.orange)
                case .idle:
                    Label("Discovery idle", systemImage: "pause.circle")
                        .foregroundColor(.secondary)
                }
                Button("Restart Discovery") {
                    discovery.restart()
                }
            }

            Section("Storage") {
                LabeledContent("Free space",
                               value: TransferEngine.freeDiskSpace().map { ByteFormat.string($0) } ?? "Unknown")
            }

            Section {
                if pairedHosts.hosts.isEmpty {
                    Text("No paired computers yet.")
                        .foregroundColor(.secondary)
                }
                ForEach(pairedHosts.hosts) { host in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(host.name)
                                .font(.body.weight(.medium))
                            Spacer()
                            Circle()
                                .fill((engine.hostOnline[host.deviceId] ?? false) ? Color.green : Color.gray.opacity(0.5))
                                .frame(width: 10, height: 10)
                        }
                        Text("\(host.lastHost):\(String(host.lastPort))")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        if let result = pingResults[host.deviceId] {
                            Text(result)
                                .font(.caption)
                                .foregroundColor(result.hasPrefix("OK") ? .green : .red)
                        }
                    }
                    .padding(.vertical, 2)
                }
                if !pairedHosts.hosts.isEmpty {
                    Button {
                        Task { await runPings() }
                    } label: {
                        if testing {
                            HStack {
                                ProgressView()
                                Text("Testing…")
                            }
                        } else {
                            Text("Test Connections")
                        }
                    }
                    .disabled(testing)
                }
            } header: {
                Text("Paired Computers")
            }
        }
        .navigationTitle("Network Diagnostics")
        .navigationBarTitleDisplayMode(.inline)
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
