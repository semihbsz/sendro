package com.sendro.android.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing proof math (PROTOCOL.md §4.2), streaming SHA-256, base64url helpers.
 *
 * Android has no CryptoKit, and `javax.crypto` has no HKDF before API 33
 * (`HKDFParameterSpec` landed in Android 14 / conscrypt), so RFC 5869 is
 * implemented here on top of `Mac("HmacSHA256")`. It is twelve lines and has a
 * unit test with the RFC's own vectors — a dependency would be worse.
 */
object SendroCrypto {

    private const val HMAC_SHA256 = "HmacSHA256"
    private const val HASH_LEN = 32

    /** §4.2 info string. Changing it breaks pairing with every host. */
    private const val PAIR_INFO = "sendro-pair-v1"

    // -----------------------------------------------------------------------
    // HKDF-SHA256 (RFC 5869)
    // -----------------------------------------------------------------------

    /** RFC 5869 §2.2 — PRK = HMAC(salt, ikm). */
    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        // "if not provided, [salt] is set to a string of HashLen zeros"
        val key = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(key, HMAC_SHA256))
        return mac.doFinal(ikm)
    }

    /** RFC 5869 §2.3 — T(n) = HMAC(PRK, T(n-1) | info | n). */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        require(outputLength > 0) { "HKDF output length must be positive" }
        require(outputLength <= 255 * HASH_LEN) { "HKDF output length too large" }
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(prk, HMAC_SHA256))
        val output = ByteArray(outputLength)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < outputLength) {
            mac.reset()
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, outputLength - written)
            System.arraycopy(previous, 0, output, written, take)
            written += take
            counter++
        }
        return output
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        hkdfExpand(hkdfExtract(salt, ikm), info, length)

    // -----------------------------------------------------------------------
    // §4.2 proof
    // -----------------------------------------------------------------------

    /**
     * PROTOCOL.md §4.2:
     * ```
     * K     = HKDF-SHA256(ikm = UTF8(code), salt = salt, info = "sendro-pair-v1", len = 32)
     * proof = base64url( HMAC-SHA256(key = K, message = UTF8(pairingId + ":" + deviceId)) )
     * ```
     * @return the base64url (no padding) proof, or null when the salt is not
     *   decodable base64url — refusing beats burning a pairing attempt.
     */
    fun pairingProof(
        code: String,
        saltBase64url: String,
        pairingId: String,
        deviceId: String,
    ): String? {
        val salt = Base64Url.decodeOrNull(saltBase64url) ?: return null
        val key = hkdfSha256(
            ikm = code.toByteArray(Charsets.UTF_8),
            salt = salt,
            info = PAIR_INFO.toByteArray(Charsets.UTF_8),
            length = 32,
        )
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(key, HMAC_SHA256))
        val proof = mac.doFinal("$pairingId:$deviceId".toByteArray(Charsets.UTF_8))
        return Base64Url.encode(proof)
    }

    // -----------------------------------------------------------------------
    // Streaming SHA-256
    // -----------------------------------------------------------------------

    /** Chunk size for every hash/copy loop in the app. Never load a whole file. */
    const val CHUNK_BYTES = 1024 * 1024

    /**
     * Hash a whole file in [CHUNK_BYTES] chunks. Blocking — call from IO.
     */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHexLower()
    }

    /**
     * Hash a stream in [CHUNK_BYTES] chunks, reporting bytes consumed so a
     * caller can drive a progress bar. Does not close [input].
     */
    fun sha256Hex(input: InputStream, onProgress: (Long) -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
            total += read
            onProgress(total)
        }
        return digest.digest().toHexLower()
    }
}

/**
 * Incremental SHA-256. Feed arriving chunks with [update], then call [hexDigest]
 * exactly once. Not thread-safe: callers serialise access (the download loop
 * owns one instance on one coroutine).
 */
class StreamingSha256 {
    private val digest = MessageDigest.getInstance("SHA-256")

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        digest.update(bytes, offset, length)
    }

    fun hexDigest(): String = digest.digest().toHexLower()
}

/** Lowercase hex, the form §6.1 `sha256` and §7 `X-Sendro-Sha256` use. */
fun ByteArray.toHexLower(): String {
    val chars = CharArray(size * 2)
    val table = "0123456789abcdef"
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        chars[i * 2] = table[v ushr 4]
        chars[i * 2 + 1] = table[v and 0x0F]
    }
    return String(chars)
}

/**
 * base64url (RFC 4648 §5), no padding — the encoding §3/§4 use for the device
 * token, the salt and the pairing proof.
 *
 * `java.util.Base64` (API 26+, which is our minSdk) rather than
 * `android.util.Base64` on purpose: the java.util one is real JDK code, so the
 * pairing-proof unit tests run on the JVM without Robolectric or a stubbed
 * android.jar returning null.
 */
object Base64Url {

    private val encoder: java.util.Base64.Encoder =
        java.util.Base64.getUrlEncoder().withoutPadding()
    private val decoder: java.util.Base64.Decoder = java.util.Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /**
     * Decode base64url. Padding-tolerant (the RFC 4648 §5 decoder accepts
     * both), and standard base64 with `+`/`/` is accepted too — some QR
     * generators are careless. Returns null for anything malformed.
     */
    fun decodeOrNull(value: String): ByteArray? {
        val normalised = value.trim().replace('+', '-').replace('/', '_').trimEnd('=')
        if (normalised.isEmpty()) return null
        return try {
            decoder.decode(normalised)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
