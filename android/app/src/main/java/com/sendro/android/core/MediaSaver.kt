package com.sendro.android.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

enum class MediaKind { PHOTO, VIDEO }

/** Where the verified bytes ended up — mirrors §6.5 `savedTo`. */
sealed interface SaveResult {
    data class Gallery(val uri: Uri, val displayName: String) : SaveResult
    data class Files(val file: File) : SaveResult
    data class Failed(val message: String) : SaveResult

    /** The user has not granted legacy storage on API ≤ 28. */
    data object NeedsStoragePermission : SaveResult
}

/**
 * Publishes verified originals to the device's gallery (the PhotoKit import
 * analogue) or to the app's own received-files store.
 *
 * BYTE FIDELITY — the entire point:
 *  - the bytes are copied with a plain 1 MiB stream copy, never decoded into a
 *    Bitmap and re-encoded, never resized, never re-oriented, never stripped
 *    of EXIF/XMP/ICC. A `BitmapFactory` call anywhere in this file would be a
 *    bug, not an optimisation.
 *  - `IS_PENDING` keeps the entry invisible to gallery apps until the last
 *    byte is written, so nothing ever indexes a half-file.
 *
 * Routing (matches iOS):
 *  - photos  -> `Pictures/Sendro`   (MediaStore.Images)
 *  - videos  -> `Movies/Sendro`     (MediaStore.Video)
 *  - other   -> `Download/Sendro`   (MediaStore.Downloads, API 29+)
 *               or the app's `files/received` below 29.
 */
