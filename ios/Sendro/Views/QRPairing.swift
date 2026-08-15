//
//  QRPairing.swift
//  Sendro
//
//  PROTOCOL.md §13 — scan the QR code the PC shows instead of typing six
//  digits. Two panes:
//
//    QRScanPane          camera + reticle + torch, handles every permission
//                        state, hands up a parsed PairLink
//    QRPairConfirmPane   names the PC and waits for an explicit tap before
//                        anything is sent — mandatory for BOTH entry points
//                        (our scanner and an OS URL open from the Camera app)
//
//  The crypto is the ordinary §4.2 proof (SendroCrypto) run through the
//  ordinary client (SendroClient) — see Core/PairLink.swift. Nothing about
//  the pairing math is duplicated here.
//

import SwiftUI
import AVFoundation
import UIKit

// MARK: - Scan pane

struct QRScanPane: View {

    let onCancel: () -> Void
    let onLink: (PairLink) -> Void

    private enum Permission: Equatable {
        case checking
        case authorized
        case denied
        case restricted
    }

    @State private var permission: Permission = .checking
    @State private var torchOn = false
    @State private var warning: String?

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
                .accessibilityLabel("Back")

                Text("Scan QR code")
                    .font(Theme.sans(22, .semibold))
                    .foregroundColor(Theme.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)

                Spacer(minLength: 0)

                if permission == .authorized {
                    Button {
                        torchOn.toggle()
                        QRScannerViewController.setTorch(torchOn)
                    } label: {
                        Image(systemName: torchOn ? "bolt.fill" : "bolt.slash")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(torchOn ? Theme.onAccent : Theme.textBase.opacity(0.8))
                            .frame(width: 32, height: 32)
                            .background(
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .fill(torchOn ? Theme.iris : Color.white.opacity(0.07))
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .strokeBorder(Color.white.opacity(0.1), lineWidth: 0.5)
                            )
                    }
                    .buttonStyle(PressableButtonStyle())
                    .accessibilityLabel(torchOn ? "Turn torch off" : "Turn torch on")
                }
            }

            Text("Point at the QR code on your PC — Sendro › Pair › Show QR.")
                .font(Theme.sans(13.5))
                .foregroundColor(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)

            permissionBody(for: permission)
                .padding(.top, 18)

            if let warning {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.system(size: 12, weight: .semibold))
                    Text(warning)
                        .font(Theme.sans(12.5))
                        .fixedSize(horizontal: false, vertical: true)
                }
                .foregroundColor(Theme.warn)
                .padding(.top, 14)
            }

            Text("The code travels screen → camera, never over the network, and expires after 120 seconds.")
                .font(Theme.sans(12))
                .foregroundColor(Theme.textFaint)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 16)
        }
        .task {
            await resolvePermission()
        }
        .onDisappear {
            if torchOn {
                torchOn = false
                QRScannerViewController.setTorch(false)
            }
        }
    }

    @ViewBuilder
    private func permissionBody(for permission: Permission) -> some View {
        switch permission {
        case .checking:
            placeholder {
                ProgressView()
                    .tint(Theme.irisSoft)
            }
        case .authorized:
            cameraFrame
        case .denied:
            deniedCard(title: "Camera access is off",
                       message: "Sendro needs the camera to read the pairing QR code. Turn it on in Settings › Apps › Sendro › Camera — or pair with the 6-digit code instead.",
                       showsSettings: true)
        case .restricted:
            deniedCard(title: "Camera unavailable",
                       message: "This iPhone doesn't allow camera access (Screen Time restrictions). Pair with the 6-digit code instead.",
                       showsSettings: false)
        }
    }

    private var cameraFrame: some View {
        ZStack {
            QRScannerRepresentable { value in
                handle(value)
            }
            Reticle()
        }
        .frame(height: 320)
        .frame(maxWidth: .infinity)
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .strokeBorder(Color.white.opacity(0.12), lineWidth: 0.5)
        )
    }

    private func placeholder<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        ZStack {
            content()
        }
        .frame(height: 320)
        .frame(maxWidth: .infinity)
        .glassRow(cornerRadius: 24, fillOpacity: 0.05, borderOpacity: 0.09)
    }

    private func deniedCard(title: String, message: String, showsSettings: Bool) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "camera.fill")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(Theme.warn)
                Text(title)
                    .font(Theme.sans(15, .semibold))
                    .foregroundColor(Theme.textPrimary)
                    .lineLimit(2)
                    .minimumScaleFactor(0.85)
            }
            Text(message)
                .font(Theme.sans(12.5))
                .foregroundColor(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if showsSettings {
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
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassRow(cornerRadius: 20, fillOpacity: 0.06, borderOpacity: 0.1)
    }

    // MARK: Logic

    private func resolvePermission() async {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            permission = .authorized
        case .notDetermined:
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            permission = granted ? .authorized : .denied
        case .denied:
            permission = .denied
        case .restricted:
            permission = .restricted
        @unknown default:
            permission = .denied
        }
    }

    /// Every scanned string lands here. Only a well-formed §13 URL is passed
    /// up; anything else keeps the camera running with a hint.
    private func handle(_ value: String) {
        guard let url = URL(string: value), let link = PairLink.parse(url) else {
            withAnimation(.easeOut(duration: 0.15)) {
                warning = "That QR code isn't a Sendro pairing code."
            }
            return
        }
        warning = nil
        if torchOn {
            torchOn = false
            QRScannerViewController.setTorch(false)
        }
        onLink(link)
    }
}

