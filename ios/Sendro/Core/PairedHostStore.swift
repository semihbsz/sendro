//
//  PairedHostStore.swift
//  Sendro
//
//  Persistent registry of paired Windows hosts. The bearer token itself
//  lives in the Keychain (KeychainStore); this store keeps metadata plus
//  the last known reachable endpoint.
//

import Foundation
import Combine

struct PairedHost: Codable, Identifiable, Equatable {
    let deviceId: String        // host deviceId (uuid, lowercase)
    var name: String
    var lastHost: String        // ip or hostname
    var lastPort: UInt16
    var pairedAtMs: Int64

    var id: String { deviceId }
}

final class PairedHostStore: ObservableObject {

    @Published private(set) var hosts: [PairedHost] = []

    private static let defaultsKey = "sendro.pairedHosts"

    init() {
        load()
    }

    func host(id: String) -> PairedHost? {
        hosts.first { $0.deviceId == id }
    }

    func add(_ host: PairedHost) {
        var list = hosts.filter { $0.deviceId != host.deviceId }
        list.append(host)
        hosts = list.sorted { $0.pairedAtMs < $1.pairedAtMs }
        save()
    }

    func remove(id: String) {
        hosts.removeAll { $0.deviceId == id }
        save()
    }

    /// Called when discovery resolves a fresh address for a paired host.
    func updateEndpoint(id: String, host: String, port: UInt16, name: String?) {
        guard let idx = hosts.firstIndex(where: { $0.deviceId == id }) else { return }
        var entry = hosts[idx]
        var changed = false
        if entry.lastHost != host { entry.lastHost = host; changed = true }
        if entry.lastPort != port { entry.lastPort = port; changed = true }
        if let name, !name.isEmpty, entry.name != name { entry.name = name; changed = true }
        if changed {
            hosts[idx] = entry
            save()
        }
    }

    private func load() {
        guard let data = UserDefaults.standard.data(forKey: Self.defaultsKey) else { return }
        if let decoded = try? JSONDecoder().decode([PairedHost].self, from: data) {
            hosts = decoded
        }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(hosts) {
            UserDefaults.standard.set(data, forKey: Self.defaultsKey)
        }
    }
}
