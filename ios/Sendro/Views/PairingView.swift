//
//  PairingView.swift
//  Sendro
//
//  6-digit code entry (code shown on the PC). Computes the HKDF/HMAC proof
//  from PROTOCOL.md §4.2 — the code itself never crosses the wire.
//

import SwiftUI
import Combine

struct PairingView: View {

    let target: PairTarget

    @EnvironmentObject private var settings: Settings
    @EnvironmentObject private var pairedHosts: PairedHostStore
    @Environment(\.dismiss) private var dismiss

    private enum Stage: Equatable {
        case starting
        case enterCode
        case confirming
        case done
        case failed(String)
    }

    @State private var stage: Stage = .starting
    @State private var pairingId: String?
    @State private var salt: String?
    @State private var code = ""
    @State private var inlineError: String?
    @State private var remainingSeconds = 120
    @FocusState private var codeFocused: Bool

    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Spacer(minLength: 12)

                Image(systemName: "laptopcomputer.and.iphone")
                    .font(.system(size: 44))
                    .foregroundColor(.accentColor)

                VStack(spacing: 6) {
                    Text("Pair with \(target.name)")
                        .font(.title2.weight(.semibold))
                    Text("Type the 6-digit code shown on your PC.")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                }

                switch stage {
                case .starting:
                    ProgressView("Contacting \(target.name)…")
                        .padding(.top, 20)

                case .enterCode, .confirming:
                    codeEntry
                    if stage == .confirming {
                        ProgressView("Verifying…")
                    } else {
                        if let inlineError {
                            Text(inlineError)
                                .font(.subheadline)
                                .foregroundColor(.red)
                        }
                        Text(remainingSeconds > 0
                             ? "Code expires in \(remainingSeconds)s"
                             : "Code expired — restart pairing on your PC.")
                            .font(.footnote)
                            .foregroundColor(remainingSeconds > 10 ? .secondary : .orange)
                    }

                case .done:
                    Label("Paired!", systemImage: "checkmark.circle.fill")
                        .font(.title3.weight(.semibold))
                        .foregroundColor(.green)

                case .failed(let message):
                    VStack(spacing: 14) {
                        Label("Pairing failed", systemImage: "xmark.octagon.fill")
                            .font(.headline)
                            .foregroundColor(.red)
                        Text(message)
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                        Button("Try Again") {
                            Task { await startPairing() }
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .padding(.top, 8)
                }

                Spacer()
            }
            .padding(.horizontal, 24)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .task {
                await startPairing()
            }
            .onReceive(timer) { _ in
                if stage == .enterCode || stage == .confirming {
                    if remainingSeconds > 0 { remainingSeconds -= 1 }
                }
            }
        }
        .interactiveDismissDisabled(stage == .confirming)
    }

    // MARK: Code entry UI

    private var codeEntry: some View {
        ZStack {
            TextField("", text: $code)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .focused($codeFocused)
                .opacity(0.02)
                .frame(width: 1, height: 1)

            HStack(spacing: 10) {
                ForEach(0..<6, id: \.self) { index in
                    let characters = Array(code)
                    let digit = index < characters.count ? String(characters[index]) : ""
                    Text(digit)
                        .font(.system(size: 32, weight: .bold, design: .rounded))
                        .frame(width: 46, height: 60)
                        .background(
                            RoundedRectangle(cornerRadius: 12)
                                .fill(Color(.secondarySystemBackground))
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(index == code.count && codeFocused
                                        ? Color.accentColor : Color.clear,
                                        lineWidth: 2)
                        )
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { codeFocused = true }
        }
        .onChange(of: code) { newValue in
            let filtered = String(newValue.filter { $0.isNumber }.prefix(6))
            if filtered != newValue {
                code = filtered
                return
            }
            if filtered.count == 6, stage == .enterCode {
                Task { await confirm() }
            }
        }
    }

    // MARK: Flow

    @MainActor
    private func startPairing() async {
        stage = .starting
        inlineError = nil
        code = ""
        guard let client = SendroClient(host: target.host, port: target.port) else {
            stage = .failed("Invalid host address.")
            return
        }
        do {
            let info = try await client.info()
            guard info.app == "sendro", info.protocolVersion == sendroProtocolVersion else {
                stage = .failed("Protocol version mismatch — update Sendro on both devices.")
                return
            }
            let request = PairStartRequest(deviceId: settings.clientDeviceId,
                                           deviceName: settings.deviceName,
                                           platform: "ios",
                                           protocolVersion: sendroProtocolVersion)
            let response = try await client.pairStart(request)
            pairingId = response.pairingId
            salt = response.salt
            remainingSeconds = response.expiresInSeconds
            stage = .enterCode
            codeFocused = true
        } catch {
            stage = .failed("Could not start pairing: \(error.localizedDescription)")
        }
    }

    @MainActor
    private func confirm() async {
        guard let pairingId, let salt else { return }
        guard let client = SendroClient(host: target.host, port: target.port) else { return }
        stage = .confirming
        inlineError = nil

        guard let proof = SendroCrypto.pairingProof(code: code,
                                                    saltBase64url: salt,
                                                    pairingId: pairingId,
                                                    deviceId: settings.clientDeviceId) else {
            stage = .failed("Could not compute pairing proof.")
            return
        }

        do {
            let request = PairConfirmRequest(pairingId: pairingId,
                                             deviceId: settings.clientDeviceId,
                                             proof: proof)
            let response = try await client.pairConfirm(request)
            KeychainStore.saveToken(response.deviceToken, forHost: response.host.deviceId)
            pairedHosts.add(PairedHost(deviceId: response.host.deviceId,
                                       name: response.host.deviceName,
                                       lastHost: target.host,
                                       lastPort: target.port,
                                       pairedAtMs: Int64(Date().timeIntervalSince1970 * 1000)))
            stage = .done
            try? await Task.sleep(nanoseconds: 700_000_000)
            dismiss()
        } catch {
            if let clientError = error as? SendroClientError, let status = clientError.httpStatus {
                switch status {
                case 403:
                    inlineError = "Wrong code — check the number on your PC."
                    code = ""
                    stage = .enterCode
                    codeFocused = true
                case 429:
                    stage = .failed("Too many wrong attempts. Start a new pairing on your PC.")
                case 400:
                    stage = .failed("The pairing session expired. Start again.")
                default:
                    stage = .failed(clientError.localizedDescription)
                }
            } else {
                stage = .failed(error.localizedDescription)
            }
        }
    }
}
