package tech.iamtitan.app.data

import android.content.Context

/**
 * User-settable event-channel connection policy (RFP_titan_app_event_channel §7.2b).
 * Currently the single "Stay connected" opt-in: when on, a persistent foreground
 * service holds the long-poll 24/7 (the [Tier.ALWAYS_ON] tier) so background messages
 * arrive near-instantly instead of on the ~15-min WorkManager cadence. Default OFF —
 * the battery-respectful default is WorkManager (AG-EVT-5).
 */
class ConnectionSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("titan_connection", Context.MODE_PRIVATE)

    var alwaysConnected: Boolean
        get() = prefs.getBoolean("always_connected", false)
        set(v) = prefs.edit().putBoolean("always_connected", v).apply()
}
