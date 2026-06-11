package tech.iamtitan.app.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
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
 * AG3 — the device identity: a software Ed25519 keypair whose 32-byte seed is
 * **sealed at rest** by an AndroidKeyStore AES-GCM key requiring user auth, and
 * unwrapped only transiently — behind a BiometricPrompt — to sign. The seed never
 * persists in the clear and never leaves the device. AndroidKeyStore can't hold an
 * Ed25519 key portably (<API 33), so the *wrapping* key is hardware-backed while the
 * Ed25519 math (BouncyCastle) runs over the transient seed.
 *
 * The live keystore/biometric path runs on a real paired device (gate G1a/c, which
 * is Tailscale-blocked this session); a DEBUG-only insecure fallback lets the app
 * run on an emulator with no enrolled credential.
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
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, wrapKey(context), GCMParameterSpec(128, iv))
        }
        val authed = authenticate(cipher, "Unlock to sign for Titan")
        return authed.doFinal(ciphertext)
    }

    /** Run BiometricPrompt over [cipher] and return the authenticated cipher. */
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

    companion object {
        private const val KEY_ALIAS = "titan.device.seed.wrap.v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"

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
         * Mint a fresh identity: random seed → Ed25519 pubkey → sealed at rest.
         * Suspends to authenticate the seal (the key requires user auth). Returns the
         * signer; the caller persists nothing else (this writes the store).
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
                // null IV; never reachable in release (canSecure() must be true there).
                store.sealedSeedB64 = Base64.encode(seed)
                store.sealedSeedIvB64 = null
                return
            }
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, wrapKey(context)) }
            val tmp = DeviceKey(context, store, activityProvider, "", ByteArray(0))
            val authed = tmp.authenticate(cipher, "Secure your Titan device key")
            store.sealedSeedB64 = Base64.encode(authed.doFinal(seed))
            store.sealedSeedIvB64 = Base64.encode(authed.iv)
        }

        /** The hardware-backed AES-GCM wrapping key; created once, user-auth required. */
        private fun wrapKey(context: Context): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .apply {
                    if (Build.VERSION.SDK_INT >= 30) {
                        // 0s timeout = re-auth on every operation, bound to the CryptoObject.
                        setUserAuthenticationParameters(
                            0,
                            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        setUserAuthenticationValidityDurationSeconds(15) // time-window auth on API 26–29
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

        /** Device fingerprint — a *secondary* signal only (AG3), never the identity. */
        @Suppress("HardwareIds")
        fun fingerprint(context: Context): String {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "?"
            return "$androidId:${Build.MODEL}:android-${Build.VERSION.SDK_INT}"
        }
    }
}
