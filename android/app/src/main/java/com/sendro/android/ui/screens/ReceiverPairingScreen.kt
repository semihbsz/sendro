package com.sendro.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sendro.android.SendroApplication
import com.sendro.android.core.host.HostPairing
import com.sendro.android.core.host.ReceiverHost
import com.sendro.android.ui.components.AccentPill
import com.sendro.android.ui.components.CodeBoxes
import com.sendro.android.ui.components.GhostPill
import com.sendro.android.ui.components.NoticeCard
import com.sendro.android.ui.components.QrCode
import com.sendro.android.ui.components.RequestInitialFocus
import com.sendro.android.ui.components.SectionTag
import com.sendro.android.ui.theme.LocalDeviceProfile
import com.sendro.android.ui.theme.Sendro
import com.sendro.android.ui.theme.glassRow
import kotlinx.coroutines.delay

/**
 * PROTOCOL.md §15.2 — "let a phone send to this TV".
 *
 * The TV opens a §13 pairing session and renders it as a QR on the big screen.
 * The phone scans it with the camera it already has, and completes the ordinary
 * §4.2 confirm. That is the only pleasant pairing path on a device with no
 * keyboard, and the security argument is unchanged: what you can scan you can
 * see, so you are in the room.
 *
 * The six digits are shown underneath for a sender without a camera — the PC
 * types them, which is the §4.1 `pair/start` path. When a computer starts that
 * path the session it created appears here as a second block, because the code
 * the user must type is the one belonging to *that* session, not to the QR.
 */
