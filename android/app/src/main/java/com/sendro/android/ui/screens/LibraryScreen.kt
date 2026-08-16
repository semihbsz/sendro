package com.sendro.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.sendro.android.core.Format
import com.sendro.android.core.HistoryEntry
import com.sendro.android.core.MediaSaver
import com.sendro.android.ui.components.TopInsetSpacer
import com.sendro.android.ui.components.GhostPill
import com.sendro.android.ui.components.Pressable
import com.sendro.android.ui.components.RequestInitialFocus
import com.sendro.android.ui.components.SectionTag
import com.sendro.android.ui.components.screenPadding
import com.sendro.android.ui.theme.LocalDeviceProfile
import com.sendro.android.ui.theme.Sendro

/**
 * Library: All / Media / Files over the transfer history, with real
 * thumbnails and an in-app preview on tap.
 *
 * History is the source of truth rather than a directory listing, because
 * most received media does not stay in the app sandbox at all — it is
 * published to the gallery and the temp copy is deleted. A directory scan
 * would show an empty Library on a phone full of received photos.
 */
private enum class LibraryFilter(val label: String) {
    ALL("All"),
    MEDIA("Media"),
    FILES("Files"),
}

@Composable
fun LibraryScreen(
    app: SendroApplication,
    onPreview: (PreviewRequest) -> Unit,
) {
    val history by app.history.entries.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var confirmClear by remember { mutableStateOf(false) }
    val profile = LocalDeviceProfile.current
    val firstFilter = remember { FocusRequester() }
    RequestInitialFocus(firstFilter)

    val visible = remember(history, filter) {
        history.filter { entry ->
            when (filter) {
                LibraryFilter.ALL -> true
                LibraryFilter.MEDIA -> MediaSaver.mediaKind(entry.fileName) != null
                LibraryFilter.FILES -> MediaSaver.mediaKind(entry.fileName) == null
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        TopInsetSpacer()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = profile.horizontalPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Library",
                style = Sendro.sans(26f, FontWeight.SemiBold),
                color = Sendro.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            if (history.isNotEmpty()) {
                Pressable(onClick = {
                    if (confirmClear) {
                        app.history.clear()
                        confirmClear = false
                    } else {
                        confirmClear = true
                    }
                }) {
                    Text(
                        text = if (confirmClear) "Clear history?" else "Clear",
                        style = Sendro.sans(12.5f, FontWeight.Medium),
                        color = if (confirmClear) Sendro.danger else Sendro.textTertiary,
                    )
                }
            }
        }

        FilterBar(
            selected = filter,
            onSelect = { filter = it },
            firstFocus = firstFilter,
            modifier = Modifier.padding(horizontal = profile.horizontalPadding),
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = screenPadding(top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (visible.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTag(
                            when (filter) {
                                LibraryFilter.ALL -> "Nothing yet"
                                LibraryFilter.MEDIA -> "No photos or videos yet"
                                LibraryFilter.FILES -> "No files yet"
                            },
                        )
                        Text(
                            text = "Everything that arrives — and everything you send — is " +
                                "listed here with its outcome. Received media goes to your " +
                                "gallery's Sendro album; other files land in Download/Sendro.",
                            style = Sendro.sans(13f),
                            color = Sendro.textTertiary,
                        )
                    }
                }
            } else {
                items(visible, key = { it.id }) { entry ->
                    LibraryRow(
                        entry = entry,
                        onClick = { onPreview(PreviewRequest.of(app, entry)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterBar(
    selected: LibraryFilter,
    onSelect: (LibraryFilter) -> Unit,
    firstFocus: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusGroup()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LibraryFilter.entries.forEachIndexed { index, option ->
            val active = option == selected
            Pressable(
                onClick = { onSelect(option) },
                modifier = Modifier.weight(1f),
                focusRequester = if (index == 0) firstFocus else null,
                focusCorner = 12.dp,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (active) Color.White.copy(alpha = 0.10f) else Color.Transparent,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option.label,
                        style = Sendro.sans(13f, FontWeight.Medium),
                        color = if (active) Sendro.textPrimary else Sendro.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(entry: HistoryEntry, onClick: () -> Unit) {
    Column {
        HistoryRow(entry = entry, onClick = onClick)
        entry.errorMessage?.let { error ->
            Text(
                text = error,
                style = Sendro.mono(10f),
                color = Sendro.danger,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
            )
        }
    }
}

/** Where the bytes went, in one line, for the detail row. */
internal fun savedToLabel(entry: HistoryEntry): String = when (entry.savedTo) {
    "photos" -> "Gallery · Sendro album"
    "files" -> "Download/Sendro"
    "temp" -> "Temporary"
    else -> Format.bytes(entry.sizeBytes)
}

/** Kept so a future "open the containing folder" action has one home. */
internal fun canOpen(entry: HistoryEntry): Boolean =
    entry.localName != null || entry.mediaUri != null

@Composable
internal fun LibraryFooterActions(onClear: () -> Unit) {
    GhostPill(title = "Clear history", onClick = onClear, height = 42.dp)
}
