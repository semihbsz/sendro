package com.sendro.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sendro.android.SendroApplication
import com.sendro.android.core.Note
import com.sendro.android.ui.components.GhostPill
import com.sendro.android.ui.components.SectionTag
import com.sendro.android.ui.components.TopInsetSpacer
import com.sendro.android.ui.components.Pressable
import com.sendro.android.ui.components.screenPadding
import com.sendro.android.ui.theme.LocalDeviceProfile
import com.sendro.android.ui.theme.Sendro
import com.sendro.android.ui.theme.glassRow
import kotlinx.coroutines.delay

/**
 * The 24-hour notes shelf (§11.3). Every text sent or received lands here and
 * deletes itself a day later.
 *
 * Reading surface only: composing still happens on the Send tab, so there is
 * exactly one place in the app that puts text on the wire.
 */
@Composable
fun NotesScreen(app: SendroApplication) {
    val notes by app.notes.notes.collectAsStateWithLifecycle()
    val profile = LocalDeviceProfile.current
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }

    // Expiry is a wall-clock property, so the shelf has to be swept while it
    // is open, not only when the app comes back to the foreground.
    LaunchedEffect(Unit) {
        while (true) {
            app.notes.prune()
            delay(60_000)
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
                "Notes",
                style = Sendro.sans(26f, FontWeight.SemiBold),
                color = Sendro.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            if (notes.isNotEmpty()) {
                Pressable(onClick = {
                    if (confirmClear) {
                        app.notes.clearAll()
                        confirmClear = false
                    } else {
                        confirmClear = true
                    }
                }) {
                    Text(
                        text = if (confirmClear) "Clear all notes?" else "Clear",
                        style = Sendro.sans(12.5f, FontWeight.Medium),
                        color = if (confirmClear) Sendro.danger else Sendro.textTertiary,
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = screenPadding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (notes.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTag("Nothing on the shelf")
                        Text(
                            text = "Text you send from the Send tab, and text your computer " +
                                "sends here, stays on this device for 24 hours and then " +
                                "deletes itself.",
                            style = Sendro.sans(13f),
                            color = Sendro.textTertiary,
                        )
                    }
                }
            } else {
                items(notes, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        onCopy = { copyToClipboard(context, note.text) },
                        onDelete = { app.notes.remove(note.id) },
                    )
                }
                item {
                    Text(
                        text = "Notes live on this device only. Your computer keeps nothing — " +
                            "it forgets each message the moment it is delivered.",
                        style = Sendro.sans(11.5f),
                        color = Sendro.textBase.copy(alpha = 0.32f),
                        modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteCard(
    note: Note,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_200)
            copied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassRow(cornerRadius = 18.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (note.isIncoming) "from ${note.peerName}" else "to ${note.peerName}",
                style = Sendro.mono(10.5f),
                color = if (note.isIncoming) Sendro.iris else Sendro.teal,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${note.hoursLeft()} h left",
                style = Sendro.mono(10f),
                color = Sendro.textBase.copy(alpha = 0.3f),
                maxLines = 1,
            )
        }

        Text(
            text = note.text,
            style = Sendro.sans(14f),
            color = Sendro.textBase.copy(alpha = 0.93f),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostPill(
                title = if (copied) "Copied" else "Copy",
                onClick = {
                    onCopy()
                    copied = true
                },
                modifier = Modifier.width(104.dp),
                height = 34.dp,
            )
            GhostPill(
                title = "Delete",
                onClick = onDelete,
                modifier = Modifier.width(104.dp),
                textColor = Sendro.danger,
                height = 34.dp,
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    // Label, not content: some launchers surface the label in a toast.
    clipboard?.setPrimaryClip(ClipData.newPlainText("Sendro note", text))
}
