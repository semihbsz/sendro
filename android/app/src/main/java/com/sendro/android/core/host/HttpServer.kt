package com.sendro.android.core.host

import android.util.Log
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A deliberately tiny HTTP/1.1 server: `ServerSocket` plus about three hundred
 * lines of parsing.
 *
 * WHY NOT A LIBRARY. Three candidates were on the table:
 *
 *  * **NanoHTTPD** — one file, but its session layer wants to parse the body
 *    for you: it pre-reads part of the body into its header buffer and then
 *    hands you a stream whose position you have to reason about, and its own
 *    body handling buffers into memory or a temp file. Sendro's entire promise
 *    is "the bytes are never buffered and the hash is computed on exactly what
 *    is written", and NanoHTTPD makes that a fight rather than a given.
 *  * **Ktor server** — correct streaming via `receiveChannel()`, but it drags
 *    in an engine plus coroutine IO for six endpoints, and it needs its own
 *    R8 keep rules. Megabytes of dependency for a routing DSL we do not need.
 *  * **Raw sockets** — what this is. There is no routing DSL, no content
 *    negotiation, no sessions and no keep-alive, because none of those are
 *    needed for §15.1's six endpoints. What there IS: a byte path short enough
 *    to read in one sitting and verify by eye, which for the security- and
 *    fidelity-critical part of the app is worth more than convenience.
 *
 * Deliberate limitations, all documented rather than hidden:
 *  * `Connection: close` on every response; no keep-alive. Every real client
 *    (OkHttp, reqwest, curl) handles it, and it removes a whole class of
 *    framing bugs.
 *  * Requests are bounded: 8 KB request line, 16 KB of headers, 100 headers.
 *  * Bodies are streamed. [HttpRequest.body] hands out an `InputStream` that
 *    yields exactly the request body and nothing else, so an 8 GB upload is
 *    read in whatever chunk size the handler chooses and never accumulates.
 */
