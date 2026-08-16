package com.sendro.android.core

import android.net.Uri

/**
 * PROTOCOL.md §13 — QR pairing over the optical channel.
 *
 * ```
 * sendro://pair?v=1&h=<host-ip>&p=<port>&id=<hostDeviceId>
 *              &n=<pct-encoded name>&pid=<pairingId>
 *              &s=<salt base64url>&c=<6-digit code>
 * ```
 *
 * The QR carries the SAME session §4.1 created, so the crypto is unchanged:
 * the §4.2 HKDF/HMAC proof is still computed locally and the code never goes
 * back over the wire.
 *
 * SECURITY (§13, last paragraph): a `sendro://` URL is only ever accepted from
 * our own camera scanner or from an OS URL open (a QR reader, the camera app).
 * Nothing in this app parses page content into this type, and every path ends
 * on a confirmation screen that names the PC before a single request is sent.
 */
data class PairLink(
    val hostName: String,
    val host: String,
    val port: Int,
    val hostDeviceId: String,
    val pairingId: String,
    /** base64url, no padding — fed straight into [SendroCrypto.pairingProof]. */
    val salt: String,
    /** The six digits. Never transmitted; only used to derive the proof. */
    val code: String,
) {
    val id: String get() = "$pairingId|$host:$port"

    companion object {

        /**
         * Strict parse. Returns null for anything that is not a well-formed v1
         * Sendro pairing URL — no partial acceptance, no defaults for the
         * security-relevant fields.
         */
        fun parse(raw: String): PairLink? = runCatching { parse(Uri.parse(raw)) }.getOrNull()

        fun parse(uri: Uri): PairLink? {
            if (!uri.scheme.equals("sendro", ignoreCase = true)) return null
            // "sendro://pair?..." parses with authority == "pair"; tolerate
            // "sendro:pair?..." (no authority), which lands in the path.
            val action = (uri.authority ?: uri.path.orEmpty()).trim('/').lowercase()
            if (action != "pair") return null

            fun value(key: String): String? =
                runCatching { uri.getQueryParameter(key) }.getOrNull()?.takeIf { it.isNotEmpty() }

            if (value("v") != "1") return null

            val host = value("h")?.trim() ?: return null
            val port = value("p")?.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            val hostDeviceId = value("id") ?: return null
            val pairingId = value("pid") ?: return null
            val salt = value("s") ?: return null
            val code = value("c") ?: return null

            // §4.1 says six digits — refuse anything else rather than burning a
            // pairing attempt on a malformed QR.
            if (code.length != 6 || !code.all { it in '0'..'9' }) return null
            // The salt must be decodable base64url, otherwise the proof cannot
            // be computed and the confirm fails with a confusing error.
            if (SendroCryptoSaltCheck.isDecodable(salt).not()) return null

            val displayName = value("n")?.trim()?.takeIf { it.isNotEmpty() } ?: host

            return PairLink(
                hostName = displayName,
                host = host,
                port = port,
                hostDeviceId = hostDeviceId,
                pairingId = pairingId,
                salt = salt,
                code = code,
            )
        }
    }
}

/** Tiny indirection so [PairLink] stays free of crypto imports. */
internal object SendroCryptoSaltCheck {
    fun isDecodable(salt: String): Boolean = Base64Url.decodeOrNull(salt) != null
}

/** Human-readable failures for the §13 flow. */
sealed class PairLinkError(val text: String) {
    data object BadAddress : PairLinkError(
        "That QR code points at an address this phone can't reach.",
    )

    data object NotSendro : PairLinkError(
        "Something answered at that address, but it isn't Sendro.",
    )

    data object WrongDevice : PairLinkError(
        "The computer at that address isn't the one in the QR code. Scan the code again.",
    )

    data object VersionMismatch : PairLinkError(
        "Protocol version mismatch — update Sendro on both devices.",
    )

    data object ProofFailed : PairLinkError(
        "Could not compute the pairing proof from that QR code.",
    )

    class Rejected(message: String) : PairLinkError(message)
}

class PairLinkException(val reason: PairLinkError) : Exception(reason.text)

/**
 * §13 client flow: verify the host at `h:p` really is the PC named in the QR,
 * then run the ordinary §4.2 confirm. Reuses [SendroClient] and
 * [SendroCrypto] — no duplicated crypto, no new endpoints.
 */
object PairLinkFlow {

    suspend fun confirm(
        link: PairLink,
        clientDeviceId: String,
        deviceName: String,
    ): PairConfirmResponse {
        val client = SendroClient.create(link.host, link.port)
            ?: throw PairLinkException(PairLinkError.BadAddress)

        val info = try {
            client.info()
        } catch (e: Exception) {
            throw PairLinkException(
                PairLinkError.Rejected(
                    "Could not reach Sendro at ${link.host}:${link.port}. " +
                        "Make sure both devices are on the same Wi-Fi. (${e.sendroMessage()})",
                ),
            )
        }

        if (info.app != "sendro") throw PairLinkException(PairLinkError.NotSendro)
        if (!info.deviceId.equals(link.hostDeviceId, ignoreCase = true)) {
            throw PairLinkException(PairLinkError.WrongDevice)
        }
        if (info.protocolVersion != SENDRO_PROTOCOL_VERSION) {
            throw PairLinkException(PairLinkError.VersionMismatch)
        }

        val proof = SendroCrypto.pairingProof(
            code = link.code,
            saltBase64url = link.salt,
            pairingId = link.pairingId,
            deviceId = clientDeviceId,
        ) ?: throw PairLinkException(PairLinkError.ProofFailed)

        // deviceName / platform are mandatory on this path: the QR flow never
        // calls pair/start, so without them the host has no name for us.
        val request = PairConfirmRequest(
            pairingId = link.pairingId,
            deviceId = clientDeviceId,
            proof = proof,
            deviceName = deviceName,
            platform = "android",
        )

        try {
            return client.pairConfirm(request)
        } catch (e: SendroHttpException) {
            throw PairLinkException(
                PairLinkError.Rejected(
                    when (e.status) {
                        403 -> "The PC rejected this code. The QR may have expired — " +
                            "show a fresh one and scan again."
                        400 -> "That pairing session expired (they last 120 seconds). " +
                            "Show a fresh QR code and scan again."
                        429 -> "Too many attempts. Start a new pairing on your PC."
                        else -> e.sendroMessage()
                    },
                ),
            )
        }
    }
}
