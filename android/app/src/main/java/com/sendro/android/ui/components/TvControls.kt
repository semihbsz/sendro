package com.sendro.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.sendro.android.ui.theme.LocalDeviceProfile
import com.sendro.android.ui.theme.Sendro
import com.sendro.android.ui.theme.glassRow

/**
 * A D-pad numeric keypad.
 *
 * On a TV a `TextField` means summoning the leanback IME, moving a cursor
 * around an on-screen keyboard, and pressing OK eleven times to type six
 * digits. A 3x4 grid of real buttons is two presses per digit and needs no
 * IME at all — and because every key is an ordinary [Pressable], focus
 * traversal is the natural 2D search with no explicit wiring.
 *
 * [onDigit] receives '0'..'9' (and '.' when [includeDot] is set).
 */
@Composable
fun DpadKeypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    includeDot: Boolean = false,
    firstKeyFocus: FocusRequester? = null,
    keyHeight: Dp = 56.dp,
) {
    // A FULL 3x4 grid, never a hole. An empty cell breaks Compose's 2D focus
    // search: pressing Down from the key above a gap has no candidate that
    // overlaps it horizontally, and the remote simply stops. The bottom-left
    // slot is therefore always a real key — the decimal point when an address
    // is being typed, otherwise Clear.
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
        listOf(if (includeDot) '.' else 'C', '0', '⌫'),
    )
    Column(
        // focusGroup keeps the remote inside the pad while it is being used:
        // a Right press from the last column stays in the grid instead of
        // jumping to whatever happens to sit beside it.
        modifier = modifier.focusGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { columnIndex, key ->
                    KeypadKey(
                        label = key.toString(),
                        onClick = {
                            when (key) {
                                '⌫' -> onBackspace()
                                'C' -> onClear()
                                else -> onDigit(key)
                            }
                        },
                        height = keyHeight,
                        focusRequester = if (rowIndex == 0 && columnIndex == 0) {
                            firstKeyFocus
                        } else {
                            null
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun KeypadKey(
    label: String,
    onClick: () -> Unit,
    height: Dp,
    focusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    Pressable(
        onClick = onClick,
        modifier = modifier,
        focusRequester = focusRequester,
        focusCorner = 14.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .glassRow(cornerRadius = 14.dp, fillAlpha = 0.07f, borderAlpha = 0.10f),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = Sendro.mono(20f, FontWeight.SemiBold),
                color = Sendro.textPrimary,
            )
        }
    }
}

/**
 * The six code boxes, shared by the phone (typed into a TextField) and the TV
 * (filled by [DpadKeypad]).
 */
@Composable
fun CodeBoxes(code: String, modifier: Modifier = Modifier) {
    val profile = LocalDeviceProfile.current
    val boxWidth = if (profile.isTv) 56.dp else 44.dp
    val boxHeight = if (profile.isTv) 72.dp else 56.dp
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(6) { index ->
            Box(
                modifier = Modifier
                    .width(boxWidth)
                    .height(boxHeight)
                    .glassRow(
                        cornerRadius = 14.dp,
                        fillAlpha = if (index < code.length) 0.10f else 0.045f,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = code.getOrNull(index)?.toString() ?: "",
                    style = Sendro.mono(if (profile.isTv) 26f else 22f, FontWeight.SemiBold),
                    color = Sendro.textPrimary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// QR rendering (local, offline)
// ---------------------------------------------------------------------------

/**
 * QR limits. A QR code tops out around 2 953 bytes at the lowest error
 * correction; §11 allows 32 KiB of text, so long payloads simply have no QR
 * and the card says so rather than showing a corrupt block.
 */
private const val QR_MAX_BYTES = 1200

/**
 * Encodes [text] to a QR matrix with the ZXing core already on the classpath
 * for the §13 scanner. Entirely local: no network, no image service, nothing
 * written to disk.
 *
 * @return null when the text is empty or too long to encode.
 */
fun encodeQr(text: String, moduleCount: Int = 0): BitMatrix? {
    if (text.isEmpty()) return null
    if (text.toByteArray(Charsets.UTF_8).size > QR_MAX_BYTES) return null
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    // The requested size is in modules, not pixels: the matrix is drawn as
    // vector rectangles, so there is no bitmap and no resampling anywhere.
    val requested = if (moduleCount > 0) moduleCount else 256
    return try {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, requested, requested, hints)
    } catch (_: WriterException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * Draws a QR matrix as rectangles on a Canvas.
 *
 * No Bitmap is allocated at any point — the matrix is a boolean grid and each
 * dark module is one `drawRect`, which is both sharper on a 4K panel and
 * cheaper than decoding an image.
 */
@Composable
fun QrCode(
    text: String,
    modifier: Modifier = Modifier,
    foreground: Color = Color.Black,
    background: Color = Color.White,
    quietZone: Dp = 10.dp,
) {
    val matrix = remember(text) { encodeQr(text) } ?: return
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .padding(quietZone),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val modules = matrix.width
            if (modules <= 0) return@Canvas
            val cell = size.minDimension / modules
            for (y in 0 until matrix.height) {
                for (x in 0 until modules) {
                    if (!matrix.get(x, y)) continue
                    drawRect(
                        color = foreground,
                        topLeft = Offset(x * cell, y * cell),
                        // A hair of overdraw so neighbouring modules do not
                        // show a seam from float rounding.
                        size = Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}

/** True when [text] will actually produce a QR (used to decide whether to offer one). */
fun canRenderQr(text: String): Boolean =
    text.isNotEmpty() && text.toByteArray(Charsets.UTF_8).size <= QR_MAX_BYTES

/** A square placeholder so a TV card keeps its layout when no QR is possible. */
@Composable
fun QrUnavailable(modifier: Modifier = Modifier, reason: String) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .glassRow(cornerRadius = 12.dp, fillAlpha = 0.05f, borderAlpha = 0.09f)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = reason,
            style = Sendro.mono(11f),
            color = Sendro.textTertiary,
        )
    }
}
