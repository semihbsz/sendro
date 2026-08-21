package com.sendro.android.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sendro.android.SendroApplication
import com.sendro.android.SharedTextBuffer
import com.sendro.android.core.Format
import com.sendro.android.core.PairedHost
import com.sendro.android.core.SENDRO_MESSAGE_BYTE_LIMIT
import com.sendro.android.core.UploadItem
import com.sendro.android.core.UploadPhase
import com.sendro.android.ui.components.TopInsetSpacer
import com.sendro.android.ui.components.AccentPill
import com.sendro.android.ui.components.FileBadge
import com.sendro.android.ui.components.GhostPill
import com.sendro.android.ui.components.NoticeCard
import com.sendro.android.ui.components.PlatformNames
import com.sendro.android.ui.components.Pressable
import com.sendro.android.ui.components.RequestInitialFocus
import com.sendro.android.ui.components.SectionTag
import com.sendro.android.ui.components.StatusChip
import com.sendro.android.ui.components.ThinProgress
import com.sendro.android.ui.components.screenPadding
import com.sendro.android.ui.theme.DeviceProfile
import com.sendro.android.ui.theme.LocalDeviceProfile
import com.sendro.android.ui.theme.PulseDot
import com.sendro.android.ui.theme.Sendro
import com.sendro.android.ui.theme.glassCard
import com.sendro.android.ui.theme.glassRow
import kotlinx.coroutines.launch

/**
 * The Send tab — a first-class surface, not a sheet. Target-PC chip, four big
 * actions (Photos & Videos, Files, Send Text, Paste), then the outgoing queue
 * with per-file progress, speed, ETA, cancel and retry.
 *
 * Backed by UploadEngine (§7) and, for text, the ephemeral message path
 * (§11.2). Nothing is ever sent without the user tapping Send.
 */