@Composable
fun ReceiverPairingScreen(app: SendroApplication, onClose: () -> Unit) {
    val profile = LocalDeviceProfile.current
    val hostState by app.receiverHost.state.collectAsStateWithLifecycle()
    val sessions by app.hostPairing.sessions.collectAsStateWithLifecycle()
    val peers by app.peers.peers.collectAsStateWithLifecycle()

    val closeFocus = remember { FocusRequester() }
    RequestInitialFocus(closeFocus)
    BackHandler(enabled = true) { onClose() }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var peerCountOnEntry by remember { mutableStateOf(peers.size) }
    var justPaired by remember { mutableStateOf<String?>(null) }

    val running = hostState as? ReceiverHost.State.Running
    val qrSession = sessions.firstOrNull { it.origin == HostPairing.Origin.QR }
    val typedSession = sessions.firstOrNull { it.origin == HostPairing.Origin.REMOTE }

    // One ticker drives the countdown, the expiry sweep and the auto-renew.
    // The user is standing in front of the TV with a phone in their hand; a
    // dead QR they have to ask for again is a bad way to spend their patience.
    LaunchedEffect(running != null) {
        if (running == null) return@LaunchedEffect
        app.hostPairing.startForQr()
        while (true) {
            nowMs = System.currentTimeMillis()
            app.hostPairing.sweep()
            val live = app.hostPairing.sessions.value
                .firstOrNull { it.origin == HostPairing.Origin.QR }
            if (live == null) app.hostPairing.startForQr()
            delay(500)
        }
    }

    // A successful pair shows up as a new peer; say so instead of leaving the
    // user staring at a QR that has silently stopped mattering.
    LaunchedEffect(peers.size) {
        if (peers.size > peerCountOnEntry) {
            justPaired = peers.lastOrNull()?.name
            peerCountOnEntry = peers.size
        }
    }

    // The QR session is this screen's own; drop it on the way out so a code
    // left on a dark TV is not still valid.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { app.hostPairing.clearQrSession() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = profile.horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (running == null) {
            NoticeCard(
                title = "Receiving is turned off",
                message = "Turn on \"Receive from other devices\" in Settings and this " +
                    "device will show a QR code that a phone can scan.",
                tint = Sendro.warn,
            )
            GhostPill(title = "Close", onClick = onClose, focusRequester = closeFocus)
            return@Column
        }

        justPaired?.let { name ->
            NoticeCard(
                title = "$name is paired",
                message = "It can send files and text to this device now. The code below " +
                    "stays live if you want to pair something else.",
                tint = Sendro.teal,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            // ---- the QR itself -------------------------------------------
            Column(
                modifier = Modifier.width(if (profile.isTv) 340.dp else 240.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (qrSession != null && running.addresses.isNotEmpty()) {
                    val url = remember(qrSession.pairingId, running.addresses.first()) {
                        app.receiverHost.pairingUrl(qrSession, running.addresses.first())
                    }
                    QrCode(text = url, modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "Expires in ${qrSession.secondsLeft(nowMs)}s",
                        style = Sendro.mono(12f),
                        color = if (qrSession.secondsLeft(nowMs) < 20) Sendro.warn
                        else Sendro.textTertiary,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassRow(cornerRadius = 12.dp)
                            .padding(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (running.addresses.isEmpty()) {
                                "No network address yet"
                            } else {
                                "Preparing…"
                            },
                            style = Sendro.mono(12f),
                            color = Sendro.textTertiary,
                        )
                    }
                }
            }

            // ---- instructions, address, digits ---------------------------
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Scan this with Sendro on your phone",
                    style = Sendro.sans(22f, FontWeight.SemiBold),
                    color = Sendro.textPrimary,
                )
                Text(
                    text = "On the phone: Devices ▸ Scan QR code. The six digits never " +
                        "travel over the network — the phone proves it saw them.",
                    style = Sendro.sans(13f),
                    color = Sendro.textSecondary,
                )

                Column(
                    modifier = Modifier.fillMaxWidth().glassRow().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SectionTag("This device", Sendro.textFaint)
                    Text(
                        text = app.receiverHost.deviceName,
                        style = Sendro.sans(15f, FontWeight.Medium),
                        color = Sendro.textPrimary,
                    )
                    running.addresses.forEach { address ->
                        Text(
                            text = "$address:${running.port}",
                            style = Sendro.mono(14f, FontWeight.SemiBold),
                            color = Sendro.irisSoft,
                        )
                    }
                    if (running.addresses.isEmpty()) {
                        Text(
                            text = "Not on a network yet",
                            style = Sendro.mono(12f),
                            color = Sendro.warn,
                        )
                    }
                }

                // The typed path. A PC has no camera, so it calls pair/start
                // and the user types whatever code THAT session produced —
                // which is why an incoming request replaces the QR digits here.
                Column(
                    modifier = Modifier.fillMaxWidth().glassRow().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionTag(
                        if (typedSession != null) "Type this on your computer" else "Or type this code",
                        if (typedSession != null) Sendro.irisSoft else Sendro.textFaint,
                    )
                    val shown = typedSession ?: qrSession
                    CodeBoxes(shown?.code.orEmpty())
                    Text(
                        text = when {
                            typedSession != null ->
                                "${typedSession.peerName ?: "A computer"} is waiting — " +
                                    "${typedSession.secondsLeft(nowMs)}s left."
                            else ->
                                "On the PC: Sendro ▸ Send to a device ▸ enter " +
                                    "${running.addresses.firstOrNull() ?: "this device's address"} " +
                                    "and this code."
                        },
                        style = Sendro.sans(12.5f),
                        color = Sendro.textTertiary,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.focusGroup().widthIn(max = 520.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GhostPill(
                title = "Done",
                onClick = onClose,
                height = 50.dp,
                focusRequester = closeFocus,
                modifier = Modifier.weight(1f),
            )
            AccentPill(
                title = "New code",
                onClick = { app.hostPairing.startForQr() },
                height = 50.dp,
                modifier = Modifier.weight(1f),
            )
        }

        if (peers.isNotEmpty()) {
            SectionTag("Devices that can send to this one", Sendro.textFaint)
            peers.forEach { peer ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassRow()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            peer.name,
                            style = Sendro.sans(15f, FontWeight.Medium),
                            color = Sendro.textPrimary,
                        )
                        Text(
                            peer.platform,
                            style = Sendro.mono(10.5f),
                            color = Sendro.textTertiary,
                        )
                    }
                    UnpairPeerButton(onUnpair = { app.peers.remove(peer.deviceId) })
                }
            }
        }

        Text(
            text = "Sendro only accepts files from devices paired here, and only over your " +
                "own Wi-Fi. Every upload is checked against the sender's SHA-256 before it " +
                "is saved.",
            style = Sendro.sans(12.5f),
            color = Sendro.textTertiary,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun UnpairPeerButton(onUnpair: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    com.sendro.android.ui.components.Pressable(
        onClick = { if (confirm) onUnpair() else confirm = true },
        focusCorner = 8.dp,
    ) {
        Text(
            text = if (confirm) "Sure?" else "Remove",
            style = Sendro.sans(12.5f, FontWeight.Medium),
            color = if (confirm) Sendro.danger else Sendro.textTertiary,
        )
    }
}

/** A compact "receiving is on/off" line for the Devices list and Settings. */
@Composable
fun ReceiverStatusLine(app: SendroApplication, modifier: Modifier = Modifier) {
    val hostState by app.receiverHost.state.collectAsStateWithLifecycle()
    val peers by app.peers.peers.collectAsStateWithLifecycle()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassRow()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (val state = hostState) {
            is ReceiverHost.State.Running -> {
                SectionTag("Receiving", Sendro.teal)
                Text(
                    text = state.addresses.firstOrNull()
                        ?.let { "Listening on $it:${state.port}" }
                        ?: "Listening on port ${state.port}",
                    style = Sendro.mono(12.5f),
                    color = Sendro.textBase.copy(alpha = 0.8f),
                )
                Text(
                    text = if (peers.isEmpty()) {
                        "No device is paired to this one yet."
                    } else {
                        "${peers.size} device${if (peers.size == 1) "" else "s"} can send here."
                    },
                    style = Sendro.sans(12.5f),
                    color = Sendro.textTertiary,
                )
            }

            is ReceiverHost.State.Failed -> {
                SectionTag("Receiving unavailable", Sendro.danger)
                Text(state.message, style = Sendro.sans(12.5f), color = Sendro.textSecondary)
            }

            ReceiverHost.State.Stopped -> {
                SectionTag("Receiving", Sendro.textFaint)
                Text(
                    text = "Off. Other devices cannot send to this one.",
                    style = Sendro.sans(12.5f),
                    color = Sendro.textTertiary,
                )
            }
        }
    }
}
