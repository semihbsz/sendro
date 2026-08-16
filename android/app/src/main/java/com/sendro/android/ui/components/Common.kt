package com.sendro.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.text.font.FontWeight
import com.sendro.android.ui.theme.LocalDeviceProfile
import com.sendro.android.ui.theme.Sendro
import com.sendro.android.ui.theme.drawHatch
import com.sendro.android.ui.theme.glassRow
import com.sendro.android.ui.theme.sendroFocusRing

/**
 * Shared atoms. Everything here is a direct port of a SwiftUI view in
 * ios/Sendro/Views/Theme.swift so the two apps read as one product.
 */

/** Uppercase tracked mono section label ("INCOMING", "RECENT", ...). */
@Composable
fun SectionTag(
    text: String,
    color: Color = Sendro.textFaint,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = Sendro.mono(10.5f, FontWeight.Medium, tracking = 1.8f),
        color = color,
        modifier = modifier,
    )
}

/**
 * The one interactive primitive. Every tappable surface in Sendro goes through
 * it, which is what makes the whole app D-pad-navigable in one change.
 *
 * `Modifier.clickable` already does two thirds of the work: it makes the node
 * focusable and it activates on DPAD_CENTER / ENTER / NUMPAD_ENTER as well as
 * on a tap (foundation's own key mapping). Deliberately no second
 * `Modifier.focusable()` — that would install a *second* focus target on the
 * same node and the remote would need two presses to walk past it.
 *
 * What is added here:
 *  - a visible focus indicator (iris ring + glow + scale), because with a
 *    remote there is no cursor and no touch feedback;
 *  - an optional [focusRequester] so a screen can put initial focus somewhere
 *    sensible;
 *  - press-scale, as before, for the phone.
 */
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    /** Corner radius of the focus ring; match the content's own shape. */
    focusCorner: Dp = 16.dp,
    onFocused: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val profile = LocalDeviceProfile.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }

    val target = when {
        pressed -> 0.97f
        focused -> profile.focusScale
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "pressScale",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .sendroFocusRing(
                focused = focused && enabled,
                cornerRadius = focusCorner,
                ringWidth = profile.focusRingWidth,
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                if (focused != it.isFocused) {
                    focused = it.isFocused
                    onFocused(it.isFocused)
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        content()
    }
}

/**
 * Requests focus once, and only on a device that is actually driven by a
 * remote. On a phone this would paint a focus ring nobody asked for.
 *
 * `requestFocus()` throws if the node is not attached yet (it can lose the
 * race with the first frame on a slow TV box), hence the retry-free
 * `runCatching` — a failed request just means the user presses D-pad once.
 */
@Composable
fun RequestInitialFocus(requester: FocusRequester, key: Any? = Unit) {
    val profile = LocalDeviceProfile.current
    LaunchedEffect(key, profile.autoFocusOnEnter) {
        if (!profile.autoFocusOnEnter) return@LaunchedEffect
        // One frame of slack so the target is composed and attached.
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
}

/** Filled primary action (iris by default). */
@Composable
fun AccentPill(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Sendro.iris,
    height: Dp = 48.dp,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    Pressable(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        focusRequester = focusRequester,
        focusCorner = 16.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(16.dp))
                .background(if (enabled) color else color.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                style = Sendro.sans(15.5f, FontWeight.SemiBold),
                color = Sendro.onAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

/** Quiet glass action. */
@Composable
fun GhostPill(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = Sendro.textBase.copy(alpha = 0.7f),
    height: Dp = 48.dp,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    Pressable(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        focusRequester = focusRequester,
        focusCorner = 16.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .glassRow(cornerRadius = 16.dp, fillAlpha = 0.07f, borderAlpha = 0.10f),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                style = Sendro.sans(15.5f, FontWeight.Medium),
                color = if (enabled) textColor else textColor.copy(alpha = 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

/** Hatched square with the uppercase extension, standing in for a preview. */
@Composable
fun FileBadge(
    fileName: String,
    modifier: Modifier = Modifier,
    side: Dp = 44.dp,
    cornerRadius: Dp = 13.dp,
) {
    val extension = remember(fileName) {
        val raw = fileName.substringAfterLast('.', "").uppercase()
        if (raw.isEmpty()) "FILE" else raw.take(4)
    }
    Box(
        modifier = modifier
            .size(side)
            .clip(RoundedCornerShape(cornerRadius))
            .border(0.5.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawHatch(cornerRadiusPx = cornerRadius.toPx())
        }
        Text(
            text = extension,
            style = Sendro.mono(if (side < 40.dp) 8.5f else 9.5f, FontWeight.SemiBold),
            color = Sendro.textBase.copy(alpha = 0.72f),
            maxLines = 1,
        )
    }
}

/** Small coloured capsule used for phases and outcomes. */
@Composable
fun StatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text.uppercase(),
            style = Sendro.mono(9f, FontWeight.Medium, tracking = 0.8f),
            color = color,
            maxLines = 1,
        )
    }
}

/** Flat 3dp progress bar in the row style. */
@Composable
fun ThinProgress(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val radius = size.height / 2f
        drawRoundRect(
            color = Color.White.copy(alpha = 0.08f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        )
        val width = (size.width * fraction.coerceIn(0f, 1f)).coerceAtLeast(size.height)
        drawRoundRect(
            color = color,
            size = androidx.compose.ui.geometry.Size(width, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        )
    }
}

/** Section header with an optional trailing action. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = Sendro.textFaint,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SectionTag(title, color)
        if (actionLabel != null && onAction != null) {
            Pressable(onClick = onAction, focusCorner = 8.dp) {
                Text(
                    text = actionLabel,
                    style = Sendro.sans(12f, FontWeight.Medium),
                    color = Sendro.irisSoft,
                )
            }
        }
    }
}

/** A titled glass card with a coloured accent, used for every explainer. */
@Composable
fun NoticeCard(
    title: String,
    message: String,
    tint: Color,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassRow(cornerRadius = 22.dp, fillAlpha = 0.05f, borderAlpha = 0.09f)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = Sendro.sans(15f, FontWeight.SemiBold), color = tint)
        Text(message, style = Sendro.sans(13f), color = Sendro.textSecondary)
        if (actionLabel != null && onAction != null) {
            Pressable(onClick = onAction, focusCorner = 12.dp) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(tint)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Text(
                        actionLabel,
                        style = Sendro.sans(13f, FontWeight.SemiBold),
                        color = Sendro.onAccent,
                    )
                }
            }
        }
    }
}

/**
 * Vertical clearance at the top of every screen.
 *
 * On a phone that is the status bar; on a TV there is no status bar but there
 * IS overscan, and a title flush against the panel edge can genuinely be
 * cropped by the set. Whichever is larger wins.
 */
@Composable
fun TopInsetSpacer() {
    val profile = LocalDeviceProfile.current
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Spacer(Modifier.height(maxOf(statusBar, profile.topPadding)))
}

/**
 * Page padding for every scrolling surface.
 *
 * On a phone the bottom value is clearance for the floating tab bar; on a TV
 * the tab bar is a real sibling in a Column and the margin is overscan
 * instead. Read from the device profile so no screen hardcodes either.
 */
@Composable
fun screenPadding(top: Dp = 8.dp): PaddingValues {
    val profile = LocalDeviceProfile.current
    return PaddingValues(
        start = profile.horizontalPadding,
        end = profile.horizontalPadding,
        top = top,
        bottom = profile.scrollBottomPadding,
    )
}
