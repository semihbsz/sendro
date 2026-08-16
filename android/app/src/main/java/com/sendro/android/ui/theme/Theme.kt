package com.sendro.android.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Sendro design language, ported token for token from
 * ios/Sendro/Views/Theme.swift.
 *
 * Near-black canvas with a top iris glow, glass cards, iris (periwinkle) as
 * the energy accent and the brand teal for "verified / online". System sans
 * for prose, monospace for the technical micro-labels.
 *
 * Iris values are oklch() from the design prototype, gamut-mapped to sRGB:
 *   oklch(0.72 0.18 265) -> #78A1FF   (primary iris)
 *   oklch(0.78 0.15 265) -> #95B6FF   (soft iris — labels, links)
 *   oklch(0.82 0.15 265) -> #A8C4FF   (bright iris — glows)
 */
object Sendro {

    // -- Palette ------------------------------------------------------------

    val bg = Color(0xFF07080B)
    val bgGlow = Color(0xFF161A2E)

    val iris = Color(0xFF78A1FF)
    val irisSoft = Color(0xFF95B6FF)
    val irisBright = Color(0xFFA8C4FF)

    val teal = Color(0xFF37E6C4)

    /** Beam gradient ends — the app icon's stroke gradient. */
    val tealDeep = Color(0xFF1FB78F)
    val tealBright = Color(0xFF6BF2D6)

    /** The icon tile's background gradient. */
    val markTileTop = Color(0xFF151A21)
    val markTileBottom = Color(0xFF0A0C10)

    val danger = Color(0xFFFF7878)
    val warn = Color(0xFFFFB86B)

    /** Ink used on top of iris / teal filled buttons. */
    val onAccent = Color(0xFF0A0B14)

    val textPrimary = Color(0xFFF5F6FA)

    /** Base for secondary text — always used with an alpha from the ramp. */
    val textBase = Color(0xFFF2F3F7)

    /**
     * The opacity ramp. On a TV the low end of it is simply invisible from a
     * sofa, so the alpha is lifted towards a floor — the *relationship*
     * between the three steps survives, the illegibility does not.
     */
    val textSecondary: Color
        @Composable get() = textBase.copy(alpha = rampAlpha(0.55f))
    val textTertiary: Color
        @Composable get() = textBase.copy(alpha = rampAlpha(0.42f))
    val textFaint: Color
        @Composable get() = textBase.copy(alpha = rampAlpha(0.35f))

    /** Static variants for the rare non-composable use (theme construction). */
    val textSecondaryStatic = textBase.copy(alpha = 0.55f)

    @Composable
    private fun rampAlpha(base: Float): Float {
        val floor = LocalDeviceProfile.current.minTextAlpha
        return if (floor <= 0f) base else maxOf(base, floor - (0.55f - base) * 0.35f)
    }

    // -- Type ---------------------------------------------------------------

    /**
     * Body/prose type.
     *
     * These are `@Composable` so they can read [LocalDeviceProfile]: on a TV
     * every size is scaled and body text gets a hard floor, without a single
     * call site changing. Every use is already inside a composable.
     */
    @Composable
    fun sans(size: Float, weight: FontWeight = FontWeight.Normal): TextStyle {
        val scaled = scaleType(size, LocalDeviceProfile.current)
        return TextStyle(
            fontFamily = FontFamily.Default,
            fontSize = scaled.sp,
            fontWeight = weight,
            lineHeight = (scaled * 1.32f).sp,
        )
    }