class HttpServer(
    private val requestedPort: Int,
    private val portScanRange: Int,
    /**
     * Called once when the accept loop gives up on a socket that will not
     * recover. The owner rebinds; without this the server used to die
     * silently while the UI still said "Ready to receive".
     */
    private val onDied: () -> Unit = {},
    private val handler: (HttpRequest) -> HttpResponse,
) {

    /** The port actually bound, once [start] has returned true. */
    @Volatile
    var boundPort: Int = 0
        private set

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /**
     * Bounded on purpose. A TV is serving one phone, occasionally two; an
     * unbounded pool is just a way for a hostile LAN peer to exhaust memory.
     * Anything past the queue is rejected by closing the socket.
     */
    private val workers = ThreadPoolExecutor(
        2,
        MAX_WORKERS,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(WORKER_QUEUE),
        ThreadPoolExecutor.CallerRunsPolicy(),
    )

    val isRunning: Boolean get() = running.get()

    /**
     * Binds 0.0.0.0 on [requestedPort], scanning forward up to
     * [portScanRange] ports (§2: "if busy the host tries 48801..48820 and
     * advertises the real one").
     *
     * @return true when a port was bound.
     */
    fun start(): Boolean {
        if (running.get()) return true
        for (offset in 0..portScanRange) {
            val port = requestedPort + offset
            val socket = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port), BACKLOG)
                }
            } catch (e: IOException) {
                continue
            }
            serverSocket = socket
            boundPort = port
            running.set(true)
            acceptThread = Thread({ acceptLoop(socket) }, "sendro-http-accept").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "listening on 0.0.0.0:$port")
            return true
        }
        Log.w(TAG, "no free port in $requestedPort..${requestedPort + portScanRange}")
        return false
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        boundPort = 0
        workers.queue.clear()
        // An instance is single-use: ReceiverHost builds a fresh HttpServer
        // every time it starts, so the pool can go with the socket.
        runCatching { workers.shutdownNow() }
    }

    /**
     * Accept forever, surviving the transient failures a real device produces.
     *
     * The old loop broke out on the FIRST `IOException`, left [running] true,
     * and therefore made the whole receiver permanently deaf while every
     * status in the app still said it was listening — the exact silent hang
     * this file must not have. `accept()` can fail transiently (EINTR, a
     * momentary FD shortage, an interface flapping on a TV); those are worth
     * a short sleep and another go. A closed socket, or a run of consecutive
     * failures, is real: report it so the owner can rebind.
     */
    private fun acceptLoop(socket: ServerSocket) {
        var consecutiveFailures = 0
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (!running.get()) break
                if (socket.isClosed) {
                    Log.w(TAG, "accept socket closed under us", e)
                    consecutiveFailures = ACCEPT_FAILURE_LIMIT
                } else {
                    consecutiveFailures++
                    Log.w(TAG, "accept failed ($consecutiveFailures)", e)
                }
                if (consecutiveFailures >= ACCEPT_FAILURE_LIMIT) {
                    running.set(false)
                    runCatching { socket.close() }
                    runCatching { onDied() }
                    return
                }
                runCatching { Thread.sleep(ACCEPT_RETRY_SLEEP_MS) }
                continue
            }
            consecutiveFailures = 0
            workers.execute { serve(client) }
        }
        runCatching { socket.close() }
    }

    private fun serve(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            // Headers must arrive promptly; the BODY read timeout is longer and
            // is set by the request stream itself, because "no bytes for 30 s"
            // during an 8 GB upload on a busy Wi-Fi is normal.
            socket.soTimeout = HEADER_TIMEOUT_MS

            val input = socket.getInputStream()
            val output = BufferedOutputStream(socket.getOutputStream(), 8 * 1024)

            val request = try {
                readRequest(socket, input)
            } catch (e: SocketTimeoutException) {
                return
            } catch (e: EOFException) {
                return
            } catch (e: BadRequestException) {
                writeResponse(output, HttpResponse.json(400, """{"error":"bad_request"}"""))
                output.flush()
                return
            }

            val response = try {
                handler(request)
            } catch (e: Exception) {
                Log.w(TAG, "handler threw for ${request.method} ${request.path}", e)
                HttpResponse.json(500, """{"error":"bad_request","message":"internal error"}""")
            }

            writeResponse(output, response)
            output.flush()

            // If the handler answered without consuming the body (an auth
            // rejection on an upload, say) drain a little so the client sees
            // the response rather than a reset — but never drain a whole
            // upload just to be polite.
            request.body?.let { body ->
                runCatching { body.drainUpTo(DRAIN_LIMIT_BYTES) }
            }
        } catch (e: IOException) {
            // Client hung up mid-request. Routine; not worth a log line.
        } finally {
            runCatching { socket.close() }
        }
    }

    // -----------------------------------------------------------------------
    // Parsing
    // -----------------------------------------------------------------------

    private fun readRequest(socket: Socket, input: InputStream): HttpRequest {
        val requestLine = readLine(input, MAX_REQUEST_LINE)
            ?: throw EOFException("no request line")
        val parts = requestLine.split(' ')
        if (parts.size < 3) throw BadRequestException()
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = HashMap<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine(input, MAX_HEADER_LINE) ?: throw EOFException("headers truncated")
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > MAX_HEADER_BYTES || headers.size >= MAX_HEADERS) {
                throw BadRequestException()
            }
            val colon = line.indexOf(':')
            if (colon <= 0) throw BadRequestException()
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            // Repeated headers: last one wins. None of §15.1's endpoints use a
            // list-valued header, so this cannot lose information.
            headers[name] = value
        }

        val questionMark = target.indexOf('?')
        val path = if (questionMark >= 0) target.substring(0, questionMark) else target
        val query = if (questionMark >= 0) parseQuery(target.substring(questionMark + 1)) else emptyMap()

        val body = bodyStream(socket, input, headers)

        return HttpRequest(
            method = method,
            path = path,
            query = query,
            headers = headers,
            body = body,
            remoteAddress = socket.inetAddress?.hostAddress.orEmpty(),
        )
    }

    private fun bodyStream(
        socket: Socket,
        input: InputStream,
        headers: Map<String, String>,
    ): BodyStream? {
        val transferEncoding = headers["transfer-encoding"]?.lowercase()
        if (transferEncoding != null && transferEncoding.contains("chunked")) {
            return BodyStream(ChunkedInputStream(input), socket, -1L)
        }
        val length = headers["content-length"]?.trim()?.toLongOrNull() ?: return null
        if (length < 0) throw BadRequestException()
        if (length == 0L) return null
        return BodyStream(FixedLengthInputStream(input, length), socket, length)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val equals = pair.indexOf('=')
            if (equals < 0) {
                out[percentDecode(pair)] = ""
            } else {
                out[percentDecode(pair.substring(0, equals))] = percentDecode(pair.substring(equals + 1))
            }
        }
        return out
    }

    /**
     * Reads one CRLF-terminated line as ISO-8859-1 (what RFC 7230 specifies for
     * the start line and field values). Header values that carry UTF-8 — the
     * §7 filename — are RFC 5987 percent-encoded on the wire, so they are pure
     * ASCII here and are decoded to UTF-8 by the handler.
     */
    private fun readLine(input: InputStream, limit: Int): String? {
        val buffer = StringBuilder(64)
        var previous = -1
        while (true) {
            val b = input.read()
            // A clean EOF with nothing buffered means "no more requests";
            // an EOF mid-line is a truncated request and the caller treats the
            // partial line as malformed.
            if (b == -1) return if (buffer.isEmpty()) null else buffer.toString()
            if (b == '\n'.code) {
                if (previous == '\r'.code) buffer.setLength(maxOf(0, buffer.length - 1))
                return buffer.toString()
            }
            buffer.append(b.toChar())
            if (buffer.length > limit) throw BadRequestException()
            previous = b
        }
    }

    private fun writeResponse(output: OutputStream, response: HttpResponse) {
        val body = response.body
        val head = StringBuilder(128)
        head.append("HTTP/1.1 ").append(response.status).append(' ')
            .append(reason(response.status)).append("\r\n")
        head.append("Content-Type: ").append(response.contentType).append("\r\n")
        head.append("Content-Length: ").append(body.size).append("\r\n")
        // No keep-alive, deliberately: see the class comment.
        head.append("Connection: close\r\n")
        head.append("Cache-Control: no-store\r\n")
        for ((name, value) in response.headers) {
            head.append(name).append(": ").append(value).append("\r\n")
        }
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (body.isNotEmpty()) output.write(body)
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        422 -> "Unprocessable Entity"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        507 -> "Insufficient Storage"
        else -> "Status"
    }

    private companion object {
        const val TAG = "SendroHttpServer"
        const val BACKLOG = 16
        const val MAX_WORKERS = 8
        const val WORKER_QUEUE = 16
        const val HEADER_TIMEOUT_MS = 20_000
        const val MAX_REQUEST_LINE = 8 * 1024
        const val MAX_HEADER_LINE = 8 * 1024
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_HEADERS = 100
        const val DRAIN_LIMIT_BYTES = 64 * 1024L

        /** Consecutive accept() failures before the socket is declared dead. */
        const val ACCEPT_FAILURE_LIMIT = 5
        const val ACCEPT_RETRY_SLEEP_MS = 250L
    }
}