class MediaSaver(
    private val context: Context,
    private val paths: AppPaths,
) {

    companion object {
        const val ALBUM = "Sendro"
        private const val TAG = "SendroMediaSaver"
        private const val PART_SUFFIX = ".sendropart"

        private val PHOTO_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "heic", "heif", "tif", "tiff", "dng", "gif",
            "webp", "bmp", "avif", "raw", "cr2", "cr3", "nef", "arw", "orf", "rw2",
        )
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mov", "m4v", "3gp", "3gpp", "mkv", "webm", "avi", "mts", "m2ts",
        )

        /**
         * null = not gallery media (goes to Downloads/Files instead).
         *
         * Extension-first on purpose: `mimeType` in the offer is best-effort
         * from the sender's extension table (§6.1) and must never be the only
         * thing deciding where a user's file lands.
         */
        fun mediaKind(fileName: String, mimeType: String? = null): MediaKind? {
            val ext = FileNames.extensionOf(fileName)
            if (ext in PHOTO_EXTENSIONS) return MediaKind.PHOTO
            if (ext in VIDEO_EXTENSIONS) return MediaKind.VIDEO
            val type = mimeType?.lowercase()
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            return when {
                type == null -> null
                type.startsWith("image/") -> MediaKind.PHOTO
                type.startsWith("video/") -> MediaKind.VIDEO
                else -> null
            }
        }

        fun mimeTypeFor(fileName: String, declared: String?): String {
            val fromExtension = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(FileNames.extensionOf(fileName))
            // Prefer what this device believes; fall back to the host's guess.
            return fromExtension
                ?: declared?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: "application/octet-stream"
        }

        /** True when the app needs WRITE_EXTERNAL_STORAGE to publish media. */
        val needsLegacyStoragePermission: Boolean
            get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P

        /**
         * Payloads that must stay in Sendro's own store rather than going to
         * MediaStore.
         *
         * An APK is the case that matters: `PackageManager.getPackageArchiveInfo`
         * and the package installer both want a real filesystem path, and a
         * `content://media/...Downloads` row gives neither. Keeping installable
         * payloads in `files/received` means the Install action can read the
         * manifest, hand the installer a FileProvider URI, and show the user
         * what they are about to install.
         */
        fun mustStayInAppStore(fileName: String): Boolean =
            FileNames.extensionOf(fileName) in APP_STORE_ONLY_EXTENSIONS

        private val APP_STORE_ONLY_EXTENSIONS = setOf("apk", "apks", "xapk", "apkm", "obb")
    }

    /**
     * Publish [source] into the gallery under [displayName].
     *
     * @param moveFile when true the source is deleted after a successful copy
     *   ("Delete temp after save"); when false it is left in place.
     */
    suspend fun saveToGallery(
        source: File,
        displayName: String,
        kind: MediaKind,
        declaredMimeType: String?,
        useAlbum: Boolean,
        moveFile: Boolean,
    ): SaveResult = withContext(Dispatchers.IO) {
        if (!source.isFile) return@withContext SaveResult.Failed("Temp file went missing.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(source, displayName, kind, declaredMimeType, useAlbum, moveFile)
        } else {
            saveLegacy(source, displayName, kind, declaredMimeType, useAlbum, moveFile)
        }
    }

    /**
     * Non-media: `Download/Sendro` on API 29+, the app's own store below that.
     * Returning a [SaveResult.Files] when MediaStore refuses is deliberate —
     * verified bytes are never thrown away because a save target said no.
     */
    suspend fun saveToDownloads(
        source: File,
        displayName: String,
        declaredMimeType: String?,
        useAlbum: Boolean,
        moveFile: Boolean,
    ): SaveResult = withContext(Dispatchers.IO) {
        if (!source.isFile) return@withContext SaveResult.Failed("Temp file went missing.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val result = insertIntoDownloads(source, displayName, declaredMimeType, useAlbum, moveFile)
            if (result is SaveResult.Failed) {
                Log.w(TAG, "Downloads insert failed (${result.message}); keeping bytes in app storage")
                saveToAppStore(source, displayName, moveFile)
            } else {
                result
            }
        } else {
            saveToAppStore(source, displayName, moveFile)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertIntoDownloads(
        source: File,
        displayName: String,
        declaredMimeType: String?,
        useAlbum: Boolean,
        moveFile: Boolean,
    ): SaveResult = insertAndCopy(
        collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        source = source,
        displayName = displayName,
        mimeType = mimeTypeFor(displayName, declaredMimeType),
        relativePath = if (useAlbum) Environment.DIRECTORY_DOWNLOADS + "/" + ALBUM
        else Environment.DIRECTORY_DOWNLOADS,
        moveFile = moveFile,
    )

    /** The Files-app-visible-ish store inside the app sandbox. */
    fun saveToAppStore(source: File, displayName: String, moveFile: Boolean): SaveResult = try {
        val destination = FileNames.availableFile(paths.received, FileNames.sanitize(displayName))
        if (moveFile && source.renameTo(destination)) {
            SaveResult.Files(destination)
        } else {
            source.copyStreamTo(destination)
            if (moveFile) source.delete()
            SaveResult.Files(destination)
        }
    } catch (e: Exception) {
        Log.w(TAG, "app-store save failed", e)
        SaveResult.Failed(e.sendroMessage())
    }

    // -----------------------------------------------------------------------
    // API 29+ : MediaStore with IS_PENDING
    // -----------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(
        source: File,
        displayName: String,
        kind: MediaKind,
        declaredMimeType: String?,
        useAlbum: Boolean,
        moveFile: Boolean,
    ): SaveResult {
        val base = when (kind) {
            MediaKind.PHOTO -> Environment.DIRECTORY_PICTURES
            MediaKind.VIDEO -> Environment.DIRECTORY_MOVIES
        }
        val collection = when (kind) {
            MediaKind.PHOTO ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            MediaKind.VIDEO ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        return insertAndCopy(
            collection = collection,
            source = source,
            displayName = displayName,
            mimeType = mimeTypeFor(displayName, declaredMimeType),
            relativePath = if (useAlbum) "$base/$ALBUM" else base,
            moveFile = moveFile,
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertAndCopy(
        collection: Uri,
        source: File,
        displayName: String,
        mimeType: String,
        relativePath: String,
        moveFile: Boolean,
    ): SaveResult {
        val resolver = context.contentResolver
        val safeName = FileNames.sanitize(displayName)

        // MediaStore silently renames a collision to "name (1).ext" on some
        // OEM builds and errors on others; picking a free name first means the
        // history entry and the gallery agree on what the file is called.
        var attempt = 1
        var uri: Uri? = null
        var chosenName = safeName
        while (attempt <= 50) {
            chosenName = FileNames.withSuffix(safeName, attempt)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, chosenName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                put(MediaStore.MediaColumns.SIZE, source.length())
                put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            }
            uri = runCatching { resolver.insert(collection, values) }.getOrNull()
            if (uri != null) break
            attempt++
        }
        val target = uri ?: return SaveResult.Failed("The gallery refused the file.")

        try {
            resolver.openOutputStream(target, "w").use { output ->
                if (output == null) throw java.io.IOException("Could not open the gallery entry.")
                source.inputStream().use { input ->
                    val buffer = ByteArray(SendroCrypto.CHUNK_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(target, done, null, null)
            }
            if (moveFile) source.delete()
            return SaveResult.Gallery(target, chosenName)
        } catch (e: Exception) {
            Log.w(TAG, "gallery copy failed", e)
            // Never leave a pending, half-written row behind.
            runCatching { resolver.delete(target, null, null) }
            return SaveResult.Failed(e.sendroMessage())
        }
    }

    // -----------------------------------------------------------------------
    // API 26..28 : legacy external storage
    // -----------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        source: File,
        displayName: String,
        kind: MediaKind,
        declaredMimeType: String?,
        useAlbum: Boolean,
        moveFile: Boolean,
    ): SaveResult {
        if (!hasLegacyStoragePermission()) return SaveResult.NeedsStoragePermission

        val base = Environment.getExternalStoragePublicDirectory(
            when (kind) {
                MediaKind.PHOTO -> Environment.DIRECTORY_PICTURES
                MediaKind.VIDEO -> Environment.DIRECTORY_MOVIES
            },
        )
        val directory = if (useAlbum) File(base, ALBUM) else base
        if (!directory.isDirectory && !directory.mkdirs()) {
            return SaveResult.Failed("Could not create ${directory.absolutePath}.")
        }

        return try {
            val destination =
                FileNames.availableFile(directory, FileNames.sanitize(displayName))
            source.copyStreamTo(destination)
            if (moveFile) source.delete()

            // Index it so the gallery sees it without a reboot. On these API
            // levels this is the only way in.
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, destination.absolutePath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, destination.name)
                put(
                    MediaStore.MediaColumns.MIME_TYPE,
                    mimeTypeFor(destination.name, declaredMimeType),
                )
                put(MediaStore.MediaColumns.SIZE, destination.length())
            }
            val collection = when (kind) {
                MediaKind.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val uri = runCatching { context.contentResolver.insert(collection, values) }.getOrNull()
            // MediaScanner as a belt-and-braces second path.
            runCatching {
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(destination.absolutePath), null, null,
                )
            }
            SaveResult.Gallery(uri ?: Uri.fromFile(destination), destination.name)
        } catch (e: Exception) {
            Log.w(TAG, "legacy gallery save failed", e)
            SaveResult.Failed(e.sendroMessage())
        }
    }

    // -----------------------------------------------------------------------
    // Streaming save (used by the §15 receiver host)
    // -----------------------------------------------------------------------

    /**
     * Opens a destination and hands back its `OutputStream`.
     *
     * This exists so an INBOUND upload can be written straight to where it
     * belongs while being hashed, instead of landing in a temp file and then
     * being copied. For an 8 GB movie arriving on a TV that is the difference
     * between one pass over the storage and two.
     *
     * Nothing is visible until [PendingSave.commit]: a MediaStore row is
     * created with `IS_PENDING = 1`, and a plain file is written under a
     * `.sendropart` name and renamed on commit. [PendingSave.abort] removes
     * the row or the partial file, which is what an integrity failure does.
     *
     * @return null when no destination could be opened at all.
     */
    fun beginSave(
        displayName: String,
        declaredMimeType: String?,
        useAlbum: Boolean,
    ): PendingSave? {
        val safeName = FileNames.sanitize(displayName)
        val mime = mimeTypeFor(safeName, declaredMimeType)
        val kind = mediaKind(safeName, declaredMimeType)

        // Two reasons to use the app's own store, kept as separate checks so
        // lint can follow the API-level one:
        //  * below API 29 there is no pending-row mechanism worth the trouble;
        //  * installables need a real File for the manifest parse and the
        //    installer hand-off.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return beginFileSave(safeName)
        if (mustStayInAppStore(safeName)) return beginFileSave(safeName)

        val collection: Uri
        val relative: String
        if (kind != null) {
            val base = when (kind) {
                MediaKind.PHOTO -> Environment.DIRECTORY_PICTURES
                MediaKind.VIDEO -> Environment.DIRECTORY_MOVIES
            }
            collection = when (kind) {
                MediaKind.PHOTO ->
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                MediaKind.VIDEO ->
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            relative = if (useAlbum) base + "/" + ALBUM else base
        } else {
            collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            relative = if (useAlbum) {
                Environment.DIRECTORY_DOWNLOADS + "/" + ALBUM
            } else {
                Environment.DIRECTORY_DOWNLOADS
            }
        }

        return beginMediaStoreSave(collection, safeName, mime, relative)
            // MediaStore refused (a few OEM builds reject RELATIVE_PATH
            // subfolders outright). Never lose the transfer over it.
            ?: beginFileSave(safeName)
    }

    private fun beginFileSave(safeName: String): PendingSave? = try {
        val destination = FileNames.availableFile(paths.received, safeName)
        val partial = File(destination.parentFile, destination.name + PART_SUFFIX)
        if (partial.exists()) partial.delete()
        PendingSave(
            saver = this,
            displayName = destination.name,
            uri = null,
            finalFile = destination,
            partialFile = partial,
            output = FileOutputStream(partial),
        )
    } catch (e: Exception) {
        Log.w(TAG, "could not open app-store destination", e)
        null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun beginMediaStoreSave(
        collection: Uri,
        safeName: String,
        mime: String,
        relativePath: String,
    ): PendingSave? {
        val resolver = context.contentResolver
        var attempt = 1
        while (attempt <= 50) {
            val chosen = FileNames.withSuffix(safeName, attempt)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, chosen)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
            }
            val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
            if (uri != null) {
                val stream = runCatching { resolver.openOutputStream(uri, "w") }.getOrNull()
                if (stream == null) {
                    runCatching { resolver.delete(uri, null, null) }
                    return null
                }
                return PendingSave(
                    saver = this,
                    displayName = chosen,
                    uri = uri,
                    finalFile = null,
                    partialFile = null,
                    output = stream,
                )
            }
            attempt++
        }
        return null
    }

    internal fun finishPending(uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        runCatching { context.contentResolver.update(uri, done, null, null) }
    }

    internal fun deletePending(uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    fun hasLegacyStoragePermission(): Boolean {
        if (!needsLegacyStoragePermission) return true
        return context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

/**
 * A destination that has been opened but not published.
 *
 * The caller streams bytes into [output] (hashing as it goes) and then either
 * [commit]s — which makes the file visible under its real name — or [abort]s,
 * which removes every trace. Nothing half-written is ever visible to a gallery
 * app, a file manager, or the Library.
 */
class PendingSave internal constructor(
    private val saver: MediaSaver,
    val displayName: String,
    private val uri: Uri?,
    private val finalFile: File?,
    private val partialFile: File?,
    val output: OutputStream,
) {

    private var closed = false

    fun commit(): SaveResult {
        closeQuietly(flush = true)
        return when {
            uri != null -> {
                saver.finishPending(uri)
                SaveResult.Gallery(uri, displayName)
            }
            finalFile != null && partialFile != null -> {
                // Bound to locals: a member `val` does not smart-cast inside a
                // lambda, which is exactly what the copy fallback below is.
                val target: File = finalFile
                val partial: File = partialFile
                if (!partial.renameTo(target)) {
                    // Same directory, so this should never fail; if it does,
                    // fall back to a copy rather than losing verified bytes.
                    runCatching {
                        partial.copyStreamTo(target)
                        partial.delete()
                    }.getOrElse { return SaveResult.Failed("Could not finish the save.") }
                }
                SaveResult.Files(target)
            }
            else -> SaveResult.Failed("No destination.")
        }
    }

    fun abort() {
        closeQuietly(flush = false)
        uri?.let { saver.deletePending(it) }
        partialFile?.let { runCatching { it.delete() } }
    }

    private fun closeQuietly(flush: Boolean) {
        if (closed) return
        closed = true
        runCatching {
            if (flush) output.flush()
            output.close()
        }
    }
}

/**
 * Plain 1 MiB stream copy. The ONLY way bytes move in this app: no decode, no
 * re-encode, no transformation of any kind.
 */
internal fun File.copyStreamTo(destination: File) {
    destination.parentFile?.mkdirs()
    inputStream().use { input ->
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(SendroCrypto.CHUNK_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
            }
            output.flush()
            output.fd.sync()
        }
    }
}
