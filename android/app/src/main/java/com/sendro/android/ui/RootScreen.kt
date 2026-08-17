package com.sendro.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sendro.android.SendroApplication
import com.sendro.android.core.AppSurface
import com.sendro.android.core.NotificationRoute
import com.sendro.android.core.PairLink
import com.sendro.android.ui.components.Pressable
import com.sendro.android.ui.screens.DevicesScreen
import com.sendro.android.ui.screens.FlightScreen
import com.sendro.android.ui.screens.LibraryScreen
import com.sendro.android.ui.screens.PreviewRequest
import com.sendro.android.ui.screens.PreviewScreen
import com.sendro.android.ui.screens.ReceiveScreen
import com.sendro.android.ui.screens.ReceiverPairingScreen
import com.sendro.android.ui.screens.SendScreen
import com.sendro.android.ui.screens.SettingsScreen
import com.sendro.android.ui.theme.LocalDeviceProfile
import com.sendro.android.ui.theme.Sendro
import com.sendro.android.ui.theme.SendroBackground
import com.sendro.android.ui.theme.glassRow
import kotlinx.coroutines.delay

/**
 * The shell: three peer surfaces — Receive · Send · Library — behind a
 * floating glass tab bar, plus the Devices screen (discovery / pairing /
 * manual connect), Settings, the full-screen Flight view for a live transfer,
 * and the ephemeral message stack floating over whichever tab is showing.
 *
 * Same information architecture as iOS, down to the tab order.
 */
enum class SendroTab(val title: String) {
    RECEIVE("Receive"),
    SEND("Send"),
    LIBRARY("Library"),
}

private sealed interface Overlay {
    data object None : Overlay
    data object Devices : Overlay
    data object Settings : Overlay
    data class Flight(val transferId: String) : Overlay
    data class Preview(val request: PreviewRequest) : Overlay

    /** §15.2 — the QR a phone scans to pair with this device. */
    data object ReceiverPairing : Overlay
}

