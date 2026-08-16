package com.sendro.android.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What kind of machine Sendro is running on, and what it can do.
 *
 * ONE APK, phone-first, TV-capable. Everything TV-specific in the UI hangs off
 * this object: nothing branches on "is this a TV" by sniffing the model name,
 * and nothing on the phone changes because the TV needed something.
 *
 * The capability flags are separate from [isTv] on purpose — a tablet without
 * a camera and a TV without a camera should behave the same way, and a rare TV
 * box *with* a USB camera should still get the QR scanner.
 */
@Immutable
data class DeviceProfile(
    /** Leanback / TV UI mode: D-pad only, ten-foot viewing distance. */
    val isTv: Boolean = false,
    /** Any camera at all — gates the §13 QR scanner. */
    val hasCamera: Boolean = true,
    /** False on TV: no touch affordances, no drag-to-scroll. */
    val hasTouchscreen: Boolean = true,
    /** The Android Photo Picker (absent on most TV firmware). */
    val hasPhotoPicker: Boolean = true,
    /** A SAF document provider that can answer ACTION_OPEN_DOCUMENT. */
    val hasDocumentPicker: Boolean = true,
) {

    /**
     * Type scale. 1.3x on TV plus a hard floor on body text — the numbers come
     * from the ten-foot-UI rule of thumb: what is comfortable at 40 cm needs
     * roughly a third more size at 3 m.
     */
    val typeScale: Float get() = if (isTv) 1.3f else 1f

    /**
     * Overscan margin. Consumer TVs still crop 3–5% of the panel, and a
     * "full-screen" Android surface can genuinely lose its outermost pixels.
     * 48dp horizontal / 27dp vertical is ~5% of a 960x540dp (1080p at
     * density 2) TV surface.
     */
    val horizontalPadding: Dp get() = if (isTv) 48.dp else 20.dp
    val topPadding: Dp get() = if (isTv) 27.dp else 8.dp

    /**
     * Bottom padding for a scrolling surface. On the phone this is clearance
     * for the floating tab bar; on TV the tab bar is a real sibling in a
     * Column, so only the overscan margin is needed.
     */
    val scrollBottomPadding: Dp get() = if (isTv) 27.dp else 130.dp

    /**
     * TV panels are dim, glossy and usually in a lit room, and most of them
     * apply their own contrast/gamma "enhancement" on top. The phone's very
     * quiet glass disappears entirely; these boosts bring it back.
     */
    val glassFillBoost: Float get() = if (isTv) 0.05f else 0f
    val glassBorderBoost: Float get() = if (isTv) 0.10f else 0f

    /** Floor for the secondary/tertiary text alpha ramp. */
    val minTextAlpha: Float get() = if (isTv) 0.62f else 0f

    /** How thick the D-pad focus ring is drawn. */
    val focusRingWidth: Dp get() = if (isTv) 2.5.dp else 2.dp

    /** Focus scale-up. Subtle on a phone (rarely seen), obvious on a TV. */
    val focusScale: Float get() = if (isTv) 1.045f else 1.02f

    /**
     * Whether the UI should place focus on a sensible element when a screen
     * opens. On a touch device that would paint focus rings nobody asked for;
     * with a remote there is no other way in.
     */
    val autoFocusOnEnter: Boolean get() = isTv || !hasTouchscreen
}

val LocalDeviceProfile = staticCompositionLocalOf { DeviceProfile() }

/**
 * Detects the profile once per configuration.
 *
 * TV detection is deliberately belt-and-braces: `UI_MODE_TYPE_TELEVISION` is
 * the correct signal and every real Android TV sets it, but some cheap boxes
 * and emulators only report `FEATURE_LEANBACK`, and a few only give themselves
 * away by having no touchscreen.
 */
@Composable
fun rememberDeviceProfile(): DeviceProfile {
    val context = LocalContext.current
    // Keyed on the configuration so a uiMode change (or a TV box switching
    // between leanback and tablet mode) re-detects rather than sticking.
    val configuration = LocalConfiguration.current
    return remember(configuration.uiMode) { detectDeviceProfile(context) }
}

fun detectDeviceProfile(context: Context): DeviceProfile {
    val pm = context.packageManager
    val uiMode = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
        ?.currentModeType

    val hasTouchscreen = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    val isTv = uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
        pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        pm.hasSystemFeature("android.software.leanback_only") ||
        !hasTouchscreen

    val hasCamera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    val hasPhotoPicker = runCatching {
        ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)
    }.getOrDefault(false)

    val documentIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "*/*"
        addCategory(Intent.CATEGORY_OPENABLE)
    }
    val hasDocumentPicker = runCatching {
        documentIntent.resolveActivity(pm) != null
    }.getOrDefault(false)

    return DeviceProfile(
        isTv = isTv,
        hasCamera = hasCamera,
        hasTouchscreen = hasTouchscreen,
        hasPhotoPicker = hasPhotoPicker,
        hasDocumentPicker = hasDocumentPicker,
    )
}