/// Iris-tinted corner reticle over the camera feed.
private struct Reticle: View {

    @State private var sweep = false

    var body: some View {
        GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height) * 0.62
            ZStack {
                Color.black.opacity(0.28)
                    .allowsHitTesting(false)

                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .strokeBorder(Theme.iris.opacity(0.9), lineWidth: 2)
                    .frame(width: side, height: side)
                    .shadow(color: Theme.iris.opacity(0.5), radius: 12)

                Rectangle()
                    .fill(LinearGradient(colors: [.clear, Theme.irisBright.opacity(0.85), .clear],
                                         startPoint: .leading, endPoint: .trailing))
                    .frame(width: side, height: 2)
                    .offset(y: sweep ? side / 2 - 8 : -side / 2 + 8)
                    .animation(.easeInOut(duration: 1.6).repeatForever(autoreverses: true),
                               value: sweep)
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .allowsHitTesting(false)
        .onAppear { sweep = true }
    }
}

// MARK: - Camera plumbing

struct QRScannerRepresentable: UIViewControllerRepresentable {

    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> QRScannerViewController {
        let controller = QRScannerViewController()
        controller.onCode = onCode
        return controller
    }

    func updateUIViewController(_ uiViewController: QRScannerViewController, context: Context) {
        uiViewController.onCode = onCode
    }
}

/// AVCaptureSession + AVCaptureMetadataOutput(.qr). Nothing is drawn here
/// except the preview layer — the reticle and controls are SwiftUI on top.
final class QRScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {

    var onCode: ((String) -> Void)?

    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "sendro.qr.session")
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var configured = false
    /// Rate-limits repeat deliveries of the same code sitting in frame.
    private var lastDelivery: TimeInterval = 0

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        configureSession()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        // startRunning() blocks — never on main.
        let captureSession = session
        sessionQueue.async {
            if !captureSession.isRunning { captureSession.startRunning() }
        }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        let captureSession = session
        sessionQueue.async {
            if captureSession.isRunning { captureSession.stopRunning() }
        }
        Self.setTorch(false)
    }

    private func configureSession() {
        guard !configured else { return }
        configured = true

        session.beginConfiguration()
        session.sessionPreset = .high

        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera,
                                                   for: .video,
                                                   position: .back),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            session.commitConfiguration()
            return
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            session.commitConfiguration()
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        // Must be set AFTER the output joins the session, and only to types
        // the session actually supports — anything else throws.
        if output.availableMetadataObjectTypes.contains(.qr) {
            output.metadataObjectTypes = [.qr]
        }
        session.commitConfiguration()

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        if let connection = layer.connection, connection.isVideoOrientationSupported {
            connection.videoOrientation = .portrait      // the app is portrait-only
        }
        view.layer.addSublayer(layer)
        previewLayer = layer
    }

    // MARK: AVCaptureMetadataOutputObjectsDelegate

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        let now = Date.timeIntervalSinceReferenceDate
        guard now - lastDelivery > 1.2 else { return }
        for object in metadataObjects {
            guard let readable = object as? AVMetadataMachineReadableCodeObject,
                  readable.type == .qr,
                  let value = readable.stringValue,
                  !value.isEmpty else { continue }
            lastDelivery = now
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            onCode?(value)
            return
        }
    }

    // MARK: Torch

    static func setTorch(_ on: Bool) {
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera,
                                                   for: .video,
                                                   position: .back),
              device.hasTorch, device.isTorchAvailable else { return }
        do {
            try device.lockForConfiguration()
            device.torchMode = on ? .on : .off
            device.unlockForConfiguration()
        } catch {
            // Torch is a nicety; a locked device is not worth surfacing.
        }
    }
}

