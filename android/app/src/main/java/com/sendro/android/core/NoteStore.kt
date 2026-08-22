package com.sendro.android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID
import kotlin.math.ceil

/**
 * The 24-hour notes shelf (PROTOCOL.md §11.3).
 *
 * A §11 text message is ephemeral ON THE WIRE and on the host: the PC keeps it
 * in RAM until it is delivered or dismissed, and never writes it down. That is
 * still true, and [MessageCenter]'s in-RAM contract is untouched.
 *
 * What this adds is a LOCAL convenience on this device: a copy of the text you
 * sent or received, kept here only, for 24 hours, so a Wi-Fi password can be
 * read again after the card is gone. The rules are deliberately narrow:
 *
 *  - Local only. Never uploaded, never synced, never in [HistoryStore].
 *  - Time-boxed. Every note carries its own expiry and is deleted on load, on
 *    every write and on every sweep. There is no way to extend one.
 *  - Deletable. One tap removes a note; "Clear" empties the shelf.
 *  - App-private. `files/state/notes.json` inside the app sandbox, which is
 *    encrypted at rest on any device with a screen lock.
 */
@Serializable
data class Note(
    val id: String,
    val text: String,
    /** The other end of the exchange — the computer's name. */
    val peerName: String,
    /** "incoming" | "outgoing" */
    val direction: String,
    val createdAtMs: Long,
    /**
     * Hard deletion time. Stored rather than derived, so changing the TTL can
     * never silently extend notes that already exist.
     */
    val expiresAtMs: Long,
) {
    val isIncoming: Boolean get() = direction == DIRECTION_IN

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs

    /** Whole hours left (rounded up, minimum 1) — the "23 h left" caption. */
    fun hoursLeft(nowMs: Long = System.currentTimeMillis()): Int {
        val remaining = expiresAtMs - nowMs
        if (remaining <= 0) return 0
        return maxOf(1, ceil(remaining / 3_600_000.0).toInt())
    }

    companion object {
        const val DIRECTION_IN = "incoming"
        const val DIRECTION_OUT = "outgoing"
    }
}

class NoteStore(private val file: File) {

    private val _notes = MutableStateFlow(load())

    /** Newest first — the order the shelf is read in. */
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    /**
     * Record text that arrived from a computer. Deduped by message id, so a
     * resend of the same §11 message never stacks twice.
     */
    @Synchronized
    fun addIncoming(messages: List<SendroMessage>) {
        if (messages.isEmpty()) return
        val now = System.currentTimeMillis()
        val known = _notes.value.mapTo(HashSet()) { it.id }
        val fresh = messages.mapNotNull { message ->
            val id = "in:${message.messageId}"
            if (!known.add(id)) return@mapNotNull null
            Note(
                id = id,
                text = message.text,
                peerName = message.senderName,
                direction = Note.DIRECTION_IN,
                // A host clock that is wrong must not produce a note that is
                // already expired: the shelf clock is ours.
                createdAtMs = minOf(message.sentAtMs, now),
                expiresAtMs = now + TTL_MS,
            )
        }
        if (fresh.isEmpty()) return
        commit(fresh + _notes.value)
    }

    /** Record text this device sent. Called only after the host accepted it. */
    @Synchronized
    fun addOutgoing(text: String, peerName: String) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        val note = Note(
            id = "out:${UUID.randomUUID()}",
            text = text,
            peerName = peerName,
            direction = Note.DIRECTION_OUT,
            createdAtMs = now,
            expiresAtMs = now + TTL_MS,
        )
        commit(listOf(note) + _notes.value)
    }

    @Synchronized
    fun remove(id: String) = commit(_notes.value.filterNot { it.id == id })

    @Synchronized
    fun clearAll() = commit(emptyList())

    /** Drop everything past its expiry. Safe to call at any time. */
    @Synchronized
    fun prune() {
        val before = _notes.value
        val after = before.filterNot { it.isExpired() }
        if (after.size != before.size) commit(after)
    }

    private fun commit(list: List<Note>) {
        val next = list
            .filterNot { it.isExpired() }
            .sortedByDescending { it.createdAtMs }
            .take(CAPACITY)
        _notes.value = next
        save(next)
    }

    private fun load(): List<Note> {
        if (!file.isFile) return emptyList()
        val decoded = runCatching { SendroJson.decodeFromString<List<Note>>(file.readText()) }
            // A corrupt shelf is not worth a recovery path: it is a 24-hour
            // cache. Start clean and overwrite it on the next write.
            .getOrDefault(emptyList())
        return decoded.filterNot { it.isExpired() }.sortedByDescending { it.createdAtMs }
    }

    private fun save(list: List<Note>) {
        runCatching {
            if (list.isEmpty()) {
                file.delete()
                return@runCatching
            }
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(SendroJson.encodeToString(list))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    companion object {
        /** How long a note survives. Fixed; not a setting. */
        const val TTL_MS = 24 * 60 * 60 * 1000L

        /** Upper bound on the shelf, oldest dropped first. */
        const val CAPACITY = 200
    }
}
