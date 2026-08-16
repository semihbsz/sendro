package com.sendro.android.ui.screens

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sendro.android.BuildConfig
import com.sendro.android.SendroApplication
import com.sendro.android.core.Format
import com.sendro.android.core.MediaSaver
import com.sendro.android.core.SaveMediaMode
import com.sendro.android.core.UpdateState
import com.sendro.android.ui.components.TopInsetSpacer
import com.sendro.android.ui.components.AccentPill
import com.sendro.android.ui.components.GhostPill
import com.sendro.android.ui.components.NoticeCard
import com.sendro.android.ui.components.Pressable
import com.sendro.android.ui.components.RequestInitialFocus
import com.sendro.android.ui.components.SectionTag
import com.sendro.android.ui.components.ThinProgress
import com.sendro.android.ui.theme.LocalDeviceProfile
import com.sendro.android.ui.theme.Sendro
import com.sendro.android.ui.theme.glassCard
import com.sendro.android.ui.theme.glassRow
import kotlinx.coroutines.launch

/**
 * Settings: identity, receiving behaviour, notifications, network
 * diagnostics, updates (UPDATES.md §4) and about.
 */
@Composable
fun SettingsScreen(app: SendroApplication, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val network by app.networkWatcher.state.collectAsStateWithLifecycle()
    val paired by app.pairedHosts.hosts.collectAsStateWithLifecycle()

    var deviceName by remember(settings.deviceName) { mutableStateOf(settings.deviceName) }
    var pingResults by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pinging by remember { mutableStateOf(false) }
    val profile = LocalDeviceProfile.current
    val closeFocus = remember { FocusRequester() }
    RequestInitialFocus(closeFocus)

    Column(modifier = Modifier.fillMaxSize().background(Sendro.bg)) {
        TopInsetSpacer()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = profile.horizontalPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Pressable(onClick = onClose, focusRequester = closeFocus, focusCorner = 17.dp) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .glassRow(cornerRadius = 17.dp, fillAlpha = 0.06f, borderAlpha = 0.12f),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Done",
                        tint = Sendro.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                "Settings",
                style = Sendro.sans(20f, FontWeight.SemiBold),
                color = Sendro.textPrimary,
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = profile.horizontalPadding,
                end = profile.horizontalPadding,
                top = 6.dp,
                bottom = maxOf(48.dp, profile.scrollBottomPadding),
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { UpdateSection(app) }

            item { SectionTag("This phone", Sendro.textFaint, Modifier.padding(top = 12.dp)) }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().glassRow().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Device name",
                        style = Sendro.sans(14f, FontWeight.Medium),
                        color = Sendro.textPrimary,
                    )
                    Text(
                        "What your PC calls this phone in its device list.",
                        style = Sendro.sans(12f),
                        color = Sendro.textTertiary,
                    )
                    TextField(
                        value = deviceName,
                        onValueChange = {
                            deviceName = it
                            scope.launch { app.settings.setDeviceName(it) }
                        },
                        singleLine = true,
                        textStyle = Sendro.sans(14f),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White.copy(alpha = 0.06f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                            focusedTextColor = Sendro.textPrimary,
                            unfocusedTextColor = Sendro.textPrimary,
                            cursorColor = Sendro.iris,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                }
            }

            item {
                SectionTag("Receive from other devices", Sendro.textFaint, Modifier.padding(top = 12.dp))
            }
            item { ReceiverStatusLine(app) }
            item {
                ToggleRow(
                    title = "Receive from other devices",
                    subtitle = "Runs a small receiver on this device so a phone or PC on " +
                        "your Wi-Fi can push files and links straight to it — no offer, no " +
                        "waiting. Off closes the port and stops advertising. " +
                        if (profile.isTv) {
                            "On by default here: a TV only ever receives."
                        } else {
                            "Off by default on a phone, which is normally the sender."
                        },
                    checked = settings.receiveFromOtherDevices,
                    onChange = { scope.launch { app.settings.setReceiveFromOtherDevices(it) } },
                )
            }

            item { SectionTag("Receiving", Sendro.textFaint, Modifier.padding(top = 12.dp)) }
            item {
                ToggleRow(
                    title = "Auto-accept from trusted devices",
                    subtitle = "Only applies to offers your PC marked auto-send. " +
                        "Everything else still asks.",
                    checked = settings.autoAcceptFromTrusted,
                    onChange = { scope.launch { app.settings.setAutoAccept(it) } },
                )
            }
            item {
                ChoiceRow(
                    title = "Save media to gallery",
                    options = SaveMediaMode.entries.map { it.label },
                    selectedIndex = SaveMediaMode.entries.indexOf(settings.saveMediaToGallery),
                    onSelect = {
                        scope.launch { app.settings.setSaveMedia(SaveMediaMode.entries[it]) }
                    },
                )
            }
            item {
                ToggleRow(
                    title = "\"${MediaSaver.ALBUM}\" album",
                    subtitle = "Photos go to Pictures/${MediaSaver.ALBUM}, videos to " +
                        "Movies/${MediaSaver.ALBUM}, other files to Download/${MediaSaver.ALBUM}.",
                    checked = settings.addToSendroAlbum,
                    onChange = { scope.launch { app.settings.setAddToAlbum(it) } },
                )
            }
            item {
                ToggleRow(
                    title = "Delete temp after save",
                    subtitle = "Off keeps a second copy inside Sendro — useful if you want " +
                        "the in-app preview to keep working.",
                    checked = settings.deleteTempAfterSave,
                    onChange = { scope.launch { app.settings.setDeleteTemp(it) } },
                )
            }

            item { SectionTag("Notifications", Sendro.textFaint, Modifier.padding(top = 12.dp)) }
            item {
                ToggleRow(
                    title = "Transfers",
                    subtitle = "Files offered, saved or failed.",
                    checked = settings.notifyTransfers,
                    onChange = { scope.launch { app.settings.setNotifyTransfers(it) } },
                )
            }
            item {
                ToggleRow(
                    title = "Messages",
                    subtitle = "Sender only — never the text.",
                    checked = settings.notifyMessages,
                    onChange = { scope.launch { app.settings.setNotifyMessages(it) } },
                )
            }
            if (!app.notifier.canPost()) {
                item {
                    NoticeCard(
                        title = "Notifications are blocked",
                        message = "Android is not letting Sendro post notifications, so you " +
                            "won't see arriving files while the app is in the background.",
                        tint = Sendro.warn,
                        actionLabel = "Open system settings",
                        onAction = {
                            runCatching {
                                context.startActivity(
                                    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(
                                            AndroidSettings.EXTRA_APP_PACKAGE,
                                            context.packageName,
                                        )
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    )
                }
            }

            item { SectionTag("Diagnostics", Sendro.textFaint, Modifier.padding(top = 12.dp)) }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().glassRow().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DiagnosticLine("Network", network.statusText)
                    DiagnosticLine(
                        "Discovery",
                        when (app.discovery.status.value) {
                            com.sendro.android.core.Discovery.Status.BROWSING -> "Browsing _sendro._tcp"
                            com.sendro.android.core.Discovery.Status.IDLE -> "Idle"
                            com.sendro.android.core.Discovery.Status.FAILED -> "Unavailable on this device"
                        },
                    )
                    DiagnosticLine(
                        "Free space",
                        app.transferEngine.freeBytes()?.let { Format.bytes(it) } ?: "unknown",
                    )
                    DiagnosticLine(
                        "Token storage",
                        if (app.tokens.isEncrypted) "Encrypted (Android Keystore)"
                        else "Plain (Keystore unavailable)",
                    )
                    if (MediaSaver.needsLegacyStoragePermission) {
                        DiagnosticLine(
                            "Storage permission",
                            if (app.mediaSaver.hasLegacyStoragePermission()) "granted" else "not granted",
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    GhostPill(
                        title = if (pinging) "Testing…" else "Test connections",
                        height = 42.dp,
                        enabled = !pinging && paired.isNotEmpty(),
                        onClick = {
                            pinging = true
                            scope.launch {
                                val results = LinkedHashMap<String, String>()
                                for (host in paired) {
                                    results[host.deviceId] =
                                        app.transferEngine.pingHost(host.deviceId)
                                }
                                pingResults = results
                                pinging = false
                            }
                        },
                    )
                    paired.forEach { host ->
                        pingResults[host.deviceId]?.let { result ->
                            DiagnosticLine(host.name, result)
                        }
                    }
                    GhostPill(
                        title = "Restart discovery",
                        height = 42.dp,
                        onClick = { app.discovery.restart() },
                    )
                }
            }

            item { SectionTag("About", Sendro.textFaint, Modifier.padding(top = 12.dp)) }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().glassRow().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DiagnosticLine(
                        "Version",
                        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    )
                    DiagnosticLine("Protocol", "v${com.sendro.android.core.SENDRO_PROTOCOL_VERSION}")
                    Text(
                        text = "Sendro moves your files over your own Wi-Fi and nowhere else. " +
                            "Nothing is re-encoded, resized or stripped, and a transfer is " +
                            "only \"verified\" when this phone's own SHA-256 of the bytes it " +
                            "wrote matches the sender's. The only thing Sendro ever fetches " +
                            "from the internet is its own update manifest.",
                        style = Sendro.sans(12.5f),
                        color = Sendro.textTertiary,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Update card (docs/UPDATES.md §4)
// ---------------------------------------------------------------------------

@Composable
private fun UpdateSection(app: SendroApplication) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by app.updateChecker.state.collectAsStateWithLifecycle()
    val settings by app.settings.state.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val current = state) {
            is UpdateState.Available -> {
                if (settings.dismissedUpdateVersion != current.manifest.version ||
                    current.required
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassCard(cornerRadius = 22.dp)
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SectionTag(
                            if (current.required) "Update required" else "Update available",
                            if (current.required) Sendro.warn else Sendro.irisSoft,
                        )
                        Text(
                            "Sendro ${current.manifest.version}",
                            style = Sendro.sans(18f, FontWeight.SemiBold),
                            color = Sendro.textPrimary,
                        )
                        if (current.manifest.pubDate.isNotBlank()) {
                            Text(
                                current.manifest.pubDate.take(10),
                                style = Sendro.mono(10.5f),
                                color = Sendro.textTertiary,
                            )
                        }
                        val notes = app.updateChecker.notesFor(current.manifest)
                        if (notes.isNotBlank()) {
                            Text(notes, style = Sendro.sans(13f), color = Sendro.textSecondary)
                        }
                        Row(
                            modifier = Modifier.focusGroup(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AccentPill(
                                title = "Update now",
                                onClick = { app.updateChecker.download(current.manifest) },
                                height = 44.dp,
                                modifier = Modifier.weight(1f),
                            )
                            if (!current.required) {
                                GhostPill(
                                    title = "Later",
                                    height = 44.dp,
                                    onClick = {
                                        app.updateChecker.dismiss(current.manifest.version)
                                    },
                                    modifier = Modifier.weight(0.5f),
                                )
                            }
                        }
                    }
                }
            }

            is UpdateState.Downloading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().glassCard(cornerRadius = 22.dp).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Downloading ${current.manifest.version}",
                        style = Sendro.sans(15f, FontWeight.SemiBold),
                        color = Sendro.textPrimary,
                    )
                    ThinProgress(current.fraction.toFloat(), Sendro.iris)
                    Text(
                        "${Format.bytes(current.bytesDownloaded)} / " +
                            Format.bytes(current.totalBytes),
                        style = Sendro.mono(10.5f),
                        color = Sendro.textTertiary,
                    )
                    GhostPill(
                        title = "Cancel",
                        height = 42.dp,
                        onClick = { app.updateChecker.cancelDownload() },
                    )
                }
            }

            is UpdateState.ReadyToInstall -> {
                var installError by remember(current.manifest.version) {
                    mutableStateOf<String?>(null)
                }
                Column(
                    modifier = Modifier.fillMaxWidth().glassCard(cornerRadius = 22.dp).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionTag("SHA-256 verified", Sendro.teal)
                    Text(
                        "Sendro ${current.manifest.version} is ready to install",
                        style = Sendro.sans(15f, FontWeight.SemiBold),
                        color = Sendro.textPrimary,
                    )
                    Text(
                        text = if (LocalDeviceProfile.current.isTv) {
                            "Android will ask you to confirm — that prompt is D-pad " +
                                "navigable. The first time it will also ask you to allow " +
                                "installs from Sendro."
                        } else {
                            "Android will ask you to confirm. The first time it will also " +
                                "ask you to allow installing apps from Sendro."
                        },
                        style = Sendro.sans(12.5f),
                        color = Sendro.textSecondary,
                    )
                    installError?.let { message ->
                        Text(message, style = Sendro.sans(12.5f), color = Sendro.warn)
                    }
                    Row(
                        modifier = Modifier.focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AccentPill(
                            title = "Install",
                            height = 44.dp,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                installError = null
                                // Two failure modes, both survivable: no
                                // permission yet, or no installer at all. The
                                // downloaded APK stays on disk either way, so
                                // the user is never stranded mid-update.
                                if (!app.updateChecker.canRequestInstall()) {
                                    if (!app.updateChecker.openInstallPermission()) {
                                        installError = "This device has no \"install unknown " +
                                            "apps\" screen. Enable it in Settings ▸ Security " +
                                            "and come back."
                                    }
                                } else if (!app.updateChecker.startInstall(current.apk)) {
                                    installError = "Nothing on this device can install an APK. " +
                                        "The verified file is kept — sideload it manually."
                                }
                            },
                        )
                        GhostPill(
                            title = "Allow installs",
                            height = 44.dp,
                            modifier = Modifier.weight(0.7f),
                            onClick = {
                                if (!app.updateChecker.openInstallPermission()) {
                                    installError = "Couldn't open the system setting."
                                }
                            },
                        )
                    }
                }
            }

            is UpdateState.Failed -> {
                NoticeCard(
                    title = "Update failed",
                    message = current.message,
                    tint = Sendro.danger,
                    actionLabel = "Dismiss",
                    onAction = { app.updateChecker.clearFailure() },
                )
            }

            UpdateState.Checking, UpdateState.Idle, UpdateState.UpToDate -> Unit
        }

        SectionTag("Updates", Sendro.textFaint)
        ToggleRow(
            title = "Check for updates automatically",
            subtitle = "The only internet request Sendro ever makes: a plain HTTPS GET for a " +
                "static file. No identifiers, no analytics.",
            checked = settings.autoCheckUpdates,
            onChange = { scope.launch { app.settings.setAutoCheckUpdates(it) } },
        )
        GhostPill(
            title = when (state) {
                UpdateState.Checking -> "Checking…"
                UpdateState.UpToDate -> "Up to date — check again"
                else -> "Check now"
            },
            height = 42.dp,
            enabled = state != UpdateState.Checking,
            onClick = { app.updateChecker.check(manual = true) },
        )
    }
}

// ---------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().glassRow().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = Sendro.sans(14f, FontWeight.Medium), color = Sendro.textPrimary)
            Text(subtitle, style = Sendro.sans(12f), color = Sendro.textTertiary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Sendro.onAccent,
                checkedTrackColor = Sendro.iris,
                uncheckedThumbColor = Sendro.textTertiary,
                uncheckedTrackColor = Color.White.copy(alpha = 0.08f),
                checkedBorderColor = Color.Transparent,
                uncheckedBorderColor = Color.White.copy(alpha = 0.12f),
            ),
        )
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().glassRow().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = Sendro.sans(14f, FontWeight.Medium), color = Sendro.textPrimary)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.04f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEachIndexed { index, label ->
                val active = index == selectedIndex
                Pressable(onClick = { onSelect(index) }, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                if (active) Color.White.copy(alpha = 0.10f) else Color.Transparent,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = Sendro.sans(12.5f, FontWeight.Medium),
                            color = if (active) Sendro.textPrimary else Sendro.textTertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            label,
            style = Sendro.mono(11f),
            color = Sendro.textFaint,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            value,
            style = Sendro.mono(11f),
            color = Sendro.textBase.copy(alpha = 0.78f),
            modifier = Modifier.weight(1f),
        )
    }
}
