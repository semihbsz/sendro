package com.sendro.android.core

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Typed HTTP against ONE Sendro host (`http://ip:port`), PROTOCOL.md v1.
 *
 * WHY OKHTTP (and not Ktor): Ktor's client on Android still needs an engine,
 * and the only sane engine here is OkHttp — so Ktor would be OkHttp plus a
 * second abstraction. Everything Sendro needs is OkHttp's bread and butter:
 * per-call timeouts, real streaming bodies (`source()` / `RequestBody` from a
 * File), Range headers, connection pooling, and cancellation that actually
 * closes the socket.
 *
 * Timeout posture mirrors the iOS client's two URLSessions:
 *   * [Clients.api]  — fail fast. ping/accept/status must error immediately
 *     when the host is gone so the poll loop can react.
 *   * [Clients.poll] — a dedicated client with a long read timeout for the
 *     §6.2 long poll (waitSeconds + slack).
 *   * [Clients.transfer] — long-lived body streaming: a short connect timeout
 *     but a read timeout measured in "gap between chunks", not total time,
 *     and NO call timeout, so an 8 GB file is not cut off mid-flight.
 */
class SendroClient private constructor(
    val baseUrl: HttpUrl,
    private val token: String?,
) {

    companion object {

        /**
         * Be forgiving about what lands here (manual entry, resolver quirks):
         * trim, drop a pasted scheme/path, strip an interface scope ("%wlan0"),
         * bracket bare IPv6 literals, and tolerate "ip:port" pasted into the
         * host field.
         *
         * @return null when nothing usable can be made of [host].
         */
        fun create(host: String, port: Int, token: String? = null): SendroClient? {
            var cleaned = host.trim()
            for (scheme in listOf("http://", "https://")) {
                if (cleaned.startsWith(scheme, ignoreCase = true)) {
                    cleaned = cleaned.substring(scheme.length)
                }
            }
            cleaned.indexOf('/').let { if (it >= 0) cleaned = cleaned.substring(0, it) }
            if (!cleaned.startsWith("[")) {
                cleaned.indexOf('%').let { if (it >= 0) cleaned = cleaned.substring(0, it) }
            }
            if (cleaned.contains(':') && !cleaned.startsWith("[")) {
                val colons = cleaned.count { it == ':' }
                cleaned = if (colons >= 2) {
                    "[$cleaned]"                       // bare IPv6 literal
                } else {
                    cleaned.substringBefore(':')       // "ip:port" pasted in
                }
            }
            if (cleaned.isEmpty() || port !in 1..65535) return null
            val url = "http://$cleaned:$port/".toHttpUrlOrNull() ?: return null
            return SendroClient(url, token)
        }

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val OCTET_MEDIA = "application/octet-stream".toMediaType()

        /**
         * RFC 5987 value-chars encoding of a UTF-8 string. attr-chars stay
         * literal — ALPHA / DIGIT and `!#$&+-.^_\`|~` — every other byte
         * becomes %XX (uppercase hex) of its UTF-8 encoding.
         *
         * Deliberately NOT "isLetterOrDigit", which admits non-ASCII letters
         * and would leave `ç` unencoded.
         */
        fun rfc5987Encode(value: String): String {
            val out = StringBuilder(value.length + 16)
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val v = byte.toInt() and 0xFF
                val c = v.toChar()
                val literal = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
                    c in "!#\$&+-.^_`|~"
                if (literal) {
                    out.append(c)
                } else {
                    out.append('%')
                    out.append("0123456789ABCDEF"[v ushr 4])
                    out.append("0123456789ABCDEF"[v and 0x0F])
                }
            }
            return out.toString()
        }
    }

    /**
     * Process-wide OkHttp clients. One dispatcher and one connection pool for
     * the whole app; the three variants differ only in timeouts.
     */
    object Clients {
        private val base: OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(6, TimeUnit.SECONDS)
            .build()

        /** Fail-fast API calls: info, pair, ping, accept, reject, status. */
        val api: OkHttpClient = base.newBuilder()
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        /**
         * §6.2 long poll. `waitSeconds` is capped at 30 by the host, so a
         * 55 s read timeout leaves generous slack while still bounding a poll
         * that will never answer.
         */
        val poll: OkHttpClient = base.newBuilder()
            .readTimeout(55, TimeUnit.SECONDS)
            .callTimeout(70, TimeUnit.SECONDS)
            .build()

        /**
         * File bytes in either direction. `readTimeout` is the maximum gap
         * between chunks, not the total transfer time, and `callTimeout` is
         * explicitly 0 (unbounded) so a multi-gigabyte file is never cut off.
         */
        val transfer: OkHttpClient = base.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    // -----------------------------------------------------------------------
    // Endpoints
    // -----------------------------------------------------------------------

    /** §5 */
    suspend fun info(): InfoResponse = get("api/v1/info")

    /** §4.1 */
    suspend fun pairStart(request: PairStartRequest): PairStartResponse =
        post("api/v1/pair/start", SendroJson.encodeToString(request))

    /** §4.2 */
    suspend fun pairConfirm(request: PairConfirmRequest): PairConfirmResponse =
        post("api/v1/pair/confirm", SendroJson.encodeToString(request))

    /** §4.3 */
    suspend fun ping(): PingResponse = get("api/v1/ping")

    /** §6.2 — long poll on the dedicated client. */
    suspend fun outboxLongPoll(waitSeconds: Int = 25): OutboxResponse =
        get("api/v1/outbox?waitSeconds=$waitSeconds", Clients.poll)

    /** §6.3 */
    suspend fun accept(transferId: String): OkResponse =
        post("api/v1/transfers/$transferId/accept", null)

    /** §6.3 */
    suspend fun reject(transferId: String): OkResponse =
        post("api/v1/transfers/$transferId/reject", null)

    /** §6.5 */
    suspend fun reportStatus(transferId: String, report: StatusReport): OkResponse =
        post("api/v1/transfers/$transferId/status", SendroJson.encodeToString(report))

    /**
     * §11.2 — send one ephemeral text message. Nothing about the text is
     * cached or persisted here; it exists only for the duration of this call.
     */
    suspend fun sendMessage(text: String): OkResponse = try {
        post("api/v1/messages", SendroJson.encodeToString(SendMessageRequest(text)))
    } catch (e: SendroHttpException) {
        if (e.status == 413) {
            throw SendroHttpException(
                413, "bad_request",
                "Message too long — the limit is 32 KB of text.",
            )
        }
        throw e
    }

    /**
     * §6.4 — the GET for the actual bytes. [rangeStart] > 0 resumes from that
     * offset with an `If-Range` guard against the file having changed.
     *
     * Returns the request; the caller executes it on [Clients.transfer] and
     * streams the body itself.
     */
    fun fileRequest(transferId: String, rangeStart: Long, sha256: String): Request {
        val builder = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/v1/transfers/$transferId/file").build())
            .get()
            .header("Accept", "*/*")
            // Belt and braces: the host already sends identity, and asking for
            // it explicitly means no proxy on the path can helpfully gzip the
            // bytes we are about to hash.
            .header("Accept-Encoding", "identity")
        if (rangeStart > 0) {
            builder.header("Range", "bytes=$rangeStart-")
            builder.header("If-Range", "\"${sha256.lowercase()}\"")
        }
        applyAuth(builder)
        return builder.build()
    }

    /**
     * §7 — the reverse upload (phone -> PC). Raw body, NOT multipart.
     *
     * The body streams from [file] in 1 MiB chunks and reports the running
     * total through [onBytesSent], which is the only place OkHttp exposes
     * upload progress at all. `Content-Length` comes from `File.length()`, so
     * the header is correct before a single byte moves and memory stays flat
     * for an 8 GB video exactly as it does for an 8 KB note.
     */
    fun uploadRequest(
        file: File,
        fileName: String,
        sha256Hex: String,
        onBytesSent: (Long) -> Unit = {},
    ): Request {
        val builder = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/v1/upload").build())
            .header("X-Sendro-File-Name", "UTF-8''" + rfc5987Encode(fileName))
            .header("X-Sendro-Sha256", sha256Hex.lowercase())
        applyAuth(builder)
        builder.post(StreamingFileBody(file, OCTET_MEDIA, onBytesSent))
        return builder.build()
    }

    // -----------------------------------------------------------------------
    // Plumbing
    // -----------------------------------------------------------------------

    private fun applyAuth(builder: Request.Builder) {
        token?.let { builder.header("Authorization", "Bearer $it") }
    }

    private suspend inline fun <reified T> get(
        path: String,
        client: OkHttpClient = Clients.api,
    ): T {
        val url = baseUrl.resolve(path) ?: throw SendroHttpException(0, "bad_request", "Bad URL")
        val builder = Request.Builder().url(url).get().header("Accept", "application/json")
        applyAuth(builder)
        return decode(execute(builder.build(), client))
    }

    private suspend inline fun <reified T> post(
        path: String,
        jsonBody: String?,
        client: OkHttpClient = Clients.api,
    ): T {
        val url = baseUrl.resolve(path) ?: throw SendroHttpException(0, "bad_request", "Bad URL")
        val body: RequestBody = jsonBody?.toRequestBody(JSON_MEDIA)
            ?: ByteArray(0).toRequestBody(null)
        val builder = Request.Builder().url(url).post(body).header("Accept", "application/json")
        applyAuth(builder)
        return decode(execute(builder.build(), client))
    }

    /** Reads the whole (small, JSON) body; never used for file bytes. */
    private suspend fun execute(request: Request, client: OkHttpClient): String {
        val response = client.newCall(request).awaitResponse()
        return response.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw errorFor(r.code, text, r.header("Retry-After"))
            text
        }
    }
}

