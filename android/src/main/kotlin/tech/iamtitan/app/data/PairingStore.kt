package tech.iamtitan.app.data

import android.content.Context

/**
 * Local, non-secret app state for pairing/transport. The Ed25519 *seed* is NOT
 * here — it lives only as keystore-sealed ciphertext (see DeviceKey). This holds
 * the device id, public key, label, endpoint, and the sealed-seed envelope.
 */
class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("titan_pairing", Context.MODE_PRIVATE)

    var endpointUrl: String?
        get() = prefs.getString("endpoint_url", null)
        set(v) = prefs.edit().putString("endpoint_url", v).apply()

    /** sha256-hex pin of the agent's self-signed TLS cert (AG-TLS); null = no pinning (dev/LAN-http). */
    var tlsPin: String?
        get() = prefs.getString("tls_pin", null)
        set(v) = prefs.edit().putString("tls_pin", v).apply()

    /** Pairing mode the QR declared (local/remote/install), for honoring AD-8. */
    var mode: String?
        get() = prefs.getString("mode", null)
        set(v) = prefs.edit().putString("mode", v).apply()

    var deviceId: String?
        get() = prefs.getString("device_id", null)
        set(v) = prefs.edit().putString("device_id", v).apply()

    /** base64(raw 32-byte Ed25519 public key). */
    var devicePubkeyB64: String?
        get() = prefs.getString("device_pubkey", null)
        set(v) = prefs.edit().putString("device_pubkey", v).apply()

    var label: String
        get() = prefs.getString("label", "phone") ?: "phone"
        set(v) = prefs.edit().putString("label", v).apply()

    /** Set once the operator has confirmed the code-match (whoAmI succeeds). */
    var paired: Boolean
        get() = prefs.getBoolean("paired", false)
        set(v) = prefs.edit().putBoolean("paired", v).apply()

    // ── sealed-seed envelope (ciphertext only; the wrap key lives in AndroidKeyStore) ──
    var sealedSeedB64: String?
        get() = prefs.getString("sealed_seed", null)
        set(v) = prefs.edit().putString("sealed_seed", v).apply()

    var sealedSeedIvB64: String?
        get() = prefs.getString("sealed_seed_iv", null)
        set(v) = prefs.edit().putString("sealed_seed_iv", v).apply()

    /**
     * True once the seed is sealed under the **time-bound** wrapping key (alias v2),
     * so one unlock authorizes signing for a window (no per-message biometric).
     * Defaults false = legacy per-op key (alias v1); flipped true on a successful
     * migration or for new pairings. See [tech.iamtitan.app.crypto.DeviceKey].
     */
    var sealedSeedWindowed: Boolean
        get() = prefs.getBoolean("sealed_seed_windowed", false)
        set(v) = prefs.edit().putBoolean("sealed_seed_windowed", v).apply()

    /** True if a sealed identity exists (started pairing at least once). */
    fun hasIdentity(): Boolean = deviceId != null && sealedSeedB64 != null

    fun clear() = prefs.edit().clear().apply()
}
