package tech.iamtitan.app.presence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phone → Titan context uplink (RFP_titan_mobile_app Phase 3 / AG6 INV-OPT-IN). A sample
 * carries only the fields the Maker has opted into + a timestamp; nullable fields are omitted
 * on the wire (WireJson `explicitNulls=false`), and the backend additionally field-gates by
 * its own opt-in store, so an un-opted-in sensor is doubly fenced. No cognition (AG8).
 */
@Serializable
data class PresenceSample(
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracy: Double? = null,
    val tz: String? = null,
    @SerialName("local_time") val localTime: String? = null,
    val motion: String? = null,
    val battery: Int? = null,
    val ts: Double,
)

@Serializable
data class ContextBody(val samples: List<PresenceSample>)

/** The per-sensor opt-in flags + cadence (console-local store, NOT config.toml). */
@Serializable
data class PresenceSettings(
    @SerialName("location_enabled") val locationEnabled: Boolean = false,
    @SerialName("time_enabled") val timeEnabled: Boolean = false,
    @SerialName("motion_enabled") val motionEnabled: Boolean = false,
    @SerialName("battery_enabled") val batteryEnabled: Boolean = false,
    @SerialName("cadence_minutes") val cadenceMinutes: Int = 15,
)

/** GET /console/presence — the Maker's latest uploaded context (flat readout). */
@Serializable
data class PresenceLatest(
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracy: Double? = null,
    val tz: String? = null,
    @SerialName("local_time") val localTime: String? = null,
    val motion: String? = null,
    val battery: Int? = null,
    val ts: Double? = null,
    @SerialName("device_id") val deviceId: String? = null,
) {
    val hasData: Boolean get() = ts != null
}