@Composable
fun RootScreen(
    app: SendroApplication,
    pendingRoute: MutableState<NotificationRoute?>,
    pendingPairLink: MutableState<PairLink?>,
    pendingLinkWarning: MutableState<String?>,
    sharedFilesSignal: MutableState<Int>,
    onRequestNotificationPermission: () -> Unit,
) {
    val profile = LocalDeviceProfile.current
    var tab by remember { mutableStateOf(SendroTab.RECEIVE) }
    var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }
    var sendTargetId by remember { mutableStateOf<String?>(null) }

    val incoming by app.transferEngine.incoming.collectAsStateWithLifecycle()
    val messages by app.messages.inbox.collectAsStateWithLifecycle()
    val pairedHosts by app.pairedHosts.hosts.collectAsStateWithLifecycle()
    val hostOnline by app.transferEngine.hostOnline.collectAsStateWithLifecycle()

    // Notification taps and share intents route here.
    LaunchedEffect(pendingRoute.value) {
        when (pendingRoute.value) {
            NotificationRoute.RECEIVE -> tab = SendroTab.RECEIVE
            NotificationRoute.SEND -> tab = SendroTab.SEND
            NotificationRoute.LIBRARY -> tab = SendroTab.LIBRARY
            null -> Unit
        }
        if (pendingRoute.value != null) {
            overlay = Overlay.None
            pendingRoute.value = null
        }
    }

    // A scanned / opened sendro:// URL always goes through Devices, which owns
    // the confirmation pane (§13).
    LaunchedEffect(pendingPairLink.value) {
        if (pendingPairLink.value != null) overlay = Overlay.Devices
    }

    // Files shared in: jump to Send with a sensible target already picked.
    LaunchedEffect(sharedFilesSignal.value) {
        if (sharedFilesSignal.value == 0) return@LaunchedEffect
        tab = SendroTab.SEND
        overlay = Overlay.None
        // Pick an online PC if the remembered one is gone or offline. Never
        // clear an existing choice — the Send screen shows it as offline and
        // explains why, which beats silently retargeting someone's file.
        val current = sendTargetId
        if (current == null || hostOnline[current] != true) {
            sendTargetId = pairedHosts.firstOrNull { hostOnline[it.deviceId] == true }?.deviceId
                ?: current
        }
    }

    // Keep the notifier's idea of what is on screen honest, so it stays quiet
    // about things the user is already looking at.
    LaunchedEffect(tab, overlay) {
        app.notifier.updateSurface(
            when {
                overlay is Overlay.Flight -> AppSurface.FLIGHT
                tab == SendroTab.RECEIVE -> AppSurface.RECEIVE
                tab == SendroTab.SEND -> AppSurface.SEND
                else -> AppSurface.LIBRARY
            },
        )
    }

    BackHandler(enabled = overlay !is Overlay.None) { overlay = Overlay.None }
    BackHandler(enabled = overlay is Overlay.None && tab != SendroTab.RECEIVE) {
        tab = SendroTab.RECEIVE
    }

    val tabFocus = remember { FocusRequester() }

    SendroBackground {
        val surfaces: @Composable () -> Unit = {
            Crossfade(targetState = tab, label = "tab") { current ->
                when (current) {
                    SendroTab.RECEIVE -> ReceiveScreen(
                        app = app,
                        onOpenDevices = { overlay = Overlay.Devices },
                        onOpenSettings = { overlay = Overlay.Settings },
                        onOpenFlight = { overlay = Overlay.Flight(it) },
                        onPreview = { overlay = Overlay.Preview(it) },
                        onGoLibrary = { tab = SendroTab.LIBRARY },
                        onOpenReceiverPairing = { overlay = Overlay.ReceiverPairing },
                    )

                    SendroTab.SEND -> SendScreen(
                        app = app,
                        targetHostId = sendTargetId,
                        onTargetChange = { sendTargetId = it },
                        onOpenDevices = { overlay = Overlay.Devices },
                    )

                    SendroTab.LIBRARY -> LibraryScreen(
                        app = app,
                        onPreview = { overlay = Overlay.Preview(it) },
                    )
                }
            }
        }

        val navigation: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TransientWarning(pendingLinkWarning)
                TabBar(
                    tab = tab,
                    onSelect = { tab = it },
                    receiveDot = messages.isNotEmpty() ||
                        (incoming.isNotEmpty() && tab != SendroTab.RECEIVE),
                    focusRequester = tabFocus,
                )
            }
        }

        val overlayContent: @Composable () -> Unit = {
            // Full-screen destinations. Deliberately plain state rather than
            // navigation-compose routes: five destinations with no deep back
            // stack do not need a navigation graph, and this keeps every
            // transition in one readable place.
            Box(modifier = Modifier.fillMaxSize().background(Sendro.bg)) {
                when (val current = overlay) {
                    Overlay.Devices -> DevicesScreen(
                        app = app,
                        initialLink = pendingPairLink.value,
                        onLinkConsumed = { pendingPairLink.value = null },
                        onClose = {
                            pendingPairLink.value = null
                            overlay = Overlay.None
                        },
                        onPaired = onRequestNotificationPermission,
                        onOpenReceiverPairing = { overlay = Overlay.ReceiverPairing },
                    )

                    Overlay.Settings -> SettingsScreen(
                        app = app,
                        onClose = { overlay = Overlay.None },
                    )

                    is Overlay.Flight -> FlightScreen(
                        app = app,
                        transferId = current.transferId,
                        onClose = { overlay = Overlay.None },
                    )

                    is Overlay.Preview -> PreviewScreen(
                        app = app,
                        request = current.request,
                        onClose = { overlay = Overlay.None },
                    )

                    Overlay.ReceiverPairing -> ReceiverPairingScreen(
                        app = app,
                        onClose = { overlay = Overlay.None },
                    )

                    Overlay.None -> Unit
                }
            }
        }

        if (profile.isTv) {
            // TV composes exactly ONE of {overlay, message, shell}.
            //
            // This is a focus requirement, not a rendering nicety: an opaque
            // layer drawn on top of the shell hides it visually but leaves
            // every button underneath focusable, and the D-pad happily walks
            // into things the user cannot see. Removing the layer below from
            // composition is the only reliable fix.
            //
            // Precedence: an overlay is an explicit destination the user chose
            // (pairing, settings, a live transfer) and must not be hijacked, so
            // an arriving §11 message waits in the RAM inbox and appears the
            // moment that destination closes.
            when {
                overlay !is Overlay.None -> overlayContent()

                messages.isNotEmpty() -> MessageOverlay(
                    app = app,
                    messages = messages,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    // The tab bar is a real sibling BELOW the content, not a
                    // floating overlay. Compose's 2D focus search walks
                    // geometry, so an overlay sitting on top of the last list
                    // rows makes "D-pad down out of the list" ambiguous. As
                    // siblings, Down from the last row lands on the tabs and Up
                    // from the tabs goes back into the content, with no
                    // explicit focusProperties wiring at all.
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) { surfaces() }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = profile.horizontalPadding,
                                end = profile.horizontalPadding,
                                bottom = profile.topPadding,
                            ),
                    ) { navigation() }
                }
            }
        } else {
            surfaces()
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(
                        bottom = 10.dp + WindowInsets.navigationBars.asPaddingValues()
                            .calculateBottomPadding(),
                    ),
            ) { navigation() }

            // Ephemeral §11 text as a banner over whatever surface is showing.
            MessageOverlay(
                app = app,
                messages = messages,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            AnimatedVisibility(
                visible = overlay !is Overlay.None,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                overlayContent()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Floating glass tab bar
// ---------------------------------------------------------------------------

@Composable
private fun TabBar(
    tab: SendroTab,
    onSelect: (SendroTab) -> Unit,
    receiveDot: Boolean,
    focusRequester: FocusRequester? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // One focus group: Left/Right walks the three tabs, and the group
            // as a whole is what Up/Down enters and leaves.
            .focusGroup()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TabButton(SendroTab.RECEIVE, tab, onSelect, Modifier.weight(1f), receiveDot, focusRequester)
        TabButton(SendroTab.SEND, tab, onSelect, Modifier.weight(1f), false, null)
        TabButton(SendroTab.LIBRARY, tab, onSelect, Modifier.weight(1f), false, null)
    }
}

@Composable
private fun TabButton(
    target: SendroTab,
    current: SendroTab,
    onSelect: (SendroTab) -> Unit,
    modifier: Modifier,
    showDot: Boolean,
    focusRequester: FocusRequester?,
) {
    val selected = target == current
    Pressable(
        onClick = { onSelect(target) },
        modifier = modifier,
        focusRequester = focusRequester,
        focusCorner = 18.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = target.title,
                    style = Sendro.sans(12.5f, FontWeight.SemiBold),
                    color = if (selected) Sendro.textBase else Sendro.textBase.copy(alpha = 0.45f),
                    maxLines = 1,
                )
                if (showDot) {
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Sendro.iris),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Transient warning strip
// ---------------------------------------------------------------------------

@Composable
private fun TransientWarning(state: MutableState<String?>) {
    val text = state.value
    LaunchedEffect(text) {
        if (text != null) {
            delay(4_000)
            if (state.value == text) state.value = null
        }
    }
    AnimatedVisibility(visible = text != null, enter = fadeIn(), exit = fadeOut()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassRow(cornerRadius = 16.dp, fillAlpha = 0.08f, borderAlpha = 0.12f)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = Sendro.warn,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = text.orEmpty(),
                style = Sendro.sans(12.5f),
                color = Sendro.warn,
            )
        }
    }
}
