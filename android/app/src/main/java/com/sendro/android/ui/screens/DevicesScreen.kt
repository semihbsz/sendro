package com.sendro.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sendro.android.SendroApplication
import com.sendro.android.core.DiscoveredHost
import com.sendro.android.core.PairConfirmRequest
import com.sendro.android.core.PairLink
import com.sendro.android.core.PairLinkException
import com.sendro.android.core.PairLinkFlow
import com.sendro.android.core.PairStartRequest
import com.sendro.android.core.PairedHost
import com.sendro.android.core.SENDRO_PROTOCOL_VERSION
import com.sendro.android.core.SendroClient
import com.sendro.android.core.SendroCrypto
import com.sendro.android.core.sendroMessage
import com.sendro.android.ui.components.TopInsetSpacer
import com.sendro.android.ui.components.AccentPill
import com.sendro.android.ui.components.CodeBoxes
import com.sendro.android.ui.components.DpadKeypad
import com.sendro.android.ui.components.GhostPill
import com.sendro.android.ui.components.NoticeCard
import com.sendro.android.ui.components.Pressable
import com.sendro.android.ui.components.RequestInitialFocus
import com.sendro.android.ui.components.SectionTag
import com.sendro.android.ui.theme.LocalDeviceProfile
import com.sendro.android.ui.theme.PulseDot
import com.sendro.android.ui.theme.Sendro
import com.sendro.android.ui.theme.glassCard
import com.sendro.android.ui.theme.glassRow
import kotlinx.coroutines.launch

/**
 * Devices: discovered hosts, pairing (typed 6-digit code or QR per §13),
 * manual connect by IP, the paired list with unpair, and the hotspot help for
 * "no router" setups.
 */
private sealed interface DevicesPane {
    /** Named `Browse`, not `List`, so it can never shadow kotlin.collections.List. */
    data object Browse : DevicesPane

    /** Typed 6-digit pairing against a specific target. */
    data class TypeCode(val name: String, val address: String, val port: Int) : DevicesPane
    data object Scan : DevicesPane
    data class ConfirmLink(val link: PairLink) : DevicesPane
    data object Manual : DevicesPane
    data object Hotspot : DevicesPane
}

@Composable
fun DevicesScreen(
    app: SendroApplication,
    initialLink: PairLink?,
    onLinkConsumed: () -> Unit,
    onClose: () -> Unit,
    onPaired: () -> Unit,
    onOpenReceiverPairing: () -> Unit,
) {
    var pane by remember { mutableStateOf<DevicesPane>(DevicesPane.Browse) }
    val profile = LocalDeviceProfile.current

    LaunchedEffect(initialLink) {
        if (initialLink != null) pane = DevicesPane.ConfirmLink(initialLink)
    }

    // BACK inside a sub-pane goes up one level, not straight out of Devices.
    // Registered here so it takes priority over RootScreen's overlay handler.
    BackHandler(enabled = pane !is DevicesPane.Browse) {
        onLinkConsumed()
        pane = DevicesPane.Browse
    }

    Column(modifier = Modifier.fillMaxSize().background(Sendro.bg)) {
        TopInsetSpacer()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = profile.horizontalPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Pressable(focusCorner = 17.dp, onClick = {
                if (pane is DevicesPane.Browse) {
                    onClose()
                } else {
                    onLinkConsumed()
                    pane = DevicesPane.Browse
                }
            }) {
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
            Text(
                text = when (pane) {
                    DevicesPane.Browse -> "Devices"
                    is DevicesPane.TypeCode -> "Pair"
                    DevicesPane.Scan -> "Scan QR code"
                    is DevicesPane.ConfirmLink -> "Pair with this PC?"
                    DevicesPane.Manual -> "Connect by IP"
                    DevicesPane.Hotspot -> "No Wi-Fi router?"
                },
                style = Sendro.sans(20f, FontWeight.SemiBold),
                color = Sendro.textPrimary,
            )
        }

        when (val current = pane) {
            DevicesPane.Browse -> DeviceList(
                app = app,
                onScan = { pane = DevicesPane.Scan },
                onManual = { pane = DevicesPane.Manual },
                onHotspot = { pane = DevicesPane.Hotspot },
                onOpenReceiverPairing = onOpenReceiverPairing,
                onPair = { host ->
                    pane = DevicesPane.TypeCode(
                        host.name,
                        host.address.orEmpty(),
                        host.port ?: 48800,
                    )
                },
            )

            is DevicesPane.TypeCode -> TypedPairingPane(
                app = app,
                targetName = current.name,
                address = current.address,
                port = current.port,
                onDone = {
                    onPaired()
                    pane = DevicesPane.Browse
                },
            )

            DevicesPane.Scan -> ScanPane(
                onScanned = { text ->
                    val link = PairLink.parse(text)
                    pane = if (link != null) DevicesPane.ConfirmLink(link) else DevicesPane.Scan
                },
                onCancel = { pane = DevicesPane.Browse },
            )

            is DevicesPane.ConfirmLink -> ConfirmLinkPane(
                app = app,
                link = current.link,
                onDone = {
                    onLinkConsumed()
                    onPaired()
                    pane = DevicesPane.Browse
                },
                onCancel = {
                    onLinkConsumed()
                    pane = DevicesPane.Browse
                },
            )

            DevicesPane.Manual -> ManualPane(
                app = app,
                onFound = { name, address, port ->
                    pane = DevicesPane.TypeCode(name, address, port)
                },
            )

            DevicesPane.Hotspot -> HotspotPane()
        }
    }
}