    /**
     * The technical voice: sizes, hashes, IPs, section tags. Monospace is the
     * one typographic signal that separates "data" from "prose" in Sendro,
     * and it is the same on both platforms.
     */
    @Composable
    fun mono(
        size: Float,
        weight: FontWeight = FontWeight.Normal,
        tracking: Float = 0f,
    ): TextStyle {
        val scaled = scaleType(size, LocalDeviceProfile.current)
        return TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = scaled.sp,
            fontWeight = weight,
            letterSpacing = tracking.sp,
            lineHeight = (scaled * 1.3f).sp,
        )
    }

    /**
     * Ten-foot type: 1.3x everywhere, and a hard 18sp floor for anything that
     * was already body-sized. Micro-labels (the 9–11sp mono tags) are scaled
     * but not floored — flooring them too would turn a tracked caption into a
     * second headline.
     */
    private fun scaleType(size: Float, profile: DeviceProfile): Float {
        if (!profile.isTv) return size
        val scaled = size * profile.typeScale
        return if (size >= 13f) maxOf(scaled, 18f) else scaled
    }

    // -- Shapes -------------------------------------------------------------

    val cardShape: Shape = RoundedCornerShape(26.dp)
    val rowShape: Shape = RoundedCornerShape(18.dp)
    val pillShape: Shape = RoundedCornerShape(16.dp)
}

/**
 * Material3 is present as a base (Switch, TextField, ripples) but every colour
 * is Sendro's. Nothing in the app reads a Material token expecting Material
 * defaults.
 */
@Composable
fun SendroTheme(
    profile: DeviceProfile = rememberDeviceProfile(),
    content: @Composable () -> Unit,
) {
    val colors = darkColorScheme(
        primary = Sendro.iris,
        onPrimary = Sendro.onAccent,
        secondary = Sendro.teal,
        onSecondary = Sendro.onAccent,
        background = Sendro.bg,
        onBackground = Sendro.textPrimary,
        surface = Sendro.bg,
        onSurface = Sendro.textPrimary,
        surfaceVariant = Color.White.copy(alpha = 0.06f),
        onSurfaceVariant = Sendro.textSecondaryStatic,
        error = Sendro.danger,
        onError = Sendro.onAccent,
        outline = Color.White.copy(alpha = 0.12f),
    )
    // The profile has to be in scope BEFORE the typography is built, because
    // Sendro.sans/mono read it to decide the TV type scale.
    CompositionLocalProvider(LocalDeviceProfile provides profile) {
        MaterialTheme(
            colorScheme = colors,
            typography = Typography(
                bodyLarge = Sendro.sans(15f),
                bodyMedium = Sendro.sans(13.5f),
                labelLarge = Sendro.sans(14f, FontWeight.Medium),
            ),
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------
// Canvas background
// ---------------------------------------------------------------------------

/**
 * Near-black canvas with the radial iris glow bleeding in from above.
 *
 * The glow is drawn, not laid out: a 520dp child would inflate the parent's
 * size past any phone width. `drawBehind` never affects layout.
 */
@Composable
fun SendroBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Sendro.bg)
            .drawBehind {
                val radius = 260.dp.toPx()
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to Sendro.iris.copy(alpha = 0.22f),
                            0.5f to Sendro.iris.copy(alpha = 0.06f),
                            1f to Color.Transparent,
                        ),
                        center = Offset(size.width / 2f, -40.dp.toPx()),
                        radius = radius,
                    ),
                    radius = radius,
                    center = Offset(size.width / 2f, -40.dp.toPx()),
                )
            },
        content = content,
    )
}

// ---------------------------------------------------------------------------
// Glass surfaces
// ---------------------------------------------------------------------------

/**
 * Big hero glass card (incoming offer, sheet panels).
 *
 * NOTE: there is no backdrop blur. Compose's `Modifier.blur` is a RenderEffect
 * that only exists from API 31 and blurs the *content*, not what is behind it
 * — there is no `.ultraThinMaterial` equivalent below 31. The translucent
 * white gradient carries the same read on the dark canvas, and looks identical
 * on every supported device instead of good on new ones and flat on old ones.
 */
