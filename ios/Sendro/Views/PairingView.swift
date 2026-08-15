//
//  PairingView.swift
//  Sendro
//
//  6-digit code entry pane (code shown on the PC), embedded in the Devices
//  sheet. Computes the HKDF/HMAC proof from PROTOCOL.md §4.2 — the code
//  itself never crosses the wire. Custom in-sheet keypad, expiry countdown,
//  wrong-code retry.
//

import SwiftUI
import Combine

struct PairingPane: View {

    let target: PairTarget
    let onFinished: () -> Void
    let onCancel: () -> Void

    @EnvironmentObject private var settings: Settings
    @EnvironmentObject private var pairedHosts: PairedHostStore
    @EnvironmentObject private var notifier: Notifier

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

    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                Button(action: onCancel) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(Theme.textBase.opacity(0.8))
                        .frame(width: 32, height: 32)
                        .glassRow(cornerRadius: 12, fillOpacity: 0.07, borderOpacity: 0.1)
                }
                .buttonStyle(PressableButtonStyle())
                .disabled(stage == .confirming)
                .accessibilityLabel("Back")

                Text(target.name)
                    .font(Theme.sans(22, .semibold))
                    .foregroundColor(Theme.textPrimary)
                    .lineLimit(1)
            }

            Text("Type the 6-digit code shown on your PC. The code itself never crosses the wire.")
                .font(Theme.sans(13.5))
                .foregroundColor(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)

            switch stage {
            case .starting:
                HStack(spacing: 10) {
                    ProgressView()
                        .tint(Theme.irisSoft)
                    Text("Contacting \(target.name)…")
                        .font(Theme.mono(12))
                        .foregroundColor(Theme.textBase.opacity(0.6))
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 36)
                .padding(.bottom, 24)

            case .enterCode, .confirming:
                codeCells
                    .padding(.top, 22)

                if stage == .confirming {
                    HStack(spacing: 10) {
                        ProgressView()
                            .tint(Theme.irisSoft)
                        Text("Sending HMAC proof…")
                            .font(Theme.mono(12))
                            .foregroundColor(Theme.textBase.opacity(0.6))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 20)
                } else {
                    Group {
                        if let inlineError {
                            Text(inlineError)
                                .font(Theme.sans(13))
                                .foregroundColor(Theme.danger)
                        } else {
                            Text(remainingSeconds > 0
                                 ? "Code expires in \(remainingSeconds)s"
                                 : "Code expired — restart pairing on your PC.")
                                .font(Theme.mono(11.5))
                                .foregroundColor(remainingSeconds > 10
                                                 ? Theme.textBase.opacity(0.45)
                                                 : Theme.warn)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 16)

                    // BOTTOM SPACING: the keypad used to end flush against
                    // the screen edge. This 16pt sits on top of the sheet's
                    // 24pt safe-area inset (DevicesSheet) and the home
                    // indicator inset, so the last row always has room, and
                    // the whole pane stays scrollable on an SE-class screen.
                    keypad
                        .padding(.top, 18)
                        .padding(.bottom, 16)
                }

            case .done:
                HStack(spacing: 9) {
                    Image(systemName: "checkmark")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(Theme.teal)
                    Text("Paired")
                        .font(Theme.sans(15, .semibold))
                        .foregroundColor(Theme.teal)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 36)
                .padding(.bottom, 24)
                .transition(.scale(scale: 0.86).combined(with: .opacity))

            case .failed(let message):
                VStack(spacing: 14) {
                    HStack(spacing: 8) {
                        Image(systemName: "xmark.octagon")
                            .font(.system(size: 14, weight: .semibold))
                        Text("Pairing failed")
                            .font(Theme.sans(15, .semibold))
                    }
                    .foregroundColor(Theme.danger)

                    Text(message)
                        .font(Theme.sans(13))
                        .foregroundColor(Theme.textSecondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)

                    Button {
                        Task { await startPairing() }
                    } label: {
                        AccentPillLabel(title: "Try Again")
                            .frame(maxWidth: 200)
                    }
                    .buttonStyle(PressableButtonStyle())
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 28)
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

    // MARK: Code cells

    private var codeCells: some View {
        HStack(spacing: 8) {
            ForEach(0..<6, id: \.self) { index in
                let characters = Array(code)
                let digit = index < characters.count ? String(characters[index]) : ""
                let isCursor = index == code.count && stage == .enterCode
                Text(digit)
                    .font(Theme.mono(26, .medium))
                    .foregroundColor(Theme.textPrimary)
                    .frame(maxWidth: .infinity)
                    .frame(height: 60)
                    .background(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .fill(Color.white.opacity(0.06))
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .strokeBorder(isCursor
                                          ? Color.white.opacity(0.55)
                                          : Color.white.opacity(0.1),
                                          lineWidth: isCursor ? 1 : 0.5)
                    )
            }
        }
    }

    // MARK: Keypad

    private var keypad: some View {
        let keys: [String] = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫"]
        let columns = [GridItem(.flexible(), spacing: 9),
                       GridItem(.flexible(), spacing: 9),
                       GridItem(.flexible(), spacing: 9)]
        return LazyVGrid(columns: columns, spacing: 9) {
            ForEach(keys, id: \.self) { key in
                if key.isEmpty {
                    Color.clear
                        .frame(height: 52)
                } else {
                    Button {
                        press(key)
                    } label: {
                        Text(key)
                            .font(Theme.mono(22, .medium))
                            .foregroundColor(Theme.textBase.opacity(0.93))
                            .frame(maxWidth: .infinity)
                            .frame(height: 52)
                            .glassRow(cornerRadius: 16, fillOpacity: 0.06, borderOpacity: 0.09)
                    }
                    .buttonStyle(PressableButtonStyle())
                }
            }
        }
    }

    private func press(_ key: String) {
        guard stage == .enterCode else { return }
        inlineError = nil
        if key == "⌫" {
            if !code.isEmpty { code.removeLast() }
            return
        }
        guard code.count < 6 else { return }
        code += key
        if code.count == 6 {
            Task { await confirm() }
        }
    }

    // MARK: Flow

    @MainActor
    private func startPairing() async {
        stage = .starting
        inlineError = nil
        code = ""
        guard let client = SendroClient(host: target.host, port: target.port) else {
            stage = .failed("Invalid host address (\(target.host):\(String(target.port))). "
                + "Try Connect by IP with the address shown in Sendro on your PC.")
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
            // deviceName / platform are optional on the wire but always sent
            // (see PairConfirmRequest) — the host already has them from
            // pair/start on this path, so they are simply confirmed.
            let request = PairConfirmRequest(pairingId: pairingId,
                                             deviceId: settings.clientDeviceId,
                                             proof: proof,
                                             deviceName: settings.deviceName,
                                             platform: "ios")
            let response = try await client.pairConfirm(request)
            KeychainStore.saveToken(response.deviceToken, forHost: response.host.deviceId)
            pairedHosts.add(PairedHost(deviceId: response.host.deviceId,
                                       name: response.host.deviceName,
                                       lastHost: target.host,
                                       lastPort: target.port,
                                       pairedAtMs: Int64(Date().timeIntervalSince1970 * 1000)))
            // First successful pairing is the sensible moment to ask about
            // notifications — never at launch.
            notifier.requestAuthorizationAfterPairing()
            withAnimation(.spring(response: 0.34, dampingFraction: 0.75)) {
                stage = .done
            }
            try? await Task.sleep(nanoseconds: 900_000_000)
            onFinished()
        } catch {
            if let clientError = error as? SendroClientError, let status = clientError.httpStatus {
                switch status {
                case 403:
                    inlineError = "Wrong code — check the number on your PC."
                    code = ""
                    stage = .enterCode
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
