//
//  RootView.swift
//  Sendro
//
//  New shell: three peer surfaces — Receive · Send · Library — behind a
//  floating glass tab bar, plus the Devices sheet (discovery / pairing /
//  manual connect), the Settings sheet, the full-screen Flight view for a
//  live transfer, and the ephemeral message card stack that floats over
//  whichever tab is showing.
//

import SwiftUI

/// Which transfer the Flight screen is showing. Captures the offer snapshot
/// so the screen can keep rendering title/size after the engine drops the
/// transfer from `active` (completion).
struct FlightRef: Identifiable {
    let offer: TransferOffer
    let hostId: String

    var id: String { offer.transferId }
}

struct RootView: View {

    enum Tab {
        case receive
        case send
        case library
    }

    @EnvironmentObject private var engine: TransferEngine
    @EnvironmentObject private var discovery: DiscoveryService
    @EnvironmentObject private var messages: MessageCenter
    @EnvironmentObject private var pairedHosts: PairedHostStore
    @EnvironmentObject private var notifier: Notifier
    @EnvironmentObject private var network: NetworkWatcher
    @EnvironmentObject private var tray: SendTray
    @Environment(\.scenePhase) private var scenePhase

    @State private var tab: Tab = .receive
    @State private var showDevices = false
    @State private var showSettings = false
    @State private var flight: FlightRef?
    /// Lives here (not in SendView) so the chosen target survives tab switches.
    @State private var sendTargetId: String?
    /// §13 — a `sendro://pair` URL from our scanner or an OS URL open. Never
    /// set from anything the app fetched.
    @State private var pendingPairLink: PairLink?
    /// Shown when an incoming URL was a malformed sendro:// link.
    @State private var linkWarning: String?

