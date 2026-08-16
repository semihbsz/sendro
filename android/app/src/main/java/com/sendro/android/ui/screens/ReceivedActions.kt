package com.sendro.android.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sendro.android.SendroApplication
import com.sendro.android.core.ApkInstaller
import com.sendro.android.core.MediaKind
import com.sendro.android.ui.components.AccentPill
import com.sendro.android.ui.components.GhostPill
import com.sendro.android.ui.components.SectionTag
import com.sendro.android.ui.theme.LocalDeviceProfile
import com.sendro.android.ui.theme.Sendro
import com.sendro.android.ui.theme.glassRow

/**
 * What you can DO with a file that has arrived.
 *
 * One place, used by the Library row, the completed-transfer card and the
 * preview screen, so "play this movie" and "install this APK" behave the same
 * wherever the user finds the file.
 */
sealed interface ReceivedAction {
    /** Hand a video to the TV's own player (or the phone's). */
    data object PlayExternally : ReceivedAction

    /** Show it in Sendro's own viewer. */
    data object PreviewInApp : ReceivedAction

    /** Hand an APK to the system installer, after an explicit confirm. */
    data object Install : ReceivedAction

    /** A split bundle: say plainly that Android cannot install it directly. */
    data object SplitBundle : ReceivedAction

    /** Any other file: the system chooser. */
    data object OpenWith : ReceivedAction
}

/**
 * Decides the primary action for a received file.
 *
 * The rule that matters on a TV: **video is external-first**. Sendro's
 * `VideoView` is a fallback, not the destination — the TV's own player is the
 * thing that handles MKV, HEVC, DTS and embedded subtitles, and it is what the
 * remote's transport keys were designed for. Sendro plays a movie in-app only
 * when nothing else on the device will.
 */
fun primaryActionFor(
    context: Context,
    request: PreviewRequest,
    installer: ApkInstaller,
    isTv: Boolean,
): ReceivedAction {
    if (request.isGone) return ReceivedAction.PreviewInApp
    when (installer.kindOf(request.fileName)) {
        ApkInstaller.Kind.INSTALLABLE -> return ReceivedAction.Install
        ApkInstaller.Kind.SPLIT_BUNDLE -> return ReceivedAction.SplitBundle
        ApkInstaller.Kind.NOT_AN_APK -> Unit
    }
    return when (request.kind) {
        MediaKind.VIDEO ->
            if (isTv && canOpenExternally(context, request)) {
                ReceivedAction.PlayExternally
            } else {
                ReceivedAction.PreviewInApp
            }
        MediaKind.PHOTO -> ReceivedAction.PreviewInApp
        null -> if (canOpenExternally(context, request)) {
            ReceivedAction.OpenWith
        } else {
            ReceivedAction.PreviewInApp
        }
    }
}

/** True when at least one app on the device answers ACTION_VIEW for this file. */
fun canOpenExternally(context: Context, request: PreviewRequest): Boolean {
    val uri = playableUri(context, request) ?: return false
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, request.mimeType)
    return runCatching { intent.resolveActivity(context.packageManager) != null }
        .getOrDefault(false)
}

/**
 * Opens the file in someone else's app, through a chooser when several can
 * handle it. A chooser is the right default on a TV: several players are often
 * installed and the user has a favourite.
 */
fun openExternallyWithChooser(context: Context, request: PreviewRequest, title: String): Boolean {
    val uri = playableUri(context, request) ?: return false
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, request.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (runCatching { view.resolveActivity(context.packageManager) }.getOrNull() == null) {
        return false
    }
    val chooser = Intent.createChooser(view, title)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The grant rides on the inner intent; the chooser needs it too or the
        // chosen app gets a URI it may not read.
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return runCatching { context.startActivity(chooser) }.isSuccess
}

/**
 * The action row for a received file.
 *
 * Install is deliberately two-step. Everything else here is reversible; running
 * an installer is not, so the button asks once and names the package before it
 * hands anything to Android — and it says, right next to itself, that the bytes
 * were SHA-256 verified on arrival, because "is this the file they sent me" is
 * exactly the question a user should be asking at that moment.
 */
