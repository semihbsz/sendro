package com.sendro.android.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sendro.android.SendroApplication
import com.sendro.android.core.ActiveTransfer
import com.sendro.android.core.Format
import com.sendro.android.core.HistoryEntry
import com.sendro.android.core.FileNames
import com.sendro.android.core.IncomingOffer
import com.sendro.android.core.MediaKind
import com.sendro.android.core.MediaSaver
import com.sendro.android.core.TransferPhase
import com.sendro.android.ui.components.TopInsetSpacer
import com.sendro.android.ui.components.AccentPill
import com.sendro.android.ui.components.FileBadge
import com.sendro.android.ui.components.GhostPill
import com.sendro.android.ui.components.NoticeCard
import com.sendro.android.ui.components.Pressable
import com.sendro.android.ui.components.RequestInitialFocus
import com.sendro.android.ui.components.SectionHeader
import com.sendro.android.ui.components.SectionTag
import com.sendro.android.ui.components.StatusChip
import com.sendro.android.ui.components.ThinProgress
import com.sendro.android.ui.components.screenPadding
import com.sendro.android.ui.theme.BeamMark
import com.sendro.android.ui.theme.LocalDeviceProfile
import com.sendro.android.ui.theme.PulseDot
import com.sendro.android.ui.theme.Sendro
import com.sendro.android.ui.theme.glassCard
import com.sendro.android.ui.theme.glassRow

/**
 * The receive surface. An incoming file becomes the screen: offer cards up
 * top, otherwise the breathing "listening" radar. Active transfers are
 * tappable rows opening the Flight screen, recent history sits at the bottom,
 * and the device chip in the header opens Devices.
 */
