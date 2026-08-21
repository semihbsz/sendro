package com.sendro.android.core

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import okhttp3.Response
import java.io.File
import java.util.UUID

// ---------------------------------------------------------------------------
// UI-facing state
// ---------------------------------------------------------------------------

sealed interface UploadPhase {
    data object Queued : UploadPhase

    /**
     * The PC said "not now" (503 from the transfer gate, from Pause, or from
     * the §14 guest-connection limit). Amber, and it retries itself.
     */
    data class HostBusy(
        val message: String,
        val retryInSeconds: Int,
        val paused: Boolean,
    ) : UploadPhase

    /** Streaming SHA-256 before the POST. */
    data object Hashing : UploadPhase
    data object Uploading : UploadPhase

    /** Server answered 200 — it hashed while writing and agreed. */
    data object Done : UploadPhase
    data class Failed(val message: String) : UploadPhase

    val label: String
        get() = when (this) {
            Queued -> "Waiting"
            is HostBusy -> if (retryInSeconds > 0) "$message Retrying in ${retryInSeconds}s." else message
            Hashing -> "Hashing"
            Uploading -> "Sending"
            Done -> "Landed"
            is Failed -> "Failed"
        }

    /** The short chip label. */
    val shortLabel: String
        get() = when (this) {
            Queued -> "Waiting"
            is HostBusy -> if (paused) "Paused on PC" else "Host busy"
            Hashing -> "Hashing"
            Uploading -> "Sending"
            Done -> "Landed"
            is Failed -> "Failed"
        }

    val isBusy: Boolean get() = this is Hashing || this is Uploading

    /** Still going to happen by itself: never draw these as errors. */
    val isPending: Boolean get() = this is Queued || this is HostBusy

    val isLive: Boolean get() = isBusy || isPending
}

data class UploadItem(
    /** UUID; doubles as the history transferId. */
    val id: String,
    /** Our staged temp copy (original bytes). */
    val file: File,
    val fileName: String,
    val sizeBytes: Long,
    val hostId: String,
    val hostName: String,
    val phase: UploadPhase = UploadPhase.Queued,
    val bytesSent: Long = 0,
    val bytesPerSecond: Double = 0.0,
    val etaSeconds: Int? = null,
    /** From the 200 response. */
    val savedPath: String? = null,
    /** Cached so Retry skips re-hashing. */
    val sha256: String? = null,
) {
    val fraction: Double
        get() = if (sizeBytes <= 0) 0.0 else (bytesSent.toDouble() / sizeBytes).coerceIn(0.0, 1.0)
}

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

/**
 * Phone -> PC sending (PROTOCOL.md §7, v1-lite).
 *
 * A sequential queue: each staged file is hashed with streaming SHA-256 in
 * 1 MiB chunks, then POSTed raw (NOT multipart) with the body streaming
 * straight off disk, so memory stays flat regardless of file size.
 *
 * §7 has no ranged upload: a retry after a failure or an integrity reject
 * always restarts the stream from byte 0. Acceptable for v1 on a LAN.
 */
class UploadEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val paired: PairedHostStore,
    private val tokens: TokenStore,
    private val history: HistoryStore,
    private val paths: AppPaths,
    private val onUploadActivity: () -> Unit,
) {

    private val _items = MutableStateFlow<List<UploadItem>>(emptyList())
    val items: StateFlow<List<UploadItem>> = _items.asStateFlow()

    private val lock = Any()
    private var currentId: String? = null
    private var currentJob: Job? = null

    /**
     * Backpressure state, guarded by [lock]. Uploads stay strictly sequential
     * — the queue's width is 1 — so the only thing that can go wrong is
     * hammering a host that has already said it is full. Cooldowns are per
     * HOST because that is what the 503 is about.
     */
    private val hostCooldowns = HashMap<String, Long>()
    private val hostBusyStreak = HashMap<String, Int>()

    /** itemId -> when it FIRST got a busy answer, for the give-up clock. */
    private val busySince = HashMap<String, Long>()
    private var tickerJob: Job? = null

    // -----------------------------------------------------------------------
    // Queue control (UI)
    // -----------------------------------------------------------------------

    fun enqueue(files: List<File>, hostId: String, hostName: String) {
        if (files.isEmpty()) return
        _items.update { list ->
            list + files.map { file ->
                UploadItem(
                    id = UUID.randomUUID().toString(),
                    file = file,
                    fileName = file.name,
                    sizeBytes = file.length(),
                    hostId = hostId,
                    hostName = hostName,
                )
            }
        }
        pump()
    }

    /**
     * Cancel / remove one item. A live upload is torn down through its job
     * (the outcome path cleans up); anything else is removed directly.
     */
    fun cancel(itemId: String) {
        val item = _items.value.firstOrNull { it.id == itemId } ?: return
        val isCurrent = synchronized(lock) { currentId == itemId }
        if (isCurrent) {
            synchronized(lock) { currentJob?.cancel() }
            return
        }
        _items.update { list -> list.filterNot { it.id == itemId } }
        deleteStaged(item.file)
    }

    /** §7 has no ranged upload — a retry restarts the stream from byte 0. */
    fun retry(itemId: String) {
        val item = _items.value.firstOrNull { it.id == itemId }
        if (item != null) {
            // An explicit "try now" beats any countdown we were sitting on.
            synchronized(lock) {
                hostCooldowns.remove(item.hostId)
                hostBusyStreak.remove(item.hostId)
                busySince.remove(itemId)
            }
        }
        _items.update { list ->
            list.map {
                if (it.id == itemId && (it.phase is UploadPhase.Failed || it.phase is UploadPhase.HostBusy)) {
                    it.copy(
                        phase = UploadPhase.Queued,
                        bytesSent = 0,
                        bytesPerSecond = 0.0,
                        etaSeconds = null,
                    )
                } else {
                    it
                }
            }
        }
        pump()
    }

    fun clearFinished() {
        val done = _items.value.filter { it.phase is UploadPhase.Done }
        _items.update { list -> list.filterNot { it.phase is UploadPhase.Done } }
        done.forEach { deleteStaged(it.file) }
    }

    // -----------------------------------------------------------------------
    // Sequential pump
    // -----------------------------------------------------------------------

    private fun pump() {
        val now = System.currentTimeMillis()
        val next = synchronized(lock) {
            if (currentId != null) return
            val candidate = _items.value.firstOrNull {
                it.phase.isPending && (hostCooldowns[it.hostId] ?: 0L) <= now
            } ?: return
            currentId = candidate.id
            candidate
        }
        onUploadActivity()
        val job = scope.launch { runOne(next.id) }
        synchronized(lock) { currentJob = job }
        job.invokeOnCompletion {
            synchronized(lock) {
                currentId = null
                currentJob = null
            }
            onUploadActivity()
            pump()
        }
    }

    private suspend fun runOne(itemId: String) {
        val item = _items.value.firstOrNull { it.id == itemId } ?: return

        val sha = item.sha256 ?: run {
            update(itemId) { it.copy(phase = UploadPhase.Hashing) }
            try {
                withContext(Dispatchers.IO) { SendroCrypto.sha256Hex(item.file) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(itemId) {
                    it.copy(phase = UploadPhase.Failed("Could not read file: ${e.sendroMessage()}"))
                }
                return
            }
        }
        update(itemId) { it.copy(sha256 = sha) }

        val host = paired.host(item.hostId)
        val token = tokens.token(item.hostId)
        val client = if (host != null && token != null) {
            SendroClient.create(host.lastHost, host.lastPort, token)
        } else {
            null
        }
        if (client == null) {
            update(itemId) {
                it.copy(phase = UploadPhase.Failed("${item.hostName} is not reachable — check pairing."))
            }
            return
        }

        update(itemId) { it.copy(phase = UploadPhase.Uploading, bytesSent = 0) }

        val outcome = try {
            postFile(client, item, sha, itemId)
        } catch (e: CancellationException) {
            // User cancelled: drop the row and its staged copy.
            _items.update { list -> list.filterNot { it.id == itemId } }
            deleteStaged(item.file)
            throw e
        } catch (e: SendroHttpException) {
            Log.w(TAG, "upload rejected", e)
            outcomeFor(e, item.hostName)
        } catch (e: Exception) {
            Log.w(TAG, "upload failed", e)
            Outcome.Failed("Couldn't reach ${item.hostName} — the file is still here, try again.")
        }

        when (outcome) {
            is Outcome.Success -> {
                synchronized(lock) {
                    hostCooldowns.remove(item.hostId)
                    hostBusyStreak.remove(item.hostId)
                    busySince.remove(itemId)
                }
                update(itemId) {
                    it.copy(
                        phase = UploadPhase.Done,
                        bytesSent = item.sizeBytes,
                        bytesPerSecond = 0.0,
                        etaSeconds = null,
                        savedPath = outcome.savedPath,
                    )
                }
                // "Verified" here means the host's own SHA-256 check passed —
                // it hashes while writing and only answers 200 on a match.
                history.add(
                    transferId = item.id,
                    fileName = item.fileName,
                    sizeBytes = item.sizeBytes,
                    senderName = item.hostName,
                    outcome = "completed",
                    direction = "outgoing",
                )
                deleteStaged(item.file)
            }

            Outcome.IntegrityRejected -> update(itemId) {
                it.copy(
                    phase = UploadPhase.Failed(
                        "The PC's SHA-256 check failed — bytes changed in flight. " +
                            "Retry uploads the whole file again.",
                    ),
                    bytesPerSecond = 0.0,
                    etaSeconds = null,
                )
            }

            // 503 — the PC is full or paused. Not a failure: park the item on
            // an amber countdown and let the sequential pump come back to it.
            is Outcome.Busy -> noteHostBusy(item, itemId, outcome)

            is Outcome.Failed -> update(itemId) {
                it.copy(
                    phase = UploadPhase.Failed(outcome.message),
                    bytesPerSecond = 0.0,
                    etaSeconds = null,
                )
            }
        }
    }

    private sealed interface Outcome {
        data class Success(val savedPath: String?) : Outcome

        /** 422 {"error":"integrity"} */
        data object IntegrityRejected : Outcome

        /** 429 / 503 with the host's own `Retry-After`. */
        data class Busy(
            val reason: BusyReason,
            val retryAfterSeconds: Int?,
            val message: String,
        ) : Outcome

        data class Failed(val message: String) : Outcome
    }

    /** Maps one rejected request onto an outcome, without ever naming a code. */
    private fun outcomeFor(error: SendroHttpException, peerName: String): Outcome {
        val text = error.explain(peerName, receiving = false)
        return when (error.disposition) {
            HttpDisposition.BACKPRESSURE ->
                Outcome.Busy(error.busyReason, error.retryAfterSeconds, text)
            HttpDisposition.INTEGRITY -> Outcome.IntegrityRejected
            // §7 has no ranged upload, so "retry soon" and a host hiccup both
            // just mean: start the stream again in a moment.
            HttpDisposition.RETRY_SOON, HttpDisposition.HOST_ERROR ->
                Outcome.Busy(BusyReason.UNKNOWN, error.retryAfterSeconds, text)
            else -> Outcome.Failed(text)
        }
    }

    // -----------------------------------------------------------------------
    // Backpressure
    // -----------------------------------------------------------------------

    /**
     * Forget every cooldown and try again now. Called when the app comes to
     * the foreground or the network changes — a countdown measured against an
     * old interface or a frozen process is meaningless.
     */
    fun clearCooldowns() {
        synchronized(lock) {
            hostCooldowns.clear()
            hostBusyStreak.clear()
        }
        _items.update { list ->
            list.map { if (it.phase is UploadPhase.HostBusy) it.copy(phase = UploadPhase.Queued) else it }
        }
        pump()
    }

    private fun noteHostBusy(item: UploadItem, itemId: String, outcome: Outcome.Busy) {
        val now = System.currentTimeMillis()
        val seconds = synchronized(lock) {
            val streak = (hostBusyStreak[item.hostId] ?: 0) + 1
            hostBusyStreak[item.hostId] = streak
            val exponent = (streak - 1).coerceIn(0, 5)
            val mine = HttpSemantics.clampRetry(HttpSemantics.MIN_RETRY_SECONDS.toLong() shl exponent)
            val value = maxOf(outcome.retryAfterSeconds ?: HttpSemantics.MIN_RETRY_SECONDS, mine)
            hostCooldowns[item.hostId] = now + value * 1000L
            value
        }
        val since = synchronized(lock) {
            val existing = busySince[itemId]
            if (existing == null) {
                busySince[itemId] = now
                now
            } else {
                existing
            }
        }
        if (now - since >= GIVE_UP_AFTER_MS) {
            synchronized(lock) { busySince.remove(itemId) }
            update(itemId) {
                it.copy(
                    phase = UploadPhase.Failed(
                        "${outcome.message} It has stayed busy for a while — press Retry to try again.",
                    ),
                    bytesSent = 0,
                    bytesPerSecond = 0.0,
                    etaSeconds = null,
                )
            }
            return
        }
        update(itemId) {
            it.copy(
                phase = UploadPhase.HostBusy(
                    message = outcome.message,
                    retryInSeconds = seconds,
                    paused = outcome.reason == BusyReason.PAUSED,
                ),
                // §7 has no resume, so the next attempt starts from byte 0.
                bytesSent = 0,
                bytesPerSecond = 0.0,
                etaSeconds = null,
            )
        }
        ensureTicker()
    }

    private fun ensureTicker() {
        synchronized(lock) {
            if (tickerJob?.isActive == true) return
            tickerJob = scope.launch { tickLoop() }
        }
    }

    private suspend fun tickLoop() {
        while (true) {
            val now = System.currentTimeMillis()
            for (item in _items.value) {
                val phase = item.phase
                if (phase !is UploadPhase.HostBusy) continue
                val until = synchronized(lock) { hostCooldowns[item.hostId] ?: 0L }
                val seconds = if (until <= now) 0 else (((until - now) + 999L) / 1000L).toInt()
                if (seconds != phase.retryInSeconds) {
                    update(item.id) { it.copy(phase = phase.copy(retryInSeconds = seconds)) }
                }
            }
            pump()
            val done = synchronized(lock) {
                if (_items.value.none { it.phase is UploadPhase.HostBusy }) {
                    tickerJob = null
                    true
                } else {
                    false
                }
            }
            if (done) return
            delay(1_000)
        }
    }

    private suspend fun postFile(
        client: SendroClient,
        item: UploadItem,
        sha256: String,
        itemId: String,
    ): Outcome = withContext(Dispatchers.IO) {
        // Progress comes straight off the streaming body (the only place
        // OkHttp exposes it), throttled to ~4 updates a second so a fast LAN
        // cannot flood the UI.
        val samples = ArrayDeque<Pair<Long, Long>>()
        var lastEmit = 0L

        val request = client.uploadRequest(item.file, item.fileName, sha256) { sent ->
            val now = System.currentTimeMillis()
            if (sent != 0L && sent < item.sizeBytes && now - lastEmit < PROGRESS_INTERVAL_MS) return@uploadRequest
            lastEmit = now
            synchronized(samples) {
                samples.addLast(now to sent)
                while (samples.size > 1 && now - samples.first().first > 3_000) samples.removeFirst()
            }
            var speed = 0.0
            val first = samples.firstOrNull()
            val newest = samples.lastOrNull()
            if (first != null && newest != null && newest.first > first.first) {
                speed = (newest.second - first.second).toDouble() /
                    ((newest.first - first.first) / 1000.0)
            }
            val eta = if (speed > 1 && item.sizeBytes > sent) {
                ((item.sizeBytes - sent) / speed).toInt()
            } else {
                null
            }
            update(itemId) { it.copy(bytesSent = sent, bytesPerSecond = speed, etaSeconds = eta) }
        }

        val response: Response = SendroClient.Clients.transfer.newCall(request).awaitResponse()
        response.use { r ->
            val body = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
            when (r.code) {
                200 -> {
                    val parsed = runCatching {
                        SendroJson.decodeFromString<UploadResult>(body)
                    }.getOrNull()
                    Outcome.Success(parsed?.savedPath)
                }
                422 -> Outcome.IntegrityRejected
                // Everything else goes through the shared semantics so the row
                // says what happened, never a number.
                else -> outcomeFor(errorFor(r.code, body, r.header("Retry-After")), item.hostName)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun update(itemId: String, block: (UploadItem) -> UploadItem) {
        _items.update { list ->
            if (list.none { it.id == itemId }) list
            else list.map { if (it.id == itemId) block(it) else it }
        }
    }

    private fun deleteStaged(file: File) {
        runCatching {
            file.delete()
            val parent = file.parentFile ?: return@runCatching
            if (parent.absolutePath.startsWith(paths.outgoing.absolutePath) &&
                parent.absolutePath != paths.outgoing.absolutePath &&
                parent.list()?.isEmpty() == true
            ) {
                parent.delete()
            }
        }
    }

    /**
     * Copy the ORIGINAL bytes behind a content:// URI into a staged file.
     *
     * This is the single most important function in the send path: it opens
     * the provider's stream and copies bytes. It never decodes a Bitmap, never
     * touches `MediaStore.Images.Media.getBitmap`, never re-compresses — an
     * Android photo picker hands back the real HEIC/JPEG and that is exactly
     * what lands on the PC.
     */
    suspend fun stage(uris: List<Uri>): StageResult = withContext(Dispatchers.IO) {
        val batch = paths.newOutgoingBatch()
        val staged = ArrayList<File>()
        val failures = ArrayList<String>()
        for (uri in uris) {
            val name = ContentNames.displayName(context, uri)
            try {
                val destination = FileNames.availableFile(batch, FileNames.sanitize(name))
                context.contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw java.io.IOException("The app that shared this file closed it.")
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(SendroCrypto.CHUNK_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }
                staged.add(destination)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not stage $uri", e)
                failures.add("$name — ${e.sendroMessage()}")
            }
        }
        if (staged.isEmpty()) batch.delete()
        StageResult(staged, failures)
    }

    data class StageResult(val files: List<File>, val failures: List<String>)

    private companion object {
        const val TAG = "SendroUpload"
        const val PROGRESS_INTERVAL_MS = 250L

        /** Same give-up window as the download queue. */
        const val GIVE_UP_AFTER_MS = 10L * 60L * 1000L
    }
}

/** Reads a content:// URI's display name, falling back to the last path bit. */
object ContentNames {

    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") {
            return uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "file"
        }
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        val value = cursor.getString(index)
                        if (!value.isNullOrBlank()) return value
                    }
                }
            }
        }
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        if (fallback != null && fallback.contains('.')) return fallback

        // No name at all: give it a sane one with the right extension so the
        // PC side, and any later preview, know what it is.
        val type = context.contentResolver.getType(uri)
        val extension = type?.let {
            android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        return if (extension != null) "Sendro $stamp.$extension" else "Sendro $stamp"
    }
}
