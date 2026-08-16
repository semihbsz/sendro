package com.sendro.android

import com.sendro.android.core.FileNames
import com.sendro.android.core.OutboxResponse
import com.sendro.android.core.SendroClient
import com.sendro.android.core.SendroJson
import com.sendro.android.core.TransferOffer
import com.sendro.android.core.UpdateChecker
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape and filename tests. Everything here is pure Kotlin/JVM so it runs
 * in `testDebugUnitTest` without Robolectric.
 */
class ProtocolTest {

    // -- §7 / §6.4 RFC 5987 --------------------------------------------------

    @Test
    fun `rfc5987 keeps attr-chars literal`() {
        assertEquals(
            "abcXYZ019!#\$&+-.^_`|~",
            SendroClient.rfc5987Encode("abcXYZ019!#\$&+-.^_`|~"),
        )
    }

    @Test
    fun `rfc5987 percent-encodes spaces and non-ascii`() {
        // The exact case from PROTOCOL.md §8.
        assertEquals(
            "%C3%87ekmek%C3%B6y%20Re%C5%9Fadiye%20Drone.MOV",
            SendroClient.rfc5987Encode("Çekmeköy Reşadiye Drone.MOV"),
        )
    }

    @Test
    fun `rfc5987 encodes characters that look safe but are not attr-chars`() {
        // '%', '*', '\'', '(' and ')' are NOT attr-chars.
        assertEquals("100%25%20%2A%27%28%29", SendroClient.rfc5987Encode("100% *'()"))
    }

    // -- §6.2 outbox ---------------------------------------------------------

    @Test
    fun `outbox decodes with messages absent`() {
        val json = """{"offers":[]}"""
        val response = SendroJson.decodeFromString<OutboxResponse>(json)
        assertTrue(response.offers.isEmpty())
        assertTrue(response.messages.isEmpty())
    }

    @Test
    fun `outbox decodes with offers absent`() {
        val json = """
            {"messages":[{"messageId":"m1","text":"hi","sentAtMs":1,"senderName":"PC"}]}
        """.trimIndent()
        val response = SendroJson.decodeFromString<OutboxResponse>(json)
        assertTrue(response.offers.isEmpty())
        assertEquals("hi", response.messages.single().text)
    }

    @Test
    fun `transfer offer maps the extension key and tolerates unknown fields`() {
        val json = """
            {"transferId":"t1","batchId":"b1","fileId":"f1","fileName":"a b.mp4",
             "extension":"mp4","mimeType":"video/mp4","sizeBytes":8492372918,
             "sha256":"ab","createdAtMs":1,"modifiedAtMs":2,"offeredAtMs":3,
             "senderName":"Semih-PC","autoAccept":true,"somethingNew":42}
        """.trimIndent()
        val offer = SendroJson.decodeFromString<TransferOffer>(json)
        assertEquals("mp4", offer.fileExtension)
        assertEquals(8_492_372_918L, offer.sizeBytes)
        assertTrue(offer.autoAccept)

        // And it re-encodes to the same wire key.
        assertTrue(SendroJson.encodeToString(offer).contains("\"extension\":\"mp4\""))
    }

    // -- §8 filenames --------------------------------------------------------

    @Test
    fun `sanitize preserves unicode case and spaces`() {
        assertEquals(
            "Çekmeköy Reşadiye Drone.MOV",
            FileNames.sanitize("Çekmeköy Reşadiye Drone.MOV"),
        )
    }

    @Test
    fun `sanitize strips separators but keeps the rest`() {
        assertEquals("a_b_c.txt", FileNames.sanitize("a/b\\c.txt"))
        assertEquals("file", FileNames.sanitize("   "))
        assertEquals("file", FileNames.sanitize(".."))
    }

    @Test
    fun `duplicate display names get a numbered suffix before the extension`() {
        assertEquals("final.mp4", FileNames.withSuffix("final.mp4", 1))
        assertEquals("final (2).mp4", FileNames.withSuffix("final.mp4", 2))
        assertEquals("README (3)", FileNames.withSuffix("README", 3))
    }

    // -- UPDATES.md §4 -------------------------------------------------------

    @Test
    fun `version codes follow the bump_version derivation`() {
        assertEquals(10_000, UpdateChecker.versionCodeOf("1.0.0"))
        assertEquals(10_200, UpdateChecker.versionCodeOf("1.2.0"))
        assertEquals(10_203, UpdateChecker.versionCodeOf("1.2.3"))
        assertEquals(20_000, UpdateChecker.versionCodeOf("2.0.0"))
        assertEquals(null, UpdateChecker.versionCodeOf("1.2"))
        assertEquals(null, UpdateChecker.versionCodeOf("1.2.300"))
    }

    @Test
    fun `version codes are monotonic across a semver stream`() {
        val stream = listOf("1.0.0", "1.0.1", "1.1.0", "1.10.0", "2.0.0")
            .map { UpdateChecker.versionCodeOf(it)!! }
        assertEquals(stream.sorted(), stream)
    }
}