@Composable
fun ReceiveScreen(
    app: SendroApplication,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFlight: (String) -> Unit,
    onPreview: (PreviewRequest) -> Unit,
    onGoLibrary: () -> Unit,
    onOpenReceiverPairing: () -> Unit,
) {
    val incoming by app.transferEngine.incoming.collectAsStateWithLifecycle()
    val active by app.transferEngine.active.collectAsStateWithLifecycle()
    val hostOnline by app.transferEngine.hostOnline.collectAsStateWithLifecycle()
    val pairedHosts by app.pairedHosts.hosts.collectAsStateWithLifecycle()
    val history by app.history.entries.collectAsStateWithLifecycle()
    val discoveryStatus by app.discovery.status.collectAsStateWithLifecycle()
    // Devices that can push TO this one (§15). A TV with a phone paired to it
    // is connected even though it is nobody's client.
    val peers by app.peers.peers.collectAsStateWithLifecycle()
    // A §15 pair/start from a peer with no camera — in practice the Windows
    // app. The six digits it needs are on THIS screen or nowhere: a TV
    // notification is effectively invisible from a sofa.
    val hostSessions by app.hostPairing.sessions.collectAsStateWithLifecycle()
    val profile = LocalDeviceProfile.current
    val incomingPairRequest = hostSessions.firstOrNull {
        it.origin == com.sendro.android.core.host.HostPairing.Origin.REMOTE
    }

    val connected = pairedHosts.isNotEmpty() || peers.isNotEmpty()

    // Where the remote lands, in priority order: a waiting offer, then — on a
    // TV that is not connected to anything yet — the first of the two big
    // "get started" actions, and otherwise the device chip.
    val offerFocus = remember { FocusRequester() }
    val chipFocus = remember { FocusRequester() }
    val startFocus = remember { FocusRequester() }
    val hasOffers = incoming.isNotEmpty()
    val showBigStart = profile.isTv && !connected
    RequestInitialFocus(
        requester = when {
            hasOffers -> offerFocus
            showBigStart -> startFocus
            else -> chipFocus
        },
        key = Triple(hasOffers, showBigStart, profile.isTv),
    )

    val primaryHost = pairedHosts.firstOrNull { hostOnline[it.deviceId] == true }
        ?: pairedHosts.firstOrNull()

    // Only ticks while a request is actually pending, so the idle home screen
    // is not recomposing twice a second for nothing.
    var pairClockMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(incomingPairRequest?.pairingId) {
        if (incomingPairRequest == null) return@LaunchedEffect
        while (true) {
            pairClockMs = System.currentTimeMillis()
            app.hostPairing.sweep()
            kotlinx.coroutines.delay(500)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        TopInsetSpacer()

        Header(
            hostName = primaryHost?.name,
            online = primaryHost != null && hostOnline[primaryHost.deviceId] == true,
            onOpenDevices = onOpenDevices,
            onOpenSettings = onOpenSettings,
            chipFocus = chipFocus,
            modifier = Modifier.padding(
                horizontal = profile.horizontalPadding,
                vertical = 8.dp,
            ),
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = screenPadding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (discoveryStatus == com.sendro.android.core.Discovery.Status.FAILED) {
                item {
                    NoticeCard(
                        title = "Can't browse the local network",
                        message = "Sendro could not start mDNS discovery on this device. " +
                            "You can still connect by IP from Devices — the PC window " +
                            "shows its address and port.",
                        tint = Sendro.warn,
                        actionLabel = "Open Devices",
                        onAction = onOpenDevices,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }

            incomingPairRequest?.let { session ->
                item {
                    IncomingPairRequest(
                        session = session,
                        nowMs = pairClockMs,
                    )
                }
            }

            // §15 gave this device two ways to receive, and on a TV they are
            // equally important: pull from a computer, or let a phone push.
            // Burying either one in a settings sheet misrepresents the
            // product, so on a TV both live on the home screen — large while
            // nothing is connected, a compact row once something is.
            if (profile.isTv) {
                item {
                    ConnectionActions(
                        compact = connected,
                        firstFocus = startFocus,
                        onPairPc = onOpenDevices,
                        onLetPhoneSend = onOpenReceiverPairing,
                        modifier = Modifier.padding(top = if (connected) 4.dp else 14.dp),
                    )
                }
            }

            if (incoming.isNotEmpty()) {
                item {
                    SectionTag("Incoming", Sendro.irisSoft, Modifier.padding(top = 18.dp))
                }
                if (incoming.size > 1) {
                    item {
                        BulkBar(
                            count = incoming.size,
                            busy = incoming.any { it.isAccepting },
                            onAcceptAll = { app.transferEngine.acceptAll() },
                            onDeclineAll = { app.transferEngine.declineAll() },
                        )
                    }
                }
                itemsIndexed(incoming, key = { _, item -> item.id }) { index, offer ->
                    OfferCard(
                        incoming = offer,
                        // Only the first card claims the entry point; the rest
                        // are reached by pressing Down.
                        acceptFocus = if (index == 0) offerFocus else null,
                        onAccept = {
                            app.transferEngine.accept(offer)
                            onOpenFlight(offer.id)
                        },
                        onReject = { app.transferEngine.reject(offer) },
                    )
                }
            } else if (active.isEmpty()) {
                item {
                    ListeningSection(
                        connected = connected,
                        hostName = primaryHost?.name,
                        online = primaryHost != null && hostOnline[primaryHost.deviceId] == true,
                        peerCount = peers.size,
                        compact = profile.isTv && !connected,
                    )
                }
            }

            if (active.isNotEmpty()) {
                item { SectionTag("In flight", Sendro.textFaint, Modifier.padding(top = 14.dp)) }
                items(active, key = { it.id }) { transfer ->
                    ActiveRow(transfer = transfer, onClick = { onOpenFlight(transfer.id) })
                }
            }

            item {
                SectionHeader(
                    title = "Recent",
                    color = Sendro.textBase.copy(alpha = 0.4f),
                    actionLabel = "All",
                    onAction = onGoLibrary,
                    modifier = Modifier.padding(top = 22.dp),
                )
            }

            if (history.isEmpty()) {
                item {
                    Text(
                        text = "Nothing received yet. Send a file from Sendro on your PC and " +
                            "it will land here.",
                        style = Sendro.sans(13f),
                        color = Sendro.textTertiary,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            } else {
                items(history.take(3), key = { it.id }) { entry ->
                    HistoryRow(
                        entry = entry,
                        onClick = { onPreview(PreviewRequest.of(app, entry)) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun Header(
    hostName: String?,
    online: Boolean,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
    chipFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BeamMark(side = 28.dp)

        Pressable(onClick = onOpenSettings, focusCorner = 16.dp) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .glassRow(cornerRadius = 16.dp, fillAlpha = 0.06f, borderAlpha = 0.12f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = Sendro.textBase.copy(alpha = 0.6f),
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Pressable(
            onClick = onOpenDevices,
            focusRequester = chipFocus,
            focusCorner = 16.dp,
        ) {
            Row(
                modifier = Modifier
                    .height(32.dp)
                    .glassRow(cornerRadius = 16.dp, fillAlpha = 0.06f, borderAlpha = 0.12f)
                    .padding(start = 11.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (hostName != null) {
                    PulseDot(color = Sendro.teal, active = online, side = 7.dp)
                    Text(
                        text = hostName.uppercase(),
                        style = Sendro.mono(11.5f, FontWeight.Medium),
                        color = Sendro.textBase.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 130.dp),
                    )
                } else {
                    PulseDot(color = Sendro.iris, active = true, side = 7.dp)
                    Text(
                        text = "PAIR A PC",
                        style = Sendro.mono(11.5f, FontWeight.Medium),
                        color = Sendro.irisSoft,
                    )
                }
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowRight,
                    contentDescription = "Devices",
                    tint = Sendro.textBase.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** §12 — bulk accept over the existing per-transfer accept path. */
@Composable
private fun BulkBar(
    count: Int,
    busy: Boolean,
    onAcceptAll: () -> Unit,
    onDeclineAll: () -> Unit,
) {
    var confirmDecline by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AccentPill(
            title = if (busy) "Accepting…" else "Accept all ($count)",
            onClick = onAcceptAll,
            enabled = !busy,
            height = 44.dp,
            modifier = Modifier.weight(1f),
        )
        GhostPill(
            title = if (confirmDecline) "Sure?" else "Decline all",
            onClick = {
                if (confirmDecline) {
                    onDeclineAll()
                    confirmDecline = false
                } else {
                    confirmDecline = true
                }
            },
            enabled = !busy,
            height = 44.dp,
            textColor = if (confirmDecline) Sendro.danger else Sendro.textBase.copy(alpha = 0.7f),
            modifier = Modifier.weight(0.72f),
        )
    }
}

@Composable
private fun OfferCard(
    incoming: IncomingOffer,
    acceptFocus: FocusRequester?,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(cornerRadius = 26.dp)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FileBadge(incoming.offer.fileName, side = 52.dp, cornerRadius = 16.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = incoming.offer.fileName,
                    style = Sendro.sans(19f, FontWeight.SemiBold),
                    color = Sendro.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${Format.bytes(incoming.offer.sizeBytes)} · ${incoming.offer.senderName}",
                    style = Sendro.mono(11.5f),
                    color = Sendro.textBase.copy(alpha = 0.5f),
                    maxLines = 1,
                )
            }
        }

        incoming.errorMessage?.let { error ->
            Text(text = error, style = Sendro.sans(12.5f), color = Sendro.danger)
        }

        // Accept and Decline sit side by side, so Left/Right moves between
        // them and Up/Down leaves the card. focusGroup keeps that pair
        // together when several cards are stacked.
        Row(
            modifier = Modifier.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AccentPill(
                title = if (incoming.isAccepting) "Accepting…" else "Accept",
                onClick = onAccept,
                enabled = !incoming.isAccepting,
                focusRequester = acceptFocus,
                modifier = Modifier.weight(1f),
            )
            GhostPill(
                title = "Decline",
                onClick = onReject,
                enabled = !incoming.isAccepting,
                modifier = Modifier.weight(0.62f),
            )
        }
    }
}

@Composable
private fun ListeningSection(
    connected: Boolean,
    hostName: String?,
    online: Boolean,
    peerCount: Int,
    /** True when the two big start actions are already carrying the screen. */
    compact: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = if (compact) 6.dp else 18.dp)) {
        if (!compact) {
            ListeningRadar(modifier = Modifier.fillMaxWidth().height(180.dp))
            Text(
                // Never "pair your PC to start": since §15 a computer is one
                // of two equally valid ways in, and on a TV it is not even the
                // likely one.
                text = if (!connected) "Nothing connected\nyet" else "Listening\non your Wi-Fi",
                style = Sendro.sans(30f, FontWeight.SemiBold),
                color = Sendro.textPrimary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            text = when {
                !connected ->
                    "Sendro is on your Wi-Fi and waiting. Connect a computer to pull files " +
                        "from it, or let a phone push files straight here — either takes " +
                        "about twenty seconds."
                hostName == null ->
                    "Ready. ${peerCount} device${if (peerCount == 1) "" else "s"} can send " +
                        "here — anything they send lands at full size, verified byte for byte."
                online ->
                    "$hostName is online. Anything you send lands here at full size, " +
                        "verified byte for byte."
                else ->
                    "$hostName looks offline right now. Open Sendro on that computer on " +
                        "this Wi-Fi and it will reconnect by itself."
            },
            style = Sendro.sans(14f),
            color = Sendro.textBase.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = if (compact) 0.dp else 12.dp).widthIn(max = 380.dp),
        )
    }
}

/**
 * The two ways into Sendro, side by side and equally weighted.
 *
 * The distinction is genuinely hard to name — both are "pairing" — so the
 * cards say what HAPPENS rather than what the protocol calls it: one is the
 * computer sending here, the other is scanning a code with a phone. No
 * jargon, one line each, readable from a sofa.
 *
 * Once something is connected they collapse to a quiet row: they are entry
 * points, not the point of the screen.
 */
@Composable
private fun ConnectionActions(
    compact: Boolean,
    firstFocus: FocusRequester?,
    onPairPc: () -> Unit,
    onLetPhoneSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (compact) {
        Row(
            modifier = modifier.fillMaxWidth().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GhostPill(
                title = "Pair a PC",
                onClick = onPairPc,
                height = 44.dp,
                modifier = Modifier.weight(1f),
            )
            GhostPill(
                title = "Let a phone send",
                onClick = onLetPhoneSend,
                height = 44.dp,
                modifier = Modifier.weight(1f),
            )
        }
        return
    }

    Row(
        modifier = modifier.fillMaxWidth().focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StartAction(
            title = "Pair a PC",
            line = "Your computer sends files here",
            accent = Sendro.iris,
            onClick = onPairPc,
            focusRequester = firstFocus,
            modifier = Modifier.weight(1f),
        )
        StartAction(
            title = "Let a phone send",
            line = "Scan a code with your phone to send from it",
            accent = Sendro.teal,
            onClick = onLetPhoneSend,
            focusRequester = null,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StartAction(
    title: String,
    line: String,
    accent: Color,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    Pressable(
        onClick = onClick,
        modifier = modifier,
        focusRequester = focusRequester,
        focusCorner = 22.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 132.dp)
                .glassCard(cornerRadius = 22.dp)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // A colour bar rather than an icon: it survives a badly calibrated
            // TV panel and needs no new asset.
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            Text(
                text = title,
                style = Sendro.sans(21f, FontWeight.SemiBold),
                color = Sendro.textPrimary,
            )
            Text(
                text = line,
                style = Sendro.sans(14f),
                color = Sendro.textSecondary,
            )
        }
    }
}

/** Two breathing rings around a glowing teal core. */
@Composable
private fun ListeningRadar(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "radar")
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )
    Canvas(modifier = modifier) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            color = Color.White.copy(alpha = 0.07f * (0.35f + 0.4f * breathe)),
            radius = 75.dp.toPx() * (1f + 0.06f * breathe),
            center = centre,
            style = Stroke(width = 1.dp.toPx()),
        )
        drawCircle(
            color = Sendro.iris.copy(alpha = 0.28f * (0.75f - 0.4f * breathe)),
            radius = 48.dp.toPx() * (1.06f - 0.06f * breathe),
            center = centre,
            style = Stroke(width = 1.dp.toPx()),
        )
        drawCircle(color = Sendro.teal.copy(alpha = 0.35f), radius = 16.dp.toPx(), center = centre)
        drawCircle(color = Sendro.teal, radius = 6.dp.toPx(), center = centre)
    }
}

@Composable
fun ActiveRow(transfer: ActiveTransfer, onClick: () -> Unit) {
    Pressable(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassRow()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FileBadge(transfer.offer.fileName, side = 36.dp, cornerRadius = 11.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = transfer.offer.fileName,
                        style = Sendro.sans(14f, FontWeight.Medium),
                        color = Sendro.textBase.copy(alpha = 0.93f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${Format.bytes(transfer.bytesReceived)} / " +
                            Format.bytes(transfer.offer.sizeBytes),
                        style = Sendro.mono(10.5f),
                        color = Sendro.textTertiary,
                    )
                }
                StatusChip(transfer.phase.shortLabel, phaseColor(transfer.phase))
            }
            ThinProgress(transfer.fraction.toFloat(), phaseColor(transfer.phase))
        }
    }
}

fun phaseColor(phase: TransferPhase): Color = when (phase) {
    TransferPhase.Preparing, TransferPhase.Downloading -> Sendro.iris
    TransferPhase.Verifying, TransferPhase.Saving -> Sendro.teal
    TransferPhase.AwaitingSaveChoice -> Sendro.irisSoft
    TransferPhase.StorageDenied, TransferPhase.Interrupted -> Sendro.warn
    is TransferPhase.Failed -> Sendro.danger
}

@Composable
fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit) {
    Pressable(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassRow()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HistoryThumbnail(entry = entry, side = 36.dp, cornerRadius = 11.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.fileName,
                    style = Sendro.sans(14f, FontWeight.Medium),
                    color = Sendro.textBase.copy(alpha = 0.93f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${Format.bytes(entry.sizeBytes)} · ${entry.senderName} · " +
                        Format.timestamp(entry.dateMs),
                    style = Sendro.mono(10.5f),
                    color = Sendro.textTertiary,
                    maxLines = 1,
                )
            }
            OutcomeMark(entry)
        }
    }
}

@Composable
private fun OutcomeMark(entry: HistoryEntry) {
    val (label, color) = when (entry.outcome) {
        // For a file that is still here, the chip says what tapping the row
        // DOES rather than restating that it worked: on a TV the answer is
        // "Play", for an APK it is "Install".
        "completed" -> if (entry.direction == "outgoing") {
            "sent" to Sendro.teal
        } else {
            rowActionLabel(entry) to Sendro.teal
        }
        "failed" -> "failed" to Sendro.danger
        "rejected" -> "declined" to Sendro.textTertiary
        else -> entry.outcome to Sendro.textTertiary
    }
    StatusChip(label, color)
}

/** The verb for the row's primary action, matching [ReceivedActionRow]. */
private fun rowActionLabel(entry: HistoryEntry): String {
    val gone = entry.localName == null && entry.mediaUri == null
    if (gone) return "saved"
    return when {
        MediaSaver.mediaKind(entry.fileName) == MediaKind.VIDEO -> "play"
        MediaSaver.mediaKind(entry.fileName) == MediaKind.PHOTO -> "view"
        FileNames.extensionOf(entry.fileName) == "apk" -> "install"
        else -> "open"
    }
}

/** A neutral placeholder box in the row style, for when there is no preview. */
@Composable
internal fun PlaceholderTile(side: androidx.compose.ui.unit.Dp, cornerRadius: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(side)
            .clip(RoundedCornerShape(cornerRadius))
            .background(Color.White.copy(alpha = 0.05f)),
    )
}
