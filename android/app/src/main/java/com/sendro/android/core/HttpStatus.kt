package com.sendro.android.core

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Turning HTTP into something a person can act on.
 *
 * Two rules this file exists to enforce:
 *
 *  1. **A raw status code never reaches the UI.** "Host returned HTTP 503" is
 *     not an error message, it is a shrug. Every code Sendro can actually
 *     receive gets a sentence that says what happened and what will happen
 *     next, worded differently for receiving and for sending because the same
 *     code means different things in the two directions.
 *  2. **503 is backpressure, not failure.** The Rust host gates concurrent
 *     downloads (`core/src/server.rs`, default 2, user-settable 1–4) and
 *     answers `503` + `Retry-After` when the slots are full or the user has
 *     pressed Pause. The guest path does the same at 8 connections. That is
 *     the host working correctly; treating it as a failed transfer is the bug.
 */

/** What a non-2xx response means for the state machine, independent of wording. */
enum class HttpDisposition {
    /** Retry after a delay the host asked for. Never a failure. */
    BACKPRESSURE,

    /** Retry soon; the host is not ready for this specific transfer yet. */
    RETRY_SOON,

    /** Pairing is gone. Retrying cannot help. */
    UNAUTHORIZED,

    /** The transfer no longer exists on the host. Retrying cannot help. */
    GONE,

    /** Our range request did not line up. Restart from zero. */
    RANGE_MISMATCH,

    /** The bytes did not match the hash. */
    INTEGRITY,

    /** Something transient on the host; worth one more go. */
    HOST_ERROR,

    /** A genuine, permanent problem with the request. */
    FATAL,
}

/** Why a host is refusing work right now (§ the 503 bodies the Rust host sends). */
enum class BusyReason {
    /** `"transfer slots busy"` — the concurrency gate is full. */
    SLOTS_BUSY,

    /** `"transfers paused"` — the user pressed Pause on the PC. */
    PAUSED,

    /** `"too many guest connections"` — the Sendro Link path (§14). */
    GUEST_LIMIT,

    /** A 503 with a body we do not recognise. */
    UNKNOWN,
}

object HttpSemantics {

    /** Retry-After is clamped: a host asking for 0 s or an hour is ignored. */
    const val MIN_RETRY_SECONDS = 1
    const val MAX_RETRY_SECONDS = 30

    fun disposition(status: Int): HttpDisposition = when (status) {
        401, 403 -> HttpDisposition.UNAUTHORIZED
        404, 410 -> HttpDisposition.GONE
        409 -> HttpDisposition.RETRY_SOON
        416 -> HttpDisposition.RANGE_MISMATCH
        422 -> HttpDisposition.INTEGRITY
        429, 503 -> HttpDisposition.BACKPRESSURE
        // 408 and 5xx are the host having a moment, not us being wrong.
        408, in 500..599 -> HttpDisposition.HOST_ERROR
        else -> HttpDisposition.FATAL
    }

    /**
     * Classifies a 503 body. The host's `message` is the authoritative signal;
     * the `error` code for all three is `rate_limited`.
     */
    fun busyReason(message: String?): BusyReason {
        val text = message?.lowercase().orEmpty()
        return when {
            text.contains("paused") -> BusyReason.PAUSED
            text.contains("slot") -> BusyReason.SLOTS_BUSY
            text.contains("guest") -> BusyReason.GUEST_LIMIT
            else -> BusyReason.UNKNOWN
        }
    }

    /**
     * Parses `Retry-After`: an integer number of seconds, or an HTTP-date.
     * Returns null when absent or unparseable so the caller can fall back to
     * its own backoff.
     */
    fun retryAfterSeconds(header: String?, nowMs: Long = System.currentTimeMillis()): Int? {
        val raw = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        raw.toLongOrNull()?.let { return clampRetry(it) }
        // RFC 7231 also allows an HTTP-date. Rare from our host, but a proxy
        // in between is entitled to rewrite it.
        val parsed = runCatching {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT")
            format.parse(raw)
        }.getOrNull() ?: return null
        val deltaSeconds = (parsed.time - nowMs) / 1000
        return clampRetry(deltaSeconds)
    }

    fun clampRetry(seconds: Long): Int =
        seconds.coerceIn(MIN_RETRY_SECONDS.toLong(), MAX_RETRY_SECONDS.toLong()).toInt()

    /**
     * Plain language for a failed request.
     *
     * @param receiving true for a download (§6.4) — the peer is the sender;
     *   false for an upload (§7) — the peer is the receiver. The same code
     *   genuinely means different things: a 404 while receiving is "they
     *   cancelled it", a 404 while sending is "that device does not accept
     *   files".
     * @param peerName the device's own name, so the sentence names it.
     */
    fun explain(
        status: Int,
        serverMessage: String?,
        peerName: String,
        receiving: Boolean,
    ): String = when (status) {
        401 -> "$peerName doesn't recognise this device any more. Pair with it again."
        403 -> if (receiving) {
            "$peerName refused this transfer. Pair again if you removed this device from it."
        } else {
            "$peerName refused the file. Pair again if you removed this device from it."
        }
        404, 410 -> if (receiving) {
            "$peerName cancelled this one, or it expired. Nothing was lost."
        } else {
            "$peerName isn't accepting files at that address any more."
        }
        409 -> "$peerName isn't ready for this one yet — trying again shortly."
        413 -> if (receiving) {
            "$peerName says the request was too large."
        } else {
            "$peerName refused the file as too large."
        }
        416 -> "Resuming didn't line up with the file on $peerName — starting over. Nothing is lost."
        422 -> "$peerName checked the bytes and they didn't match. Nothing was saved."
        429, 503 -> when (busyReason(serverMessage)) {
            BusyReason.PAUSED -> "Transfers are paused on $peerName."
            BusyReason.SLOTS_BUSY -> "$peerName is busy with other transfers."
            BusyReason.GUEST_LIMIT -> "$peerName has too many guest connections open."
            BusyReason.UNKNOWN -> "$peerName is busy."
        }
        408 -> "$peerName took too long to answer — trying again."
        in 500..599 -> "Something went wrong on $peerName — trying again."
        // Anything else: the host's own message if it gave one, and a plain
        // sentence if it did not. Still no bare number.
        else -> serverMessage?.takeIf { it.isNotBlank() }
            ?: if (receiving) {
                "$peerName couldn't send that file."
            } else {
                "$peerName couldn't take that file."
            }
    }
}
