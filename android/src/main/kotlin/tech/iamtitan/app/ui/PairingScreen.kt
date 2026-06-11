package tech.iamtitan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The pairing flow's UI state (driven by [tech.iamtitan.app.TitanController]). */
sealed interface PairingUiState {
    data object NotPaired : PairingUiState
    data class Working(val note: String) : PairingUiState
    data class AwaitingConfirm(val code6: String, val label: String) : PairingUiState
    data class Error(val message: String) : PairingUiState
    data class Paired(val label: String) : PairingUiState
}

/** A glowing identity orb — Titan's visual signature, reused across screens. */
@Composable
fun TitanOrb(modifier: Modifier = Modifier, size: Int = 96) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    0.0f to TitanCyan,
                    0.5f to TitanViolet,
                    1.0f to TitanInk,
                ),
            ),
    )
}

@Composable
fun PairingScreen(
    state: PairingUiState,
    onScan: () -> Unit,
    onPaste: () -> Unit,
    onRetry: () -> Unit,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().background(TitanAurora),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            TitanOrb()
            Spacer(Modifier.height(22.dp))
            Text(
                "Carry Titan with you",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = TitanText,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Titan stays sovereign on its server. Your phone becomes a signed, " +
                    "private remote — paired once, by a code only you can confirm.",
                style = MaterialTheme.typography.bodyMedium,
                color = TitanMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(36.dp))

            when (state) {
                is PairingUiState.NotPaired -> NotPairedActions(onScan, onPaste)
                is PairingUiState.Working -> Working(state.note)
                is PairingUiState.AwaitingConfirm -> AwaitingConfirm(state.code6, state.label, onConfirmed)
                is PairingUiState.Error -> ErrorBox(state.message, onRetry)
                is PairingUiState.Paired -> PairedBox(state.label)
            }
        }
    }
}

@Composable
private fun NotPairedActions(onScan: () -> Unit, onPaste: () -> Unit) {
    Button(
        onClick = onScan,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        colors = ButtonDefaults.buttonColors(containerColor = TitanCyan, contentColor = TitanInk),
    ) { Text("Scan pairing QR", fontWeight = FontWeight.SemiBold) }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onPaste,
        modifier = Modifier.fillMaxWidth().height(50.dp),
    ) { Text("Paste pairing code", color = TitanText) }
    Spacer(Modifier.height(16.dp))
    Text(
        "Open your Command Center → “Pair phone” to show the QR.",
        style = MaterialTheme.typography.bodySmall,
        color = TitanMuted,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Working(note: String) {
    CircularProgressIndicator(color = TitanCyan)
    Spacer(Modifier.height(18.dp))
    Text(note, color = TitanMuted, textAlign = TextAlign.Center)
}

@Composable
private fun AwaitingConfirm(code6: String, label: String, onConfirmed: () -> Unit) {
    val pretty = if (code6.length == 6) "${code6.substring(0, 3)} ${code6.substring(3)}" else code6
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = TitanSurfaceHi),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Confirm this code", color = TitanMuted, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(14.dp))
            Text(
                pretty,
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp,
                color = TitanCyan,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "Type it into your Command Center to finish pairing “$label”.",
                color = TitanMuted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = onConfirmed,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        colors = ButtonDefaults.buttonColors(containerColor = TitanViolet, contentColor = TitanInk),
    ) { Text("I’ve entered the code", fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit) {
    Text(message, color = TitanWarn, textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
    Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = TitanSurfaceHi)) {
        Text("Try again", color = TitanText)
    }
}

@Composable
private fun PairedBox(label: String) {
    Text("✓ Paired", color = TitanGood, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text("This phone is now $label.", color = TitanMuted)
}
