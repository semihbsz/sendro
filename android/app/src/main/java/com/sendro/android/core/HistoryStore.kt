package com.sendro.android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

/**
 * One finished transfer, in either direction.
 *
 * NOTE (§11): text messages are NEVER written here. This file is the reason
 * that rule needs restating — the temptation to "just log the message" ends
 * with plaintext on disk.
 */
@Serializable
data class HistoryEntry(
    val id: String,
    val transferId: String,
    val fileName: String,
    val sizeBytes: Long,
    val senderName: String,
    val dateMs: Long,
    /** completed | failed | rejected | cancelled */
    val outcome: String,
    /** photos | files | temp */
    val savedTo: String? = null,
    val errorMessage: String? = null,
    /** null (incoming) | "outgoing" (phone -> PC upload, §7) */
    val direction: String? = null,
    /**
     * The file name inside `files/received` when the bytes are still here —
     * NOT an absolute path, because the app's data dir changes on reinstall.
     */
    val localName: String? = null,
    /**
     * MediaStore content URI of the published asset, when it went to the
     * gallery. Lets the Library preview pull the image back out of MediaStore
     * after the local temp copy was deleted.
     */
    val mediaUri: String? = null,
)

/** Local transfer history, newest first, capped at 500 entries. */
class HistoryStore(private val file: File) {

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    @Synchronized
    fun add(
        transferId: String,
        fileName: String,
        sizeBytes: Long,
        senderName: String,
        outcome: String,
        savedTo: String? = null,
        errorMessage: String? = null,
        direction: String? = null,
        localName: String? = null,
        mediaUri: String? = null,
    ) {
        val entry = HistoryEntry(
            id = UUID.randomUUID().toString(),
            transferId = transferId,
            fileName = fileName,
            sizeBytes = sizeBytes,
            senderName = senderName,
            dateMs = System.currentTimeMillis(),
            outcome = outcome,
            savedTo = savedTo,
            errorMessage = errorMessage,
            direction = direction,
            localName = localName,
            mediaUri = mediaUri,
        )
        val next = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        _entries.value = next
        save(next)
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        save(emptyList())
    }

    @Synchronized
    fun removeById(id: String) {
        val next = _entries.value.filterNot { it.id == id }
        _entries.value = next
        save(next)
    }

    private fun load(): List<HistoryEntry> {
        if (!file.isFile) return emptyList()
        return runCatching { SendroJson.decodeFromString<List<HistoryEntry>>(file.readText()) }
            .getOrDefault(emptyList())
    }

    private fun save(list: List<HistoryEntry>) {
        runCatching {
            file.parentFile?.mkdirs()
            // Atomic-ish: write beside, then rename, so a kill mid-write never
            // leaves a truncated history file.
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(SendroJson.encodeToString(list))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    companion object {
        const val MAX_ENTRIES = 500
    }
}
