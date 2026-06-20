package tech.iamtitan.app.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import tech.iamtitan.app.BuildConfig
import tech.iamtitan.app.data.PairingStore
import tech.iamtitan.app.net.RequestSigner
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * the device identity: a software Ed25519 keypair whose 32-byte seed is
 * **sealed at rest** by an AndroidKeyStore AES-GCM key requiring user auth, and
 * unwrapped only transiently to sign. The seed never persists in the clear and
 * never leaves the device. AndroidKeyStore can't hold an Ed25519 key portably
 * (<API 33), so the *wrapping* key is hardware-backed while the Ed25519 math
 * (BouncyCastle) runs over the transient seed.
 *
 * ## Auth window (kills the per-message biometric)
 * The wrapping key is **time-bound**: one successful unlock authorizes signing for
 * [WINDOW_SECONDS], so chat turns within a session don't each re-prompt. The
 * app-lock overlay (Settings) enforces the actual re-lock UX (off / immediate /
 * timer / once-per-launch); the Keystore window is only "don't prompt mid-session".
 *
 * ## Safe migration (never bricks pairing)
 * The original key (alias [KEY_ALIAS], per-op) is left **untouched**; the time-bound
 * key uses a **new alias** ([KEY_ALIAS_V2]). A device paired before this change keeps
 * signing through the unchanged legacy path and is migrated *opportunistically* on a
 * successful sign — but only by re-sealing under v2 and flipping [PairingStore.sealedSeedWindowed]
 * **after** that fully succeeds. Any failure leaves the legacy key + sealed seed intact
 * (the device simply keeps prompting per signature, exactly as before).
 */
