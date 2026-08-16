package com.sendro.android.core.host

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.sendro.android.core.AppPaths
import com.sendro.android.core.DeviceKind
import com.sendro.android.core.FileNames
import com.sendro.android.core.HistoryStore
import com.sendro.android.core.InfoResponse
import com.sendro.android.core.MediaSaver
import com.sendro.android.core.MessageCenter
import com.sendro.android.core.Notifier
import com.sendro.android.core.PairConfirmRequest
import com.sendro.android.core.PairStartRequest
import com.sendro.android.core.SENDRO_MESSAGE_BYTE_LIMIT
import com.sendro.android.core.SENDRO_PROTOCOL_VERSION
import com.sendro.android.core.SaveResult
import com.sendro.android.core.SendMessageRequest
import com.sendro.android.core.SendroCrypto
import com.sendro.android.core.SendroJson
import com.sendro.android.core.SendroMessage
import com.sendro.android.core.SettingsStore
import com.sendro.android.core.toHexLower
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import java.util.UUID

/**
 * PROTOCOL.md §15 — the reduced host.
 *
 * A TV only ever receives, so this implements exactly the six endpoints §15.1
 * marks required and answers `404 not_found` to everything else, including the
 * outbox. That 404 is not an omission: it is the documented signal a client
 * uses to decide "this peer cannot send to me, do not long-poll it".
 *
 * The client half of the app keeps running unchanged alongside this. A TV can
 * be a client of the PC (offers, resume, Range) *and* a host for a phone
 * (uploads) at the same time, which is exactly what §15.3 describes.
 */
