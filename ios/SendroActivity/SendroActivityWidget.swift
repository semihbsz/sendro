//
//  SendroActivityWidget.swift
//  SendroActivity — Live Activity (Dynamic Island + Lock Screen)
//
//  This target's deployment target is iOS 16.1 (the app itself stays on
//  16.0), so no availability gymnastics are needed in here; the app side
//  guards every ActivityKit call with `#available(iOS 16.1, *)` and behaves
//  identically when the extension can't run.
//
//  The attributes type comes from ../Shared/SendroActivityAttributes.swift,
//  which is compiled into BOTH targets — ActivityKit passes the state across,
//  so no App Group (and therefore no paid-account entitlement) is involved.
//
//  Colors come from ../Sendro/Views/Theme.swift, also shared into this
//  target, so the Island matches the app exactly.
//

import ActivityKit
import WidgetKit
import SwiftUI

struct SendroTransferLiveActivity: Widget {

    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SendroTransferAttributes.self) { context in
            LockScreenView(attributes: context.attributes, state: context.state)
                .activityBackgroundTint(Color.black.opacity(0.6))
                .activitySystemActionForegroundColor(Theme.irisSoft)
        } dynamicIsland: { context in
            let fraction = context.state.fraction(of: context.attributes.totalBytes)
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    HStack(spacing: 8) {
                        Image(systemName: context.state.phase.systemImage)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(activityTint(for: context.state.phase))
                        Text(SendroActivityFormat.percent(fraction))
                            .font(.system(size: 15, weight: .semibold, design: .monospaced))
                            .foregroundColor(Theme.textPrimary)
                    }
                }
                DynamicIslandExpandedRegion(.trailing) {
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(SendroActivityFormat.speed(context.state.speedBytesPerSecond))
                            .font(.system(size: 12, weight: .medium, design: .monospaced))
                            .foregroundColor(Theme.textBase.opacity(0.85))
                            .lineLimit(1)
                        Text("ETA \(SendroActivityFormat.eta(context.state.etaSeconds))")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundColor(Theme.textBase.opacity(0.5))
                            .lineLimit(1)
                    }
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.attributes.fileName)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(Theme.textPrimary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 6) {
                        ProgressBar(fraction: fraction, tint: activityTint(for: context.state.phase))
                        HStack {
                            Text(context.state.phase.label)
                                .font(.system(size: 11, weight: .medium))
                                .foregroundColor(activityTint(for: context.state.phase))
                                .lineLimit(1)
                            Spacer(minLength: 6)
                            Text("\(SendroActivityFormat.bytes(context.state.bytesReceived)) / \(SendroActivityFormat.bytes(context.attributes.totalBytes))")
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundColor(Theme.textBase.opacity(0.5))
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                        }
                    }
                }
            } compactLeading: {
                Image(systemName: context.state.phase.systemImage)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(activityTint(for: context.state.phase))
            } compactTrailing: {
                Text(SendroActivityFormat.percent(fraction))
                    .font(.system(size: 13, weight: .semibold, design: .monospaced))
                    .foregroundColor(Theme.textBase)
            } minimal: {
                MinimalRing(fraction: fraction, tint: activityTint(for: context.state.phase))
            }
            .keylineTint(Theme.iris)
        }
    }

}

/// Free function on purpose: it is called from inside the escaping
/// `dynamicIsland:` builder closure, so it never touches `self`.
private func activityTint(for phase: SendroActivityPhase) -> Color {
    switch phase {
    case .downloading: return Theme.iris
    case .verifying:   return Theme.teal
    case .saving:      return Theme.teal
    case .completed:   return Theme.teal
    case .failed:      return Theme.danger
    }
}

// MARK: - Lock Screen

private struct LockScreenView: View {

    let attributes: SendroTransferAttributes
    let state: SendroTransferAttributes.ContentState

    private var fraction: Double { state.fraction(of: attributes.totalBytes) }

    private var tint: Color { activityTint(for: state.phase) }

    var body: some View {
        HStack(alignment: .center, spacing: 14) {
            Ring(fraction: fraction, tint: tint, phase: state.phase)
                .frame(width: 46, height: 46)

            VStack(alignment: .leading, spacing: 5) {
                Text(attributes.fileName)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(Theme.textPrimary)
                    .lineLimit(1)
                    .truncationMode(.middle)

                Text("\(state.phase.label) · from \(attributes.senderName)")
                    .font(.system(size: 11))
                    .foregroundColor(Theme.textBase.opacity(0.55))
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)

                ProgressBar(fraction: fraction, tint: tint)

                HStack(spacing: 6) {
                    Text("\(SendroActivityFormat.bytes(state.bytesReceived)) / \(SendroActivityFormat.bytes(attributes.totalBytes))")
                        .font(.system(size: 10.5, design: .monospaced))
                        .foregroundColor(Theme.textBase.opacity(0.5))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                    Spacer(minLength: 4)
                    if state.phase == .downloading {
                        Text("\(SendroActivityFormat.speed(state.speedBytesPerSecond)) · \(SendroActivityFormat.eta(state.etaSeconds))")
                            .font(.system(size: 10.5, design: .monospaced))
                            .foregroundColor(Theme.textBase.opacity(0.5))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                }
            }
        }
        .padding(14)
    }
}

// MARK: - Pieces

/// Progress ring with the percent (or the phase glyph) in the middle.
private struct Ring: View {

    let fraction: Double
    let tint: Color
    let phase: SendroActivityPhase

    var body: some View {
        ZStack {
            Circle()
                .stroke(Color.white.opacity(0.12), lineWidth: 4)
            Circle()
                .trim(from: 0, to: max(0.001, min(1, fraction)))
                .stroke(tint, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                .rotationEffect(.degrees(-90))
            if phase == .downloading {
                Text(SendroActivityFormat.percent(fraction))
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .foregroundColor(Theme.textPrimary)
                    .minimumScaleFactor(0.7)
                    .lineLimit(1)
            } else {
                Image(systemName: phase.systemImage)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(tint)
            }
        }
    }
}

private struct MinimalRing: View {

    let fraction: Double
    let tint: Color

    var body: some View {
        ZStack {
            Circle()
                .stroke(Color.white.opacity(0.2), lineWidth: 2.5)
            Circle()
                .trim(from: 0, to: max(0.001, min(1, fraction)))
                .stroke(tint, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .padding(1)
    }
}

/// Plain capsule bar — GeometryReader-free so it never fights the widget's
/// layout system.
private struct ProgressBar: View {

    let fraction: Double
    let tint: Color

    var body: some View {
        ProgressView(value: max(0, min(1, fraction)))
            .progressViewStyle(.linear)
            .tint(tint)
    }
}

// MARK: - Bundle

@main
struct SendroActivityBundle: WidgetBundle {
    var body: some Widget {
        SendroTransferLiveActivity()
    }
}
