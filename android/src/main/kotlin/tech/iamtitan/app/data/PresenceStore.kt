package tech.iamtitan.app.data

import android.content.Context
import tech.iamtitan.app.presence.PresenceSettings

/**
 * Local cache of the per-sensor presence opt-in ( / ). Mirrors
 * the console-local settings (the backend is the gate of record) so the background
 * [tech.iamtitan.app.work.PresenceWorker] knows what to collect without a network round-trip
 * each cycle. All flags default OFF. Writing here is paired with a signed POST to
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

    // ── Adaptive cadence: fast while moving, slow while still. The worker
    // compares each fix to the last; a move ≥ threshold drives a fast one-shot chain. ──
    var lastLat: Float
        get() = prefs.getFloat("last_lat", Float.NaN)
        set(v) = prefs.edit().putFloat("last_lat", v).apply()

    var lastLon: Float
        get() = prefs.getFloat("last_lon", Float.NaN)
        set(v) = prefs.edit().putFloat("last_lon", v).apply()

    /** Any sensor opted in ⇒ the background sampler should run. */
    val anyEnabled: Boolean get() = locationEnabled || timeEnabled || batteryEnabled

    fun settings(): PresenceSettings = PresenceSettings(
        locationEnabled = locationEnabled,
        timeEnabled = timeEnabled,
        batteryEnabled = batteryEnabled,
        cadenceMinutes = cadenceMinutes,
    )
}