@Composable
fun Modifier.glassCard(
    cornerRadius: Dp = 26.dp,
    shadow: Dp = 20.dp,
): Modifier {
    val profile = LocalDeviceProfile.current
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .shadow(
            elevation = shadow,
            shape = shape,
            clip = false,
            ambientColor = Color.Black,
            spotColor = Color.Black,
        )
        .clip(shape)
        .background(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.09f + profile.glassFillBoost),
                    Color.White.copy(alpha = 0.035f + profile.glassFillBoost * 0.8f),
                ),
            ),
        )
        .border(
            width = if (profile.isTv) 1.dp else 0.5.dp,
            color = Color.White.copy(alpha = 0.13f + profile.glassBorderBoost),
            shape = shape,
        )
}

/** Quiet list-row glass. */
@Composable
fun Modifier.glassRow(
    cornerRadius: Dp = 18.dp,
    fillAlpha: Float = 0.045f,
    borderAlpha: Float = 0.07f,
): Modifier {
    val profile = LocalDeviceProfile.current
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .clip(shape)
        .background(Color.White.copy(alpha = fillAlpha + profile.glassFillBoost))
        .border(
            width = if (profile.isTv) 1.dp else 0.5.dp,
            color = Color.White.copy(alpha = borderAlpha + profile.glassBorderBoost),
            shape = shape,
        )
}

/**
 * The D-pad focus indicator.
 *
 * Drawn, not laid out: a border/padding change on focus would reflow the whole
 * row every time the remote moves. `drawWithContent` paints an iris ring plus a
 * soft outer glow just outside the element's bounds, which costs nothing and
 * never shifts anything.
 *
 * It is deliberately NOT colour-only — the ring is a shape change and it is
 * paired with a scale in [com.sendro.android.ui.components.Pressable], so it
 * survives a colour-blind viewer and a badly calibrated TV panel alike.
 */
fun Modifier.sendroFocusRing(
    focused: Boolean,
    cornerRadius: Dp,
    ringWidth: Dp,
    color: Color = Sendro.irisBright,
): Modifier = this.drawWithContent {
    drawContent()
    if (!focused) return@drawWithContent
    val stroke = ringWidth.toPx()
    val inset = stroke * 1.5f
    val radius = cornerRadius.toPx() + inset
    // Soft outer glow first, then the crisp ring on top.
    drawRoundRect(
        color = color.copy(alpha = 0.22f),
        topLeft = Offset(-inset * 2f, -inset * 2f),
        size = Size(size.width + inset * 4f, size.height + inset * 4f),
        cornerRadius = CornerRadius(radius + inset, radius + inset),
        style = Stroke(width = stroke * 2.4f),
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(-inset, -inset),
        size = Size(size.width + inset * 2f, size.height + inset * 2f),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = stroke),
    )
}

// ---------------------------------------------------------------------------
// The beam mark (must match the shipped app icon)
// ---------------------------------------------------------------------------

/**
 * The Sendro beam, pixel-faithful to the home-screen icon.
 *
 * Every constant is in the icon's 1024x1024 design space
 * (scripts/generate_icons.py) and scaled into the view:
 *
 *   beam   M 252 708 C 560 708, 452 316, 700 316   stroke 88, round caps
 *          gradient #1FB78F -> #37E6C4 -> #6BF2D6 along (300,712)->(756,312)
 *   dot    r 42 at (806, 316), #6BF2D6
 *   tile   #151A21 -> #0A0C10 vertical, corner radius 228/1024
 *   edge   6/1024 hairline at 5.5% white
 */
private const val ICON_CANVAS = 1024f

/** The bare beam path in the icon's 1024-space, scaled by [unit]. */
private fun beamPath(unit: Float, originX: Float, originY: Float): Path {
    fun x(v: Float) = originX + v * unit
    fun y(v: Float) = originY + v * unit
    return Path().apply {
        moveTo(x(252f), y(708f))
        cubicTo(x(560f), y(708f), x(452f), y(316f), x(700f), y(316f))
    }
}

@Composable
fun BeamMark(side: Dp = 28.dp, modifier: Modifier = Modifier) {
    val corner = side * (228f / ICON_CANVAS)
    Canvas(
        modifier = modifier
            .size(side)
            .clip(RoundedCornerShape(corner))
            .border(
                width = side * (6f / ICON_CANVAS),
                color = Color.White.copy(alpha = 0.055f),
                shape = RoundedCornerShape(corner),
            ),
    ) {
        drawBeamMark(this)
    }
}

