//
//  NetworkWatcher.swift
//  Sendro
//
//  One NWPathMonitor for the whole app. Two jobs:
//
//  1. Describe the current network for Diagnostics ("Connected via Wi-Fi").
//  2. Publish a change token every time the path meaningfully flips, so the
//     rest of the app can re-resolve instead of sitting on a dead endpoint.
//
//  Why (2) matters for hotspots (§ user request 5 & 6): joining the PC's
//  Mobile Hotspot, or turning on iPhone Personal Hotspot for the PC to join,
//  changes the interface under a running app. mDNS results, the resolved
//  ip:port and any in-flight long poll all belong to the old network. On a
//  change we restart discovery and re-ping every paired host — a manually
//  entered or QR-scanned address is re-probed rather than being marked
//  offline forever.
//

import Foundation
import Network
import Combine

final class NetworkWatcher: ObservableObject {

    @Published private(set) var isConnected = false
    @Published private(set) var isWifi = false
    @Published private(set) var isExpensive = false
    @Published private(set) var statusText = "Checking…"
    /// Incremented on every meaningful path change (including the first).
    @Published private(set) var changeToken = 0

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "sendro.networkwatcher")
    private var lastSignature: String?

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == .satisfied
            let wifi = path.usesInterfaceType(.wifi)
            let wired = path.usesInterfaceType(.wiredEthernet)
            let cellular = path.usesInterfaceType(.cellular)
            let expensive = path.isExpensive
            let signature = "\(satisfied)|\(wifi)|\(wired)|\(cellular)|\(expensive)"
            DispatchQueue.main.async {
                guard let self else { return }
                self.isConnected = satisfied
                self.isWifi = wifi
                self.isExpensive = expensive
                if satisfied {
                    if wifi {
                        self.statusText = "Connected via Wi-Fi"
                    } else if wired {
                        self.statusText = "Connected via Ethernet"
                    } else if cellular {
                        self.statusText = "Connected via cellular"
                    } else {
                        self.statusText = "Connected (interface unknown)"
                    }
                } else {
                    self.statusText = "No network connection"
                }
                // The first update just records the baseline — the app has
                // only just started discovery, restarting it there would be
                // pure churn. Every later change bumps the token.
                if self.lastSignature == nil {
                    self.lastSignature = signature
                } else if self.lastSignature != signature {
                    self.lastSignature = signature
                    self.changeToken &+= 1
                }
            }
        }
        monitor.start(queue: queue)
    }

    deinit {
        monitor.cancel()
    }
}