/**
 * A raw file body that streams from disk and counts as it goes.
 *
 * OkHttp's own `File.asRequestBody()` would stream just as well but gives no
 * progress; this is the same loop with a callback. `writeTo` can be invoked
 * more than once if OkHttp retries the request, so the counter restarts each
 * time and the callback always reports an absolute total.
 */
private class StreamingFileBody(
    private val file: File,
    private val mediaType: okhttp3.MediaType,
    private val onBytesSent: (Long) -> Unit,
) : RequestBody() {

    override fun contentType(): okhttp3.MediaType = mediaType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: okio.BufferedSink) {
        var total = 0L
        onBytesSent(0L)
        file.inputStream().use { input ->
            val buffer = ByteArray(SendroCrypto.CHUNK_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                sink.write(buffer, 0, read)
                total += read
                onBytesSent(total)
            }
        }
        sink.flush()
    }
}

/** JSON decode with a uniform failure, so callers only handle one error type. */
private inline fun <reified T> decode(text: String): T =
    try {
        SendroJson.decodeFromString<T>(text)
    } catch (_: Exception) {
        throw SendroHttpException(0, "bad_response", "Could not read the host's response.")
    }

/**
 * Suspend bridge for an OkHttp call. Cancelling the coroutine cancels the call
 * (which closes the socket) — the property the poll loop and the transfer
 * cancel button both depend on.
 */
suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            // If the coroutine is already gone nobody will close this body —
            // do it here rather than leaking the connection.
            if (continuation.isActive) continuation.resume(response) else response.closeQuietly()
        }
    })
}

internal fun Response.closeQuietly() {
    runCatching { close() }
}

/**
 * §9 — an HTTP error carrying everything the state machine needs.
 *
 * [retryAfterSeconds] is the host's own `Retry-After`, already clamped. It is
 * the difference between "this transfer failed" and "the host told us when to
 * come back", and the whole 503 story depends on it surviving up to the
 * engine rather than being flattened into a string here.
 */
class SendroHttpException(
    val status: Int,
    val code: String?,
    val serverMessage: String?,
    /** Clamped `Retry-After`, or null when the host did not send a usable one. */
    val retryAfterSeconds: Int? = null,
) : IOException(
    // Even the exception's own message must be readable: it can reach the UI
    // through `sendroMessage()` on paths that have no peer name to hand.
    when {
        !serverMessage.isNullOrBlank() -> serverMessage
        status > 0 -> HttpSemantics.explain(status, serverMessage, "The other device", receiving = true)
        else -> "The request was refused."
    },
) {
    val disposition: HttpDisposition get() = HttpSemantics.disposition(status)

    val busyReason: BusyReason get() = HttpSemantics.busyReason(serverMessage)

    /** Plain language for the UI. Never contains a status number. */
    fun explain(peerName: String, receiving: Boolean): String =
        HttpSemantics.explain(status, serverMessage, peerName, receiving)
}

/**
 * Builds the typed exception for a non-2xx response, keeping the host's
 * `Retry-After` intact.
 */
internal fun errorFor(status: Int, body: String, retryAfter: String?): SendroHttpException {
    val parsed = runCatching { SendroJson.decodeFromString<ApiError>(body) }.getOrNull()
    return SendroHttpException(
        status = status,
        code = parsed?.error,
        serverMessage = parsed?.message,
        retryAfterSeconds = HttpSemantics.retryAfterSeconds(retryAfter),
    )
}

/** Human text for anything thrown by the client, for direct UI display. */
fun Throwable.sendroMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: (this::class.simpleName ?: "Unknown error")

/** Headers helper: the §6.4 `Content-Range` total, when the host sent one. */
fun Headers.contentRangeTotal(): Long? {
    val value = this["Content-Range"] ?: return null
    val slash = value.lastIndexOf('/')
    if (slash < 0) return null
    return value.substring(slash + 1).trim().toLongOrNull()
}