@Composable
fun ReceivedActionRow(
    app: SendroApplication,
    request: PreviewRequest,
    modifier: Modifier = Modifier,
    primaryFocus: FocusRequester? = null,
    onPreviewInApp: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val profile = LocalDeviceProfile.current
    var status by remember(request.fileName) { mutableStateOf<String?>(null) }
    var confirmingInstall by remember(request.fileName) { mutableStateOf(false) }

    val action = remember(request.fileName, request.uri, request.file) {
        primaryActionFor(context, request, app.apkInstaller, profile.isTv)
    }
    val apkInfo = remember(request.file) {
        if (action == ReceivedAction.Install) request.file?.let { app.apkInstaller.inspect(it) }
        else null
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {

        if (action == ReceivedAction.Install) {
            Column(
                modifier = Modifier.fillMaxWidth().glassRow().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // The verification badge belongs next to the install button,
                // not buried in the transfer detail: this is the moment the
                // provenance of the bytes actually matters.
                SectionTag("SHA-256 verified on arrival", Sendro.teal)
                if (apkInfo != null) {
                    Text(
                        text = apkInfo.packageName,
                        style = Sendro.mono(13f, FontWeight.SemiBold),
                        color = Sendro.textPrimary,
                    )
                    Text(
                        text = buildString {
                            append("Version ").append(apkInfo.versionName ?: "?")
                            append(" (").append(apkInfo.versionCode).append(')')
                            if (apkInfo.installed) {
                                append(" · installed: ")
                                append(apkInfo.installedVersionName ?: "?")
                            }
                        },
                        style = Sendro.mono(11.5f),
                        color = Sendro.textTertiary,
                    )
                    if (apkInfo.isDowngrade) {
                        Text(
                            text = "This is OLDER than the version already installed. " +
                                "Android will refuse a downgrade unless you uninstall first.",
                            style = Sendro.sans(12.5f),
                            color = Sendro.warn,
                        )
                    }
                } else {
                    Text(
                        text = "Sendro could not read this APK's manifest. Install it only " +
                            "if you know where it came from.",
                        style = Sendro.sans(12.5f),
                        color = Sendro.warn,
                    )
                }
            }
        }

        status?.let { message ->
            Text(text = message, style = Sendro.sans(12.5f), color = Sendro.warn)
        }

        Row(
            modifier = Modifier.fillMaxWidth().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (action) {
                ReceivedAction.PlayExternally -> {
                    AccentPill(
                        title = "Play now",
                        onClick = {
                            status = null
                            if (!openExternallyWithChooser(context, request, "Play ${request.fileName}")) {
                                status = "No player on this device would take it — " +
                                    "opening it in Sendro instead."
                                onPreviewInApp?.invoke()
                            }
                        },
                        height = if (profile.isTv) 52.dp else 48.dp,
                        focusRequester = primaryFocus,
                        modifier = Modifier.weight(1f),
                    )
                    if (onPreviewInApp != null) {
                        GhostPill(
                            title = "Play in Sendro",
                            onClick = onPreviewInApp,
                            height = if (profile.isTv) 52.dp else 48.dp,
                            modifier = Modifier.weight(0.7f),
                        )
                    }
                }

                ReceivedAction.Install -> {
                    AccentPill(
                        title = if (confirmingInstall) "Confirm install" else "Install",
                        color = if (confirmingInstall) Sendro.warn else Sendro.iris,
                        onClick = {
                            status = null
                            // Never one press. The first press arms it and
                            // renames the button; the second hands the file to
                            // Android, which then asks again itself.
                            if (!confirmingInstall) {
                                confirmingInstall = true
                            } else {
                                confirmingInstall = false
                                val file = request.file
                                when {
                                    file == null ->
                                        status = "The APK is not in Sendro's storage any more."
                                    !app.apkInstaller.canRequestInstall() -> {
                                        if (!app.apkInstaller.openPermissionSettings()) {
                                            status = "This device has no \"install unknown " +
                                                "apps\" screen. Enable it in Settings ▸ " +
                                                "Security first."
                                        }
                                    }
                                    !app.apkInstaller.install(file) ->
                                        status = "Nothing on this device can install an APK."
                                }
                            }
                        },
                        height = if (profile.isTv) 52.dp else 48.dp,
                        focusRequester = primaryFocus,
                        modifier = Modifier.weight(1f),
                    )
                    GhostPill(
                        title = if (confirmingInstall) "Cancel" else "Allow installs",
                        onClick = {
                            if (confirmingInstall) {
                                confirmingInstall = false
                            } else if (!app.apkInstaller.openPermissionSettings()) {
                                status = "Couldn't open the system setting."
                            }
                        },
                        height = if (profile.isTv) 52.dp else 48.dp,
                        modifier = Modifier.weight(0.7f),
                    )
                }

                ReceivedAction.SplitBundle -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "This is a split app bundle. Android cannot install one " +
                                "directly — it has to be unpacked by a bundle installer " +
                                "first. The file arrived intact and is in your Library.",
                            style = Sendro.sans(13f),
                            color = Sendro.textSecondary,
                        )
                        GhostPill(
                            title = "Open with…",
                            onClick = {
                                if (!openExternallyWithChooser(context, request, request.fileName)) {
                                    status = "No app on this device can open it."
                                }
                            },
                            focusRequester = primaryFocus,
                        )
                    }
                }

                ReceivedAction.OpenWith -> {
                    AccentPill(
                        title = "Open",
                        onClick = {
                            status = null
                            if (!openExternallyWithChooser(context, request, request.fileName)) {
                                status = "No app on this device can open it."
                            }
                        },
                        height = if (profile.isTv) 52.dp else 48.dp,
                        focusRequester = primaryFocus,
                        modifier = Modifier.weight(1f),
                    )
                }

                ReceivedAction.PreviewInApp -> {
                    if (onPreviewInApp != null) {
                        AccentPill(
                            title = if (request.kind == MediaKind.VIDEO) "Play" else "View",
                            onClick = onPreviewInApp,
                            height = if (profile.isTv) 52.dp else 48.dp,
                            focusRequester = primaryFocus,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (canOpenExternally(context, request)) {
                        GhostPill(
                            title = "Open with…",
                            onClick = {
                                if (!openExternallyWithChooser(context, request, request.fileName)) {
                                    status = "No app on this device can open it."
                                }
                            },
                            height = if (profile.isTv) 52.dp else 48.dp,
                            modifier = Modifier.weight(0.7f),
                        )
                    }
                }
            }
        }
    }
}
