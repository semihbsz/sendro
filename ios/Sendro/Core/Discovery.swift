//
//  Discovery.swift
//  Sendro
//
//  Bonjour discovery of "_sendro._tcp" hosts (PROTOCOL.md §2).
//  NWBrowser finds service instances + TXT records; each result is then
//  resolved to a concrete ip:port by opening a short-lived NWConnection and
//  reading the ready path's remoteEndpoint.
//

import Foundation
import Network
import Combine

// MARK: - Model

struct DiscoveredHost: Identifiable, Equatable {
    let deviceId: String        // TXT "id"
    var name: String            // TXT "nm" (fallback: instance name)
    var platform: String        // TXT "pf"
    var protocolVersion: Int    // TXT "v"
    var ipAddress: String?      // resolved
    var port: UInt16?           // resolved

    var id: String { deviceId }
    var isResolved: Bool { ipAddress != nil && port != nil }
}

// MARK: - Service

final class DiscoveryService: ObservableObject {

    enum BrowserStatus: Equatable {
        case idle
        case browsing
        case permissionDenied      // local network permission refused
        case failed(String)
    }

    @Published private(set) var hosts: [DiscoveredHost] = []
    @Published private(set) var status: BrowserStatus = .idle

    private var browser: NWBrowser?
    /// deviceId -> resolved endpoint
    private var resolved: [String: (ip: String, port: UInt16)] = [:]
    /// endpoints currently being resolved (endpoint debug key)
    private var resolving: Set<String> = []
    /// latest parsed results keyed by deviceId
    private var latest: [String: (host: DiscoveredHost, endpoint: NWEndpoint)] = [:]

    // kDNSServiceErr_PolicyDenied — local network permission denied.
    private static let policyDeniedCode: Int32 = -65570

    func start() {
        stop()
        let parameters = NWParameters()
        parameters.includePeerToPeer = false
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: "_sendro._tcp", domain: nil),
                                using: parameters)
        browser.stateUpdateHandler = { [weak self] state in
            DispatchQueue.main.async {
                guard let self else { return }
                switch state {
                case .setup:
                    break
                case .ready:
                    self.status = .browsing
                case .waiting(let error):
                    if case .dns(let code) = error, code == Self.policyDeniedCode {
                        self.status = .permissionDenied
                    } else {
                        self.status = .failed(error.localizedDescription)
                    }
                case .failed(let error):
                    if case .dns(let code) = error, code == Self.policyDeniedCode {
                        self.status = .permissionDenied
                    } else {
                        self.status = .failed(error.localizedDescription)
                    }
                case .cancelled:
                    self.status = .idle
                @unknown default:
                    break
                }
            }
        }
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            DispatchQueue.main.async {
                self?.handleResults(results)
            }
        }
        self.browser = browser
        status = .browsing
        browser.start(queue: .main)
    }

    func stop() {
        browser?.cancel()
        browser = nil
        status = .idle
    }

    func restart() {
        resolved.removeAll()
        resolving.removeAll()
        latest.removeAll()
        hosts = []
        start()
    }

    // MARK: Results

    private func handleResults(_ results: Set<NWBrowser.Result>) {
        var next: [String: (host: DiscoveredHost, endpoint: NWEndpoint)] = [:]

        for result in results {
            guard case .bonjour(let txt) = result.metadata else { continue }
            guard let deviceId = txt["id"], !deviceId.isEmpty else { continue }
            let version = Int(txt["v"] ?? "") ?? 1
            var name = txt["nm"] ?? ""
            if name.isEmpty, case .service(let instanceName, _, _, _) = result.endpoint {
                name = instanceName
            }
            let platform = txt["pf"] ?? "windows"

            var host = DiscoveredHost(deviceId: deviceId,
                                      name: name.isEmpty ? "Unknown PC" : name,
                                      platform: platform,
                                      protocolVersion: version,
                                      ipAddress: nil,
                                      port: nil)
            if let ep = resolved[deviceId] {
                host.ipAddress = ep.ip
                host.port = ep.port
            }
            next[deviceId] = (host, result.endpoint)
        }

        latest = next
        // Drop cached resolutions for hosts that disappeared.
        resolved = resolved.filter { next[$0.key] != nil }
        publish()

        for (deviceId, entry) in next where resolved[deviceId] == nil {
            resolve(deviceId: deviceId, endpoint: entry.endpoint)
        }
    }

    private func publish() {
        hosts = latest.values.map { pair in
            var host = pair.host
            if let ep = resolved[host.deviceId] {
                host.ipAddress = ep.ip
                host.port = ep.port
            }
            return host
        }
        .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    // MARK: Resolution

    /// Resolve a Bonjour service endpoint to ip:port by opening a TCP
    /// connection, reading currentPath.remoteEndpoint, then cancelling.
    private func resolve(deviceId: String, endpoint: NWEndpoint) {
        let key = "\(deviceId)|\(endpoint)"
        guard !resolving.contains(key) else { return }
        resolving.insert(key)

        let connection = NWConnection(to: endpoint, using: .tcp)
        var finished = false

        let finish: (String?, UInt16?) -> Void = { [weak self] ip, port in
            DispatchQueue.main.async {
                guard let self else { return }
                guard !finished else { return }
                finished = true
                connection.cancel()
                self.resolving.remove(key)
                if let ip, let port {
                    self.resolved[deviceId] = (ip, port)
                    self.publish()
                }
            }
        }

        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                if let remote = connection.currentPath?.remoteEndpoint,
                   case .hostPort(let host, let port) = remote {
                    finish(Self.string(for: host), port.rawValue)
                } else {
                    finish(nil, nil)
                }
            case .failed, .cancelled:
                finish(nil, nil)
            default:
                break
            }
        }
        connection.start(queue: .main)

        // Give up after 5 seconds.
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) {
            finish(nil, nil)
        }
    }

    /// Render an NWEndpoint.Host as a URL-usable host string.
    static func string(for host: NWEndpoint.Host) -> String {
        switch host {
        case .ipv4(let address):
            return "\(address)"
        case .ipv6(let address):
            // Strip any scope suffix ("%en0") — bracket form for URLs.
            let raw = "\(address)"
            let bare = raw.split(separator: "%").first.map(String.init) ?? raw
            return "[\(bare)]"
        case .name(let name, _):
            return name
        @unknown default:
            return ""
        }
    }
}
