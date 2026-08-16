package com.sendro.android.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire models mirroring docs/PROTOCOL.md (v1) exactly — the same shapes the
 * Rust host and the iOS client implement. Property names are the JSON keys.
 */

/** The protocol major version this client implements (§10). */
const val SENDRO_PROTOCOL_VERSION = 1

/** §11 hard limit: UTF-8 *bytes*, not characters. */
const val SENDRO_MESSAGE_BYTE_LIMIT = 32 * 1024

/**
 * One shared Json. `ignoreUnknownKeys` so a newer host adding a field never
 * breaks a poll; `explicitNulls = false` so optional request fields are simply
 * absent rather than `null` (the host's serde treats both the same, but absent
 * is what the iOS client sends and what §4.1/§4.2 document).
 */
val SendroJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = false
    encodeDefaults = true
}

// ---------------------------------------------------------------------------
// §5 Info
// ---------------------------------------------------------------------------

@Serializable
data class InfoResponse(
    val app: String,
    val protocolVersion: Int,
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val apiPort: Int = 0,
)

// ---------------------------------------------------------------------------
// §4 Pairing
// ---------------------------------------------------------------------------

@Serializable
data class PairStartRequest(
    val deviceId: String,
    val deviceName: String,
    val platform: String = "android",
    val protocolVersion: Int = SENDRO_PROTOCOL_VERSION,
)

@Serializable
data class PairStartResponse(
    val pairingId: String,
    /** base64url, no padding. */
    val salt: String,
    val expiresInSeconds: Int = 120,
)

/**
 * §4.2 confirm body.
 *
 * `deviceName` / `platform` are optional on the wire (the host accepts a
 * confirm without them) but this client always sends them: on the §13 QR path
 * there is no `pair/start` call, so the host would otherwise have no name for
 * this phone at all. Harmless on the typed path — the host just sees the same
 * values it already got from `pair/start`.
 */
@Serializable
data class PairConfirmRequest(
    val pairingId: String,
    val deviceId: String,
    /** base64url(HMAC-SHA256), no padding. */
    val proof: String,
    val deviceName: String? = null,
    val platform: String? = "android",
)

@Serializable
data class PairConfirmResponse(
    /** base64url 32 bytes, no padding. */
    val deviceToken: String,
    val host: HostIdentity,
) {
    @Serializable
    data class HostIdentity(
        val deviceId: String,
        val deviceName: String,
        val platform: String,
    )
}

// ---------------------------------------------------------------------------
// §4.3 Ping
// ---------------------------------------------------------------------------

@Serializable
data class PingResponse(
    val ok: Boolean = false,
    val deviceName: String = "",
)

// ---------------------------------------------------------------------------
// §6.1 Transfer model
// ---------------------------------------------------------------------------

/** The canonical Transfer JSON from PROTOCOL.md §6.1. */
@Serializable
data class TransferOffer(
    val transferId: String,
    val batchId: String = "",
    val fileId: String = "",
    val fileName: String,
    /** `extension` is not a Kotlin keyword, but the field name is mapped
     *  explicitly so a rename here can never silently change the wire key. */
    @SerialName("extension") val fileExtension: String = "",
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long,
    /** lowercase hex, 64 chars — authoritative, computed by the host. */
    val sha256: String,
    val createdAtMs: Long = 0,
    val modifiedAtMs: Long = 0,
    val offeredAtMs: Long = 0,
    val senderName: String = "",
    val autoAccept: Boolean = false,
)

// ---------------------------------------------------------------------------
// §11 Text messages (ephemeral)
// ---------------------------------------------------------------------------

/**
 * A short text payload from a paired host.
 *
 * EPHEMERALITY: this type is decode-only by convention — nothing in the app
 * serialises a [SendroMessage]. See [MessageCenter] for the full contract.
 */
@Serializable
data class SendroMessage(
    val messageId: String,
    val text: String,
    val sentAtMs: Long = 0,
    val senderName: String = "",
)

/** §11.2 body for `POST /api/v1/messages`. */
@Serializable
data class SendMessageRequest(val text: String)

// ---------------------------------------------------------------------------
// §6.2 Outbox
// ---------------------------------------------------------------------------

/**
 * Long-poll response. Both arrays are optional: a host with only messages
 * pending may omit `offers`, and `messages` is absent when there are none.
 */
@Serializable
data class OutboxResponse(
    val offers: List<TransferOffer> = emptyList(),
    val messages: List<SendroMessage> = emptyList(),
)

// ---------------------------------------------------------------------------
// §6.3 Accept / reject, generic ok
// ---------------------------------------------------------------------------

@Serializable
data class OkResponse(val ok: Boolean = false)

// ---------------------------------------------------------------------------
// §6.5 Status reporting (client -> host)
// ---------------------------------------------------------------------------

@Serializable
data class StatusReport(
    /** downloading | verifying | verified | saving | completed | failed | cancelled */
    val state: String,
    val bytesReceived: Long? = null,
    val error: String? = null,
    /** for `completed`: "photos" | "files" | "temp" */
    val savedTo: String? = null,
)

/** The §6.5 state vocabulary, so callers cannot typo a state name. */
object TransferState {
    const val DOWNLOADING = "downloading"
    const val VERIFYING = "verifying"
    const val VERIFIED = "verified"
    const val SAVING = "saving"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

// ---------------------------------------------------------------------------
// §7 Upload
// ---------------------------------------------------------------------------

@Serializable
data class UploadResult(
    val ok: Boolean = false,
    val savedPath: String? = null,
)

// ---------------------------------------------------------------------------
// §9 Errors
// ---------------------------------------------------------------------------

@Serializable
data class ApiError(
    val error: String = "",
    val message: String? = null,
)
