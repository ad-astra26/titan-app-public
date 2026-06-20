package tech.iamtitan.app.data

import android.content.Context

/** When the app re-locks (requiring a biometric/credential unlock to view + sign). */
enum class LockMode {
    /** No app lock. (Signing still needs one auth per ~8 h key window — the security floor.) */
    OFF,

    /** Lock the moment the app goes to the background; unlock on every return. */
    IMMEDIATE,

    /** Lock after [SecuritySettings.lockTimerMinutes] minutes in the background. */
    TIMER,

    /** Unlock once per cold start; never auto-lock while the process is alive. (Default.) */
    ON_LAUNCH,
}

/**
 * User-settable app-lock policy (the Settings screen writes these). Separate from
 * the device-key crypto — this governs the *UX* gate (the lock overlay); the
 * Keystore window (DeviceKey) governs "don't prompt per chat turn". Default is the
 * UX-friendly [LockMode.ON_LAUNCH].
 */
class SecuritySettings(context: Context) {
    private val prefs = context.getSharedPreferences("titan_security", Context.MODE_PRIVATE)

    var lockMode: LockMode
        get() = runCatching { LockMode.valueOf(prefs.getString("lock_mode", null) ?: DEFAULT_MODE.name) }
            .getOrDefault(DEFAULT_MODE)
        set(v) = prefs.edit().putString("lock_mode", v.name).apply()

    /** Minutes in the background before [LockMode.TIMER] re-locks. */
    var lockTimerMinutes: Int
        get() = prefs.getInt("lock_timer_minutes", DEFAULT_TIMER_MINUTES).coerceIn(1, 240)
        set(v) = prefs.edit().putInt("lock_timer_minutes", v.coerceIn(1, 240)).apply()

    /**
     * Advanced ops surface gate ( decision-c). OFF by default;
     * flipping it on requires an app-lock re-auth (the controller runs DeviceKey.unlock
     * before persisting true). Hides the privileged per-layer ops / reboot / reap surface.
     */
    var advancedOpsEnabled: Boolean
        get() = prefs.getBoolean("advanced_ops_enabled", false)
        set(v) = prefs.edit().putBoolean("advanced_ops_enabled", v).apply()

    private companion object {
        val DEFAULT_MODE = LockMode.ON_LAUNCH
        const val DEFAULT_TIMER_MINUTES = 5
    }
}
