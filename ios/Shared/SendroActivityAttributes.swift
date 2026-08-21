//
//  SendroActivityAttributes.swift
//  Sendro — SHARED SOURCE
//
//  Compiled into BOTH the app target and the SendroActivity widget extension
//  (see ios/project.yml). ActivityKit passes the state between them, so this
//  needs no App Group — which is exactly why Live Activities are compatible
//  with free personal-team signing while a Share Extension is not.
//
//  Keep this file dependency-free: Foundation only, no Theme, no engine
//  types. Anything imported here has to exist in the widget target too.
//

import Foundation

#if canImport(ActivityKit)
import ActivityKit
#endif

/// Coarse phase shown on the Lock Screen / Dynamic Island. Plain String enum
/// so it stays Codable/Hashable and carries no availability of its own.
enum SendroActivityPhase: String, Codable, Hashable {
    /// Parked on purpose: queued behind other files, or waiting out the
    /// host's Retry-After. Nothing is streaming, so the Island must not
    /// claim otherwise.
    case waiting
    case downloading
    case verifying
    case saving
    case completed
    case failed

    var label: String {
        switch self {
        case .waiting:     return "Waiting for the PC"
        case .downloading: return "Receiving"
        case .verifying:   return "Verifying SHA-256"
        case .saving:      return "Saving"
        case .completed:   return "Saved"
        case .failed:      return "Failed"
        }
    }

    var systemImage: String {
        switch self {
        case .waiting:     return "hourglass"
        case .downloading: return "arrow.down.circle"
        case .verifying:   return "number"
        case .saving:      return "tray.and.arrow.down"
        case .completed:   return "checkmark.circle"
        case .failed:      return "exclamationmark.triangle"
        }
    }
}

#if canImport(ActivityKit)

/// One big incoming transfer, live on the Lock Screen and in the Dynamic
/// Island. Started only for transfers over ~200 MB (below that it is noise).
@available(iOS 16.1, *)
struct SendroTransferAttributes: ActivityAttributes {

    /// The moving parts. Updated at most once per second.
    struct ContentState: Codable, Hashable {
        var phase: SendroActivityPhase
        var bytesReceived: Int64
        var speedBytesPerSecond: Double
        var etaSeconds: Int?

        /// 0…1 — computed against the immutable total in the attributes.
        func fraction(of totalBytes: Int64) -> Double {
            guard totalBytes > 0 else { return phase == .completed ? 1 : 0 }
            if phase == .completed { return 1 }
            return min(1, max(0, Double(bytesReceived) / Double(totalBytes)))
        }
    }

    /// Immutable for the life of the activity.
    var fileName: String
    var totalBytes: Int64
    var senderName: String
}

#endif

// MARK: - Formatting (shared by app + widget)

/// Small, dependency-free formatters. The app has richer helpers in
/// ByteFormat; the widget target can't see them, so these live here and both
/// sides render identical strings.
enum SendroActivityFormat {

    static func bytes(_ value: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: max(0, value), countStyle: .file)
    }

    static func speed(_ bytesPerSecond: Double) -> String {
        guard bytesPerSecond > 1 else { return "—" }
        return bytes(Int64(bytesPerSecond)) + "/s"
    }

    static func eta(_ seconds: Int?) -> String {
        guard let seconds, seconds > 0 else { return "—" }
        if seconds < 60 { return "\(seconds)s" }
        if seconds < 3600 { return "\(seconds / 60)m \(seconds % 60)s" }
        return "\(seconds / 3600)h \((seconds % 3600) / 60)m"
    }

    static func percent(_ fraction: Double) -> String {
        "\(Int((min(1, max(0, fraction)) * 100).rounded()))%"
    }
}