@Composable
fun SendScreen(
    app: SendroApplication,
    targetHostId: String?,
    onTargetChange: (String?) -> Unit,
    onOpenDevices: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pairedHosts by app.pairedHosts.hosts.collectAsStateWithLifecycle()
    val hostOnline by app.transferEngine.hostOnline.collectAsStateWithLifecycle()
    val queue by app.uploadEngine.items.collectAsStateWithLifecycle()
    val tray by app.sendTray.items.collectAsStateWithLifecycle()
    val profile = LocalDeviceProfile.current
    val firstAction = remember { FocusRequester() }
    RequestInitialFocus(firstAction)

    val onlineHosts = pairedHosts.filter { hostOnline[it.deviceId] == true }
    val target: PairedHost? = pairedHosts.firstOrNull {
        it.deviceId == targetHostId && hostOnline[it.deviceId] == true
    } ?: onlineHosts.firstOrNull()
    val chipHost = target
        ?: pairedHosts.firstOrNull { it.deviceId == targetHostId }
        ?: pairedHosts.firstOrNull()

    var hint by remember { mutableStateOf<String?>(null) }
    var composerText by remember { mutableStateOf<String?>(null) }
    var showTargetPicker by remember { mutableStateOf(false) }

    // A text/plain share opens the composer pre-filled.
    LaunchedEffect(Unit) {
        SharedTextBuffer.take()?.let { composerText = it }
    }

    // --- pickers -----------------------------------------------------------

    fun stageAndQueue(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val staged = app.uploadEngine.stage(uris)
            app.sendTray.add(staged.files)
            hint = when {
                staged.failures.isEmpty() && staged.files.isNotEmpty() ->
                    "${staged.files.size} ready — tap Send."
                staged.failures.isNotEmpty() ->
                    "Some items couldn't be added: ${staged.failures.first()}"
                else -> null
            }
        }
    }

    // The Photo Picker needs NO permission at all and returns the ORIGINAL
    // item — Sendro copies its bytes verbatim, it never decodes a Bitmap.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30),
    ) { uris -> stageAndQueue(uris) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> stageAndQueue(uris) }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TopInsetSpacer()

        SendHeader(
            chipHost = chipHost,
            online = chipHost != null && hostOnline[chipHost.deviceId] == true,
            onTapChip = {
                if (pairedHosts.size > 1) showTargetPicker = !showTargetPicker else onOpenDevices()
            },
            modifier = Modifier.padding(
                horizontal = profile.horizontalPadding,
                vertical = 10.dp,
            ),
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = screenPadding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showTargetPicker && pairedHosts.size > 1) {
                item {
                    TargetPicker(
                        hosts = pairedHosts,
                        online = hostOnline,
                        selectedId = target?.deviceId,
                        onPick = {
                            onTargetChange(it)
                            showTargetPicker = false
                        },
                        onManage = onOpenDevices,
                    )
                }
            }

            if (pairedHosts.isEmpty()) {
                item {
                    NoticeCard(
                        title = "No paired PC yet",
                        message = "Pair a computer first — open Sendro on your PC, then tap " +
                            "the chip above.",
                        tint = Sendro.irisSoft,
                        actionLabel = "Pair a PC",
                        onAction = onOpenDevices,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            } else if (target == null) {
                item {
                    NoticeCard(
                        title = "Nothing online to send to",
                        message = "${chipHost?.name ?: "Your PC"} looks offline. Open Sendro " +
                            "on your computer on this Wi-Fi and these actions light up by " +
                            "themselves.",
                        tint = Sendro.warn,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }

            if (tray.isNotEmpty()) {
                item {
                    TrayCard(
                        count = tray.size,
                        totalBytes = app.sendTray.totalBytes,
                        canSend = target != null,
                        targetName = target?.name,
                        onSend = {
                            if (target != null) {
                                app.uploadEngine.enqueue(
                                    files = app.sendTray.takeAll(),
                                    hostId = target.deviceId,
                                    hostName = target.name,
                                )
                            }
                        },
                        onClear = { app.sendTray.clear() },
                    )
                }
            }

            item {
                ActionGrid(
                    enabled = target != null,
                    profile = profile,
                    firstActionFocus = firstAction,
                    // Every picker is guarded twice: the entry point is hidden
                    // when the device cannot answer the intent at all, and the
                    // launch itself is wrapped — a TV that claims a picker and
                    // then throws ActivityNotFoundException must not take the
                    // app down with it.
                    onPhotos = {
                        val launched = runCatching {
                            photoPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                                ),
                            )
                        }.isSuccess
                        if (!launched) hint = "This device has no photo picker."
                    },
                    onFiles = {
                        val launched = runCatching { filePicker.launch(arrayOf("*/*")) }.isSuccess
                        if (!launched) hint = "This device has no file picker."
                    },
                    onText = { composerText = "" },
                    onPaste = {
                        val pasted = runCatching { readClipboard(context) }.getOrNull()
                        when {
                            pasted == null -> hint = "Nothing on the clipboard."
                            pasted.uri != null -> stageAndQueue(listOf(pasted.uri))
                            else -> composerText = pasted.text.orEmpty()
                        }
                    },
                )
            }

            hint?.let { text ->
                item {
                    Text(
                        text = text,
                        style = Sendro.sans(12.5f),
                        color = Sendro.irisSoft,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }

            item {
                SectionTag(
                    text = if (queue.isEmpty()) "Outgoing" else "Outgoing (${queue.size})",
                    color = Sendro.textFaint,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            if (queue.isEmpty()) {
                item {
                    Text(
                        text = "Nothing in flight. Pick photos or files above — Sendro sends " +
                            "the original bytes and the PC verifies the SHA-256 as it writes.",
                        style = Sendro.sans(13f),
                        color = Sendro.textTertiary,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            } else {
                items(queue, key = { it.id }) { item ->
                    UploadRow(
                        item = item,
                        onCancel = { app.uploadEngine.cancel(item.id) },
                        onRetry = { app.uploadEngine.retry(item.id) },
                    )
                }
                if (queue.any { it.phase is UploadPhase.Done }) {
                    item {
                        GhostPill(
                            title = "Clear done",
                            onClick = { app.uploadEngine.clearFinished() },
                            height = 42.dp,
                        )
                    }
                }
            }
        }
    }

    composerText?.let { initial ->
        TextComposer(
            initialText = initial,
            targetName = target?.name,
            onDismiss = { composerText = null },
            onSend = { text ->
                val host = target
                if (host == null) {
                    hint = "No PC online to send to."
                    composerText = null
                } else {
                    composerText = null
                    scope.launch {
                        val error = app.transferEngine.sendMessage(text, host.deviceId)
                        hint = error ?: "Text delivered to ${host.name}."
                    }
                }
            },
        )
    }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun SendHeader(
    chipHost: PairedHost?,
    online: Boolean,
    onTapChip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Send", style = Sendro.sans(26f, FontWeight.SemiBold), color = Sendro.textPrimary)
        Spacer(Modifier.weight(1f))
        Pressable(onClick = onTapChip) {
            Row(
                modifier = Modifier
                    .height(32.dp)
                    .glassRow(cornerRadius = 16.dp, fillAlpha = 0.06f, borderAlpha = 0.12f)
                    .padding(start = 11.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PulseDot(
                    color = if (chipHost == null) Sendro.iris else Sendro.teal,
                    active = online || chipHost == null,
                    side = 7.dp,
                )
                Text(
                    text = chipHost?.name?.uppercase() ?: "PAIR A PC",
                    style = Sendro.mono(11.5f, FontWeight.Medium),
                    color = if (chipHost == null) Sendro.irisSoft
                    else Sendro.textBase.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 130.dp),
                )
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = "Target computer",
                    tint = Sendro.textBase.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun TargetPicker(
    hosts: List<PairedHost>,
    online: Map<String, Boolean>,
    selectedId: String?,
    onPick: (String) -> Unit,
    onManage: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().glassCard(cornerRadius = 22.dp).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTag("Send to", Sendro.textFaint)
        hosts.forEach { host ->
            Pressable(onClick = { onPick(host.deviceId) }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassRow(
                            cornerRadius = 14.dp,
                            fillAlpha = if (host.deviceId == selectedId) 0.10f else 0.04f,
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PulseDot(active = online[host.deviceId] == true, side = 7.dp)
                    Text(
                        host.name,
                        style = Sendro.sans(14f, FontWeight.Medium),
                        color = Sendro.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = platformLabel(host),
                        style = Sendro.mono(10f),
                        color = if (online[host.deviceId] == true) Sendro.teal else Sendro.textFaint,
                    )
                }
            }
        }
        GhostPill(title = "Manage computers…", onClick = onManage, height = 40.dp)
    }
}

/**
 * What a target actually is. A TV paired through §15 is just another target
 * for the §7 upload the Send queue already speaks — the only difference the
 * user needs to see is which box the file is going to.
 */
private fun platformLabel(host: PairedHost): String = PlatformNames.label(host.platform)

@Composable
private fun TrayCard(
    count: Int,
    totalBytes: Long,
    canSend: Boolean,
    targetName: String?,
    onSend: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().glassCard(cornerRadius = 22.dp).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = if (count == 1) "1 file ready" else "$count files ready",
            style = Sendro.sans(17f, FontWeight.SemiBold),
            color = Sendro.textPrimary,
        )
        Text(
            text = "${Format.bytes(totalBytes)} · staged, nothing sent yet",
            style = Sendro.mono(11f),
            color = Sendro.textTertiary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AccentPill(
                title = if (canSend) "Send to ${targetName ?: "PC"}" else "No PC online",
                onClick = onSend,
                enabled = canSend,
                height = 46.dp,
                modifier = Modifier.weight(1f),
            )
            GhostPill(
                title = "Clear",
                onClick = onClear,
                height = 46.dp,
                modifier = Modifier.weight(0.45f),
            )
        }
    }
}

@Composable
private fun ActionGrid(
    enabled: Boolean,
    profile: DeviceProfile,
    firstActionFocus: FocusRequester?,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onText: () -> Unit,
    onPaste: () -> Unit,
) {
    // Hide, do not crash. On an Android TV there is usually no Photo Picker
    // and often no SAF document provider; showing a card that can only fail is
    // worse than not showing it. Sending FROM the TV is the niche case anyway —
    // "Send Text" is the one that stays useful with a remote.
    data class Action(val title: String, val subtitle: String, val onClick: () -> Unit)

    val actions = buildList {
        if (profile.hasPhotoPicker) {
            add(Action("Photos & Videos", "Original bytes", onPhotos))
        }
        if (profile.hasDocumentPicker) {
            add(
                Action(
                    "Files",
                    if (profile.isTv) "Anything on this TV" else "Anything on this phone",
                    onFiles,
                ),
            )
        }
        add(Action("Send Text", "A link, a code — vanishes after", onText))
        if (profile.hasTouchscreen || !profile.isTv) {
            add(Action("Paste", "Whatever's on the clipboard", onPaste))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.chunked(2).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEachIndexed { columnIndex, action ->
                    ActionCard(
                        title = action.title,
                        subtitle = action.subtitle,
                        enabled = enabled,
                        onClick = action.onClick,
                        focusRequester = if (rowIndex == 0 && columnIndex == 0) {
                            firstActionFocus
                        } else {
                            null
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keep a lone card at half width rather than letting it
                // stretch across the row and read as a banner.
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    Pressable(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        focusRequester = focusRequester,
        focusCorner = 20.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .glassRow(cornerRadius = 20.dp, fillAlpha = 0.055f, borderAlpha = 0.09f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = Sendro.sans(15.5f, FontWeight.SemiBold),
                color = if (enabled) Sendro.textPrimary else Sendro.textTertiary,
            )
            Text(
                text = subtitle,
                style = Sendro.mono(10.5f),
                color = Sendro.textTertiary,
            )
        }
    }
}

@Composable
private fun UploadRow(item: UploadItem, onCancel: () -> Unit, onRetry: () -> Unit) {
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
            FileBadge(item.fileName, side = 36.dp, cornerRadius = 11.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.fileName,
                    style = Sendro.sans(14f, FontWeight.Medium),
                    color = Sendro.textBase.copy(alpha = 0.93f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when (val phase = item.phase) {
                        UploadPhase.Queued -> "Waiting…"
                        // The host asked us to wait — say so, and count down.
                        is UploadPhase.HostBusy -> phase.label
                        UploadPhase.Hashing -> "Hashing SHA-256…"
                        UploadPhase.Uploading ->
                            "${Format.bytes(item.bytesSent)} / ${Format.bytes(item.sizeBytes)}" +
                                " · ${Format.speed(item.bytesPerSecond)}" +
                                " · ${Format.eta(item.etaSeconds)}"
                        UploadPhase.Done -> "Landed on ${item.hostName}"
                        is UploadPhase.Failed -> phase.message
                    },
                    style = Sendro.mono(10.5f),
                    color = when (item.phase) {
                        is UploadPhase.Failed -> Sendro.danger
                        is UploadPhase.HostBusy -> Sendro.warn
                        else -> Sendro.textTertiary
                    },
                    maxLines = 2,
                )
            }
            StatusChip(
                text = item.phase.shortLabel,
                color = when (item.phase) {
                    UploadPhase.Done -> Sendro.teal
                    is UploadPhase.Failed -> Sendro.danger
                    // Amber: waiting on the PC is not an error.
                    is UploadPhase.HostBusy -> Sendro.warn
                    else -> Sendro.iris
                },
            )
            if (item.phase is UploadPhase.Failed || item.phase is UploadPhase.HostBusy) {
                Pressable(onClick = onRetry) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Retry upload",
                        tint = Sendro.irisSoft,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Pressable(onClick = onCancel) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Cancel upload",
                    tint = Sendro.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (item.phase == UploadPhase.Uploading) {
            ThinProgress(item.fraction.toFloat(), Sendro.iris)
        }
    }
}

// ---------------------------------------------------------------------------
// §11.2 composer
// ---------------------------------------------------------------------------

@Composable
private fun TextComposer(
    initialText: String,
    targetName: String?,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    val byteCount = remember(text) { text.toByteArray(Charsets.UTF_8).size }
    val tooLong = byteCount > SENDRO_MESSAGE_BYTE_LIMIT
    val profile = LocalDeviceProfile.current

    // A sheet must always have a way out that does not depend on aiming at a
    // scrim: BACK closes it on every device.
    BackHandler(enabled = true) { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .then(
                // Tap-outside-to-dismiss is a touch idiom. On a TV the scrim
                // would just be one more focus stop that does nothing useful.
                if (profile.hasTouchscreen) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .glassCard(cornerRadius = 24.dp)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (targetName != null) "Send text to $targetName" else "Send text",
                style = Sendro.sans(16f, FontWeight.SemiBold),
                color = Sendro.textPrimary,
            )
            Text(
                text = "Lives in memory on both sides. Never written to disk, never in history.",
                style = Sendro.sans(12f),
                color = Sendro.textTertiary,
            )
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = {
                    Text(
                        "Paste a link, a path, a code…",
                        style = Sendro.sans(14f),
                        color = Sendro.textFaint,
                    )
                },
                textStyle = Sendro.sans(14f),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp, max = 220.dp)
                    .clip(RoundedCornerShape(14.dp)),
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
            Text(
                text = if (tooLong) "Too long — $byteCount / $SENDRO_MESSAGE_BYTE_LIMIT bytes"
                else "$byteCount / $SENDRO_MESSAGE_BYTE_LIMIT bytes",
                style = Sendro.mono(10.5f),
                color = if (tooLong) Sendro.danger else Sendro.textFaint,
            )
            Row(
                modifier = Modifier.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AccentPill(
                    title = "Send",
                    onClick = { onSend(text) },
                    enabled = text.isNotBlank() && !tooLong && targetName != null,
                    height = 46.dp,
                    modifier = Modifier.weight(1f),
                )
                GhostPill(
                    title = "Cancel",
                    onClick = onDismiss,
                    height = 46.dp,
                    modifier = Modifier.weight(0.5f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Clipboard
// ---------------------------------------------------------------------------

private data class Pasted(val text: String?, val uri: android.net.Uri?)

/**
 * Read the clipboard once.
 *
 * An image on the clipboard arrives as a content URI — Sendro stages that
 * URI's ORIGINAL bytes as a file, exactly like a picked photo. It never
 * renders the clip into a bitmap and re-encodes it.
 */
private fun readClipboard(context: Context): Pasted? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return null
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    val item = clip.getItemAt(0)
    item.uri?.let { return Pasted(null, it) }
    val text = item.coerceToText(context)?.toString()
    return if (text.isNullOrBlank()) null else Pasted(text, null)
}
