package com.sendro.android.core.host

import com.sendro.android.core.Base64Url
import com.sendro.android.core.PairConfirmRequest
import com.sendro.android.core.PairStartRequest
import com.sendro.android.core.SendroCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The host half of PROTOCOL.md §4 — the same pairing the Windows app performs,
 * implemented here so a phone can pair to this device (§15.2).
 *
 * Nothing about the crypto is new: the code never crosses the wire, the client
 * proves knowledge of it with an HMAC over an HKDF-derived key, and this class
 * recomputes the same proof with the same [SendroCrypto] the client uses. If
 * the two implementations ever drift, pairing simply stops working — which is
 * the correct failure mode for a security check.
 *
 * §3 limits are enforced here: at most [MAX_SESSIONS] concurrent sessions, at
 * most [MAX_ATTEMPTS] confirm attempts each, 120 second expiry.
 */
class HostPairing(
    private val now: () -> Long = System::currentTimeMillis,
) {

    enum class Origin {
        /** Created by the TV's own pairing screen and rendered as a §13 QR. */
        QR,

        /** Created by a remote `POST /pair/start` — the typed-code path. */
        REMOTE,
    }

    data class Session(
        val pairingId: String,
        /** The six digits. Shown on screen; never sent anywhere. */
        val code: String,
        /** base64url, no padding — handed to the client in `pair/start`/the QR. */
        val salt: String,
        val origin: Origin,
        val createdAtMs: Long,
        val expiresAtMs: Long,
        val attempts: Int = 0,
        /** Who asked, when we know (the `pair/start` path tells us). */
        val peerName: String? = null,
        val peerPlatform: String? = null,
    ) {
        fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs
        fun secondsLeft(nowMs: Long): Int =
            (((expiresAtMs - nowMs) + 999) / 1000).coerceAtLeast(0).toInt()
    }

    sealed interface ConfirmResult {
        data class Ok(
            val deviceId: String,
            val deviceName: String,
            val platform: String,
            /** base64url, 32 bytes — returned to the client exactly once. */
            val token: String,
        ) : ConfirmResult

        /** 400: unknown or expired session. */
        data object BadSession : ConfirmResult

        /** 403: proof mismatch; the attempt has been counted. */
        data object WrongProof : ConfirmResult

        /** 429: attempts exhausted. */
        data object TooManyAttempts : ConfirmResult
    }

    private val random = SecureRandom()
    private val lock = Any()
    private val _sessions = MutableStateFlow<List<Session>>(emptyList())

    /** Live view for the pairing screen: the QR, the digits, the countdown. */
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    // -----------------------------------------------------------------------
    // Creating sessions
    // -----------------------------------------------------------------------

    /**
     * §13: the TV opens a session itself and renders it as a QR. The client
     * that scans it goes straight to `pair/confirm` — there is no `pair/start`
     * on this path, which is exactly why §4.2's `deviceName`/`platform` fields
     * are populated by the scanning client.
     */
    fun startForQr(): Session = synchronized(lock) {
        sweepLocked()
        // The QR session is the screen's own; replacing it is what "show a
        // fresh code" does, so an existing one is dropped rather than counted.
        val kept = _sessions.value.filterNot { it.origin == Origin.QR }
        val session = newSessionLocked(Origin.QR, null, null)
        _sessions.value = kept + session
        session
    }

    /**
     * §4.1. Returns null when [MAX_SESSIONS] are already live — the caller
     * answers 429.
     */
    fun startFromRemote(request: PairStartRequest): Session? = synchronized(lock) {
        sweepLocked()
        if (_sessions.value.size >= MAX_SESSIONS) return@synchronized null
        val session = newSessionLocked(
            Origin.REMOTE,
            request.deviceName.takeIf { it.isNotBlank() },
            request.platform.takeIf { it.isNotBlank() },
        )
        _sessions.value = _sessions.value + session
        session
    }

    private fun newSessionLocked(
        origin: Origin,
        peerName: String?,
        peerPlatform: String?,
    ): Session {
        val saltBytes = ByteArray(16).also { random.nextBytes(it) }
        // Uniform over 000000..999999 — nextInt(bound) is unbiased, unlike
        // nextInt() % 1_000_000.
        // Locale-pinned: "%06d" under a locale with non-ASCII digits would
        // render a code the user cannot type on a Latin keypad.
        val code = String.format(java.util.Locale.US, "%06d", random.nextInt(1_000_000))
        val createdAt = now()
        return Session(
            pairingId = java.util.UUID.randomUUID().toString(),
            code = code,
            salt = Base64Url.encode(saltBytes),
            origin = origin,
            createdAtMs = createdAt,
            expiresAtMs = createdAt + SESSION_TTL_MS,
            peerName = peerName,
            peerPlatform = peerPlatform,
        )
    }

    // -----------------------------------------------------------------------
    // Confirming
    // -----------------------------------------------------------------------

    /** §4.2 host side: recompute the proof and constant-time compare. */
    fun confirm(request: PairConfirmRequest): ConfirmResult = synchronized(lock) {
        sweepLocked()
        val index = _sessions.value.indexOfFirst { it.pairingId == request.pairingId }
        if (index < 0) return@synchronized ConfirmResult.BadSession
        val session = _sessions.value[index]
        if (session.isExpired(now())) {
            dropLocked(session.pairingId)
            return@synchronized ConfirmResult.BadSession
        }
        if (session.attempts >= MAX_ATTEMPTS) {
            dropLocked(session.pairingId)
            return@synchronized ConfirmResult.TooManyAttempts
        }

        val expected = SendroCrypto.pairingProof(
            code = session.code,
            saltBase64url = session.salt,
            pairingId = session.pairingId,
            deviceId = request.deviceId,
        )
        val supplied = request.proof

        // Compare the decoded bytes, not the strings: MessageDigest.isEqual is
        // the constant-time comparison on the platform, and comparing bytes
        // also means a differently-padded but equal proof still matches.
        val ok = expected != null &&
            MessageDigest.isEqual(
                Base64Url.decodeOrNull(expected) ?: ByteArray(0),
                Base64Url.decodeOrNull(supplied) ?: ByteArray(1),
            )

        if (!ok) {
            val counted = session.copy(attempts = session.attempts + 1)
            _sessions.value = _sessions.value.toMutableList().also { it[index] = counted }
            // §3: "failed confirm burns the session" once the cap is reached.
            if (counted.attempts >= MAX_ATTEMPTS) dropLocked(session.pairingId)
            return@synchronized ConfirmResult.WrongProof
        }

        dropLocked(session.pairingId)
        val token = Base64Url.encode(ByteArray(32).also { random.nextBytes(it) })
        ConfirmResult.Ok(
            deviceId = request.deviceId,
            deviceName = request.deviceName?.takeIf { it.isNotBlank() }
                ?: session.peerName
                ?: "Paired device",
            platform = request.platform?.takeIf { it.isNotBlank() }
                ?: session.peerPlatform
                ?: "unknown",
            token = token,
        )
    }

    /** Drops every session — used when the pairing screen closes. */
    fun clear() = synchronized(lock) {
        _sessions.value = emptyList()
    }

    fun clearQrSession() = synchronized(lock) {
        _sessions.value = _sessions.value.filterNot { it.origin == Origin.QR }
    }

    /** Expiry is lazy; call this from a ticking UI so the list stays honest. */
    fun sweep() = synchronized(lock) { sweepLocked() }

    private fun sweepLocked() {
        val nowMs = now()
        val live = _sessions.value.filterNot { it.isExpired(nowMs) }
        if (live.size != _sessions.value.size) _sessions.value = live
    }

    private fun dropLocked(pairingId: String) {
        _sessions.value = _sessions.value.filterNot { it.pairingId == pairingId }
    }

    companion object {
        const val SESSION_TTL_MS = 120_000L
        const val MAX_SESSIONS = 3
        const val MAX_ATTEMPTS = 5
    }
}