/** Split out so both [BeamMark] and any decorative use share one drawing. */
private fun drawBeamMark(scope: DrawScope) = with(scope) {
    val unit = minOf(size.width, size.height) / ICON_CANVAS
    val originX = (size.width - ICON_CANVAS * unit) / 2f
    val originY = (size.height - ICON_CANVAS * unit) / 2f

    fun px(v: Float) = originX + v * unit
    fun py(v: Float) = originY + v * unit

    // Tile: vertical gradient + the faint teal ambient wash.
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Sendro.markTileTop, Sendro.markTileBottom),
            startY = originY,
            endY = originY + ICON_CANVAS * unit,
        ),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to Sendro.teal.copy(alpha = 0.10f),
                0.55f to Sendro.teal.copy(alpha = 0.03f),
                1f to Color.Transparent,
            ),
            center = Offset(px(512f), py(330f)),
            radius = 620f * unit,
        ),
        radius = 620f * unit,
        center = Offset(px(512f), py(330f)),
    )

    val path = beamPath(unit, originX, originY)
    val gradient = Brush.linearGradient(
        colorStops = arrayOf(
            0f to Sendro.tealDeep,
            0.62f to Sendro.teal,
            1f to Sendro.tealBright,
        ),
        start = Offset(px(300f), py(712f)),
        end = Offset(px(756f), py(312f)),
    )

    // Glow pass (the icon draws stroke-width 92 at 0.32 alpha plus a soft dot;
    // a real blur is not available in a DrawScope, so the glow is approximated
    // by a wider, softer stroke underneath — the same read at icon sizes).
    drawPath(
        path = path,
        color = Sendro.teal.copy(alpha = 0.20f),
        style = Stroke(width = 116f * unit, cap = StrokeCap.Round),
    )
    drawCircle(
        color = Sendro.teal.copy(alpha = 0.22f),
        radius = 62f * unit,
        center = Offset(px(806f), py(316f)),
    )

    // The mark itself.
    drawPath(
        path = path,
        brush = gradient,
        style = Stroke(width = 88f * unit, cap = StrokeCap.Round),
    )
    drawCircle(
        color = Sendro.tealBright,
        radius = 42f * unit,
        center = Offset(px(806f), py(316f)),
    )
}

// ---------------------------------------------------------------------------
// Small primitives
// ---------------------------------------------------------------------------

/** Status dot with an expanding pulse ring when active. */
@Composable
fun PulseDot(
    color: Color = Sendro.teal,
    active: Boolean = true,
    side: Dp = 7.dp,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseScale",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseAlpha",
    )
    Canvas(modifier = modifier.size(side * 2.6f)) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val radius = side.toPx() / 2f
        if (active) {
            drawCircle(color = color.copy(alpha = alpha), radius = radius * scale, center = centre)
        }
        drawCircle(
            color = if (active) color else Sendro.textBase.copy(alpha = 0.3f),
            radius = radius,
            center = centre,
        )
    }
}

/**
 * The hatched square that stands in for a preview: the same 135-degree
 * repeating hatch the prototype uses, drawn straight into a Canvas.
 */
fun DrawScope.drawHatch(cornerRadiusPx: Float, alpha: Float = 1f) {
    val stripe = 5.dp.toPx()
    val clip = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                radiusX = cornerRadiusPx,
                radiusY = cornerRadiusPx,
            ),
        )
    }
    clipPath(clip) {
        drawRect(Color.White.copy(alpha = 0.04f * alpha), size = Size(size.width, size.height))
        var x = -size.height
        while (x < size.width + size.height) {
            drawLine(
                color = Color.White.copy(alpha = 0.07f * alpha),
                start = Offset(x, 0f),
                end = Offset(x + size.height, size.height),
                strokeWidth = stripe,
            )
            x += stripe * 2
        }
    }
}
