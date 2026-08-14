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
    @StateObject private var uploader: UploadEngine

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
        let uploader = UploadEngine(paired: pairedHosts, history: history)
        _settings = StateObject(wrappedValue: settings)
        _pairedHosts = StateObject(wrappedValue: pairedHosts)
        _history = StateObject(wrappedValue: history)
        _fileStore = StateObject(wrappedValue: fileStore)
        _discovery = StateObject(wrappedValue: discovery)
        _engine = StateObject(wrappedValue: engine)
        _uploader = StateObject(wrappedValue: uploader)
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
                .environmentObject(uploader)
        }
    }
}
