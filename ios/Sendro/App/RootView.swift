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
    @Environment(\.scenePhase) private var scenePhase

    @State private var tab: Tab = .receive
    @State private var showDevices = false
    @State private var showSettings = false
    @State private var flight: FlightRef?
    /// Lives here (not in SendView) so the chosen target survives tab switches.
    @State private var sendTargetId: String?

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

            VStack {
                Spacer()
                tabBar
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 10)

            // Ephemeral text (§11) — above the tabs, top-anchored, over
            // whatever surface is showing.
            MessageInboxOverlay()
        }
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showDevices) {
            DevicesSheet()
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
            }
        }
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
