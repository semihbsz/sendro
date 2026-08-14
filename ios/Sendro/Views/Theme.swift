//
//  Theme.swift
//  Sendro
//
//  The new Sendro design language: near-black canvas with a top iris glow,
//  glass cards, iris (periwinkle) energy accent + brand teal for "verified",
//  SF Pro for prose and SF Mono for data labels.
//
//  Iris values are oklch() from the design prototype, gamut-mapped to sRGB:
//    oklch(0.72 0.18 265) -> #78A1FF   (primary iris)
//    oklch(0.78 0.15 265) -> #95B6FF   (soft iris — labels, links)
//    oklch(0.82 0.15 265) -> #A8C4FF   (bright iris — glows)
//

import SwiftUI

// MARK: - Palette

enum Theme {

    static let bg          = Color(red: 0x07 / 255, green: 0x08 / 255, blue: 0x0B / 255)
    static let bgGlow      = Color(red: 0x16 / 255, green: 0x1A / 255, blue: 0x2E / 255)

    static let iris        = Color(red: 0x78 / 255, green: 0xA1 / 255, blue: 0xFF / 255)
    static let irisSoft    = Color(red: 0x95 / 255, green: 0xB6 / 255, blue: 0xFF / 255)
    static let irisBright  = Color(red: 0xA8 / 255, green: 0xC4 / 255, blue: 0xFF / 255)
    static let teal        = Color(red: 0x37 / 255, green: 0xE6 / 255, blue: 0xC4 / 255)
    static let danger      = Color(red: 1.0,        green: 0x78 / 255, blue: 0x78 / 255)
    static let warn        = Color(red: 1.0,        green: 0xB8 / 255, blue: 0x6B / 255)

    /// Ink used on top of iris / teal filled buttons.
    static let onAccent    = Color(red: 0x0A / 255, green: 0x0B / 255, blue: 0x14 / 255)

    static let textPrimary = Color(red: 0xF5 / 255, green: 0xF6 / 255, blue: 0xFA / 255)
    /// Base for secondary text — use with .opacity().
    static let textBase    = Color(red: 0xF2 / 255, green: 0xF3 / 255, blue: 0xF7 / 255)

    static var textSecondary: Color { textBase.opacity(0.55) }
    static var textTertiary: Color  { textBase.opacity(0.42) }
    static var textFaint: Color     { textBase.opacity(0.35) }

    // MARK: Type

    static func sans(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .default)
    }

    static func mono(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .monospaced)
    }
}

// MARK: - Canvas background

/// Near-black canvas with the radial iris glow bleeding in from above.
struct SendroBackground: View {
    var body: some View {
        ZStack(alignment: .top) {
            Theme.bg
            RadialGradient(colors: [Theme.iris.opacity(0.22),
                                    Theme.iris.opacity(0.06),
                                    .clear],
                           center: .center,
                           startRadius: 0,
                           endRadius: 260)
                .frame(width: 520, height: 520)
                .offset(y: -220)
        }
        .ignoresSafeArea()
    }
}

// MARK: - Glass surfaces

/// Big hero glass card (incoming offer, sheet panels).
struct GlassCard: ViewModifier {
    var cornerRadius: CGFloat = 26

    func body(content: Content) -> some View {
        content
            .background(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(LinearGradient(colors: [Color.white.opacity(0.09),
                                                  Color.white.opacity(0.035)],
                                         startPoint: .topLeading,
                                         endPoint: .bottomTrailing))
                    .background(.ultraThinMaterial,
                                in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            )
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.13), lineWidth: 0.5)
            )
            .shadow(color: .black.opacity(0.5), radius: 30, x: 0, y: 20)
    }
}

/// Quiet list-row glass.
struct GlassRow: ViewModifier {
    var cornerRadius: CGFloat = 18
    var fillOpacity: Double = 0.045
    var borderOpacity: Double = 0.07

    func body(content: Content) -> some View {
        content
            .background(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(Color.white.opacity(fillOpacity))
            )
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(Color.white.opacity(borderOpacity), lineWidth: 0.5)
            )
    }
}

extension View {
    func glassCard(cornerRadius: CGFloat = 26) -> some View {
        modifier(GlassCard(cornerRadius: cornerRadius))
    }

    func glassRow(cornerRadius: CGFloat = 18,
                  fillOpacity: Double = 0.045,
                  borderOpacity: Double = 0.07) -> some View {
        modifier(GlassRow(cornerRadius: cornerRadius,
                          fillOpacity: fillOpacity,
                          borderOpacity: borderOpacity))
    }
}

// MARK: - Buttons

/// Scale-on-press, like the prototype's style-active transforms.
struct PressableButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1.0)
            .animation(.spring(response: 0.25, dampingFraction: 0.7),
                       value: configuration.isPressed)
    }
}

/// Filled primary action (iris by default).
struct AccentPillLabel: View {
    let title: String
    var color: Color = Theme.iris
    var height: CGFloat = 48

    var body: some View {
        Text(title)
            .font(Theme.sans(15.5, .semibold))
            .foregroundColor(Theme.onAccent)
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(color))
            .shadow(color: color.opacity(0.4), radius: 14, x: 0, y: 8)
    }
}

/// Quiet glass action.
struct GhostPillLabel: View {
    let title: String
    var textColor: Color = Theme.textBase.opacity(0.7)
    var height: CGFloat = 48

