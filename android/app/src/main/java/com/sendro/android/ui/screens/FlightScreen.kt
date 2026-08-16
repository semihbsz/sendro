package com.sendro.android.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sendro.android.SendroApplication
import com.sendro.android.core.ActiveTransfer
import com.sendro.android.core.Format
import com.sendro.android.core.TransferPhase
import com.sendro.android.ui.components.TopInsetSpacer
import com.sendro.android.ui.components.AccentPill
import com.sendro.android.ui.components.GhostPill
import com.sendro.android.ui.components.Pressable
import com.sendro.android.ui.components.RequestInitialFocus
import com.sendro.android.ui.components.SectionTag
import com.sendro.android.ui.theme.LocalDeviceProfile
import com.sendro.android.ui.theme.Sendro
import com.sendro.android.ui.theme.glassRow

/**
 * The full-screen view of one transfer: progress ring, the Prep · Stream ·
 * Verify · Save phase rail, live rate and ETA, the authoritative SHA-256, and
 * cancel / retry.
 *
 * When the engine drops the transfer (it completed), the screen says so rather
 * than closing itself out from under the user.
 */
@Composable
fun FlightScreen(
    app: SendroApplication,
    transferId: String,
    onClose: () -> Unit,
) {
    val active by app.transferEngine.active.collectAsStateWithLifecycle()
    val history by app.history.entries.collectAsStateWithLifecycle()
    val transfer = active.firstOrNull { it.id == transferId }
    val finished = history.firstOrNull { it.transferId == transferId }
    val profile = LocalDeviceProfile.current
    // Every state of this screen has exactly one primary action; that is what
    // the remote lands on.
    val primaryFocus = remember { FocusRequester() }
    RequestInitialFocus(primaryFocus, key = transfer?.phase?.shortLabel ?: "done")

    Column(modifier = Modifier.fillMaxSize().background(Sendro.bg)) {
        TopInsetSpacer()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = profile.horizontalPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pressable(onClick = onClose, focusCorner = 17.dp) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .glassRow(cornerRadius = 17.dp, fillAlpha = 0.06f, borderAlpha = 0.12f),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Back",
                        tint = Sendro.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        when {
            transfer != null -> InFlightBody(
                app = app,
                transfer = transfer,
                primaryFocus = primaryFocus,
                onClose = onClose,
            )

            finished != null -> FinishedBody(
                app = app,
                fileName = finished.fileName,
                outcome = finished.outcome,
                savedTo = savedToLabel(finished),
                // A completed transfer is the moment the user most wants to
                // DO something with the file: play the movie, install the APK.
                request = if (finished.outcome == "completed") {
                    PreviewRequest.of(app, finished)
                } else {
                    null
                },
                primaryFocus = primaryFocus,
                onClose = onClose,
            )

            else -> FinishedBody(
                app = app,
                fileName = "This transfer",
                outcome = "completed",
                savedTo = "",
                request = null,
                primaryFocus = primaryFocus,
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun InFlightBody(
    app: SendroApplication,
    transfer: ActiveTransfer,
    primaryFocus: FocusRequester,
    onClose: () -> Unit,
) {
    val phaseTint = phaseColor(transfer.phase)
    val profile = LocalDeviceProfile.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            // A 220dp ring plus the rail, metrics, the hash and the action row
            // does not fit a 5" screen — scroll rather than clip.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = maxOf(24.dp, profile.horizontalPadding), vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = transfer.offer.fileName,
            style = Sendro.sans(20f, FontWeight.SemiBold),
            color = Sendro.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "${Format.bytes(transfer.offer.sizeBytes)} · from ${transfer.offer.senderName}",
            style = Sendro.mono(11.5f),
            color = Sendro.textTertiary,
        )

        ProgressRing(
            fraction = transfer.fraction.toFloat(),
            tint = phaseTint,
            centreLabel = Format.percent(transfer.fraction),
            subLabel = transfer.phase.label,
            modifier = Modifier.size(220.dp),
        )

        PhaseRail(phase = transfer.phase)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Metric("Rate", Format.speed(transfer.bytesPerSecond))
            Metric("ETA", Format.eta(transfer.etaSeconds))
            Metric("Got", Format.bytes(transfer.bytesReceived))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassRow(cornerRadius = 16.dp)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionTag("SHA-256 (sender)", Sendro.textFaint)
            Text(
                // The whole product in one line: the phone only calls a
                // transfer verified when its own streamed digest equals this.
                text = transfer.offer.sha256,
                style = Sendro.mono(10.5f),
                color = Sendro.textBase.copy(alpha = 0.7f),
            )
        }

        when (val phase = transfer.phase) {
            is TransferPhase.Failed -> {
                Text(
                    text = phase.message,
                    style = Sendro.sans(13f),
                    color = Sendro.danger,
                    textAlign = TextAlign.Center,
                )
                Row(
                    modifier = Modifier.focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (phase.resumable) {
                        AccentPill(
                            title = "Retry",
                            onClick = { app.transferEngine.resume(transfer.id) },
                            focusRequester = primaryFocus,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    GhostPill(
                        title = "Discard",
                        focusRequester = if (phase.resumable) null else primaryFocus,
                        onClick = {
                            app.transferEngine.cancel(transfer.id)
                            onClose()
                        },
                        textColor = Sendro.danger,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            TransferPhase.AwaitingSaveChoice -> {
                Text(
                    text = "Where should this go?",
                    style = Sendro.sans(14f, FontWeight.Medium),
                    color = Sendro.textPrimary,
                )
                Row(
                    modifier = Modifier.focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AccentPill(
                        title = "Gallery",
                        onClick = {
                            app.transferEngine.resolveSaveChoice(transfer.id, toGallery = true)
                        },
                        focusRequester = primaryFocus,
                        modifier = Modifier.weight(1f),
                    )
                    GhostPill(
                        title = "Files",
                        onClick = {
                            app.transferEngine.resolveSaveChoice(transfer.id, toGallery = false)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            TransferPhase.StorageDenied -> {
                Text(
                    text = "Sendro needs storage access to put this in your gallery. " +
                        "The verified bytes are safe on the phone in the meantime.",
                    style = Sendro.sans(13f),
                    color = Sendro.warn,
                    textAlign = TextAlign.Center,
                )
                Row(
                    modifier = Modifier.focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AccentPill(
                        title = "Try gallery again",
                        onClick = {
                            app.transferEngine.resolveSaveChoice(transfer.id, toGallery = true)
                        },
                        color = Sendro.warn,
                        focusRequester = primaryFocus,
                        modifier = Modifier.weight(1f),
                    )
                    GhostPill(
                        title = "Keep in Files",
                        onClick = {
                            app.transferEngine.resolveSaveChoice(transfer.id, toGallery = false)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            TransferPhase.Interrupted -> {
                Row(
                    modifier = Modifier.focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AccentPill(
                        title = "Resume",
                        onClick = { app.transferEngine.resume(transfer.id) },
                        focusRequester = primaryFocus,
                        modifier = Modifier.weight(1f),
                    )
                    GhostPill(
                        title = "Cancel",
                        onClick = {
                            app.transferEngine.cancel(transfer.id)
                            onClose()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            else -> {
                GhostPill(
                    title = "Cancel transfer",
                    onClick = {
                        app.transferEngine.cancel(transfer.id)
                        onClose()
                    },
                    textColor = Sendro.danger,
                    focusRequester = primaryFocus,
                )
            }
        }
    }
}

@Composable
private fun FinishedBody(
    app: SendroApplication,
    fileName: String,
    outcome: String,
    savedTo: String,
    request: PreviewRequest?,
    primaryFocus: FocusRequester,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = when (outcome) {
                "completed" -> "Verified and saved"
                "failed" -> "Transfer failed"
                "cancelled" -> "Cancelled"
                "rejected" -> "Declined"
                else -> outcome
            },
            style = Sendro.sans(22f, FontWeight.SemiBold),
            color = if (outcome == "completed") Sendro.teal else Sendro.textPrimary,
        )
        Text(
            text = fileName,
            style = Sendro.sans(14f),
            color = Sendro.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (savedTo.isNotBlank()) {
            Text(savedTo, style = Sendro.mono(11f), color = Sendro.textTertiary)
        }
        if (request != null && !request.isGone) {
            ReceivedActionRow(
                app = app,
                request = request,
                primaryFocus = primaryFocus,
                modifier = Modifier.widthIn(max = 520.dp),
            )
            GhostPill(title = "Done", onClick = onClose, modifier = Modifier.widthIn(max = 520.dp))
        } else {
            AccentPill(title = "Done", onClick = onClose, focusRequester = primaryFocus)
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun ProgressRing(
    fraction: Float,
    tint: Color,
    centreLabel: String,
    subLabel: String,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 260),
        label = "ringProgress",
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2f
            drawArc(
                color = Color.White.copy(alpha = 0.07f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(
                    size.width - stroke,
                    size.height - stroke,
                ),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = tint,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(
                    size.width - stroke,
                    size.height - stroke,
                ),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = centreLabel,
                style = Sendro.sans(38f, FontWeight.SemiBold),
                color = Sendro.textPrimary,
            )
            Text(text = subLabel, style = Sendro.mono(11f), color = tint)
        }
    }
}

/** Prep · Stream · Verify · Save, with the reached ones lit. */
@Composable
private fun PhaseRail(phase: TransferPhase) {
    val steps = listOf("Prep", "Stream", "Verify", "Save")
    val reached = when (phase) {
        TransferPhase.Preparing, TransferPhase.Interrupted -> 0
        TransferPhase.Downloading -> 1
        TransferPhase.Verifying -> 2
        TransferPhase.Saving, TransferPhase.AwaitingSaveChoice, TransferPhase.StorageDenied -> 3
        is TransferPhase.Failed -> -1
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { index, label ->
            val lit = reached >= index
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(
                            if (lit) phaseColor(phase) else Color.White.copy(alpha = 0.09f),
                        ),
                )
                Text(
                    text = label,
                    style = Sendro.mono(9.5f, FontWeight.Medium, tracking = 0.6f),
                    color = if (lit) Sendro.textBase.copy(alpha = 0.8f) else Sendro.textFaint,
                )
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SectionTag(label, Sendro.textFaint)
        Text(value, style = Sendro.mono(14f, FontWeight.Medium), color = Sendro.textPrimary)
    }
}
