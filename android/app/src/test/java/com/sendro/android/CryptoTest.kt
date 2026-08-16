package com.sendro.android

import com.sendro.android.core.Base64Url
import com.sendro.android.core.SendroCrypto
import com.sendro.android.core.StreamingSha256
import com.sendro.android.core.toHexLower
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pairing proof is the one place a silent bug means "pairing just doesn't
 * work and nobody can tell you why", so HKDF is pinned to RFC 5869's own test
 * vectors rather than to whatever this implementation happens to produce.
 *
 * These run on the JVM: nothing here touches android.* (Base64Url is
 * deliberately java.util.Base64 for exactly this reason).
 */
class CryptoTest {

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** RFC 5869 Appendix A.1 — Test Case 1 (SHA-256, basic). */
    @Test
    fun `hkdf matches rfc5869 test case 1`() {
        val ikm = hex("0b".repeat(22))
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")

        val prk = SendroCrypto.hkdfExtract(salt, ikm)
        assertEquals(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            prk.toHexLower(),
        )

        val okm = SendroCrypto.hkdfExpand(prk, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHexLower(),
        )
    }

    /** RFC 5869 Appendix A.3 — Test Case 3 (zero-length salt and info). */
    @Test
    fun `hkdf matches rfc5869 test case 3`() {
        val ikm = hex("0b".repeat(22))
        val prk = SendroCrypto.hkdfExtract(ByteArray(0), ikm)
        assertEquals(
            "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04",
            prk.toHexLower(),
        )
        val okm = SendroCrypto.hkdfExpand(prk, ByteArray(0), 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            okm.toHexLower(),
        )
    }

    /**
     * PROTOCOL.md §4.2 end to end. The expected value is not magic: it is
     * recomputed here with the same primitives from an independent path
     * (HKDF -> HMAC), so a refactor that breaks either half fails.
     */
    @Test
    fun `pairing proof is deterministic and base64url`() {
        val salt = Base64Url.encode(hex("000102030405060708090a0b0c0d0e0f"))
        val proof = SendroCrypto.pairingProof(
            code = "482913",
            saltBase64url = salt,
            pairingId = "8f14e45f-ea8f-4b2b-9c1d-2b0f8a1c1234",
            deviceId = "3d4a1f22-1111-2222-3333-444455556666",
        )
        assertNotNull(proof)

        val key = SendroCrypto.hkdfSha256(
            ikm = "482913".toByteArray(Charsets.UTF_8),
            salt = hex("000102030405060708090a0b0c0d0e0f"),
            info = "sendro-pair-v1".toByteArray(Charsets.UTF_8),
            length = 32,
        )
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        }
        val expected = Base64Url.encode(
            mac.doFinal(
                "8f14e45f-ea8f-4b2b-9c1d-2b0f8a1c1234:3d4a1f22-1111-2222-3333-444455556666"
                    .toByteArray(Charsets.UTF_8),
            ),
        )
        assertEquals(expected, proof)
        // base64url, no padding: 32 bytes -> 43 chars, no '+', '/' or '='.
        assertEquals(43, proof!!.length)
        assertEquals(false, proof.contains('+'))
        assertEquals(false, proof.contains('/'))
        assertEquals(false, proof.contains('='))
    }

    @Test
    fun `pairing proof refuses a malformed salt`() {
        assertNull(
            SendroCrypto.pairingProof(
                code = "123456",
                saltBase64url = "not base64!!!",
                pairingId = "p",
                deviceId = "d",
            ),
        )
    }

    @Test
    fun `base64url round trips without padding`() {
        val bytes = ByteArray(32) { it.toByte() }
        val encoded = Base64Url.encode(bytes)
        assertEquals(43, encoded.length)
        assertEquals(bytes.toHexLower(), Base64Url.decodeOrNull(encoded)!!.toHexLower())
        // Padded and standard-alphabet input must still decode.
        assertEquals(bytes.toHexLower(), Base64Url.decodeOrNull("$encoded=")!!.toHexLower())
    }

    @Test
    fun `streaming sha256 matches a one shot digest`() {
        val data = ByteArray(3 * 1024 + 17) { (it % 251).toByte() }
        val streaming = StreamingSha256()
        var offset = 0
        while (offset < data.size) {
            val take = minOf(512, data.size - offset)
            streaming.update(data, offset, take)
            offset += take
        }
        val oneShot = java.security.MessageDigest.getInstance("SHA-256").digest(data).toHexLower()
        assertEquals(oneShot, streaming.hexDigest())
    }
}
