package tech.iamtitan.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
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
import androidx.lifecycle.lifecycleScope
import com.google.zxing.integration.android.IntentIntegrator
import tech.iamtitan.app.ui.ChatScreen
import tech.iamtitan.app.ui.PairingScreen
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
        super.onCreate(savedInstanceState)
        controller = TitanController(this, lifecycleScope)
        setContent {
            TitanTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = TitanInk) {
                    TitanApp(controller, onScan = ::launchScan)
                }
            }
        }
        // DEBUG-only: inject a pairing payload over adb for headless emulator testing.
        if (BuildConfig.DEBUG) {
            intent?.getStringExtra("pair_payload")?.let(controller::onScanned)
        }
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
}

@Composable
private fun TitanApp(controller: TitanController, onScan: () -> Unit) {
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
        )
    }

    if (showPaste) {
        PastePayloadDialog(
            onDismiss = { showPaste = false },
            onSubmit = { payload -> showPaste = false; controller.onScanned(payload) },
        )
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
