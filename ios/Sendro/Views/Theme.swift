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
    /// Beam gradient ends — these three are the app icon's stroke gradient
    /// (see scripts/generate_icons.py): #1FB78F → #37E6C4 → #6BF2D6.
    static let tealDeep    = Color(red: 0x1F / 255, green: 0xB7 / 255, blue: 0x8F / 255)
    static let tealBright  = Color(red: 0x6B / 255, green: 0xF2 / 255, blue: 0xD6 / 255)
    /// The icon tile's background gradient: #151A21 → #0A0C10.
    static let markTileTop    = Color(red: 0x15 / 255, green: 0x1A / 255, blue: 0x21 / 255)
    static let markTileBottom = Color(red: 0x0A / 255, green: 0x0C / 255, blue: 0x10 / 255)
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
///
/// LAYOUT NOTE: the 520pt glow disc must NOT be a layout child — a fixed
/// 520pt-wide sibling inflates the parent ZStack's union size past any
/// iPhone width (393pt) and the whole UI gets centered + clipped on both
/// edges. It lives in an .overlay (which never affects layout) and is
/// clipped, so this view always reports exactly the proposed size.
struct SendroBackground: View {
    var body: some View {
        Theme.bg
            .ignoresSafeArea()
            .overlay(alignment: .top) {
                RadialGradient(colors: [Theme.iris.opacity(0.22),
                                        Theme.iris.opacity(0.06),
                                        .clear],
                               center: .center,
                               startRadius: 0,
                               endRadius: 260)
                    .frame(width: 520, height: 520)
                    .offset(y: -220)
                    .allowsHitTesting(false)
            }
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
            .lineLimit(1)
            .minimumScaleFactor(0.75)
            .padding(.horizontal, 8)
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
            .lineLimit(1)
            .minimumScaleFactor(0.75)
            .padding(.horizontal, 8)
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

// MARK: - Beam mark (must match the shipped app icon)

/// The Sendro beam: the exact path the icon pipeline draws
/// (scripts/generate_icons.py), `M 252 708 C 560 708, 452 316, 700 316`,
/// expressed in the icon's 1024 × 1024 design space and scaled into the
/// view's rect. Stroke it 88/1024 wide with round caps.
struct BeamMarkShape: Shape {

    /// The icon's design canvas — every constant below is in this space.
    static let canvas: CGFloat = 1024

    func path(in rect: CGRect) -> Path {
        let unit = min(rect.width, rect.height) / Self.canvas
        // Centre the 1024-square inside a non-square rect so the geometry
        // never skews.
        let originX = rect.minX + (rect.width - Self.canvas * unit) / 2
        let originY = rect.minY + (rect.height - Self.canvas * unit) / 2
        func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: originX + x * unit, y: originY + y * unit)
        }
        var path = Path()
        path.move(to: point(252, 708))
        path.addCurve(to: point(700, 316),
                      control1: point(560, 708),
                      control2: point(452, 316))
        return path
    }
}

/// The app mark, pixel-faithful to the home-screen icon: the #151A21→#0A0C10
/// tile with its teal ambient glow, the gradient beam (#1FB78F → #37E6C4 →
/// #6BF2D6), the bright destination dot at (806, 316) r 42, and the 6/1024
/// hairline edge — all in the icon's 1024 space, scaled to `side`.
struct BeamMark: View {

    var side: CGFloat = 28

    /// One unit of the icon's 1024-space, in points.
    private var unit: CGFloat { side / BeamMarkShape.canvas }
    private var corner: CGFloat { 228 * unit }

    /// Offset of the destination dot's centre from the tile's centre.
    private var dotOffset: CGSize {
        CGSize(width: (806 - 512) * unit, height: (316 - 512) * unit)
    }

    private var beamGradient: LinearGradient {
        LinearGradient(gradient: Gradient(stops: [
            Gradient.Stop(color: Theme.tealDeep, location: 0),
            Gradient.Stop(color: Theme.teal, location: 0.62),
            Gradient.Stop(color: Theme.tealBright, location: 1)
        ]),
        startPoint: UnitPoint(x: 300 / BeamMarkShape.canvas, y: 712 / BeamMarkShape.canvas),
        endPoint: UnitPoint(x: 756 / BeamMarkShape.canvas, y: 312 / BeamMarkShape.canvas))
    }

    var body: some View {
        ZStack {
            // Tile: vertical gradient + the faint teal ambient wash.
            LinearGradient(colors: [Theme.markTileTop, Theme.markTileBottom],
                           startPoint: .top, endPoint: .bottom)
                .overlay(
                    RadialGradient(gradient: Gradient(stops: [
                        Gradient.Stop(color: Theme.teal.opacity(0.10), location: 0),
                        Gradient.Stop(color: Theme.teal.opacity(0.03), location: 0.55),
                        Gradient.Stop(color: Theme.teal.opacity(0), location: 1)
                    ]),
                    center: UnitPoint(x: 0.5, y: 330 / BeamMarkShape.canvas),
                    startRadius: 0,
                    endRadius: 620 * unit)
                )

            // Glow pass (stroke-width 92 @ 0.32 + dot r 46 @ 0.35, blurred).
            BeamMarkShape()
                .stroke(Theme.teal.opacity(0.32),
                        style: StrokeStyle(lineWidth: 92 * unit, lineCap: .round))
                .blur(radius: 22 * unit)
            Circle()
                .fill(Theme.teal.opacity(0.35))
                .frame(width: 92 * unit, height: 92 * unit)
                .offset(x: dotOffset.width, y: dotOffset.height)
                .blur(radius: 22 * unit)

            // The mark itself.
            BeamMarkShape()
                .stroke(beamGradient,
                        style: StrokeStyle(lineWidth: 88 * unit, lineCap: .round))
            Circle()
                .fill(Theme.tealBright)
                .frame(width: 84 * unit, height: 84 * unit)
                .offset(x: dotOffset.width, y: dotOffset.height)
        }
        .frame(width: side, height: side)
        .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: corner, style: .continuous)
                .strokeBorder(Color.white.opacity(0.055), lineWidth: 6 * unit)
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

