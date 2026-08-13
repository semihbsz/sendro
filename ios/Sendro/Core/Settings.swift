//
//  Settings.swift
//  Sendro
//
//  UserDefaults-backed app settings. SwiftUI views bind with
//  @AppStorage(Settings.Keys.*) against the same keys; this object is the
//  read side for the engine and other non-View code, so both always agree.
//

import Foundation
import Combine
import UIKit

final class Settings: ObservableObject {

    enum Keys {
        static let deviceName = "sendro.deviceName"
        static let autoAcceptFromTrusted = "sendro.autoAcceptFromTrusted"
        static let saveMediaToPhotos = "sendro.saveMediaToPhotos"
        static let deleteTempAfterImport = "sendro.deleteTempAfterImport"
        static let addToSendroAlbum = "sendro.addToSendroAlbum"
        static let clientDeviceId = "sendro.clientDeviceId"
    }

    enum SaveMediaMode: String, CaseIterable, Identifiable {
        case always
        case ask
        case never

        var id: String { rawValue }

        var label: String {
            switch self {
            case .always: return "Always"
            case .ask:    return "Ask Every Time"
            case .never:  return "Never"
            }
        }
    }

    private let defaults = UserDefaults.standard

    /// Stable client deviceId (UUID v4, lowercase), generated once.
    var clientDeviceId: String {
        if let existing = defaults.string(forKey: Keys.clientDeviceId) {
            return existing
        }
        let fresh = UUID().uuidString.lowercased()
        defaults.set(fresh, forKey: Keys.clientDeviceId)
        return fresh
    }

    var deviceName: String {
        get {
            let stored = defaults.string(forKey: Keys.deviceName)
            if let stored, !stored.trimmingCharacters(in: .whitespaces).isEmpty {
                return stored
            }
            return UIDevice.current.name
        }
        set {
            defaults.set(newValue, forKey: Keys.deviceName)
            objectWillChange.send()
        }
    }

    var autoAcceptFromTrusted: Bool {
        get { defaults.object(forKey: Keys.autoAcceptFromTrusted) as? Bool ?? false }
        set { defaults.set(newValue, forKey: Keys.autoAcceptFromTrusted); objectWillChange.send() }
    }

    var saveMediaToPhotos: SaveMediaMode {
        get {
            guard let raw = defaults.string(forKey: Keys.saveMediaToPhotos),
                  let mode = SaveMediaMode(rawValue: raw) else { return .always }
            return mode
        }
        set { defaults.set(newValue.rawValue, forKey: Keys.saveMediaToPhotos); objectWillChange.send() }
    }

    var deleteTempAfterImport: Bool {
        get { defaults.object(forKey: Keys.deleteTempAfterImport) as? Bool ?? true }
        set { defaults.set(newValue, forKey: Keys.deleteTempAfterImport); objectWillChange.send() }
    }

    var addToSendroAlbum: Bool {
        get { defaults.object(forKey: Keys.addToSendroAlbum) as? Bool ?? true }
        set { defaults.set(newValue, forKey: Keys.addToSendroAlbum); objectWillChange.send() }
    }
}
