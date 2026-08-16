package com.sendro.android.core

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import okhttp3.OkHttpClient
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

/**
 * One streaming, resumable, hash-as-you-go download of the §6.4 bytes.
 *
 * Invariants (these ARE the product):
 *  - never more than [SendroCrypto.CHUNK_BYTES] of the file in memory;
 *  - the SHA-256 is computed from the bytes this client actually wrote to
 *    disk, incrementally, never by re-reading and trusting the host;
 *  - on resume the existing prefix is re-hashed from disk before appending,
 *    because an incremental hasher cannot be restored any other way;
 *  - a 200 answer to a ranged request (host ignored `Range`, or the file
 *    changed and `If-Range` did its job) truncates and starts over rather
 *    than silently concatenating two different files.
 */
class DownloadTask(
    private val offer: TransferOffer,
    private val partFile: File,
    private val client: SendroClient,
    private val http: OkHttpClient = SendroClient.Clients.transfer,
) {

    sealed interface Outcome {
        /** Streamed, hashed, and the digest matched §6.1 `sha256`. */
        data class Verified(val file: File) : Outcome

        /** Streamed fully, digest did NOT match. The temp file is deleted. */
        data object IntegrityMismatch : Outcome

        data class Failed(val message: String, val resumable: Boolean) : Outcome
    }

    data class Progress(
        val bytesReceived: Long,
        val totalBytes: Long,
        val bytesPerSecond: Double,
        val etaSeconds: Int?,
    )

    /**
     * Runs the whole download on [Dispatchers.IO]. Cancelling the calling
     * coroutine cancels the HTTP call and closes the file; the partial stays
     * on disk so a later resume can continue from it.
     *
     * @param onBegan invoked once with the byte offset the transfer starts at.
     * @param onProgress throttled to ~4/s by the caller's own timing needs.
     * @param onVerifying invoked when all bytes are on disk, before hashing
     *   finishes (the digest itself is already incremental, so this is
     *   instantaneous — but the phase still exists for §6.5 parity with iOS).
     */
    suspend fun run(
        onBegan: (Long) -> Unit,
        onProgress: (Progress) -> Unit,
        onVerifying: () -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        try {
            download(onBegan, onProgress, onVerifying)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download failed for ${offer.transferId}", e)
            Outcome.Failed(e.sendroMessage(), resumable = true)
        }
    }

    private suspend fun download(
        onBegan: (Long) -> Unit,
        onProgress: (Progress) -> Unit,
        onVerifying: () -> Unit,
    ): Outcome {
        partFile.parentFile?.mkdirs()
        if (!partFile.exists()) partFile.createNewFile()

        var existing = partFile.length()
        if (existing > offer.sizeBytes) {
            // Cannot be ours — a different file under the same transferId, or
            // a truncated write that grew. Start over.
            RandomAccessFile(partFile, "rw").use { it.setLength(0) }
            existing = 0
        }

        val hasher = StreamingSha256()
        if (existing > 0) {
            // Progressive hashing means the on-disk prefix has to be re-fed to
            // the digest before we can append — streamed in 1 MiB chunks, so
            // resuming a 7 GB file costs a read pass, never 7 GB of RAM.
            if (!rehashPrefix(hasher, existing)) {
                RandomAccessFile(partFile, "rw").use { it.setLength(0) }
                existing = 0
            }
        }

        // Everything already on disk: nothing to fetch, just verify.
        if (offer.sizeBytes > 0 && existing == offer.sizeBytes) {
            onBegan(existing)
            onVerifying()
            return verify(hasher)
        }

        onBegan(existing)

        val request = client.fileRequest(offer.transferId, existing, offer.sha256)
        val call = http.newCall(request)
        val response = call.awaitResponse()

        return response.use { r ->
            var written = existing
            var digest = hasher

            when (r.code) {
                206 -> Unit // host honoured the range; keep appending
                200 -> {
                    if (existing > 0) {
                        // Range ignored, or If-Range said the file changed:
                        // reset and take the whole thing from byte 0.
                        RandomAccessFile(partFile, "rw").use { it.setLength(0) }
                        written = 0
                        digest = StreamingSha256()
                    }
                }
                416 -> {
                    // Our offset is past the end — the file on the host is
                    // smaller than our partial. Drop it and let the caller
                    // retry from scratch.
                    RandomAccessFile(partFile, "rw").use { it.setLength(0) }
                    return Outcome.Failed(
                        "The file on the PC changed while transferring. Retry to start over.",
                        resumable = true,
                    )
                }
                else -> {
                    val body = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
                    val parsed = runCatching { SendroJson.decodeFromString<ApiError>(body) }.getOrNull()
                    return Outcome.Failed(
                        parsed?.message ?: "Host returned HTTP ${r.code}.",
                        // 5xx and 408 are worth retrying; 401/403/404/410 are not.
                        resumable = r.code >= 500 || r.code == 408,
                    )
                }
            }

            val body = r.body ?: return Outcome.Failed("Host sent no body.", resumable = true)

            RandomAccessFile(partFile, "rw").use { raf ->
                raf.seek(written)
                body.byteStream().use { input ->
                    val buffer = ByteArray(SendroCrypto.CHUNK_BYTES)
                    var lastEmit = 0L
                    val samples = ArrayDeque<Pair<Long, Long>>()   // (uptimeMs, bytes)

                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        raf.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read

                        val now = System.currentTimeMillis()
                        samples.addLast(now to written)
                        // Rolling 3-second window, like the iOS speed meter.
                        while (samples.size > 1 && now - samples.first().first > 3_000) {
                            samples.removeFirst()
                        }
                        if (now - lastEmit >= PROGRESS_INTERVAL_MS || written == offer.sizeBytes) {
                            lastEmit = now
                            onProgress(makeProgress(written, samples))
                        }
                    }
                }
            }

            if (offer.sizeBytes > 0 && written != offer.sizeBytes) {
                return Outcome.Failed(
                    "Connection closed early ($written/${offer.sizeBytes} bytes).",
                    resumable = true,
                )
            }

            onProgress(Progress(written, offer.sizeBytes, 0.0, null))
            onVerifying()
            verify(digest)
        }
    }

    private fun verify(hasher: StreamingSha256): Outcome {
        val hex = hasher.hexDigest()
        // §6.5: case-insensitive hex compare against the host's authoritative
        // digest. This is the ONLY thing that makes a transfer "verified".
        return if (hex.equals(offer.sha256, ignoreCase = true)) {
            Outcome.Verified(partFile)
        } else {
            Log.w(TAG, "integrity mismatch ${offer.fileName}: got $hex want ${offer.sha256}")
            partFile.delete()
            Outcome.IntegrityMismatch
        }
    }

    /** @return false when the prefix could not be fully re-read. */
    private suspend fun rehashPrefix(hasher: StreamingSha256, byteCount: Long): Boolean {
        var remaining = byteCount
        partFile.inputStream().use { stream ->
            val buffer = ByteArray(SendroCrypto.CHUNK_BYTES)
            while (remaining > 0) {
                coroutineContext.ensureActive()
                val want = minOf(remaining, buffer.size.toLong()).toInt()
                val read = stream.read(buffer, 0, want)
                if (read <= 0) return false
                hasher.update(buffer, 0, read)
                remaining -= read
            }
        }
        return remaining == 0L
    }

    private fun makeProgress(written: Long, samples: ArrayDeque<Pair<Long, Long>>): Progress {
        var speed = 0.0
        val first = samples.firstOrNull()
        val last = samples.lastOrNull()
        if (first != null && last != null && last.first > first.first) {
            speed = (last.second - first.second).toDouble() /
                ((last.first - first.first) / 1000.0)
        }
        val eta = if (speed > 1.0 && offer.sizeBytes > written) {
            ((offer.sizeBytes - written) / speed).toInt()
        } else {
            null
        }
        return Progress(written, offer.sizeBytes, speed, eta)
    }

    private companion object {
        const val TAG = "SendroDownload"
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
