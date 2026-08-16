package com.sendro.android

import com.sendro.android.core.Base64Url
import com.sendro.android.core.PairConfirmRequest
import com.sendro.android.core.PairStartRequest
import com.sendro.android.core.SendroCrypto
import com.sendro.android.core.host.HostPairing
import com.sendro.android.core.host.ReceiverHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROTOCOL.md §15.1 host-side pairing.
 *
 * These matter more than most: the host and the client compute the proof in
 * two different places, and the only thing keeping them honest is that both
 * go through [SendroCrypto]. A test that pairs "a client" against the real
 * host implementation is the cheapest way to catch a drift.
 *
 * Pure JVM — no android.* anywhere in this path.
 */
class HostPairingTest {

    private var now = 1_700_000_000_000L
    private fun pairing() = HostPairing(now = { now })

    /** Exactly what the phone does in §4.2. */
    private fun clientProof(session: HostPairing.Session, deviceId: String): String =
        SendroCrypto.pairingProof(
            code = session.code,
            saltBase64url = session.salt,
            pairingId = session.pairingId,
            deviceId = deviceId,
        )!!

    @Test
    fun `a correct proof pairs and yields a 32 byte token`() {
        val host = pairing()
        val session = host.startForQr()
        val result = host.confirm(
            PairConfirmRequest(
                pairingId = session.pairingId,
                deviceId = "device-a",
                proof = clientProof(session, "device-a"),
                deviceName = "Semih's phone",
                platform = "android",
            ),
        )
        assertTrue(result is HostPairing.ConfirmResult.Ok)
        val ok = result as HostPairing.ConfirmResult.Ok
        assertEquals("Semih's phone", ok.deviceName)
        // base64url, no padding: 32 bytes -> 43 chars.
        assertEquals(43, ok.token.length)
        assertEquals(32, Base64Url.decodeOrNull(ok.token)!!.size)
        // The session is consumed, so a replay cannot mint a second token.
        assertTrue(host.sessions.value.isEmpty())
    }

    @Test
    fun `a proof computed for a different device is rejected`() {
        val host = pairing()
        val session = host.startForQr()
        val result = host.confirm(
            PairConfirmRequest(
                pairingId = session.pairingId,
                deviceId = "device-b",
                // Proof bound to device-a; §4.2 signs "pairingId:deviceId".
                proof = clientProof(session, "device-a"),
            ),
        )
        assertEquals(HostPairing.ConfirmResult.WrongProof, result)
    }

    @Test
    fun `attempts are capped and then the session is burned`() {
        val host = pairing()
        val session = host.startForQr()
        val wrong = PairConfirmRequest(session.pairingId, "device-a", "AAAA")
        repeat(HostPairing.MAX_ATTEMPTS) {
            assertEquals(HostPairing.ConfirmResult.WrongProof, host.confirm(wrong))
        }
        // Burned: even the RIGHT proof cannot rescue it now.
        assertEquals(
            HostPairing.ConfirmResult.BadSession,
            host.confirm(
                PairConfirmRequest(
                    session.pairingId,
                    "device-a",
                    clientProof(session, "device-a"),
                ),
            ),
        )
    }

    @Test
    fun `a session expires after 120 seconds`() {
        val host = pairing()
        val session = host.startForQr()
        now += HostPairing.SESSION_TTL_MS + 1
        assertEquals(
            HostPairing.ConfirmResult.BadSession,
            host.confirm(
                PairConfirmRequest(
                    session.pairingId,
                    "device-a",
                    clientProof(session, "device-a"),
                ),
            ),
        )
    }

    @Test
    fun `concurrent remote sessions are capped`() {
        val host = pairing()
        repeat(HostPairing.MAX_SESSIONS) {
            assertNotNull(host.startFromRemote(PairStartRequest("d$it", "Peer $it")))
        }
        assertNull(host.startFromRemote(PairStartRequest("overflow", "Peer")))
    }

    @Test
    fun `the QR session is replaced rather than stacked`() {
        val host = pairing()
        repeat(5) { host.startForQr() }
        assertEquals(1, host.sessions.value.size)
    }

    @Test
    fun `codes are six ASCII digits`() {
        val host = pairing()
        repeat(50) {
            val code = host.startForQr().code
            assertEquals(6, code.length)
            assertTrue(code.all { c -> c in '0'..'9' })
        }
    }

    // -- §7 / §8 header decoding ------------------------------------------

    @Test
    fun `rfc5987 file names round-trip through the receiver`() {
        val original = "Çekmeköy Reşadiye Drone.MOV"
        val onWire = "UTF-8''" + com.sendro.android.core.SendroClient.rfc5987Encode(original)
        assertEquals(original, ReceiverHost.decodeRfc5987(onWire))
    }

    @Test
    fun `a plain file name header is accepted and sanitised`() {
        assertEquals("a_b.txt", ReceiverHost.decodeRfc5987("a/b.txt"))
        assertEquals("file", ReceiverHost.decodeRfc5987("   "))
    }

    @Test
    fun `json escaping cannot break out of a response`() {
        val escaped = ReceiverHost.jsonEscape("he said \"hi\"\n\\path")
        assertEquals("he said \\\"hi\\\"\\n\\\\path", escaped)
    }
}
