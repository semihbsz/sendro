package com.sendro.android.core

import android.content.Context
import java.io.File

/**
 * Every directory Sendro writes to, in one place. All of it is app-scoped
 * (internal storage or the app cache) — the only bytes that leave the sandbox
 * are the ones MediaSaver deliberately publishes to MediaStore.
 */
class AppPaths(private val context: Context) {

    /** `files/incoming` — `<transferId>.part` while downloading. */
    val incoming: File get() = dir(File(context.filesDir, "incoming"))

    /** `files/received` — verified files kept on the device (the Files store). */
    val received: File get() = dir(File(context.filesDir, "received"))

    /** `cache/outgoing` — staged copies of picked/shared files, per batch. */
    val outgoing: File get() = dir(File(context.cacheDir, "outgoing"))

    /** `files/updates` — the downloaded APK handed to the installer. */
    val updates: File get() = dir(File(context.filesDir, "updates"))

    /** `files/state` — small JSON state (in-flight records, history). */
    val state: File get() = dir(File(context.filesDir, "state"))

    fun partFile(transferId: String): File = File(incoming, "$transferId.part")

    /**
     * Where a verified file waits between "verified" and "saved": under its
     * real (sanitised) name so MediaStore/DocumentFile sees a sane extension,
     * prefixed with the transferId so two transfers of the same name cannot
     * collide.
     */
    fun stagedFile(transferId: String, fileName: String): File =
        File(incoming, "$transferId-${FileNames.sanitize(fileName)}")

    val inFlightStateFile: File get() = File(state, "inflight.json")

    /** §11.3 — the 24-hour notes shelf. App-private; expires on its own. */
    val notesFile: File get() = File(state, "notes.json")
    val historyFile: File get() = File(state, "history.json")

    /** A fresh subdirectory for one picker/share batch. */
    fun newOutgoingBatch(): File = dir(File(outgoing, java.util.UUID.randomUUID().toString()))

    private fun dir(file: File): File {
        if (!file.isDirectory) file.mkdirs()
        return file
    }
}

/** PROTOCOL.md §8 — filenames & Unicode. */
object FileNames {

    /**
     * Strip path separators and characters no Android/exFAT/FAT32 volume will
     * take, preserve everything else — case, spaces, full Unicode. A received
     * `Çekmeköy Reşadiye Drone.MOV` must land as exactly that.
     */
    fun sanitize(fileName: String): String {
        var s = fileName
            .replace('/', '_')
            .replace('\\', '_')
            .replace(':', '_')
        // Reserved on the FAT/exFAT volumes shared storage often is.
        for (c in charArrayOf('<', '>', '"', '|', '?', '*')) s = s.replace(c, '_')
        // NUL and other control characters never belong in a path component.
        s = s.filter { it.code >= 0x20 }
        s = s.trim()
        // Trailing dots/spaces are silently dropped by some filesystems, which
        // turns "report." into "report" and breaks a later name comparison.
        s = s.trimEnd('.', ' ')
        if (s.isEmpty() || s == "." || s == "..") s = "file"
        // Long Unicode names can exceed the 255-BYTE limit on ext4.
        return truncateToBytes(s, 200)
    }

    /** §8 — duplicates get " (n)" before the extension. */
    fun availableFile(directory: File, name: String): File {
        val first = File(directory, name)
        if (!first.exists()) return first
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var n = 2
        while (true) {
            val candidate = if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"
            val file = File(directory, candidate)
            if (!file.exists()) return file
            n++
        }
    }

    /** The " (n)" variant of a *display* name, for MediaStore collisions. */
    fun withSuffix(name: String, n: Int): String {
        if (n <= 1) return name
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        return if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"
    }

    fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()

    /** Truncate without splitting a UTF-8 sequence or losing the extension. */
    private fun truncateToBytes(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val ext = value.substringAfterLast('.', "")
        val suffix = if (ext.isEmpty() || ext.length > 12) "" else ".$ext"
        val budget = maxBytes - suffix.toByteArray(Charsets.UTF_8).size
        val base = value.substringBeforeLast('.', value)
        val builder = StringBuilder()
        var used = 0
        for (ch in base) {
            val size = ch.toString().toByteArray(Charsets.UTF_8).size
            if (used + size > budget) break
            builder.append(ch)
            used += size
        }
        val head = builder.toString().trimEnd('.', ' ').ifEmpty { "file" }
        return head + suffix
    }
}
