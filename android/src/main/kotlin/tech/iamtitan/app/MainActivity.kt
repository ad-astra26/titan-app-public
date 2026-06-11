package tech.iamtitan.app

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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import tech.iamtitan.app.ui.ChatScreen
import tech.iamtitan.app.ui.PairingScreen
import tech.iamtitan.app.ui.TitanInk
import tech.iamtitan.app.ui.TitanTheme

/**
 * Single-activity host (FragmentActivity for BiometricPrompt). All screen state
 * lives in [TitanController]; this only wires the QR scanner + paste dialog.
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = TitanController(this, lifecycleScope)
        setContent {
            TitanTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = TitanInk) {
                    TitanApp(controller)
                }
            }
        }
        // DEBUG-only: inject a pairing payload over adb for headless emulator testing
        // (RFP §5 manual-endpoint dev menu). `am start … -e pair_payload '<json>'`.
        if (BuildConfig.DEBUG) {
            intent?.getStringExtra("pair_payload")?.let(controller::onScanned)
        }
    }
}

@Composable
private fun TitanApp(controller: TitanController) {
    val scanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { controller.onScanned(it) }
    }
    var showPaste by remember { mutableStateOf(false) }

    when (controller.screen) {
        Screen.Pairing -> PairingScreen(
            state = controller.pairing,
            onScan = {
                scanLauncher.launch(
                    ScanOptions()
                        .setOrientationLocked(false)
                        .setBeepEnabled(false)
                        .setPrompt("Scan the Titan pairing QR"),
                )
            },
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
