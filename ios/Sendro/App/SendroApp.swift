//
//  SendroApp.swift
//  Sendro
//
//  App entry point. Builds the object graph once and injects it.
//

import SwiftUI

@main
struct SendroApp: App {

    @StateObject private var settings: Settings
    @StateObject private var pairedHosts: PairedHostStore
    @StateObject private var history: HistoryStore
    @StateObject private var fileStore: FileStore
    @StateObject private var discovery: DiscoveryService
    @StateObject private var engine: TransferEngine

    init() {
        let settings = Settings()
        let pairedHosts = PairedHostStore()
        let history = HistoryStore()
        let fileStore = FileStore()
        let discovery = DiscoveryService()
        let engine = TransferEngine(settings: settings,
                                    paired: pairedHosts,
                                    history: history,
                                    fileStore: fileStore,
                                    discovery: discovery)
        _settings = StateObject(wrappedValue: settings)
        _pairedHosts = StateObject(wrappedValue: pairedHosts)
        _history = StateObject(wrappedValue: history)
        _fileStore = StateObject(wrappedValue: fileStore)
        _discovery = StateObject(wrappedValue: discovery)
        _engine = StateObject(wrappedValue: engine)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(settings)
                .environmentObject(pairedHosts)
                .environmentObject(history)
                .environmentObject(fileStore)
                .environmentObject(discovery)
                .environmentObject(engine)
        }
    }
}