// ---------------------------------------------------------------------------
// List
// ---------------------------------------------------------------------------

@Composable
private fun DeviceList(
    app: SendroApplication,
    onScan: () -> Unit,
    onManual: () -> Unit,
    onHotspot: () -> Unit,
    onOpenReceiverPairing: () -> Unit,
    onPair: (DiscoveredHost) -> Unit,
) {
    val discovered by app.discovery.hosts.collectAsStateWithLifecycle()
    val paired by app.pairedHosts.hosts.collectAsStateWithLifecycle()
    val online by app.transferEngine.hostOnline.collectAsStateWithLifecycle()
    val pairedIds = remember(paired) { paired.map { it.deviceId }.toSet() }
    val profile = LocalDeviceProfile.current
    val firstAction = remember { FocusRequester() }
    RequestInitialFocus(firstAction)

    LazyColumn(
        contentPadding = PaddingValues(
            start = profile.horizontalPadding,
            end = profile.horizontalPadding,
            top = 6.dp,
            bottom = maxOf(40.dp, profile.scrollBottomPadding),
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // §13 needs a camera. Almost no Android TV has one, so the
                // scanner is hidden rather than offered-and-broken, and the
                // 6-digit keypad becomes the primary path.
                if (profile.hasCamera) {
                    AccentPill(
                        title = "Scan QR code",
                        onClick = onScan,
                        height = 46.dp,
                        focusRequester = firstAction,
                        modifier = Modifier.weight(1f),
                    )
                    GhostPill(
                        title = "By IP",
                        onClick = onManual,
                        height = 46.dp,
                        modifier = Modifier.weight(0.5f),
                    )
                } else {
                    AccentPill(
                        title = "Connect by IP address",
                        onClick = onManual,
                        height = 46.dp,
                        focusRequester = firstAction,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        item {
            Text(
                text = if (profile.hasCamera) {
                    "Scanning the PC's QR is fastest — no typing, and the code travels " +
                        "screen → camera, never over the network."
                } else {
                    "This device has no camera, so QR scanning is off. Pick your PC below " +
                        "and type the six digits with the remote, or connect by IP."
                },
                style = Sendro.sans(12.5f),
                color = Sendro.textTertiary,
            )
        }

        // §15: the other direction. On a TV this is the headline feature, so
        // it sits above the discovery list rather than under it.
        item { SectionTag("Send to this device", Sendro.irisSoft, Modifier.padding(top = 14.dp)) }
        item { ReceiverStatusLine(app) }
        item {
            AccentPill(
                title = if (profile.isTv) {
                    "Let a phone send to this TV"
                } else {
                    "Let another device send here"
                },
                onClick = onOpenReceiverPairing,
                height = 46.dp,
            )
        }

        if (paired.isNotEmpty()) {
            item { SectionTag("Paired", Sendro.teal, Modifier.padding(top = 12.dp)) }
            items(paired, key = { it.deviceId }) { host ->
                PairedRow(
                    host = host,
                    online = online[host.deviceId] == true,
                    onUnpair = { app.transferEngine.unpair(host.deviceId) },
                )
            }
        }

        item { SectionTag("On this Wi-Fi", Sendro.textFaint, Modifier.padding(top = 12.dp)) }

        val discoverable = discovered.filterNot { it.deviceId in pairedIds }
        if (discoverable.isEmpty()) {
            item {
                Text(
                    text = "Looking for Sendro on your Wi-Fi… Make sure the PC app is open " +
                        "and both devices are on the same network.",
                    style = Sendro.sans(13f),
                    color = Sendro.textTertiary,
                )
            }
        } else {
            items(discoverable, key = { it.serviceName }) { host ->
                DiscoveredRow(host = host, onPair = { onPair(host) })
            }
        }

        item {
            GhostPill(
                title = "No Wi-Fi router? Use a hotspot",
                onClick = onHotspot,
                height = 44.dp,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun PairedRow(host: PairedHost, online: Boolean, onUnpair: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassRow()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PulseDot(active = online, side = 8.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                host.name,
                style = Sendro.sans(15f, FontWeight.Medium),
                color = Sendro.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(host.lastHost).append(':').append(host.lastPort)
                    append(" · ").append(if (online) "online" else "offline")
                    // §15.1: a peer that answered 404 to the outbox is a
                    // receiver, not a broken host. Say which.
                    if (host.receiveOnly) append(" · receive-only")
                },
                style = Sendro.mono(10.5f),
                color = Sendro.textTertiary,
            )
        }
        Pressable(
            onClick = { if (confirm) onUnpair() else confirm = true },
            focusCorner = 8.dp,
        ) {
            Text(
                text = if (confirm) "Sure?" else "Unpair",
                style = Sendro.sans(12.5f, FontWeight.Medium),
                color = if (confirm) Sendro.danger else Sendro.textTertiary,
            )
        }
    }
}

@Composable
private fun DiscoveredRow(host: DiscoveredHost, onPair: () -> Unit) {
    val supported = host.protocolVersion == SENDRO_PROTOCOL_VERSION
    Pressable(
        onClick = onPair,
        enabled = supported && host.isResolved,
        modifier = Modifier.fillMaxWidth(),
        focusCorner = 18.dp,
    ) {
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
                    host.name,
                    style = Sendro.sans(15f, FontWeight.Medium),
                    color = Sendro.textPrimary,
                    maxLines = 1,
                )
                Text(
                    text = when {
                        !supported -> "Different protocol version — update Sendro"
                        host.isResolved -> "${host.address}:${host.port}"
                        else -> "Resolving…"
                    },
                    style = Sendro.mono(10.5f),
                    color = if (supported) Sendro.textTertiary else Sendro.warn,
                )
            }
            Text(
                text = if (supported) "Pair" else "—",
                style = Sendro.sans(13f, FontWeight.SemiBold),
                color = Sendro.irisSoft,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// §4 typed pairing
// ---------------------------------------------------------------------------

private sealed interface PairStage {
    data object Entry : PairStage
    data object Contacting : PairStage
    data object Proving : PairStage
    data class Failed(val message: String) : PairStage
    data class Paired(val name: String) : PairStage
}

@Composable
private fun TypedPairingPane(
    app: SendroApplication,
    targetName: String,
    address: String,
    port: Int,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val profile = LocalDeviceProfile.current
    var code by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf<PairStage>(PairStage.Entry) }
    val firstKey = remember { FocusRequester() }
    RequestInitialFocus(firstKey)

    fun submitIfComplete(next: String) {
        code = next
        if (next.length == 6 && stage is PairStage.Entry) {
            stage = PairStage.Contacting
            scope.launch {
                stage = runPairing(app, targetName, address, port, next)
                if (stage is PairStage.Paired) onDone()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = profile.horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = targetName,
            style = Sendro.sans(22f, FontWeight.SemiBold),
            color = Sendro.textPrimary,
        )
        Text(
            text = "Type the 6-digit code shown on your PC. The code itself never crosses " +
                "the wire — the phone proves it knows the code with an HMAC.",
            style = Sendro.sans(13f),
            color = Sendro.textSecondary,
            textAlign = TextAlign.Center,
        )

        CodeBoxes(code)

        val entering = stage is PairStage.Entry || stage is PairStage.Failed

        if (profile.isTv) {
            // A remote plus a TextField means the leanback IME, a cursor and
            // eleven OK presses for six digits. A real 3x4 grid is two presses
            // per digit and needs no IME at all.
            DpadKeypad(
                onDigit = { digit -> if (entering) submitIfComplete((code + digit).take(6)) },
                onBackspace = { if (entering) code = code.dropLast(1) },
                onClear = { if (entering) code = "" },
                firstKeyFocus = firstKey,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        } else {
            // A plain numeric field drives the six boxes: it gets the system
            // keyboard, paste, and accessibility for free, which a hand-rolled
            // keypad would all have to reimplement.
            TextField(
                value = code,
                onValueChange = { input -> submitIfComplete(input.filter { it.isDigit() }.take(6)) },
                enabled = entering,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                textStyle = Sendro.mono(20f, FontWeight.SemiBold),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
                colors = sendroFieldColors(),
            )
        }

        when (val current = stage) {
            PairStage.Contacting -> StageLine("Contacting $targetName…", Sendro.irisSoft)
            PairStage.Proving -> StageLine("Sending HMAC proof…", Sendro.irisSoft)
            is PairStage.Failed -> {
                StageLine(current.message, Sendro.danger)
                GhostPill(
                    title = "Try again",
                    onClick = {
                        code = ""
                        stage = PairStage.Entry
                    },
                )
            }
            is PairStage.Paired -> StageLine("Paired with ${current.name}", Sendro.teal)
            PairStage.Entry -> Unit
        }
    }
}

/** Runs §4.1 + §4.2. Returns the terminal stage. */
private suspend fun runPairing(
    app: SendroApplication,
    targetName: String,
    address: String,
    port: Int,
    code: String,
): PairStage {
    val client = SendroClient.create(address, port)
        ?: return PairStage.Failed("That address can't be reached.")
    val deviceId = app.settings.clientDeviceId
    val deviceName = app.settings.current.deviceName

    val start = try {
        client.pairStart(
            PairStartRequest(
                deviceId = deviceId,
                deviceName = deviceName,
                platform = "android",
                protocolVersion = SENDRO_PROTOCOL_VERSION,
            ),
        )
    } catch (e: Exception) {
        return PairStage.Failed("Could not start pairing: ${e.sendroMessage()}")
    }

    val proof = SendroCrypto.pairingProof(
        code = code,
        saltBase64url = start.salt,
        pairingId = start.pairingId,
        deviceId = deviceId,
    ) ?: return PairStage.Failed("Could not compute the pairing proof.")

    val confirmed = try {
        client.pairConfirm(
            PairConfirmRequest(
                pairingId = start.pairingId,
                deviceId = deviceId,
                proof = proof,
                deviceName = deviceName,
                platform = "android",
            ),
        )
    } catch (e: com.sendro.android.core.SendroHttpException) {
        return PairStage.Failed(
            when (e.status) {
                403 -> "Wrong code. Check the six digits on your PC and try again."
                400 -> "That pairing session expired (they last 120 seconds). " +
                    "Start a new one on your PC."
                429 -> "Too many attempts. Start a new pairing on your PC."
                else -> e.sendroMessage()
            },
        )
    } catch (e: Exception) {
        return PairStage.Failed(e.sendroMessage())
    }

    app.tokens.save(confirmed.host.deviceId, confirmed.deviceToken)
    app.pairedHosts.add(
        PairedHost(
            deviceId = confirmed.host.deviceId,
            name = confirmed.host.deviceName.ifBlank { targetName },
            lastHost = address,
            lastPort = port,
            pairedAtMs = System.currentTimeMillis(),
            // Informational only (§15.1). Capability is discovered from the
            // outbox 404, not from this string.
            platform = confirmed.host.platform,
        ),
    )
    return PairStage.Paired(confirmed.host.deviceName.ifBlank { targetName })
}

@Composable
private fun StageLine(text: String, color: Color) {
    Text(text = text, style = Sendro.sans(13f), color = color, textAlign = TextAlign.Center)
}

// ---------------------------------------------------------------------------
// §13 QR pairing
// ---------------------------------------------------------------------------

@Composable
private fun ScanPane(onScanned: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }

    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        denied = !result
    }

    LaunchedEffect(Unit) {
        if (!granted) request.launch(Manifest.permission.CAMERA)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LocalDeviceProfile.current.horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (granted) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black),
            ) {
                QrScannerView(onScanned = onScanned, modifier = Modifier.fillMaxSize())
            }
            Text(
                text = "Point at the QR code on your PC — Sendro › Pair › Show QR.",
                style = Sendro.sans(13f),
                color = Sendro.textSecondary,
            )
            Text(
                text = "The code travels screen → camera, never over the network, and " +
                    "expires after 120 seconds.",
                style = Sendro.sans(12f),
                color = Sendro.textTertiary,
            )
            // The camera preview is not a focus target, so without this the
            // screen would have nothing to focus and only BACK would work.
            GhostPill(title = "Type the code instead", onClick = onCancel)
        } else {
            NoticeCard(
                title = if (denied) "Camera access is off" else "Camera access needed",
                message = "Sendro needs the camera only to read the pairing QR on your PC. " +
                    "Nothing is recorded, and no image ever leaves the phone. You can " +
                    "always type the six digits instead.",
                tint = Sendro.warn,
                actionLabel = "Ask again",
                onAction = { request.launch(Manifest.permission.CAMERA) },
            )
            GhostPill(title = "Type the code instead", onClick = onCancel)
        }
    }
}

@Composable
private fun ConfirmLinkPane(
    app: SendroApplication,
    link: PairLink,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<PairStage>(PairStage.Entry) }
    val pairFocus = remember { FocusRequester() }
    RequestInitialFocus(pairFocus)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LocalDeviceProfile.current.horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().glassCard(cornerRadius = 22.dp).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                link.hostName,
                style = Sendro.sans(20f, FontWeight.SemiBold),
                color = Sendro.textPrimary,
            )
            Text(
                "${link.host}:${link.port}",
                style = Sendro.mono(11.5f),
                color = Sendro.textTertiary,
            )
        }

        Text(
            text = "Sendro will check that this really is the computer in the QR code, " +
                "then prove it knows the six digits without sending them.",
            style = Sendro.sans(13f),
            color = Sendro.textSecondary,
            textAlign = TextAlign.Center,
        )

        when (val current = stage) {
            PairStage.Entry -> {
                Row(
                    modifier = Modifier.focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AccentPill(
                        title = "Pair",
                        focusRequester = pairFocus,
                        onClick = {
                            stage = PairStage.Proving
                            scope.launch {
                                stage = confirmLink(app, link)
                                if (stage is PairStage.Paired) onDone()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    GhostPill(
                        title = "Cancel",
                        onClick = onCancel,
                        modifier = Modifier.weight(0.6f),
                    )
                }
            }
            PairStage.Contacting, PairStage.Proving ->
                StageLine("Verifying and sending HMAC proof…", Sendro.irisSoft)
            is PairStage.Failed -> {
                StageLine(current.message, Sendro.danger)
                GhostPill(title = "Back", onClick = onCancel)
            }
            is PairStage.Paired -> StageLine("Paired with ${current.name}", Sendro.teal)
        }
    }
}

private suspend fun confirmLink(app: SendroApplication, link: PairLink): PairStage = try {
    val response = PairLinkFlow.confirm(
        link = link,
        clientDeviceId = app.settings.clientDeviceId,
        deviceName = app.settings.current.deviceName,
    )
    app.tokens.save(response.host.deviceId, response.deviceToken)
    app.pairedHosts.add(
        PairedHost(
            deviceId = response.host.deviceId,
            name = response.host.deviceName.ifBlank { link.hostName },
            lastHost = link.host,
            lastPort = link.port,
            pairedAtMs = System.currentTimeMillis(),
            // A scanned QR may belong to a PC or to a TV; §13 and §15.2 are
            // the same flow and nothing here gates on which.
            platform = response.host.platform,
        ),
    )
    PairStage.Paired(response.host.deviceName.ifBlank { link.hostName })
} catch (e: PairLinkException) {
    PairStage.Failed(e.reason.text)
} catch (e: Exception) {
    PairStage.Failed(e.sendroMessage())
}

// ---------------------------------------------------------------------------
// Manual connect
// ---------------------------------------------------------------------------

@Composable
private fun ManualPane(
    app: SendroApplication,
    onFound: (String, String, Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val profile = LocalDeviceProfile.current
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("48800") }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    /** Which of the two values the D-pad keypad is editing (TV only). */
    var editingPort by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    RequestInitialFocus(firstFocus)

    fun check() {
        checking = true
        status = null
        scope.launch {
            val parsedPort = port.toIntOrNull() ?: 48800
            val client = SendroClient.create(address, parsedPort)
            if (client == null) {
                status = "That doesn't look like an address."
                checking = false
                return@launch
            }
            val info = runCatching { client.info() }.getOrNull()
            checking = false
            when {
                info == null ->
                    status = "Nothing answered at $address:$parsedPort. Check the " +
                        "address, and that both devices are on the same network."
                info.app != "sendro" ->
                    status = "Something answered there, but it isn't Sendro."
                info.protocolVersion != SENDRO_PROTOCOL_VERSION ->
                    status = "That PC speaks protocol v${info.protocolVersion}; this " +
                        "device speaks v$SENDRO_PROTOCOL_VERSION. Update Sendro."
                else -> onFound(info.deviceName, address, parsedPort)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = profile.horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "The Sendro window on your PC shows its address and port. Manual connect " +
                "works even when mDNS is blocked (guest Wi-Fi, some routers, a hotspot).",
            style = Sendro.sans(13f),
            color = Sendro.textSecondary,
        )

        if (profile.isTv) {
            // Two value chips select which field the keypad writes into, so
            // the whole thing is a D-pad grid with no IME anywhere.
            Row(
                modifier = Modifier.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ValueChip(
                    label = "IP address",
                    value = address.ifEmpty { "—" },
                    selected = !editingPort,
                    onClick = { editingPort = false },
                    focusRequester = firstFocus,
                    modifier = Modifier.weight(1f),
                )
                ValueChip(
                    label = "Port",
                    value = port.ifEmpty { "—" },
                    selected = editingPort,
                    onClick = { editingPort = true },
                    modifier = Modifier.weight(0.5f),
                )
            }
            DpadKeypad(
                includeDot = !editingPort,
                onDigit = { key ->
                    if (editingPort) {
                        if (key != '.') port = (port + key).take(5)
                    } else {
                        address = (address + key).take(45)
                    }
                },
                onBackspace = {
                    if (editingPort) port = port.dropLast(1) else address = address.dropLast(1)
                },
                onClear = { if (editingPort) port = "" else address = "" },
                modifier = Modifier.widthIn(max = 320.dp),
            )
        } else {
            PlainField(
                value = address,
                onValueChange = { address = it },
                placeholder = "192.168.1.24",
                focusRequester = firstFocus,
            )
            PlainField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                placeholder = "48800",
                numeric = true,
            )
        }

        AccentPill(
            title = if (checking) "Checking…" else "Check and continue",
            enabled = !checking && address.isNotBlank(),
            onClick = { check() },
        )
        status?.let { Text(it, style = Sendro.sans(13f), color = Sendro.warn) }
    }
}

/** A labelled value that doubles as the D-pad keypad's target selector. */
@Composable
private fun ValueChip(
    label: String,
    value: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    Pressable(
        onClick = onClick,
        modifier = modifier,
        focusRequester = focusRequester,
        focusCorner = 14.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassRow(cornerRadius = 14.dp, fillAlpha = if (selected) 0.11f else 0.045f)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionTag(label, if (selected) Sendro.irisSoft else Sendro.textFaint)
            Text(
                text = value,
                style = Sendro.mono(18f, FontWeight.SemiBold),
                color = Sendro.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    numeric: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = {
            Text(placeholder, style = Sendro.mono(14f), color = Sendro.textFaint)
        },
        textStyle = Sendro.mono(14f),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Uri,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
            .clip(RoundedCornerShape(14.dp)),
        colors = sendroFieldColors(),
    )
}

/** One field palette, so every TextField in the app looks the same. */
@Composable
private fun sendroFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.White.copy(alpha = 0.06f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
    focusedTextColor = Sendro.textPrimary,
    unfocusedTextColor = Sendro.textPrimary,
    cursorColor = Sendro.iris,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)

// ---------------------------------------------------------------------------
// Hotspot help
// ---------------------------------------------------------------------------

@Composable
private fun HotspotPane() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LocalDeviceProfile.current.horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Sendro only needs the two devices on the same network — a router is one " +
                "way to get that, not the only one.",
            style = Sendro.sans(13f),
            color = Sendro.textSecondary,
        )
        HotspotStep(
            tag = "Easiest",
            title = "Phone hotspot, PC joins",
            body = "Turn on this phone's hotspot, connect the PC to it, then pair by IP " +
                "(the PC's Sendro window shows the address it got).",
        )
        HotspotStep(
            tag = "Also fine",
            title = "PC hotspot, phone joins",
            body = "Windows Mobile Hotspot works the same way. Sendro notices the network " +
                "change and re-pings every paired computer by itself.",
        )
        HotspotStep(
            tag = "Note",
            title = "mDNS can be blocked",
            body = "Some hotspots and most guest networks block multicast, so discovery " +
                "finds nothing. Connect by IP — everything else is identical.",
        )
    }
}

@Composable
private fun HotspotStep(tag: String, title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth().glassRow(cornerRadius = 18.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionTag(tag, Sendro.irisSoft)
        Text(title, style = Sendro.sans(15f, FontWeight.SemiBold), color = Sendro.textPrimary)
        Text(body, style = Sendro.sans(13f), color = Sendro.textSecondary)
    }
}
