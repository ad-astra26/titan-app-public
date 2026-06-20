package tech.iamtitan.app.data

import android.content.Context
import tech.iamtitan.app.presence.PresenceSettings

/**
 * Local cache of the per-sensor presence opt-in (RFP_titan_mobile_app Phase 3 / AG6). Mirrors
 * the console-local settings (the backend is the gate of record) so the background
 * [tech.iamtitan.app.work.PresenceWorker] knows what to collect without a network round-trip
 * each cycle. All flags default OFF (INV-OPT-IN). Writing here is paired with a signed POST to
 * /console/presence/settings; the two stay in lockstep.
 */
class PresenceStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("titan_presence", Context.MODE_PRIVATE)

    var locationEnabled: Boolean
        get() = prefs.getBoolean("location_enabled", false)
        set(v) = prefs.edit().putBoolean("location_enabled", v).apply()

    var timeEnabled: Boolean
        get() = prefs.getBoolean("time_enabled", false)
        set(v) = prefs.edit().putBoolean("time_enabled", v).apply()

    var batteryEnabled: Boolean
        get() = prefs.getBoolean("battery_enabled", false)
        set(v) = prefs.edit().putBoolean("battery_enabled", v).apply()

    var cadenceMinutes: Int
        get() = prefs.getInt("cadence_minutes", 15)
        set(v) = prefs.edit().putInt("cadence_minutes", v.coerceIn(1, 1440)).apply()

    /** Any sensor opted in ⇒ the background sampler should run. */
    val anyEnabled: Boolean get() = locationEnabled || timeEnabled || batteryEnabled

    fun settings(): PresenceSettings = PresenceSettings(
        locationEnabled = locationEnabled,
        timeEnabled = timeEnabled,
        batteryEnabled = batteryEnabled,
        cadenceMinutes = cadenceMinutes,
    )
}
