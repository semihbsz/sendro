package com.sendro.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sendro.android.SendroApplication
import com.sendro.android.core.Format
import com.sendro.android.core.SendroMessage
import com.sendro.android.ui.components.GhostPill
import com.sendro.android.ui.components.Pressable
import com.sendro.android.ui.components.QrCode
import com.sendro.android.ui.components.QrUnavailable
import com.sendro.android.ui.components.RequestInitialFocus
import com.sendro.android.ui.components.SectionTag
import com.sendro.android.ui.components.canRenderQr
import com.sendro.android.ui.theme.LocalDeviceProfile
import com.sendro.android.ui.theme.Sendro
import com.sendro.android.ui.theme.glassCard

/**
 * PROTOCOL.md §11.3 UI contract:
 *  - the card names the sender, shows the text (selectable, scrollable when
 *    long), and offers **Copy** and **Close**;
 *  - Copy writes to the OS clipboard, Close frees the memory;
 *  - nothing about the message is logged, persisted, or shown in history.
 *
 * The newest message is on top of the stack; the rest are counted, not
 * rendered, so a burst of twenty never buries the app.
 */
@Composable
fun MessageOverlay(
    app: SendroApplication,
    messages: List<SendroMessage>,
    modifier: Modifier = Modifier,
) {
    val profile = LocalDeviceProfile.current
    val newest = messages.lastOrNull()

    if (profile.isTv) {
        // On a TV a notification is invisible and a banner across the top is
        // unreadable from the sofa, so an arriving message takes the screen.
        // It is still the same RAM-only object — nothing about this path
        // persists it.
        if (newest != null) {
            TvMessageScreen(
                message = newest,
                remaining = messages.size - 1,
                onDismiss = { app.messages.dismiss(newest.messageId) },
                onDismissAll = { app.messages.clear() },
                modifier = modifier,
            )
        }
        return
    }

    AnimatedVisibility(
        visible = newest != null,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = modifier,
    ) {
        if (newest != null) {
            MessageCard(
                message = newest,
                remaining = messages.size - 1,
                onDismiss = { app.messages.dismiss(newest.messageId) },
                onDismissAll = { app.messages.clear() },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Phone
// ---------------------------------------------------------------------------

@Composable
private fun MessageCard(
    message: SendroMessage,
    remaining: Int,
    onDismiss: () -> Unit,
    onDismissAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassCard(cornerRadius = 22.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${message.senderName.ifBlank { "Your PC" }} sent you text",
                    style = Sendro.sans(14.5f, FontWeight.SemiBold),
                    color = Sendro.textPrimary,
                )
                Text(
                    // The reassurance is the point: this never touches disk.
                    text = "${Format.timestamp(message.sentAtMs)} · not saved anywhere",
                    style = Sendro.mono(10.5f),
                    color = Sendro.textTertiary,
                )
            }
            Pressable(onClick = onDismiss, focusCorner = 10.dp) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = Sendro.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = message.text,
                style = Sendro.sans(14f),
                color = Sendro.textBase.copy(alpha = 0.92f),
            )
        }

        Row(
            modifier = Modifier.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GhostPill(
                title = "Copy",
                onClick = {
                    copyToClipboard(context, message.text)
                    onDismiss()
                },
                height = 42.dp,
                textColor = Sendro.irisSoft,
                modifier = Modifier.weight(1f),
            )
            GhostPill(
                title = if (remaining > 0) "Close (+$remaining)" else "Close",
                onClick = { if (remaining > 0) onDismissAll() else onDismiss() },
                height = 42.dp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// TV
// ---------------------------------------------------------------------------

/**
 * The ten-foot version of the §11 card.
 *
 * Two problems this solves that a phone does not have:
 *  1. The text has to be readable from three metres — it is set large and
 *     given the whole screen.
 *  2. A TV has no keyboard and no browser worth using, so a link on screen is
 *     useless. The text is therefore ALSO rendered as a QR code: the user
 *     points a phone at the TV and opens it there. The QR is generated locally
 *     with the ZXing core already on the classpath — no network, no image
 *     service, and the matrix is drawn as vector rectangles rather than a
 *     bitmap, so nothing is ever written anywhere.
 *
 * The ephemerality contract is unchanged: this composable holds the same
 * in-RAM object, and Close drops it.
 */
@Composable
private fun TvMessageScreen(
    message: SendroMessage,
    remaining: Int,
    onDismiss: () -> Unit,
    onDismissAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val profile = LocalDeviceProfile.current
    val closeFocus = remember { FocusRequester() }
    // The card is the only thing on screen, so it takes focus outright —
    // otherwise the remote would still be driving the surface underneath it.
    RequestInitialFocus(closeFocus, key = message.messageId)

    val qrPossible = remember(message.text) { canRenderQr(message.text) }

    // The card covers the whole screen, so BACK must dismiss it rather than
    // fall through to the shell's tab handling.
    BackHandler(enabled = true) { onDismiss() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .padding(
                horizontal = profile.horizontalPadding,
                vertical = profile.topPadding,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassCard(cornerRadius = 28.dp)
                .padding(32.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SectionTag("Text from ${message.senderName.ifBlank { "your PC" }}", Sendro.irisSoft)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = message.text,
                        // Deliberately much larger than the 1.3x TV scale: this
                        // is the one thing on screen and it has to read across
                        // a room.
                        style = Sendro.sans(22f, FontWeight.Medium),
                        color = Sendro.textPrimary,
                    )
                }

                Text(
                    text = "${Format.timestamp(message.sentAtMs)} · held in memory only, " +
                        "never written to this TV",
                    style = Sendro.mono(11f),
                    color = Sendro.textTertiary,
                )

                Row(
                    modifier = Modifier.focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GhostPill(
                        title = if (remaining > 0) "Close (+$remaining more)" else "Close",
                        onClick = { if (remaining > 0) onDismissAll() else onDismiss() },
                        height = 52.dp,
                        focusRequester = closeFocus,
                        modifier = Modifier.weight(1f),
                    )
                    GhostPill(
                        title = "Copy",
                        onClick = {
                            copyToClipboard(context, message.text)
                            onDismiss()
                        },
                        height = 52.dp,
                        textColor = Sendro.irisSoft,
                        modifier = Modifier.weight(0.6f),
                    )
                }
            }

            Column(
                modifier = Modifier.width(260.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (qrPossible) {
                    QrCode(text = message.text, modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "Point your phone's camera here to open it",
                        style = Sendro.mono(11f),
                        color = Sendro.textTertiary,
                    )
                } else {
                    QrUnavailable(
                        modifier = Modifier.fillMaxWidth(),
                        reason = "Too long for a QR code",
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------

/**
 * Copy with the sensitive-content flag set where the platform supports it, so
 * Android 13+ does not toast a preview of the text.
 */
internal fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText("Sendro", text)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(ClipDescriptionSensitive, true)
        }
    }
    runCatching { manager.setPrimaryClip(clip) }
}

/**
 * `ClipDescription.EXTRA_IS_SENSITIVE` is API 33; the string constant is
 * stable and using it by value keeps this compiling against minSdk 26.
 */
private const val ClipDescriptionSensitive = "android.content.extra.IS_SENSITIVE"
