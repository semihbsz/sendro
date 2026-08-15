//
//  HotspotHelp.swift
//  Sendro
//
//  "No Wi-Fi router?" — Sendro's transfer path is LAN-only and needs no
//  internet at all, so a hotspot is a perfectly good network. This card
//  explains the two setups that work, and which one needs Manual Connect / QR
//  because Bonjour is unreliable there.
//
//  Used in the Devices sheet and in Network Diagnostics.
//

import SwiftUI

struct HotspotHelpCard: View {

    /// Starts collapsed in the Devices sheet, expanded in Diagnostics.
    var initiallyExpanded: Bool = false
    /// Optional shortcut into the manual/QR flows from the card itself.
    var onManualConnect: (() -> Void)? = nil
    var onScanQR: (() -> Void)? = nil

    @State private var expanded: Bool

    init(initiallyExpanded: Bool = false,
         onManualConnect: (() -> Void)? = nil,
         onScanQR: (() -> Void)? = nil) {
        self.initiallyExpanded = initiallyExpanded
        self.onManualConnect = onManualConnect
        self.onScanQR = onScanQR
        _expanded = State(initialValue: initiallyExpanded)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                withAnimation(.easeOut(duration: 0.2)) { expanded.toggle() }
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: "personalhotspot")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(Theme.irisSoft)
                    Text("No Wi-Fi router?")
                        .font(Theme.sans(14.5, .semibold))
                        .foregroundColor(Theme.textPrimary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(Theme.textBase.opacity(0.4))
                        .rotationEffect(.degrees(expanded ? 0 : -90))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(PressableButtonStyle())

            if expanded {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Sendro never touches the internet — it only needs the two devices on the same local network. A hotspot is one.")
                        .font(Theme.sans(12.5))
                        .foregroundColor(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)

                    option(number: "1",
                           title: "PC's Mobile Hotspot",
                           detail: "Windows › Settings › Network & internet › Mobile hotspot. Join it from this iPhone. Discovery normally works, and full LAN speed with it.",
                           recommended: true)

                    option(number: "2",
                           title: "iPhone Personal Hotspot",
                           detail: "Turn on Personal Hotspot and let the PC join it. Bonjour discovery is unreliable on this one, so use Manual Connect or QR — the Sendro window on your PC shows the hotspot address it got (usually 172.20.10.x).",
                           recommended: false)

                    if onManualConnect != nil || onScanQR != nil {
                        HStack(spacing: 10) {
                            if let onScanQR {
                                Button(action: onScanQR) {
                                    smallAction(title: "Scan QR", systemImage: "qrcode.viewfinder")
                                }
                                .buttonStyle(PressableButtonStyle())
                            }
                            if let onManualConnect {
                                Button(action: onManualConnect) {
                                    smallAction(title: "Connect by IP", systemImage: "number")
                                }
                                .buttonStyle(PressableButtonStyle())
                            }
                        }
                    }

                    Text("Cellular data is not used for the transfer itself; on iPhone Personal Hotspot the PC may still route its own internet through your data plan.")
                        .font(Theme.sans(11.5))
                        .foregroundColor(Theme.textFaint)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.top, 14)
                .transition(.opacity)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassRow(cornerRadius: 20, fillOpacity: 0.05, borderOpacity: 0.09)
    }

    private func option(number: String, title: String, detail: String, recommended: Bool) -> some View {
        HStack(alignment: .top, spacing: 11) {
            ZStack {
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .fill(recommended ? Theme.teal.opacity(0.16) : Color.white.opacity(0.07))
                Text(number)
                    .font(Theme.mono(11, .semibold))
                    .foregroundColor(recommended ? Theme.teal : Theme.textBase.opacity(0.6))
            }
            .frame(width: 26, height: 26)

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(title)
                        .font(Theme.sans(13.5, .semibold))
                        .foregroundColor(Theme.textPrimary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                    if recommended {
                        Text("EASIEST")
                            .font(Theme.mono(8.5, .medium))
                            .tracking(0.6)
                            .foregroundColor(Theme.teal)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(Capsule().fill(Theme.teal.opacity(0.14)))
                    }
                }
                Text(detail)
                    .font(Theme.sans(12))
                    .foregroundColor(Theme.textBase.opacity(0.55))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func smallAction(title: String, systemImage: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: systemImage)
                .font(.system(size: 11, weight: .semibold))
            Text(title)
                .font(Theme.sans(12.5, .medium))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .foregroundColor(Theme.irisSoft)
        .frame(maxWidth: .infinity)
        .frame(height: 38)
        .glassRow(cornerRadius: 13, fillOpacity: 0.06, borderOpacity: 0.1)
    }
}
