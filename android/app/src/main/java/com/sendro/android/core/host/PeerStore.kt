package com.sendro.android.core.host

import android.content.Context
import com.sendro.android.core.SecurePrefs
import com.sendro.android.core.SendroJson
import com.sendro.android.core.toHexLower
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.security.MessageDigest

/**
 * Devices that paired **to** this one — the mirror image of `PairedHostStore`.
 *
 * `PairedHostStore` remembers hosts this device is a client of and keeps their
 * bearer tokens. This remembers clients of *this* device and keeps only
 * `SHA-256(token)`, exactly as §3 requires of a host: "The host stores only
 * SHA-256(deviceToken) at rest, plus device metadata."
 *
 * The verifier is not the credential, but it is still stored in
 * EncryptedSharedPreferences — it costs nothing and it keeps every
 * pairing-related secret in one place with one threat model.
 */
@Serializable
data class Peer(
    val deviceId: String,
    val name: String,
    /** `ios` | `android` | `androidtv` | `windows` — informational (§15.1). */
    val platform: String,
    /** lowercase hex of SHA-256(token). The token itself is never stored. */
    val tokenSha256: String,
    val pairedAtMs: Long,
    var lastSeenMs: Long = 0,
)

class PeerStore(context: Context) {

    private val opened = SecurePrefs.open(context, FILE)
    private val prefs = opened.prefs
    val isEncrypted: Boolean = opened.encrypted

    private val _peers = MutableStateFlow(load())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    @Synchronized
    fun add(deviceId: String, name: String, platform: String, token: String): Peer {
        val peer = Peer(
            deviceId = deviceId,
            name = name,
            platform = platform,
            tokenSha256 = hash(token),
            pairedAtMs = System.currentTimeMillis(),
        )
        // Re-pairing the same device replaces its verifier, which invalidates
        // the previous token — the correct behaviour for "pair again".
        val next = _peers.value.filterNot { it.deviceId == deviceId } + peer
        _peers.value = next
        save(next)
        return peer
    }

    @Synchronized
    fun remove(deviceId: String) {
        val next = _peers.value.filterNot { it.deviceId == deviceId }
        _peers.value = next
        save(next)
    }

    @Synchronized
    fun clear() {
        _peers.value = emptyList()
        save(emptyList())
    }

    /**
     * Resolves a bearer token to a peer.
     *
     * Every candidate is compared with [MessageDigest.isEqual] and the loop
     * does NOT break early, so the time taken does not depend on which peer
     * matched or on how many leading bytes of a wrong token were right.
     */
    fun authenticate(token: String): Peer? {
        if (token.isEmpty()) return null
        val supplied = sha256(token)
        var match: Peer? = null
        for (peer in _peers.value) {
            val stored = hexToBytes(peer.tokenSha256) ?: continue
            if (MessageDigest.isEqual(stored, supplied)) match = peer
        }
        return match
    }

    /**
     * Purely cosmetic ("last seen"); never part of an auth decision.
     *
     * Rate-limited because a receive-only peer pings every ten seconds, and
     * rewriting an encrypted preferences file that often for a timestamp
     * nobody is watching is pure wear.
     */
    @Synchronized
    fun touch(deviceId: String) {
        val current = _peers.value
        val index = current.indexOfFirst { it.deviceId == deviceId }
        if (index < 0) return
        if (System.currentTimeMillis() - current[index].lastSeenMs < TOUCH_INTERVAL_MS) return
        val next = current.toMutableList()
        next[index] = next[index].copy(lastSeenMs = System.currentTimeMillis())
        _peers.value = next
        save(next)
    }

    private fun hash(token: String): String = sha256(token).toHexLower()

    private fun sha256(token: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun load(): List<Peer> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { SendroJson.decodeFromString<List<Peer>>(raw) }
            .getOrDefault(emptyList())
    }

    private fun save(list: List<Peer>) {
        prefs.edit().putString(KEY, SendroJson.encodeToString(list)).apply()
    }

    private companion object {
        const val FILE = "sendro_peers_v1"
        const val KEY = "peers"
        const val TOUCH_INTERVAL_MS = 60_000L
    }
}