class ReceiverHost(
    private val context: Context,
    private val settings: SettingsStore,
    private val paths: AppPaths,
    private val mediaSaver: MediaSaver,
    private val history: HistoryStore,
    private val messages: MessageCenter,
    private val notifier: Notifier,
    private val peers: PeerStore,
    val pairing: HostPairing,
    private val advertiser: Advertiser,
    /** Called whenever the host starts or stops, so the FGS can follow. */
    private val onStateChanged: () -> Unit = {},
) {

    sealed interface State {
        data object Stopped : State
        data class Running(val port: Int, val addresses: List<String>) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Any()
    private var server: HttpServer? = null

    val isRunning: Boolean get() = _state.value is State.Running

    /** The port actually bound, or the default when stopped. */
    val port: Int get() = (_state.value as? State.Running)?.port ?: DEFAULT_PORT

    val deviceId: String get() = settings.clientDeviceId
    val deviceName: String get() = settings.current.deviceName
    val platform: String get() = DeviceKind.platformString(context)

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    fun start() {
        synchronized(lock) {
            if (server?.isRunning == true) return
            // Single-use: a stopped HttpServer has shut its worker pool down.
            val fresh = HttpServer(DEFAULT_PORT, PORT_SCAN_RANGE) { request -> route(request) }
            if (!fresh.start()) {
                _state.value = State.Failed("No free port in $DEFAULT_PORT..${DEFAULT_PORT + PORT_SCAN_RANGE}")
                onStateChanged()
                return
            }
            server = fresh
            _state.value = State.Running(fresh.boundPort, Advertiser.localIpv4Addresses())
        }
        advertise()
        onStateChanged()
    }

    fun stop() {
        synchronized(lock) {
            advertiser.unregister()
            server?.stop()
            server = null
            pairing.clear()
            _state.value = State.Stopped
        }
        onStateChanged()
    }

    /**
     * A network change invalidates both the advertisement (it is bound to the
     * old interface) and the addresses shown on the pairing screen. The socket
     * itself is bound to 0.0.0.0 and survives, so only mDNS is redone.
     */
    fun onNetworkChanged() {
        synchronized(lock) {
            val running = _state.value as? State.Running ?: return
            _state.value = State.Running(running.port, Advertiser.localIpv4Addresses())
        }
        advertise()
    }

    private fun advertise() {
        val running = _state.value as? State.Running ?: return
        advertiser.register(
            instanceName = deviceName,
            port = running.port,
            deviceId = deviceId,
            deviceName = deviceName,
            platform = platform,
        )
    }

    /**
     * The §13 URL for the TV's QR. Same shape the Windows host renders, so the
     * phone's existing scanner needs no change at all.
     */
    fun pairingUrl(session: HostPairing.Session, address: String): String {
        val running = _state.value as? State.Running
        val effectivePort = running?.port ?: DEFAULT_PORT
        return buildString {
            append("sendro://pair?v=1")
            append("&h=").append(encodeComponent(address))
            append("&p=").append(effectivePort)
            append("&id=").append(encodeComponent(deviceId))
            append("&n=").append(encodeComponent(deviceName))
            append("&pid=").append(encodeComponent(session.pairingId))
            append("&s=").append(encodeComponent(session.salt))
            append("&c=").append(encodeComponent(session.code))
        }
    }

    // -----------------------------------------------------------------------
    // Routing (§15.1)
    // -----------------------------------------------------------------------

    private fun route(request: HttpRequest): HttpResponse {
        val path = request.path.trimEnd('/').ifEmpty { "/" }
        return when {
            path == "/api/v1/info" && request.method == "GET" -> info()

            path == "/api/v1/pair/start" && request.method == "POST" -> pairStart(request)

            path == "/api/v1/pair/confirm" && request.method == "POST" -> pairConfirm(request)

            path == "/api/v1/ping" && request.method == "GET" -> authenticated(request) {
                HttpResponse.json(200, """{"ok":true,"deviceName":"${jsonEscape(deviceName)}"}""")
            }

            path == "/api/v1/upload" && request.method == "POST" -> authenticated(request) { peer ->
                upload(request, peer)
            }

            path == "/api/v1/messages" && request.method == "POST" -> authenticated(request) { peer ->
                message(request, peer)
            }

            // §15.1: "a receiver never offers files; return 404 not_found".
            // This is load-bearing — it is how a client learns this peer is
            // receive-only and stops long-polling it.
            else -> HttpResponse.error(404, "not_found")
        }
    }

    private inline fun authenticated(
        request: HttpRequest,
        block: (Peer) -> HttpResponse,
    ): HttpResponse {
        val token = request.bearerToken()
            ?: return HttpResponse.error(401, "unauthorized")
        val peer = peers.authenticate(token)
            ?: return HttpResponse.error(401, "unauthorized")
        peers.touch(peer.deviceId)
        return block(peer)
    }

    // -----------------------------------------------------------------------
    // §5 info
    // -----------------------------------------------------------------------

    private fun info(): HttpResponse {
        val body = SendroJson.encodeToString(
            InfoResponse(
                app = "sendro",
                protocolVersion = SENDRO_PROTOCOL_VERSION,
                deviceId = deviceId,
                deviceName = deviceName,
                platform = platform,
                apiPort = port,
            ),
        )
        return HttpResponse.json(200, body)
    }

    // -----------------------------------------------------------------------
    // §4 pairing
    // -----------------------------------------------------------------------

    private fun pairStart(request: HttpRequest): HttpResponse {
        val payload = readJsonBody(request) ?: return HttpResponse.error(400, "bad_request")
        val parsed = runCatching { SendroJson.decodeFromString<PairStartRequest>(payload) }
            .getOrNull() ?: return HttpResponse.error(400, "bad_request")
        if (parsed.deviceId.isBlank()) return HttpResponse.error(400, "bad_request")

        val session = pairing.startFromRemote(parsed)
            ?: return HttpResponse.error(429, "rate_limited", "Too many pairing sessions in flight.")

        // The user has to see the digits to type them, so a remote pair/start
        // is only useful while the pairing screen is up. It is not an error if
        // it is not — the session simply expires unused.
        notifier.notifyPairingRequest(parsed.deviceName, session.code)

        val body = """{"pairingId":"${jsonEscape(session.pairingId)}",""" +
            """"salt":"${jsonEscape(session.salt)}",""" +
            """"expiresInSeconds":${HostPairing.SESSION_TTL_MS / 1000}}"""
        return HttpResponse.json(200, body)
    }

    private fun pairConfirm(request: HttpRequest): HttpResponse {
        val payload = readJsonBody(request) ?: return HttpResponse.error(400, "bad_request")
        val parsed = runCatching { SendroJson.decodeFromString<PairConfirmRequest>(payload) }
            .getOrNull() ?: return HttpResponse.error(400, "bad_request")

        return when (val result = pairing.confirm(parsed)) {
            HostPairing.ConfirmResult.BadSession ->
                HttpResponse.error(400, "expired", "That pairing session is unknown or expired.")

            HostPairing.ConfirmResult.WrongProof ->
                HttpResponse.error(403, "unauthorized", "Wrong code.")

            HostPairing.ConfirmResult.TooManyAttempts ->
                HttpResponse.error(429, "rate_limited", "Too many attempts.")

            is HostPairing.ConfirmResult.Ok -> {
                // The verifier is stored BEFORE the token is handed out, so a
                // crash between the two can only cost the client a retry — it
                // can never leave a valid token with no record of it.
                peers.add(result.deviceId, result.deviceName, result.platform, result.token)
                notifier.notifyPaired(result.deviceName)
                val body = """{"deviceToken":"${jsonEscape(result.token)}","host":""" +
                    """{"deviceId":"${jsonEscape(deviceId)}",""" +
                    """"deviceName":"${jsonEscape(deviceName)}",""" +
                    """"platform":"${jsonEscape(platform)}"}}"""
                HttpResponse.json(200, body)
            }
        }
    }

    // -----------------------------------------------------------------------
    // §7 upload — the reason this whole thing exists
    // -----------------------------------------------------------------------

    private fun upload(request: HttpRequest, peer: Peer): HttpResponse {
        val body = request.body ?: return HttpResponse.error(400, "bad_request", "No body.")
        val declaredLength = body.contentLength

        val rawName = request.header("X-Sendro-File-Name")
            ?: return HttpResponse.error(400, "bad_request", "Missing X-Sendro-File-Name.")
        val fileName = decodeRfc5987(rawName)
        if (fileName.isBlank()) return HttpResponse.error(400, "bad_request", "Empty file name.")

        val expectedSha = request.header("X-Sendro-Sha256")?.trim()?.lowercase()
        if (expectedSha.isNullOrEmpty() || expectedSha.length != 64) {
            return HttpResponse.error(400, "bad_request", "Missing or malformed X-Sendro-Sha256.")
        }

        // §9 insufficient_storage: refuse before writing rather than filling
        // the disk and failing at the last byte.
        if (declaredLength > 0) {
            val free = freeBytes()
            if (free != null && free < declaredLength + STORAGE_MARGIN_BYTES) {
                return HttpResponse.error(
                    507,
                    "insufficient_storage",
                    "Not enough free space on this device.",
                )
            }
        }

        val pending = mediaSaver.beginSave(
            displayName = fileName,
            declaredMimeType = null,
            useAlbum = settings.current.addToSendroAlbum,
        ) ?: return HttpResponse.error(500, "bad_request", "Could not open a place to save it.")

        body.useTransferTimeout()

        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            // THE byte loop. 1 MiB at a time, straight from the socket into the
            // destination stream, hashing exactly what is written. Nothing here
            // accumulates: an 8 GB movie costs one buffer.
            val buffer = ByteArray(SendroCrypto.CHUNK_BYTES)
            while (true) {
                val read = body.read(buffer)
                if (read <= 0) break
                pending.output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                written += read
                if (declaredLength > 0 && written > declaredLength) {
                    // More bytes than promised: the framing is wrong, so the
                    // hash would be meaningless.
                    pending.abort()
                    return HttpResponse.error(400, "bad_request", "Body longer than Content-Length.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "upload aborted after $written bytes", e)
            pending.abort()
            return HttpResponse.error(400, "bad_request", "Transfer interrupted.")
        }

        if (declaredLength > 0 && written != declaredLength) {
            pending.abort()
            return HttpResponse.error(400, "bad_request", "Body shorter than Content-Length.")
        }

        val transferId = UUID.randomUUID().toString()
        val actual = digest.digest().toHexLower()
        if (!MessageDigest.isEqual(actual.toByteArray(), expectedSha.toByteArray())) {
            // §7: "422 {"error":"integrity"} and deletes the partial on
            // mismatch". The bytes never became visible in the first place.
            pending.abort()
            history.add(
                transferId = transferId,
                fileName = fileName,
                sizeBytes = written,
                senderName = peer.name,
                outcome = "failed",
                errorMessage = "integrity",
            )
            notifier.notifyTransferFailed(transferId, fileName, "integrity check")
            return HttpResponse.error(422, "integrity", "SHA-256 did not match.")
        }

        val saved = pending.commit()
        return when (saved) {
            is SaveResult.Gallery -> {
                history.add(
                    transferId = transferId,
                    fileName = saved.displayName,
                    sizeBytes = written,
                    senderName = peer.name,
                    outcome = "completed",
                    savedTo = if (MediaSaver.mediaKind(saved.displayName) != null) "photos" else "files",
                    mediaUri = saved.uri.toString(),
                )
                notifier.notifyTransferFinished(transferId, saved.displayName, "photos")
                ok(saved.displayName)
            }

            is SaveResult.Files -> {
                history.add(
                    transferId = transferId,
                    fileName = saved.file.name,
                    sizeBytes = written,
                    senderName = peer.name,
                    outcome = "completed",
                    savedTo = "files",
                    localName = saved.file.name,
                )
                notifier.notifyTransferFinished(transferId, saved.file.name, "files")
                ok(saved.file.absolutePath)
            }

            is SaveResult.Failed -> HttpResponse.error(500, "bad_request", saved.message)
            SaveResult.NeedsStoragePermission ->
                HttpResponse.error(500, "bad_request", "This device will not let Sendro save files.")
        }
    }

    private fun ok(savedPath: String): HttpResponse =
        HttpResponse.json(200, """{"ok":true,"savedPath":"${jsonEscape(savedPath)}"}""")

    // -----------------------------------------------------------------------
    // §11.2 messages
    // -----------------------------------------------------------------------

    private fun message(request: HttpRequest, peer: Peer): HttpResponse {
        if (request.body == null) return HttpResponse.error(400, "bad_request")
        val payload = readJsonBody(request, limit = SENDRO_MESSAGE_BYTE_LIMIT + 4096)
            ?: return HttpResponse.error(413, "bad_request", "message too long")
        val parsed = runCatching { SendroJson.decodeFromString<SendMessageRequest>(payload) }
            .getOrNull() ?: return HttpResponse.error(400, "bad_request")
        if (parsed.text.toByteArray(Charsets.UTF_8).size > SENDRO_MESSAGE_BYTE_LIMIT) {
            return HttpResponse.error(413, "bad_request", "message too long")
        }
        // Straight into the RAM inbox and nowhere else. Never logged, never
        // written to history, never persisted — §11's contract is the same
        // whether the text arrived by poll or by push.
        messages.receive(
            listOf(
                SendroMessage(
                    messageId = UUID.randomUUID().toString(),
                    text = parsed.text,
                    sentAtMs = System.currentTimeMillis(),
                    senderName = peer.name,
                ),
            ),
        )
        notifier.notifyMessage(peer.name)
        return HttpResponse.json(200, """{"ok":true}""")
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Reads a small JSON body fully. Only used for the four endpoints whose
     * bodies are bounded — the upload never comes through here.
     */
    private fun readJsonBody(request: HttpRequest, limit: Int = 64 * 1024): String? {
        val body = request.body ?: return null
        if (body.contentLength > limit) return null
        val out = java.io.ByteArrayOutputStream(minOf(limit, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = body.read(buffer)
            if (read <= 0) break
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun freeBytes(): Long? = runCatching {
        val stat = StatFs(paths.received.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrNull()

    companion object {
        private const val TAG = "SendroReceiverHost"
        const val DEFAULT_PORT = 48800
        const val PORT_SCAN_RANGE = 20
        private const val STORAGE_MARGIN_BYTES = 200L * 1024 * 1024

        /**
         * Decodes the §7 / §8 `X-Sendro-File-Name` header.
         *
         * The wire form is RFC 5987: `UTF-8''%C3%87ekmek%C3%B6y....MOV`. A
         * plain (already-decoded) name is accepted too, because being strict
         * about it would only ever break a transfer over cosmetics — but the
         * result is always run through [FileNames.sanitize] before it touches
         * a filesystem.
         */
        fun decodeRfc5987(raw: String): String {
            val trimmed = raw.trim()
            val marker = trimmed.indexOf("''")
            val encoded = if (marker >= 0) trimmed.substring(marker + 2) else trimmed
            val charset = if (marker >= 0) trimmed.substring(0, marker).substringBefore('\'') else "UTF-8"
            val decoded = percentDecode(encoded)
            // Only UTF-8 is produced by any Sendro client; anything else is
            // taken verbatim rather than guessed at.
            return FileNames.sanitize(
                if (charset.equals("UTF-8", ignoreCase = true)) decoded else encoded,
            )
        }

        fun jsonEscape(value: String): String = buildString(value.length + 8) {
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

        private fun encodeComponent(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
