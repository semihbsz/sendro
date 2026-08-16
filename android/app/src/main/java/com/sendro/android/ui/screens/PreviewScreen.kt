package com.sendro.android.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import com.sendro.android.SendroApplication
import com.sendro.android.core.Format
import com.sendro.android.core.HistoryEntry
import com.sendro.android.core.MediaKind
import com.sendro.android.core.MediaSaver
import com.sendro.android.ui.components.TopInsetSpacer
import com.sendro.android.ui.components.FileBadge
import com.sendro.android.ui.components.GhostPill
import com.sendro.android.ui.components.Pressable
import com.sendro.android.ui.components.RequestInitialFocus
import com.sendro.android.ui.theme.LocalDeviceProfile
import com.sendro.android.ui.theme.Sendro
import com.sendro.android.ui.theme.glassRow
import kotlinx.coroutines.delay
import java.io.File

/**
 * Everything the in-app preview needs, resolved once at tap time.
 *
 * A received file can live in three places, and history records which:
 *  - `mediaUri`  the MediaStore entry we published (the temp copy may be gone);
 *  - `localName` a file in the app's own received store;
 *  - neither     the bytes went to the gallery and the temp was deleted, or
 *                the user removed them — we say so instead of showing a
 *                broken frame.
 */
data class PreviewRequest(
    val fileName: String,
    val sizeBytes: Long,
    val uri: Uri?,
    val file: File?,
    val kind: MediaKind?,
    val mimeType: String,
    /**
     * Human-readable "where did this end up", shown when there is nothing to
     * preview. On a TV that is often the only answer the user gets, so it is
     * carried on the request rather than looked up in the view.
     */
    val savedLocation: String? = null,
) {
    val isGone: Boolean get() = uri == null && file == null

    companion object {
        fun of(app: SendroApplication, entry: HistoryEntry): PreviewRequest {
            val local = entry.localName?.let { File(app.paths.received, it) }?.takeIf { it.isFile }
            val uri = entry.mediaUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            return PreviewRequest(
                fileName = entry.fileName,
                sizeBytes = entry.sizeBytes,
                uri = uri,
                file = local,
                kind = MediaSaver.mediaKind(entry.fileName),
                mimeType = MediaSaver.mimeTypeFor(entry.fileName, null),
                savedLocation = when {
                    local != null -> "Kept inside Sendro · Library"
                    entry.savedTo != null -> "Saved to " + savedToLabel(entry)
                    else -> null
                },
            )
        }

        fun of(file: File): PreviewRequest = PreviewRequest(
            fileName = file.name,
            sizeBytes = file.length(),
            uri = null,
            file = file,
            kind = MediaSaver.mediaKind(file.name),
            mimeType = MediaSaver.mimeTypeFor(file.name, null),
        )
    }
}

/**
 * In-app preview.
 *
 * Images are zoomable (pinch + drag). Video uses `VideoView` rather than
 * ExoPlayer: Media3 is ~2 MB of dependency for a preview of a file the user
 * already has, and the platform player handles everything a phone camera or a
 * PC produces. Anything else opens in the user's own viewer through
 * FileProvider + ACTION_VIEW, with a clean fallback when nothing can open it.
 */
@Composable
fun PreviewScreen(
    app: com.sendro.android.SendroApplication,
    request: PreviewRequest,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val profile = LocalDeviceProfile.current
    val closeFocus = remember { FocusRequester() }
    val playerFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }

    val action = remember(request.fileName, request.uri, request.file, profile.isTv) {
        primaryActionFor(context, request, app.apkInstaller, profile.isTv)
    }
    // "Play it here" is a decision the user makes; until then a video that the
    // TV's own player should handle does not start in Sendro at all.
    var playInApp by remember(request.fileName) {
        mutableStateOf(action == ReceivedAction.PreviewInApp)
    }

    RequestInitialFocus(
        requester = when {
            !playInApp -> actionFocus
            profile.isTv && request.kind != null && !request.isGone -> playerFocus
            else -> closeFocus
        },
        key = request.fileName to playInApp,
    )

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
                        contentDescription = "Close preview",
                        tint = Sendro.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = request.fileName,
                    style = Sendro.sans(14.5f, FontWeight.SemiBold),
                    color = Sendro.textPrimary,
                    maxLines = 1,
                )
                Text(
                    text = Format.bytes(request.sizeBytes),
                    style = Sendro.mono(10.5f),
                    color = Sendro.textTertiary,
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                request.isGone -> GoneNotice(request)
                !playInApp -> ActionLanding(request)
                request.kind == MediaKind.PHOTO -> ZoomableImage(request, playerFocus)
                request.kind == MediaKind.VIDEO -> VideoPlayer(request, playerFocus)
                else -> NoPreview(request)
            }
        }

        if (!request.isGone) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = profile.horizontalPadding)
                    .padding(bottom = if (profile.isTv) profile.topPadding else 24.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ReceivedActionRow(
                    app = app,
                    request = request,
                    primaryFocus = actionFocus,
                    onPreviewInApp = { playInApp = true },
                )
                if (profile.hasTouchscreen) {
                    GhostPill(
                        title = "Share",
                        onClick = { shareExternally(context, request) },
                    )
                }
            }
        }
    }
}

