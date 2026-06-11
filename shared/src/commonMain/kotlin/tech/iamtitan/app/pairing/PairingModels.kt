package tech.iamtitan.app.pairing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The QR payload minted by `pairing.mint_pairing` (SPEC §3 step 1):
 * `{mode?, pairing_token, server_pubkey, titan_id, ttl, endpoint_url?, server_tls_pin?}`
 * — all base64 for the binary fields. Field names are snake_case to match the Python wire.
 *
 * - `mode` ∈ {local, remote, install} (AD-8); absent = legacy/dev.
 * - `serverTlsPin` = sha256-hex of the agent's self-signed cert DER (AG-TLS/AD-9). When
 *   present, the transport MUST pin it; a `remote`-mode QR without a pin is rejected.
 */
@Serializable
data class PairingPayload(
    @SerialName("pairing_token") val pairingToken: String,
    @SerialName("server_pubkey") val serverPubkey: String,
    @SerialName("titan_id") val titanId: String,
    val ttl: Int = 90,
    val mode: String? = null,
    @SerialName("endpoint_url") val endpointUrl: String? = null,
    @SerialName("server_tls_pin") val serverTlsPin: String? = null,
)

/** App→agent device submission (SPEC §3 step 3) — POST /console/pair/submit. */
@Serializable
data class SubmitRequest(
    @SerialName("pairing_token") val pairingToken: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_pubkey") val devicePubkey: String,
    val fingerprint: String = "",
    val label: String = "phone",
)

@Serializable
data class SubmitResponse(
    val ok: Boolean = false,
    @SerialName("awaiting_confirm") val awaitingConfirm: Boolean = false,
    val error: String? = null,
)

/** The device's own registration record (signed GET /console/device/me). */
@Serializable
data class DeviceRecord(
    @SerialName("device_id") val deviceId: String,
    val label: String = "phone",
    @SerialName("paired_at") val pairedAt: Double? = null,
)

/** Lenient JSON for the whole wire (ignore fields we don't model). */
val WireJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** Parse the scanned/pasted QR text into a [PairingPayload]; null if malformed. */
fun parsePairingPayload(text: String): PairingPayload? = try {
    WireJson.decodeFromString(PairingPayload.serializer(), text.trim())
} catch (_: Exception) {
    null
}
