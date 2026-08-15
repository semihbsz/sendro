//
//  LiveActivityController.swift
//  Sendro
//
//  App-side driver for the SendroActivity Live Activity (Dynamic Island +
//  Lock Screen). Everything is behind `#available(iOS 16.1, *)`: the app's
//  floor is 16.0 and it must behave identically when Live Activities don't
//  exist, are disabled in Settings, or the widget extension isn't installed.
//
//  Policy:
//  - Start only for transfers ≥ 200 MB. A 4 MB photo does not deserve the
//    Dynamic Island.
//  - Update at most once per second (ActivityKit throttles aggressively and
//    budget-limits chatty apps).
//  - Always end with a final state, on success and on failure.
//
//  No App Group is involved — ActivityKit ships the state across itself,
//  which is what makes this compatible with free personal-team signing.
//

import Foundation

#if canImport(ActivityKit)
import ActivityKit
#endif

@MainActor
final class LiveActivityController {

    static let shared = LiveActivityController()

    /// Below this a Live Activity is noise.
    static let minimumBytes: Int64 = 200 * 1024 * 1024

    /// transferId -> Activity<SendroTransferAttributes>, stored as Any so the
    /// property itself needs no availability annotation.
    private var activities: [String: Any] = [:]
    private var lastUpdate: [String: TimeInterval] = [:]

    private init() {}

    var isSupported: Bool {
        if #available(iOS 16.1, *) { return true }
        return false
    }

    // MARK: Lifecycle

    func start(transferId: String, fileName: String, totalBytes: Int64, senderName: String) {
        guard totalBytes >= Self.minimumBytes else { return }
        guard #available(iOS 16.1, *) else { return }
        requestActivity(transferId: transferId,
                        fileName: fileName,
                        totalBytes: totalBytes,
                        senderName: senderName)
    }

    func update(transferId: String,
                phase: SendroActivityPhase,
                bytesReceived: Int64,
                speedBytesPerSecond: Double,
                etaSeconds: Int?,
                force: Bool = false) {
        guard #available(iOS 16.1, *) else { return }
        guard activities[transferId] != nil else { return }
        let now = Date.timeIntervalSinceReferenceDate
        if !force, let last = lastUpdate[transferId], now - last < 1.0 { return }
        lastUpdate[transferId] = now
        pushState(transferId: transferId,
                  phase: phase,
                  bytesReceived: bytesReceived,
                  speedBytesPerSecond: speedBytesPerSecond,
                  etaSeconds: etaSeconds)
    }

    func end(transferId: String, phase: SendroActivityPhase, bytesReceived: Int64) {
        guard #available(iOS 16.1, *) else { return }
        finish(transferId: transferId, phase: phase, bytesReceived: bytesReceived)
    }

    /// A crash or a force-quit can leave an activity on the Lock Screen with
    /// no app behind it. Called once at engine start.
    func endStaleActivities() {
        guard #available(iOS 16.1, *) else { return }
        clearAll()
    }

    // MARK: - iOS 16.1+ implementation
    //
    // ActivityKit's 16.1 entry points (request(attributes:contentState:),
    // update(using:), end(using:dismissalPolicy:)) are used deliberately:
    // they exist on every OS this app runs on. The 16.2 ActivityContent
    // variants would need a second code path for 16.1 devices and buy
    // nothing here beyond silencing a deprecation warning.

    #if canImport(ActivityKit)

    @available(iOS 16.1, *)
    private func requestActivity(transferId: String,
                                 fileName: String,
                                 totalBytes: Int64,
                                 senderName: String) {
        guard activities[transferId] == nil else { return }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let attributes = SendroTransferAttributes(fileName: fileName,
                                                  totalBytes: totalBytes,
                                                  senderName: senderName)
        let state = SendroTransferAttributes.ContentState(phase: .downloading,
                                                          bytesReceived: 0,
                                                          speedBytesPerSecond: 0,
                                                          etaSeconds: nil)
        do {
            let activity = try Activity<SendroTransferAttributes>.request(attributes: attributes,
                                                                          contentState: state,
                                                                          pushType: nil)
            activities[transferId] = activity
            lastUpdate[transferId] = Date.timeIntervalSinceReferenceDate
        } catch {
            // Budget exhausted / activities disabled mid-flight: the transfer
            // itself is unaffected, so this is intentionally silent.
        }
    }

    @available(iOS 16.1, *)
    private func pushState(transferId: String,
                           phase: SendroActivityPhase,
                           bytesReceived: Int64,
                           speedBytesPerSecond: Double,
                           etaSeconds: Int?) {
        guard let activity = activities[transferId] as? Activity<SendroTransferAttributes> else { return }
        let state = SendroTransferAttributes.ContentState(phase: phase,
                                                          bytesReceived: bytesReceived,
                                                          speedBytesPerSecond: speedBytesPerSecond,
                                                          etaSeconds: etaSeconds)
        Task {
            await activity.update(using: state)
        }
    }

    @available(iOS 16.1, *)
    private func finish(transferId: String, phase: SendroActivityPhase, bytesReceived: Int64) {
        guard let activity = activities.removeValue(forKey: transferId)
                as? Activity<SendroTransferAttributes> else { return }
        lastUpdate[transferId] = nil
        let state = SendroTransferAttributes.ContentState(phase: phase,
                                                          bytesReceived: bytesReceived,
                                                          speedBytesPerSecond: 0,
                                                          etaSeconds: nil)
        Task {
            await activity.end(using: state, dismissalPolicy: .default)
        }
    }

    @available(iOS 16.1, *)
    private func clearAll() {
        activities.removeAll()
        lastUpdate.removeAll()
        Task {
            for activity in Activity<SendroTransferAttributes>.activities {
                await activity.end(using: activity.contentState, dismissalPolicy: .immediate)
            }
        }
    }

    #else

    private func requestActivity(transferId: String, fileName: String,
                                 totalBytes: Int64, senderName: String) {}
    private func pushState(transferId: String, phase: SendroActivityPhase,
                           bytesReceived: Int64, speedBytesPerSecond: Double,
                           etaSeconds: Int?) {}
    private func finish(transferId: String, phase: SendroActivityPhase, bytesReceived: Int64) {}
    private func clearAll() {}

    #endif
}