// MARK: - Confirm pane

/// Mandatory confirmation step. A scanned or externally-opened
/// `sendro://pair` URL never completes silently — the PC is named here first.
struct QRPairConfirmPane: View {

    let link: PairLink
    let onFinished: () -> Void
    let onCancel: () -> Void

    @EnvironmentObject private var settings: Settings
    @EnvironmentObject private var pairedHosts: PairedHostStore
    @EnvironmentObject private var notifier: Notifier

    private enum Stage: Equatable {
        case confirm
        case working
        case done
        case failed(String)
    }

    @State private var stage: Stage = .confirm

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
                .disabled(stage == .working)
                .accessibilityLabel("Back")

                Text("Pair with this PC?")
                    .font(Theme.sans(22, .semibold))
                    .foregroundColor(Theme.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }

            hostCard
                .padding(.top, 18)

            switch stage {
            case .confirm:
                Text("Sendro will verify that this really is the computer in the QR code, then pair — the 6-digit code inside the QR never goes back over the network.")
                    .font(Theme.sans(13))
                    .foregroundColor(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 16)

                HStack(spacing: 10) {
                    Button {
                        Task { await run() }
                    } label: {
                        AccentPillLabel(title: "Pair")
                    }
                    .buttonStyle(PressableButtonStyle())

                    Button(action: onCancel) {
                        GhostPillLabel(title: "Cancel")
                    }
                    .buttonStyle(PressableButtonStyle())
                    .frame(maxWidth: 120)
                }
                .padding(.top, 20)

            case .working:
                HStack(spacing: 10) {
                    ProgressView()
                        .tint(Theme.irisSoft)
                    Text("Verifying and sending HMAC proof…")
                        .font(Theme.mono(12))
                        .foregroundColor(Theme.textBase.opacity(0.6))
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 30)
                .padding(.bottom, 20)

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
                .padding(.top, 30)
                .padding(.bottom, 20)
                .transition(.scale(scale: 0.86).combined(with: .opacity))

            case .failed(let message):
                VStack(alignment: .leading, spacing: 14) {
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
                        .fixedSize(horizontal: false, vertical: true)

                    HStack(spacing: 10) {
                        Button {
                            Task { await run() }
                        } label: {
                            AccentPillLabel(title: "Try Again")
                        }
                        .buttonStyle(PressableButtonStyle())

                        Button(action: onCancel) {
                            GhostPillLabel(title: "Back")
                        }
                        .buttonStyle(PressableButtonStyle())
                        .frame(maxWidth: 120)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 18)
            }
        }
    }

    private var hostCard: some View {
        HStack(spacing: 13) {
            ZStack {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(Theme.iris.opacity(0.16))
                Image(systemName: "desktopcomputer")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundColor(Theme.irisBright)
            }
            .frame(width: 46, height: 46)

            VStack(alignment: .leading, spacing: 3) {
                Text(link.hostName)
                    .font(Theme.sans(17, .semibold))
                    .foregroundColor(Theme.textPrimary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text("\(link.host):\(String(link.port))")
                    .font(Theme.mono(11))
                    .foregroundColor(Theme.textTertiary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassRow(cornerRadius: 22, fillOpacity: 0.06, borderOpacity: 0.1)
    }

    @MainActor
    private func run() async {
        stage = .working
        do {
            let response = try await PairLinkFlow.confirm(link: link,
                                                          clientDeviceId: settings.clientDeviceId,
                                                          deviceName: settings.deviceName)
            KeychainStore.saveToken(response.deviceToken, forHost: response.host.deviceId)
            pairedHosts.add(PairedHost(deviceId: response.host.deviceId,
                                       name: response.host.deviceName,
                                       lastHost: link.host,
                                       lastPort: link.port,
                                       pairedAtMs: Int64(Date().timeIntervalSince1970 * 1000)))
            notifier.requestAuthorizationAfterPairing()
            withAnimation(.spring(response: 0.34, dampingFraction: 0.75)) {
                stage = .done
            }
            try? await Task.sleep(nanoseconds: 900_000_000)
            onFinished()
        } catch {
            stage = .failed(error.localizedDescription)
        }
    }
}