    var body: some View {
        Text(title)
            .font(Theme.sans(15.5, .medium))
            .foregroundColor(textColor)
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .glassRow(cornerRadius: 16, fillOpacity: 0.07, borderOpacity: 0.1)
    }
}

// MARK: - Labels

/// Uppercase tracked mono section label ("INCOMING", "RECENT", …).
struct SectionTag: View {
    let text: String
    var color: Color = Theme.textFaint

    var body: some View {
        Text(text.uppercased())
            .font(Theme.mono(10.5, .medium))
            .tracking(1.8)
            .foregroundColor(color)
    }
}

// MARK: - File type badge

/// Hatched square with the uppercase extension, standing in for previews.
struct FileBadge: View {
    let fileName: String
    var side: CGFloat = 44
    var cornerRadius: CGFloat = 13

    private var ext: String {
        let e = (fileName as NSString).pathExtension.uppercased()
        return e.isEmpty ? "FILE" : String(e.prefix(4))
    }

    var body: some View {
        ZStack {
            HatchPattern()
                .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            Text(ext)
                .font(Theme.mono(side < 40 ? 8.5 : 9.5, .semibold))
                .foregroundColor(Theme.textBase.opacity(0.72))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .padding(.horizontal, 3)
        }
        .frame(width: side, height: side)
        .overlay(
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .strokeBorder(Color.white.opacity(0.1), lineWidth: 0.5)
        )
    }
}

/// Cheap 135° repeating hatch, like the prototype's repeating-linear-gradient.
struct HatchPattern: View {
    var body: some View {
        GeometryReader { geo in
            let size = max(geo.size.width, geo.size.height)
            Canvas { context, _ in
                let stripe: CGFloat = 5
                context.fill(Path(CGRect(origin: .zero,
                                         size: CGSize(width: size, height: size))),
                             with: .color(Color.white.opacity(0.04)))
                var x: CGFloat = -size
                while x < size * 2 {
                    var path = Path()
                    path.move(to: CGPoint(x: x, y: 0))
                    path.addLine(to: CGPoint(x: x + size, y: size))
                    context.stroke(path,
                                   with: .color(Color.white.opacity(0.07)),
                                   lineWidth: stripe)
                    x += stripe * 2
                }
            }
        }
    }
}

// MARK: - Beam mark

/// The Sendro beam mark: an S-curve beam with a terminal dot, drawn as a
/// Path (no assets). Stroke it in white on an iris gradient tile.
struct BeamMarkShape: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        let w = rect.width
        let h = rect.height
        // S-curve rising from bottom-left to upper-right.
        path.move(to: CGPoint(x: rect.minX + 0.18 * w, y: rect.minY + 0.82 * h))
        path.addCurve(to: CGPoint(x: rect.minX + 0.5 * w, y: rect.minY + 0.5 * h),
                      control1: CGPoint(x: rect.minX + 0.62 * w, y: rect.minY + 0.94 * h),
                      control2: CGPoint(x: rect.minX + 0.24 * w, y: rect.minY + 0.48 * h))
        path.addCurve(to: CGPoint(x: rect.minX + 0.74 * w, y: rect.minY + 0.26 * h),
                      control1: CGPoint(x: rect.minX + 0.76 * w, y: rect.minY + 0.52 * h),
                      control2: CGPoint(x: rect.minX + 0.44 * w, y: rect.minY + 0.18 * h))
        return path
    }
}

/// App-icon-like tile: rounded square, iris gradient, beam + dot.
struct BeamMark: View {
    var side: CGFloat = 28

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: side * 0.29, style: .continuous)
                .fill(LinearGradient(colors: [Theme.iris, Theme.bgGlow],
                                     startPoint: .topLeading,
                                     endPoint: .bottomTrailing))
            BeamMarkShape()
                .stroke(Color.white,
                        style: StrokeStyle(lineWidth: max(1.6, side * 0.09),
                                           lineCap: .round))
                .frame(width: side * 0.86, height: side * 0.86)
            Circle()
                .fill(Theme.teal)
                .frame(width: side * 0.14, height: side * 0.14)
                .offset(x: side * 0.27, y: -side * 0.27)
        }
        .frame(width: side, height: side)
        .overlay(
            RoundedRectangle(cornerRadius: side * 0.29, style: .continuous)
                .strokeBorder(Color.white.opacity(0.15), lineWidth: 0.5)
        )
    }
}

// MARK: - Pulse dot

/// Small status dot with an expanding pulse ring when active.
struct PulseDot: View {
    var color: Color = Theme.teal
    var active: Bool = true
    var side: CGFloat = 7

    var body: some View {
        ZStack {
            if active {
                PulseRing(color: color, side: side)
            }
            Circle()
                .fill(active ? color : Theme.textBase.opacity(0.3))
                .frame(width: side, height: side)
                .shadow(color: active ? color.opacity(0.8) : .clear, radius: 6)
        }
    }
}

/// The expanding ring; owns its animation so it restarts on insertion.
private struct PulseRing: View {
    let color: Color
    let side: CGFloat

    @State private var pulsing = false

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: side, height: side)
            .scaleEffect(pulsing ? 2.4 : 1.0)
            .opacity(pulsing ? 0 : 0.9)
            .onAppear {
                withAnimation(.easeOut(duration: 2.2).repeatForever(autoreverses: false)) {
                    pulsing = true
                }
            }
    }
}

