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
    @StateObject private var messages: MessageCenter
    @StateObject private var notifier: Notifier
    @StateObject private var network: NetworkWatcher
    @StateObject private var tray: SendTray
    @StateObject private var engine: TransferEngine
    @StateObject private var uploader: UploadEngine

    init() {
        let settings = Settings()
        let pairedHosts = PairedHostStore()
        let history = HistoryStore()
        let fileStore = FileStore()
        let discovery = DiscoveryService()
        let messages = MessageCenter()
        // Owns the UNUserNotificationCenter delegate; must outlive every
        // notification, hence a StateObject rather than a local.
        let notifier = Notifier(settings: settings)
        let network = NetworkWatcher()
        let tray = SendTray()
        let engine = TransferEngine(settings: settings,
                                    paired: pairedHosts,
                                    history: history,
                                    fileStore: fileStore,
                                    discovery: discovery,
                                    messages: messages,
                                    notifier: notifier)
        let uploader = UploadEngine(paired: pairedHosts, history: history)
        _settings = StateObject(wrappedValue: settings)
        _pairedHosts = StateObject(wrappedValue: pairedHosts)
        _history = StateObject(wrappedValue: history)
        _fileStore = StateObject(wrappedValue: fileStore)
        _discovery = StateObject(wrappedValue: discovery)
        _messages = StateObject(wrappedValue: messages)
        _notifier = StateObject(wrappedValue: notifier)
        _network = StateObject(wrappedValue: network)
        _tray = StateObject(wrappedValue: tray)
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
                .environmentObject(messages)
                .environmentObject(notifier)
                .environmentObject(network)
                .environmentObject(tray)
                .environmentObject(engine)
                .environmentObject(uploader)
        }
    }
}
