package tech.iamtitan.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.google.zxing.integration.android.IntentIntegrator
import tech.iamtitan.app.notify.Notifier
import tech.iamtitan.app.ui.ChatScreen
import tech.iamtitan.app.ui.LockScreen
import tech.iamtitan.app.ui.PairingScreen
import tech.iamtitan.app.ui.SettingsScreen
import tech.iamtitan.app.ui.TitanInk
import tech.iamtitan.app.ui.TitanTheme

/**
 * Single-activity host (FragmentActivity for BiometricPrompt). All screen state
 * lives in [TitanController].
 *
 * QR scan launches via ZXing's classic [IntentIntegrator] + [onActivityResult]
 * (request code 0xc0de = 49374, within 16 bits) rather than the Compose
 * ActivityResult API: the result registry generates a >16-bit request code, which
 * `FragmentActivity.startActivityForResult` rejects ("Can only use lower 16 bits
 * for requestCode"). We need FragmentActivity (BiometricPrompt), so we use the
 * 16-bit-safe classic path.
 */
class MainActivity : FragmentActivity() {
    private lateinit var controller: TitanController

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge is enforced on targetSdk 35; declare it explicitly so the bars are
        // transparent with correct icon contrast. Each screen consumes its own insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Process-lifetime scope (not lifecycleScope) so an in-flight chat reply
        // survives backgrounding (see TitanApp.appScope + TitanReplyService).
        controller = TitanController(this, (application as TitanApp).appScope)
        setContent {
            TitanTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = TitanInk) {
                    TitanRoot(controller, onScan = ::launchScan)
                }
            }
        }
        maybeRequestNotificationPermission()
        handleActionIntent(intent)
        // DEBUG-only: inject a pairing payload over adb for headless emulator testing.
        if (BuildConfig.DEBUG) {
            intent?.getStringExtra("pair_payload")?.let(controller::onScanned)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleActionIntent(intent)
    }

    /** A notification action opens us with an extra: the health "Restart", or a Channel-2
     *  action (RFP §7.3 — a `needs_app` action, or a headless tap whose key window lapsed). */
    private fun handleActionIntent(intent: Intent?) {
        when (intent?.getStringExtra(Notifier.EXTRA_ACTION)) {
            Notifier.ACTION_RESTART -> controller.onRestartRequested()
            Notifier.ACTION_RESPOND -> {
                val actionId = intent.getStringExtra(Notifier.EXTRA_ACTION_ID)
                if (actionId != null) {
                    controller.onRespondRequested(
                        intent.getIntExtra(Notifier.EXTRA_SEQ, -1), actionId,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Backstop: show a reply the appScope request may have delivered while this
        // Activity was stopped/recreated; also re-evaluates the app-lock policy.
        controller.onAppResume()
    }

    override fun onStop() {
        super.onStop()
        controller.onBackground() // timestamp for the TIMER lock policy
    }

    /** POST_NOTIFICATIONS is a runtime grant on API 33+. Classic API + a 16-bit
     *  request code (the ActivityResult registry's >16-bit codes crash
     *  FragmentActivity — same reason we use classic IntentIntegrator for the QR). */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS,
        )
    }

    @Suppress("DEPRECATION")
    private fun launchScan() {
        IntentIntegrator(this).apply {
            setOrientationLocked(false)
            setBeepEnabled(false)
            setPrompt("Scan the Titan pairing QR")
            setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            captureActivity = com.journeyapps.barcodescanner.CaptureActivity::class.java
        }.initiateScan()
    }

    @Deprecated("ZXing classic result path — FragmentActivity-safe (16-bit request code)")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            result.contents?.let(controller::onScanned)
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private companion object {
        const val REQ_POST_NOTIFICATIONS = 0x4E0F // 16-bit-safe request code
    }
}

@Composable
private fun TitanRoot(controller: TitanController, onScan: () -> Unit) {
    var showPaste by remember { mutableStateOf(false) }

    when (controller.screen) {
        Screen.Pairing -> PairingScreen(
            state = controller.pairing,
            onScan = onScan,
            onPaste = { showPaste = true },
            onRetry = controller::onRetry,
            onConfirmed = controller::onConfirmed,
        )
        Screen.Chat -> ChatScreen(
            titanLabel = controller.titanLabel,
            turns = controller.turns,
            draft = controller.draft,
            sending = controller.sending,
            resting = controller.resting,
            onDraftChange = controller::onDraftChange,
            onSend = controller::onSend,
            onOpenSettings = controller::openSettings,
            onFeedback = { seq, reaction -> controller.onFeedback(seq, reaction) },
        )
    }

    if (showPaste) {
        PastePayloadDialog(
            onDismiss = { showPaste = false },
            onSubmit = { payload -> showPaste = false; controller.onScanned(payload) },
        )
    }

    // Settings sits over the base screen; the lock overlay sits over everything.
    if (controller.showSettings) {
        SettingsScreen(
            lockMode = controller.lockMode,
            lockTimerMinutes = controller.lockTimerMinutes,
            alwaysConnected = controller.alwaysConnected,
            availability = controller.availabilityState,
            onLockModeChange = controller::updateLockMode,
            onTimerChange = controller::updateLockTimerMinutes,
            onAlwaysConnectedChange = controller::updateAlwaysConnected,
            onAvailabilityChange = controller::setAvailability,
            onClose = controller::closeSettings,
        )
    }
    if (controller.locked) {
        LockScreen(onUnlock = controller::unlock)
    }
}

/** Dev fallback (no camera): paste the QR JSON the Command Center prints. */
@Composable
private fun PastePayloadDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste pairing code") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("{\"pairing_token\":\"…\",\"server_pubkey\":\"…\"}") },
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onSubmit(text.trim()) }) {
                Text("Pair", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
