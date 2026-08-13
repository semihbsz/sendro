//
//  Keychain.swift
//  Sendro
//
//  Device-token storage. One generic-password item per paired host,
//  service "com.sendro.token", account = host deviceId.
//

import Foundation
import Security

enum KeychainStore {

    static let service = "com.sendro.token"

    private static func baseQuery(hostId: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: hostId
        ]
    }

    /// Store (or replace) the bearer token for a host. Returns true on success.
    @discardableResult
    static func saveToken(_ token: String, forHost hostId: String) -> Bool {
        deleteToken(forHost: hostId)
        var attributes = baseQuery(hostId: hostId)
        attributes[kSecValueData as String] = Data(token.utf8)
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        let status = SecItemAdd(attributes as CFDictionary, nil)
        return status == errSecSuccess
    }

    /// Load the token for a host, or nil if not paired / not found.
    static func token(forHost hostId: String) -> String? {
        var query = baseQuery(hostId: hostId)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Remove the token for a host (unpair).
    static func deleteToken(forHost hostId: String) {
        SecItemDelete(baseQuery(hostId: hostId) as CFDictionary)
    }
}
