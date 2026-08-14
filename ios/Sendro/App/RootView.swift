//
//  RootView.swift
//  Sendro
//
//  New shell: a single receive surface (Home) + Library, a floating glass
//  tab bar, the Devices sheet (discovery / pairing / manual connect), the
//  Settings sheet, and the full-screen Flight view for a live transfer.
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
        case library
    }

    @EnvironmentObject private var engine: TransferEngine
    @EnvironmentObject private var discovery: DiscoveryService
    @Environment(\.scenePhase) private var scenePhase

    @State private var tab: Tab = .receive
    @State private var showDevices = false
    @State private var showSettings = false
    @State private var showSend = false
    @State private var flight: FlightRef?

    var body: some View {
        ZStack {
            SendroBackground()

            Group {
                switch tab {
                case .receive:
                    HomeView(openDevices: { showDevices = true },
                             openSettings: { showSettings = true },
                             openSend: { showSend = true },
                             openFlight: { ref in flight = ref },
                             goLibrary: { withAnimation(.easeOut(duration: 0.2)) { tab = .library } })
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
        }
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showDevices) {
            DevicesSheet()
        }
        .sheet(isPresented: $showSettings) {
            SettingsView()
        }
        .sheet(isPresented: $showSend) {
            SendSheet()
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

    private var tabBar: some View {
        HStack(spacing: 8) {
            tabButton(.receive, title: "Receive", systemImage: "tray.and.arrow.down",
                      showsDot: !engine.incomingOffers.isEmpty && tab != .receive)
            tabButton(.library, title: "Library", systemImage: "folder")
        }
        .padding(6)
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
            HStack(spacing: 8) {
                Image(systemName: systemImage)
                    .font(.system(size: 15, weight: .medium))
                Text(title)
                    .font(Theme.sans(13, .semibold))
                if showsDot {
                    Circle()
                        .fill(Theme.iris)
                        .frame(width: 6, height: 6)
                }
            }
            .foregroundColor(selected ? Theme.textBase : Theme.textBase.opacity(0.45))
            .frame(maxWidth: .infinity)
            .frame(height: 46)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(selected ? Color.white.opacity(0.12) : Color.clear)
            )
        }
        .buttonStyle(PressableButtonStyle())
    }
}