    var body: some View {
        ZStack {
            SendroBackground()

            Group {
                switch tab {
                case .receive:
                    HomeView(openDevices: { showDevices = true },
                             openSettings: { showSettings = true },
                             openFlight: { ref in flight = ref },
                             goLibrary: { withAnimation(.easeOut(duration: 0.2)) { tab = .library } })
                case .send:
                    SendView(targetHostId: $sendTargetId,
                             openDevices: { showDevices = true })
                case .library:
                    LibraryView()
                }
            }
            .transition(.opacity)

            VStack(spacing: 10) {
                Spacer()
                if let linkWarning {
                    HStack(alignment: .top, spacing: 8) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.system(size: 12, weight: .semibold))
                        Text(linkWarning)
                            .font(Theme.sans(12.5))
                            .fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 0)
                    }
                    .foregroundColor(Theme.warn)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 11)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .glassRow(cornerRadius: 16, fillOpacity: 0.08, borderOpacity: 0.12)
                    .transition(.opacity)
                }
                tabBar
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 10)

            // Ephemeral text (§11) — above the tabs, top-anchored, over
            // whatever surface is showing.
            MessageInboxOverlay()
        }
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showDevices, onDismiss: { pendingPairLink = nil }) {
            DevicesSheet(initialLink: pendingPairLink)
                // A pairing URL arriving while the sheet is already open must
                // rebuild it on the confirm step — @State initial values only
                // apply to a fresh identity.
                .id(pendingPairLink?.id ?? "devices")
        }
        .sheet(isPresented: $showSettings) {
            SettingsView()
        }
        .fullScreenCover(item: $flight) { ref in
            FlightView(flightRef: ref)
        }
        .task {
            engine.start()
            discovery.start()
            tray.drainInbox()
            updateNotificationContext()
        }
        // Files handed to Sendro by iOS ("Copy to Sendro", Open In) and
        // sendro:// pairing URLs (Camera app / any QR reader) both arrive
        // here. This is the ONLY place either is accepted.
        .onOpenURL { url in
            handleOpenURL(url)
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                // Suspension killed the in-flight long-polls; restart them
                // immediately (fresh ping per host) so paired computers show
                // connected within a couple of seconds of returning.
                engine.applicationDidBecomeActive()
                if discovery.status != .browsing {
                    discovery.start()
                }
                // A "Copy to Sendro" that landed while we were suspended.
                let before = tray.items.count
                tray.drainInbox()
                if tray.items.count > before {
                    goToSendWithTray()
                }
            }
            updateNotificationContext(active: phase == .active)
        }
        // A network switch (joining the PC's hotspot, turning on Personal
        // Hotspot, changing Wi-Fi) invalidates resolved endpoints and parks
        // the poll sockets — re-resolve and re-ping instead of caching a dead
        // endpoint. Manual / QR hosts are re-probed too, never written off.
        .onChange(of: network.changeToken) { _ in
            discovery.restart()
            engine.networkChanged()
        }
        .onChange(of: tab) { _ in
            updateNotificationContext()
        }
        .onChange(of: flight?.id) { _ in
            updateNotificationContext()
        }
        // Tapping a notification lands on the right surface.
        .onChange(of: notifier.pendingRoute) { route in
            guard let route else { return }
            switch route {
            case .receive: withAnimation(.easeOut(duration: 0.2)) { tab = .receive }
            case .send:    withAnimation(.easeOut(duration: 0.2)) { tab = .send }
            case .library: withAnimation(.easeOut(duration: 0.2)) { tab = .library }
            }
            notifier.pendingRoute = nil
        }
    }

    // MARK: Incoming URLs

    private func handleOpenURL(_ url: URL) {
        if url.scheme?.lowercased() == "sendro" {
            guard let link = PairLink.parse(url) else {
                showLinkWarning("That pairing link isn't valid — show a fresh QR code on your PC.")
                return
            }
            // Always via the confirmation pane: §13 forbids completing a
            // scanned pairing without naming the PC first.
            pendingPairLink = link
            showDevices = true
            return
        }
        guard url.isFileURL else { return }
        if tray.accept(fileURL: url) {
            goToSendWithTray()
        }
    }

    /// Jump to Send with the shared files queued and a target picked. Never
    /// auto-sends — the user taps Send.
    private func goToSendWithTray() {
        if sendTargetId == nil
            || pairedHosts.host(id: sendTargetId ?? "") == nil
            || engine.hostOnline[sendTargetId ?? ""] != true {
            sendTargetId = pairedHosts.hosts
                .first { engine.hostOnline[$0.deviceId] == true }?
                .deviceId ?? sendTargetId
        }
        withAnimation(.easeOut(duration: 0.2)) { tab = .send }
    }

    private func showLinkWarning(_ text: String) {
        withAnimation(.easeOut(duration: 0.18)) { linkWarning = text }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if linkWarning == text {
                withAnimation(.easeOut(duration: 0.18)) { linkWarning = nil }
            }
        }
    }

    // MARK: Notification presentation context

    private func updateNotificationContext(active: Bool? = nil) {
        let surface: AppSurface
        if flight != nil {
            surface = .flight
        } else {
            switch tab {
            case .receive: surface = .receive
            case .send:    surface = .send
            case .library: surface = .library
            }
        }
        notifier.updateContext(active: active ?? (scenePhase == .active), surface: surface)
    }

    // MARK: Floating glass tab bar

    /// Three tabs have to fit inside 320pt: 20pt page margins, 5pt inner
    /// padding and 6pt gaps leave ~86pt per tab, and the labels below (14pt
    /// glyph + 6pt + a ≤52pt caption) come in well under that. The pending
    /// badge is an overlay on the glyph, so it costs no width.
    private var tabBar: some View {
        HStack(spacing: 6) {
            tabButton(.receive, title: "Receive", systemImage: "tray.and.arrow.down",
                      showsDot: messages.hasMessages
                                || (!engine.incomingOffers.isEmpty && tab != .receive))
            tabButton(.send, title: "Send", systemImage: "paperplane")
            tabButton(.library, title: "Library", systemImage: "folder")
        }
        .padding(5)
        .background(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(Color.white.opacity(0.07))
                .background(.ultraThinMaterial,
                            in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .strokeBorder(Color.white.opacity(0.12), lineWidth: 0.5)
        )
        .shadow(color: .black.opacity(0.55), radius: 22, x: 0, y: 16)
    }

    private func tabButton(_ target: Tab, title: String, systemImage: String,
                           showsDot: Bool = false) -> some View {
        let selected = tab == target
        return Button {
            withAnimation(.easeOut(duration: 0.2)) { tab = target }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: systemImage)
                    .font(.system(size: 14, weight: .medium))
                    .overlay(alignment: .topTrailing) {
                        if showsDot {
                            Circle()
                                .fill(Theme.iris)
                                .frame(width: 6, height: 6)
                                .offset(x: 4, y: -3)
                        }
                    }
                Text(title)
                    .font(Theme.sans(12.5, .semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .foregroundColor(selected ? Theme.textBase : Theme.textBase.opacity(0.45))
            .frame(maxWidth: .infinity)
            .frame(height: 46)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(selected ? Color.white.opacity(0.12) : Color.clear)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(PressableButtonStyle())
    }
}
