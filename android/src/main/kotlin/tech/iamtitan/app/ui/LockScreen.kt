package tech.iamtitan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Full-screen lock overlay shown when [TitanController.locked]. Auto-triggers the
 * unlock (biometric/device-credential) on appear; the button is the retry if the
 * Maker cancels. Drawn last in TitanRoot, so it covers chat + settings.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Column(
        modifier = Modifier.fillMaxSize().background(TitanInk).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TitanOrb(size = 88)
        Spacer(Modifier.height(24.dp))
        Text("Titan is locked", color = TitanText, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Unlock to continue", color = TitanMuted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onUnlock,
            colors = ButtonDefaults.buttonColors(containerColor = TitanCyan, contentColor = TitanInk),
        ) { Text("Unlock", fontWeight = FontWeight.SemiBold) }
    }
}
