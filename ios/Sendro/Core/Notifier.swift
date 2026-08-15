//
//  Notifier.swift
//  Sendro
//
//  Local notifications (UNUserNotificationCenter) — no push server, no
//  entitlement, no APNs. Everything here is scheduled by the app itself with
//  a nil trigger, i.e. "show this now".
//
//  BEST-EFFORT BY DESIGN: iOS suspends and eventually terminates a
//  backgrounded app. Sendro's outbox long-poll only runs while the app is
//  running (or briefly backgrounded), so these notifications fire for things
//  that arrive in that window — they are NOT push, and the app must never
//  promise "you'll be notified even if Sendro is closed".
//
//  Privacy: a §11 text message NEVER puts its text in a notification body.
//  The body says "Sent you text" and nothing else.
//

import Foundation
import UserNotifications
import Combine

/// Where a notification tap should land.
enum NotificationRoute: String {
    case receive
    case send
    case library
}

/// Which surface the user is looking at right now — used to suppress banners
/// for things that are already visible.
enum AppSurface: String {
    case receive
    case send
    case library
    /// The full-screen Flight view is covering everything else.
    case flight
}

final class Notifier: NSObject, ObservableObject, UNUserNotificationCenterDelegate {

    /// Set when the user taps a notification; RootView consumes and clears it.
    @Published var pendingRoute: NotificationRoute?

    private let settings: Settings

    /// Guards the presentation context, which is written on the main thread
    /// and read on UNUserNotificationCenter's delegate queue.
    private let lock = NSLock()
    private var appActive = true
    private var surface: AppSurface = .receive

    private static let didAskKey = "sendro.notificationsAsked"

    init(settings: Settings) {
        self.settings = settings
        super.init()
        UNUserNotificationCenter.current().delegate = self
    }

    // MARK: Context (called from the UI)

    func updateContext(active: Bool, surface: AppSurface) {
        lock.lock()
        self.appActive = active
        self.surface = surface
        lock.unlock()
    }

    private func snapshot() -> (active: Bool, surface: AppSurface) {
        lock.lock()
        defer { lock.unlock() }
        return (appActive, surface)
    }

    // MARK: Authorization

    /// Asked once, at the first moment it makes sense: right after a pairing
    /// succeeds. Never at launch — a permission sheet before the user has
    /// anything to be notified about is the classic way to get denied.
    func requestAuthorizationAfterPairing() {
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: Self.didAskKey) else { return }
        let center = UNUserNotificationCenter.current()
        let key = Self.didAskKey
        center.getNotificationSettings { current in
            guard current.authorizationStatus == .notDetermined else {
                defaults.set(true, forKey: key)
                return
            }
            center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in
                defaults.set(true, forKey: key)
            }
        }
    }

    // MARK: Posting

    /// Offers landed in the outbox poll.
    func notifyIncomingOffers(count: Int, senderName: String) {
        guard settings.notifyTransfers, count > 0 else { return }
        guard shouldPost(for: .receive) else { return }
        let body = count == 1
            ? "1 file is waiting for you."
            : "\(count) files are waiting for you."
        post(title: "\(senderName) wants to send \(count == 1 ? "a file" : "\(count) files")",
             body: body,
             route: .receive,
             identifier: "sendro.offers")
    }

    /// A transfer finished (or failed) while the user wasn't watching it.
    func notifyTransferFinished(fileName: String, savedTo: String?) {
        guard settings.notifyTransfers else { return }
        guard shouldPost(for: .library) else { return }
        let destination = savedTo == "photos" ? "Photos" : "Files"
        post(title: "Saved to \(destination)",
             body: fileName,
             route: .library,
             identifier: "sendro.done.\(fileName)")
    }

    func notifyTransferFailed(fileName: String, reason: String) {
        guard settings.notifyTransfers else { return }
        guard shouldPost(for: .receive) else { return }
        post(title: "Transfer failed",
             body: "\(fileName) — \(reason)",
             route: .receive,
             identifier: "sendro.failed.\(fileName)")
    }

    /// §11 text. The text itself is deliberately absent from the payload.
    func notifyMessage(senderName: String) {
        guard settings.notifyMessages else { return }
        guard shouldPost(for: .receive) else { return }
        post(title: senderName,
             body: "Sent you text",
             route: .receive,
             identifier: "sendro.message")
    }

    /// Don't post at all when the user is already looking at the surface the
    /// notification would send them to.
    private func shouldPost(for route: NotificationRoute) -> Bool {
        let state = snapshot()
        guard state.active else { return true }
        return !surfaceShows(route, state.surface)
    }

    private func surfaceShows(_ route: NotificationRoute, _ surface: AppSurface) -> Bool {
        switch route {
        case .receive:  return surface == .receive || surface == .flight
        case .send:     return surface == .send
        case .library:  return surface == .library || surface == .flight
        }
    }

    private func post(title: String, body: String, route: NotificationRoute, identifier: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.userInfo = ["route": route.rawValue]
        let request = UNNotificationRequest(identifier: "\(identifier).\(UUID().uuidString)",
                                            content: content,
                                            trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    // MARK: UNUserNotificationCenterDelegate

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler:
                                    @escaping (UNNotificationPresentationOptions) -> Void) {
        let route = Self.route(from: notification.request.content.userInfo)
        let state = snapshot()
        if state.active, let route, surfaceShows(route, state.surface) {
            completionHandler([])           // already on screen — stay quiet
        } else {
            completionHandler([.banner, .sound])
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let route = Self.route(from: response.notification.request.content.userInfo)
        DispatchQueue.main.async { [weak self] in
            self?.pendingRoute = route
            completionHandler()
        }
    }

    private static func route(from userInfo: [AnyHashable: Any]) -> NotificationRoute? {
        guard let raw = userInfo["route"] as? String else { return nil }
        return NotificationRoute(rawValue: raw)
    }
}