/**
 * What the screen shows before the user has chosen how to open the file: the
 * name, a thumbnail where there is one, and nothing playing. This is the
 * external-first behaviour — Sendro does not start decoding a 4K HEVC MKV that
 * the TV's own player would handle better.
 */
@Composable
private fun ActionLanding(request: PreviewRequest) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        if (request.kind == MediaKind.PHOTO || request.kind == MediaKind.VIDEO) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(request.uri ?: request.file)
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(220.dp),
            )
        } else {
            FileBadge(request.fileName, side = 72.dp, cornerRadius = 20.dp)
        }
        Text(
            text = request.fileName,
            style = Sendro.sans(18f, FontWeight.SemiBold),
            color = Sendro.textPrimary,
            textAlign = TextAlign.Center,
        )
        request.savedLocation?.let { where ->
            Text(where, style = Sendro.mono(11.5f), color = Sendro.textTertiary)
        }
    }
}

@Composable
private fun ZoomableImage(request: PreviewRequest, focusRequester: FocusRequester) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    val profile = LocalDeviceProfile.current

    /** One D-pad step, in pixels, when the image is zoomed in. */
    val panStep = 160f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (profile.hasTouchscreen) {
                    Modifier.pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            // The remote: CENTER toggles 1x / 2.5x, the arrows pan while
            // zoomed in. When it is not zoomed the arrows are left alone so
            // focus can still leave the image.
            .focusRequester(focusRequester)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (scale > 1f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2.5f
                        }
                        true
                    }
                    Key.DirectionLeft -> if (scale > 1f) { offsetX += panStep; true } else false
                    Key.DirectionRight -> if (scale > 1f) { offsetX -= panStep; true } else false
                    Key.DirectionUp -> if (scale > 1f) { offsetY += panStep; true } else false
                    Key.DirectionDown -> if (scale > 1f) { offsetY -= panStep; true } else false
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(request.uri ?: request.file)
                .crossfade(true)
                .build(),
            contentDescription = request.fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
        )
        if (profile.isTv) {
            Text(
                text = if (scale > 1f) {
                    "OK to fit · arrows to pan"
                } else {
                    "OK to zoom"
                },
                style = Sendro.mono(11f),
                color = Sendro.textTertiary,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun VideoPlayer(request: PreviewRequest, focusRequester: FocusRequester) {
    val context = LocalContext.current
    val profile = LocalDeviceProfile.current
    val uri = remember(request) { playableUri(context, request) }
    if (uri == null) {
        NoPreview(request)
        return
    }

    var player by remember { mutableStateOf<VideoView?>(null) }
    var playing by remember { mutableStateOf(true) }
    var hudText by remember { mutableStateOf<String?>(null) }

    // Keep the HUD visible for a couple of seconds after a transport action.
    LaunchedEffect(hudText) {
        if (hudText != null) {
            delay(2_000)
            hudText = null
        }
    }

    DisposableEffect(Unit) {
        onDispose { runCatching { player?.stopPlayback() } }
    }

    /** ±10 s, clamped. Cheap seek: VideoView delegates to MediaPlayer. */
    fun seekBy(deltaMs: Int) {
        val view = player ?: return
        val duration = view.duration
        val target = (view.currentPosition + deltaMs)
            .coerceAtLeast(0)
            .let { if (duration > 0) minOf(it, duration - 250) else it }
        runCatching { view.seekTo(target) }
        hudText = "${formatClock(target)} / ${formatClock(maxOf(duration, 0))}"
    }

    fun togglePlay() {
        val view = player ?: return
        if (view.isPlaying) {
            runCatching { view.pause() }
            playing = false
            hudText = "Paused · ${formatClock(view.currentPosition)}"
        } else {
            runCatching { view.start() }
            playing = true
            hudText = "Playing"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
                    Key.MediaPlayPause,
                    -> {
                        togglePlay(); true
                    }
                    Key.MediaPlay -> {
                        if (player?.isPlaying != true) togglePlay(); true
                    }
                    Key.MediaPause -> {
                        if (player?.isPlaying == true) togglePlay(); true
                    }
                    Key.DirectionLeft, Key.MediaRewind -> { seekBy(-10_000); true }
                    Key.DirectionRight, Key.MediaFastForward -> { seekBy(10_000); true }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                VideoView(viewContext).apply {
                    setVideoURI(uri)
                    // A MediaController is a touch affordance; on a TV it is
                    // dead weight and it steals the D-pad. The remote drives
                    // the transport instead.
                    if (profile.hasTouchscreen) {
                        setMediaController(
                            MediaController(viewContext).also { it.setAnchorView(this) },
                        )
                    }
                    setOnPreparedListener { it.isLooping = false }
                    setOnCompletionListener { playing = false }
                    player = this
                    start()
                }
            },
            onRelease = { view ->
                runCatching { view.stopPlayback() }
                player = null
            },
        )

        if (profile.isTv) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                hudText?.let { text ->
                    Text(
                        text = text,
                        style = Sendro.mono(16f, FontWeight.SemiBold),
                        color = Sendro.textPrimary,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
                Text(
                    text = if (playing) "OK to pause · ◀ ▶ to seek 10s" else "OK to play",
                    style = Sendro.mono(12f),
                    color = Sendro.textTertiary,
                )
            }
        }
    }
}

/** mm:ss (or h:mm:ss) for the transport HUD. */
private fun formatClock(millis: Int): String {
    if (millis <= 0) return "0:00"
    val total = millis / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
    } else {
        String.format(java.util.Locale.US, "%d:%02d", m, sec)
    }
}

