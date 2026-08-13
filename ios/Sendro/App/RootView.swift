//
//  RootView.swift
//  Sendro
//
//  Tab shell: Devices, Transfers, Files, Settings.
//

import SwiftUI

struct RootView: View {

    @EnvironmentObject private var engine: TransferEngine
    @EnvironmentObject private var discovery: DiscoveryService
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        TabView {
            DevicesView()
                .tabItem { Label("Devices", systemImage: "desktopcomputer") }

            TransfersView()
                .tabItem { Label("Transfers", systemImage: "arrow.down.circle") }
                .badge(engine.incomingOffers.count)

            FilesView()
                .tabItem { Label("Files", systemImage: "folder") }

            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape") }
        }
        .task {
            engine.start()
            discovery.start()
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active, discovery.status != .browsing {
                discovery.start()
            }
        }
    }
}
