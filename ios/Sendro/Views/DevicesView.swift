//
//  DevicesView.swift
//  Sendro
//
//  Discovered + paired hosts, manual connect, local network permission card.
//

import SwiftUI
import UIKit

/// A host the user is about to pair with (from discovery or manual entry).
struct PairTarget: Identifiable {
    let name: String
    let host: String
    let port: UInt16

    var id: String { "\(host):\(port)" }
}

struct DevicesView: View {

    @EnvironmentObject private var discovery: DiscoveryService
    @EnvironmentObject private var pairedHosts: PairedHostStore
    @EnvironmentObject private var engine: TransferEngine

    @State private var showManualSheet = false
    @State private var manualResult: PairTarget?
    @State private var pairTarget: PairTarget?
    @State private var unpairCandidate: PairedHost?

    var body: some View {
        NavigationStack {
            List {
                if discovery.status == .permissionDenied {
                    permissionSection
                }

                if !pairedHosts.hosts.isEmpty {
                    pairedSection
                }

                nearbySection
            }
            .navigationTitle("Devices")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showManualSheet = true
                    } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel("Connect manually")
                }
            }
            .sheet(isPresented: $showManualSheet) {
                ManualConnectSheet { target in
                    manualResult = target
                }
                .presentationDetents([.medium])
            }
            .onChange(of: showManualSheet) { shown in
                if !shown, let target = manualResult {
                    manualResult = nil
                    // Delay so the first sheet fully dismisses.
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                        pairTarget = target
                    }
                }
            }
            .sheet(item: $pairTarget) { target in
                PairingView(target: target)
            }
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
    }

    // MARK: Sections

    private var permissionSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 10) {
                Label("Local Network access needed", systemImage: "wifi.exclamationmark")
                    .font(.headline)
                Text("Sendro can't see your PC because Local Network access is turned off. Enable it under Settings › Apps › Sendro › Local Network.")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                Button {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                } label: {
                    Text("Open Settings")
                        .fontWeight(.semibold)
                }
                .buttonStyle(.borderedProminent)
            }
            .padding(.vertical, 6)
        }
    }

    private var pairedSection: some View {
        Section("Paired") {
            ForEach(pairedHosts.hosts) { host in
                HStack(spacing: 12) {
                    Image(systemName: "desktopcomputer")
                        .font(.title3)
                        .foregroundColor(.accentColor)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(host.name)
                            .font(.body.weight(.medium))
                        Text("\(host.lastHost):\(String(host.lastPort))")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                    Circle()
                        .fill((engine.hostOnline[host.deviceId] ?? false) ? Color.green : Color.gray.opacity(0.5))
                        .frame(width: 10, height: 10)
                }
                .contextMenu {
                    Button(role: .destructive) {
                        unpairCandidate = host
                    } label: {
                        Label("Unpair", systemImage: "trash")
                    }
                }
            }
        }
    }

    private var nearbySection: some View {
        Section {
            let unpaired = discovery.hosts.filter { pairedHosts.host(id: $0.deviceId) == nil }
            if unpaired.isEmpty {
                HStack(spacing: 10) {
                    if discovery.status == .browsing {
                        ProgressView()
                        Text("Looking for Sendro on your Wi-Fi…")
                            .foregroundColor(.secondary)
                    } else {
                        Image(systemName: "magnifyingglass")
                            .foregroundColor(.secondary)
                        Text("No computers found yet.")
                            .foregroundColor(.secondary)
                    }
                }
                .font(.subheadline)
                .padding(.vertical, 4)
            }
            ForEach(unpaired) { host in
                HStack(spacing: 12) {
                    Image(systemName: "pc")
                        .font(.title3)
                        .foregroundColor(.secondary)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(host.name)
                            .font(.body.weight(.medium))
                        if let ip = host.ipAddress, let port = host.port {
                            Text("\(ip):\(String(port))")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        } else {
                            Text("Resolving…")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                    Spacer()
                    if host.protocolVersion != sendroProtocolVersion {
                        Text("Update Sendro")
                            .font(.caption)
                            .foregroundColor(.orange)
                    } else if let ip = host.ipAddress, let port = host.port {
                        Button("Pair") {
                            pairTarget = PairTarget(name: host.name, host: ip, port: port)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.small)
                    } else {
                        ProgressView()
                    }
                }
            }
        } header: {
            Text("Nearby")
        } footer: {
            Text("Your PC must be running Sendro on the same Wi-Fi network. Use + to connect by IP address if discovery is blocked.")
        }
    }
}

// MARK: - Manual connect

struct ManualConnectSheet: View {

    @Environment(\.dismiss) private var dismiss

    @State private var ipText = ""
    @State private var portText = "48800"
    @State private var checking = false
    @State private var errorText: String?

    let onFound: (PairTarget) -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("IP address (e.g. 192.168.1.20)", text: $ipText)
                        .keyboardType(.numbersAndPunctuation)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    TextField("Port", text: $portText)
                        .keyboardType(.numberPad)
                } header: {
                    Text("Connect to your PC directly")
                } footer: {
                    Text("The Sendro window on your PC shows its address and port.")
                }

                if let errorText {
                    Section {
                        Label(errorText, systemImage: "exclamationmark.triangle")
                            .foregroundColor(.red)
                            .font(.subheadline)
                    }
                }

                Section {
                    Button {
                        Task { await connect() }
                    } label: {
                        if checking {
                            HStack {
                                ProgressView()
                                Text("Checking…")
                            }
                        } else {
                            Text("Connect")
                                .fontWeight(.semibold)
                        }
                    }
                    .disabled(checking || ipText.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .navigationTitle("Manual Connect")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
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
            dismiss()
        } catch {
            errorText = "Could not reach Sendro at \(host):\(portValue). \(error.localizedDescription)"
        }
    }
}