@Composable
private fun NoPreview(request: PreviewRequest) {
    val profile = LocalDeviceProfile.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        FileBadge(request.fileName, side = 72.dp, cornerRadius = 20.dp)
        Text(
            text = "No preview available",
            style = Sendro.sans(16f, FontWeight.SemiBold),
            color = Sendro.textPrimary,
        )
        Text(
            text = if (profile.isTv) {
                "Sendro can't show this file type, but it arrived intact and verified. " +
                    "It is listed in Library, and \"Open with…\" hands it to any app on " +
                    "this TV that understands it."
            } else {
                "The file is on this phone, untouched. Use \"Open with…\" to hand it to " +
                    "an app that understands it."
            },
            style = Sendro.sans(13f),
            color = Sendro.textSecondary,
            textAlign = TextAlign.Center,
        )
        request.savedLocation?.let { where ->
            Text(
                text = where,
                style = Sendro.mono(11.5f),
                color = Sendro.textTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun GoneNotice(request: PreviewRequest) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        Text(
            text = "Saved to your gallery",
            style = Sendro.sans(16f, FontWeight.SemiBold),
            color = Sendro.textPrimary,
        )
        Text(
            text = "Sendro's temporary copy was deleted after the save, so there is nothing " +
                "left here to show.",
            style = Sendro.sans(13f),
            color = Sendro.textSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = request.savedLocation ?: "Look for it in the Sendro album.",
            style = Sendro.mono(11.5f),
            color = Sendro.textTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// Sharing / opening
// ---------------------------------------------------------------------------

/**
 * A URI other apps (and VideoView) can actually read: the MediaStore entry
 * when we have one, otherwise a FileProvider grant for our own copy.
 */
internal fun playableUri(context: Context, request: PreviewRequest): Uri? {
    request.uri?.let { return it }
    val file = request.file ?: return null
    return runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}

internal fun openExternally(context: Context, request: PreviewRequest): Boolean {
    val uri = playableUri(context, request) ?: return false
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, request.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent) }.isSuccess
}

internal fun shareExternally(context: Context, request: PreviewRequest): Boolean {
    val uri = playableUri(context, request) ?: return false
    val intent = Intent(Intent.ACTION_SEND)
        .setType(request.mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(intent, "Share ${request.fileName}")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(chooser) }.isSuccess
}

// ---------------------------------------------------------------------------
// Row thumbnails
// ---------------------------------------------------------------------------

/**
 * A real thumbnail for a received file, downsampled by Coil to the tile size —
 * never the full image. Falls back to the hatched extension badge.
 *
 * `coil-video` gives the video frame decoder, so an .mp4 row shows a frame
 * rather than a grey square.
 */
@Composable
fun HistoryThumbnail(
    entry: HistoryEntry,
    side: Dp,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val model = remember(entry.id) {
        val local = entry.localName?.let { File(context.filesDir, "received/$it") }
            ?.takeIf { it.isFile }
        local ?: entry.mediaUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }
    val kind = remember(entry.fileName) { MediaSaver.mediaKind(entry.fileName) }

    if (model == null || kind == null) {
        FileBadge(entry.fileName, modifier, side, cornerRadius)
        return
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val pixels = remember(side) { with(density) { side.roundToPx() } * 2 }

    Box(
        modifier = modifier
            .size(side)
            .clip(RoundedCornerShape(cornerRadius))
            .background(Color.White.copy(alpha = 0.05f)),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(model)
                .size(pixels, pixels)
                .scale(Scale.FILL)
                .crossfade(false)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