@OptIn(ExperimentalEncodingApi::class)
class DeviceKey private constructor(
    private val context: Context,
    private val store: PairingStore,
    private val activityProvider: () -> FragmentActivity,
    override val deviceId: String,
    override val publicKey: ByteArray,
) : RequestSigner {

    override suspend fun sign(canonicalBytes: ByteArray): ByteArray {
        val seed = unsealSeed()
        try {
            return Ed25519.sign(seed, canonicalBytes)
        } finally {
            seed.fill(0) // wipe the transient seed
        }
    }

    /**
     * App-lock unlock entry point: a single biometric/credential auth that opens the
     * time-bound signing window (so chat turns then sign without re-prompting). Returns
     * true on success. For a not-yet-migrated device it still verifies the user; the
     * first subsequent sign performs the migration.
     */
    suspend fun unlock(): Boolean =
        try {
            authenticateForWindow("Unlock Titan")
            true
        } catch (_: Exception) {
            false
        }

    private suspend fun unsealSeed(): ByteArray {
        val ivB64 = store.sealedSeedIvB64
        val ctB64 = store.sealedSeedB64
            ?: error("no sealed device seed — re-pair this phone")
        val ciphertext = Base64.decode(ctB64)

        if (ivB64 == null) {
            // DEBUG insecure fallback (no Keystore) — never compiled-active in release.
            check(BuildConfig.DEBUG) { "missing sealed-seed IV" }
            return ciphertext.copyOf()
        }
        val iv = Base64.decode(ivB64)

        if (store.sealedSeedWindowed) {
            return unsealWindowed(ciphertext, iv)
        }
        // ── Legacy per-op path (unchanged): one biometric per signature ──
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, wrapKey(context), GCMParameterSpec(128, iv))
        }
        val authed = authenticate(cipher, "Unlock to sign for Titan")
        val seed = authed.doFinal(ciphertext)
        // Opportunistic, safe-degrading upgrade to the time-bound key (no-op on failure).
        tryMigrateToWindowed(seed)
        return seed
    }

    /** Decrypt the seed with the time-bound v2 key; if the window lapsed, prompt once
     * (no CryptoObject) to reopen it, then retry. */
    private suspend fun unsealWindowed(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        fun decrypt(): ByteArray =
            Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, wrapKeyWindowed(context), GCMParameterSpec(128, iv))
            }.doFinal(ciphertext)
        return try {
            decrypt()
        } catch (_: UserNotAuthenticatedException) {
            authenticateForWindow("Unlock to sign for Titan")
            decrypt()
        }
    }

    /** Re-seal the (already-unsealed) seed under the time-bound v2 key. SAFE-DEGRADE:
     * the store is rewritten only on full success; any failure leaves the legacy v1
     * key + sealed seed intact. Never bricks. */
    private suspend fun tryMigrateToWindowed(seed: ByteArray) {
        if (store.sealedSeedWindowed) return
        try {
            val (ct, ivOut) = encryptWithWindow(wrapKeyWindowed(context), seed, "Enable staying unlocked")
            store.sealedSeedB64 = Base64.encode(ct)
            store.sealedSeedIvB64 = Base64.encode(ivOut)
            store.sealedSeedWindowed = true
        } catch (_: Exception) {
            // keep the legacy per-op path; the device just keeps prompting per signature.
        }
    }

    /** Encrypt under a time-bound key: on a lapsed window prompt once, then retry.
     * Returns (ciphertext, iv). */
    private suspend fun encryptWithWindow(
        key: SecretKey,
        plaintext: ByteArray,
        subtitle: String,
    ): Pair<ByteArray, ByteArray> {
        fun enc(): Pair<ByteArray, ByteArray> {
            val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
            return c.doFinal(plaintext) to c.iv
        }
        return try {
            enc()
        } catch (_: UserNotAuthenticatedException) {
            authenticateForWindow(subtitle)
            enc()
        }
    }

    /** Run BiometricPrompt over [cipher] and return the authenticated cipher (per-op,
     * legacy path — CryptoObject-bound). */
    private suspend fun authenticate(cipher: Cipher, subtitle: String): Cipher =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val activity = activityProvider()
                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(context),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            val c = result.cryptoObject?.cipher ?: cipher
                            cont.resume(c)
                        }

                        override fun onAuthenticationError(code: Int, msg: CharSequence) {
                            cont.resumeWithException(SecurityException("auth failed: $msg"))
                        }
                    },
                )
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Titan")
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(authenticators())
                    .build()
                prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            }
        }

    /** Authenticate WITHOUT a CryptoObject — opens the time-bound key's auth window. */
    private suspend fun authenticateForWindow(subtitle: String): Unit =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val activity = activityProvider()
                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(context),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            cont.resume(Unit)
                        }

                        override fun onAuthenticationError(code: Int, msg: CharSequence) {
                            cont.resumeWithException(SecurityException("auth failed: $msg"))
                        }
                    },
                )
                val builder = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Titan")
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(authenticators())
                if (Build.VERSION.SDK_INT < 30) builder.setNegativeButtonText("Cancel")
                prompt.authenticate(builder.build())
            }
        }

    companion object {
        private const val KEY_ALIAS = "titan.device.seed.wrap.v1"        // legacy, per-op
        private const val KEY_ALIAS_V2 = "titan.device.seed.wrap.v2"     // time-bound window
        private const val TRANSFORM = "AES/GCM/NoPadding"

        /** One unlock authorizes signing for this long (8 h). The app-lock overlay
         * governs the actual re-lock UX; this just avoids a prompt every chat turn. */
        private const val WINDOW_SECONDS = 8 * 60 * 60

        private fun authenticators(): Int =
            if (Build.VERSION.SDK_INT >= 30) {
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            } else {
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            }

        /** Can this device gate a key behind biometrics or a device credential? */
        fun canSecure(context: Context): Boolean =
            BiometricManager.from(context).canAuthenticate(authenticators()) ==
                BiometricManager.BIOMETRIC_SUCCESS

        /** Load the existing identity, or null if this phone has never paired. */
        fun existing(
            context: Context,
            store: PairingStore,
            activityProvider: () -> FragmentActivity,
        ): DeviceKey? {
            val id = store.deviceId ?: return null
            val pub = store.devicePubkeyB64?.let { Base64.decode(it) } ?: return null
            return DeviceKey(context, store, activityProvider, id, pub)
        }

        /**
         * Mint a fresh identity: random seed → Ed25519 pubkey → sealed at rest under the
         * time-bound key. Suspends to authenticate the seal. Returns the signer; the
         * caller persists nothing else (this writes the store).
         */
        suspend fun create(
            context: Context,
            store: PairingStore,
            label: String,
            activityProvider: () -> FragmentActivity,
        ): DeviceKey {
            val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val pub = Ed25519.publicKey(seed)
            val deviceId = UUID.randomUUID().toString()
            try {
                sealSeed(context, store, seed, activityProvider)
                store.deviceId = deviceId
                store.devicePubkeyB64 = Base64.encode(pub)
                store.label = label
                store.paired = false
                return DeviceKey(context, store, activityProvider, deviceId, pub)
            } finally {
                seed.fill(0)
            }
        }

        private suspend fun sealSeed(
            context: Context,
            store: PairingStore,
            seed: ByteArray,
            activityProvider: () -> FragmentActivity,
        ) {
            if (!canSecure(context)) {
                check(BuildConfig.DEBUG) {
                    "no screen lock / biometric enrolled — set one up to secure your Titan key"
                }
                // DEV-ONLY: store the seed unsealed so the emulator flow works. Marked by a
                // null IV; never reachable in release (canSecure must be true there).
                store.sealedSeedB64 = Base64.encode(seed)
                store.sealedSeedIvB64 = null
                store.sealedSeedWindowed = false
                return
            }
            // New pairings seal under the time-bound v2 key (no per-message prompt).
            val tmp = DeviceKey(context, store, activityProvider, "", ByteArray(0))
            val (ct, iv) = tmp.encryptWithWindow(
                wrapKeyWindowed(context), seed, "Secure your Titan device key",
            )
            store.sealedSeedB64 = Base64.encode(ct)
            store.sealedSeedIvB64 = Base64.encode(iv)
            store.sealedSeedWindowed = true
        }

        /** Legacy per-op wrapping key (alias v1) — read-only path for already-paired
         * devices; never generated for new pairings now (those use [wrapKeyWindowed]). */
        private fun wrapKey(context: Context): SecretKey =
            buildWrapKey(context, KEY_ALIAS, windowSeconds = 0)

        /** Time-bound wrapping key (alias v2) — one auth authorizes [WINDOW_SECONDS]. */
        private fun wrapKeyWindowed(context: Context): SecretKey =
            buildWrapKey(context, KEY_ALIAS_V2, windowSeconds = WINDOW_SECONDS)

        /** The hardware-backed AES-GCM wrapping key; created once. [windowSeconds] 0 =
         * per-op (CryptoObject) on API 30+; >0 = time-bound auth-validity window. */
        private fun buildWrapKey(context: Context, alias: String, windowSeconds: Int): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(alias, null) as? SecretKey)?.let { return it }

            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .apply {
                    if (Build.VERSION.SDK_INT >= 30) {
                        // windowSeconds=0 ⇒ per-op (CryptoObject); >0 ⇒ time-bound window.
                        setUserAuthenticationParameters(
                            windowSeconds,
                            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        setUserAuthenticationValidityDurationSeconds(
                            if (windowSeconds > 0) windowSeconds else 15,
                        )
                    }
                }

            // StrongBox (hardware secure element) when present (API 28+); else TEE-backed.
            if (Build.VERSION.SDK_INT >= 28 &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            ) {
                try {
                    spec.setIsStrongBoxBacked(true)
                    kg.init(spec.build())
                    return kg.generateKey()
                } catch (_: StrongBoxUnavailableException) {
                    // fall through to a TEE-backed key
                }
            }
            kg.init(spec.build())
            return kg.generateKey()
        }

        /** Device fingerprint — a *secondary* signal only (), never the identity. */
        @Suppress("HardwareIds")
        fun fingerprint(context: Context): String {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "?"
            return "$androidId:${Build.MODEL}:android-${Build.VERSION.SDK_INT}"
        }
    }
}