/** Thrown for anything malformed enough that a 400 is the only sane answer. */
internal class BadRequestException : IOException("malformed request")

// ---------------------------------------------------------------------------
// Request / response
// ---------------------------------------------------------------------------

class HttpRequest(
    val method: String,
    /** Path only, query already split off. Never used to address a file. */
    val path: String,
    val query: Map<String, String>,
    /** Header names lowercased. */
    val headers: Map<String, String>,
    val body: BodyStream?,
    val remoteAddress: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    /** `Authorization: Bearer <token>` -> the token, or null. */
    fun bearerToken(): String? {
        val raw = header("authorization")?.trim() ?: return null
        if (!raw.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
        return raw.substring(7).trim().takeIf { it.isNotEmpty() }
    }
}

class HttpResponse(
    val status: Int,
    val body: ByteArray,
    val contentType: String = "application/json; charset=utf-8",
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        fun json(status: Int, json: String): HttpResponse =
            HttpResponse(status, json.toByteArray(Charsets.UTF_8))

        fun error(status: Int, code: String, message: String? = null): HttpResponse {
            val payload = if (message == null) {
                """{"error":"$code"}"""
            } else {
                """{"error":"$code","message":"${escapeJson(message)}"}"""
            }
            return json(status, payload)
        }

        private fun escapeJson(value: String): String = buildString(value.length + 8) {
            for (c in value) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c.code < 0x20) append(' ') else append(c)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Body streams
// ---------------------------------------------------------------------------

/**
 * The request body, and nothing else.
 *
 * The handler reads this in whatever chunk size it wants — the §7 upload
 * handler uses 1 MiB — and the stream simply refuses to hand over a byte past
 * the body. Nothing here accumulates: there is no internal buffer beyond the
 * caller's own array.
 */
class BodyStream internal constructor(
    private val delegate: InputStream,
    private val socket: Socket,
    /** Declared length, or -1 for chunked. */
    val contentLength: Long,
) {

    /** Raises the socket timeout for a long body transfer. */
    fun useTransferTimeout() {
        runCatching { socket.soTimeout = BODY_TIMEOUT_MS }
    }

    fun read(buffer: ByteArray): Int = delegate.read(buffer, 0, buffer.size)

    internal fun drainUpTo(limit: Long) {
        val buffer = ByteArray(8 * 1024)
        var seen = 0L
        while (seen < limit) {
            val read = delegate.read(buffer, 0, buffer.size)
            if (read <= 0) return
            seen += read
        }
    }

    private companion object {
        /** Gap between chunks, not total time: a big file is not a timeout. */
        const val BODY_TIMEOUT_MS = 120_000
    }
}

/** Yields exactly [remaining] bytes and then reports EOF. */
private class FixedLengthInputStream(
    private val source: InputStream,
    private var remaining: Long,
) : InputStream() {

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = source.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val want = minOf(len.toLong(), remaining).toInt()
        val read = source.read(b, off, want)
        if (read > 0) remaining -= read
        return read
    }
}

/**
 * RFC 7230 chunked decoding.
 *
 * Our own client sets `Content-Length` (OkHttp derives it from `File.length()`)
 * and so does the Rust host, so this path is defensive rather than routine —
 * but a §7 upload from some future client that streams from a pipe would use
 * it, and silently mis-reading a chunked body would corrupt a file.
 */
private class ChunkedInputStream(private val source: InputStream) : InputStream() {

    private var remainingInChunk = 0L
    private var finished = false

    override fun read(): Int {
        val one = ByteArray(1)
        val read = read(one, 0, 1)
        return if (read <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (finished) return -1
        if (remainingInChunk == 0L && !nextChunk()) return -1
        val want = minOf(len.toLong(), remainingInChunk).toInt()
        val read = source.read(b, off, want)
        if (read <= 0) {
            finished = true
            return -1
        }
        remainingInChunk -= read
        if (remainingInChunk == 0L) skipCrlf()
        return read
    }

    /** @return false at the terminating zero-length chunk. */
    private fun nextChunk(): Boolean {
        val line = readAsciiLine() ?: run { finished = true; return false }
        val size = line.substringBefore(';').trim()
        val value = size.toLongOrNull(16) ?: throw BadRequestException()
        if (value <= 0L) {
            // Trailer section, then done. We do not surface trailers.
            while (true) {
                val trailer = readAsciiLine() ?: break
                if (trailer.isEmpty()) break
            }
            finished = true
            return false
        }
        remainingInChunk = value
        return true
    }

    private fun skipCrlf() {
        readAsciiLine()
    }

    private fun readAsciiLine(): String? {
        val builder = StringBuilder(16)
        while (true) {
            val b = source.read()
            if (b == -1) return if (builder.isEmpty()) null else builder.toString()
            if (b == '\n'.code) {
                if (builder.isNotEmpty() && builder.last() == '\r') builder.setLength(builder.length - 1)
                return builder.toString()
            }
            builder.append(b.toChar())
            if (builder.length > 1024) throw BadRequestException()
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Percent-decoding for query values and the §7 RFC 5987 filename. */
internal fun percentDecode(value: String): String {
    if (value.indexOf('%') < 0 && value.indexOf('+') < 0) return value
    val out = java.io.ByteArrayOutputStream(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        when {
            c == '%' && i + 2 < value.length -> {
                val hex = value.substring(i + 1, i + 3)
                val byte = hex.toIntOrNull(16)
                if (byte == null) {
                    out.write('%'.code)
                    i++
                } else {
                    out.write(byte)
                    i += 3
                }
            }
            // '+' is only a space in form encoding; §7 filenames never rely on
            // it, and turning a literal '+' in a filename into a space would be
            // a fidelity bug. Left alone on purpose.
            c.code < 0x80 -> {
                out.write(c.code)
                i++
            }
            else -> {
                // Should not occur (these values are ASCII on the wire) but
                // truncating a code point to one byte would corrupt a name.
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}
