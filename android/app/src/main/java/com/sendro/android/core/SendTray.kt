package com.sendro.android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID

/**
 * Files handed to Sendro by Android itself — "Share → Sendro"
 * (ACTION_SEND / ACTION_SEND_MULTIPLE) — and by the in-app pickers.
 *
 * They are staged (real bytes copied out of the provider URI, which may be
 * revoked the moment the sharing app is killed) and then wait here.
 *
 * Files are NEVER auto-sent: they sit in this tray until the user taps Send.
 * That is a deliberate product rule, not an oversight — a share sheet tap must
 * not silently push a file onto someone's PC.
 */
class SendTray {

    data class Item(
        val id: String,
        val file: File,
        val name: String,
        val sizeBytes: Long,
    )

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    val totalBytes: Long get() = _items.value.sumOf { it.sizeBytes }

    fun add(files: List<File>) {
        if (files.isEmpty()) return
        _items.update { list ->
            list + files.map {
                Item(
                    id = UUID.randomUUID().toString(),
                    file = it,
                    name = it.name,
                    sizeBytes = it.length(),
                )
            }
        }
    }

    /**
     * Hand every staged file to the caller (UploadEngine) and empty the tray.
     * Ownership transfers with them — UploadEngine deletes each staged file
     * when its item finishes.
     */
    fun takeAll(): List<File> {
        val files = _items.value.map { it.file }
        _items.value = emptyList()
        return files
    }

    fun remove(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        _items.update { list -> list.filterNot { it.id == id } }
        runCatching { item.file.delete() }
    }

    fun clear() {
        val files = _items.value.map { it.file }
        _items.value = emptyList()
        files.forEach { runCatching { it.delete() } }
    }
}
