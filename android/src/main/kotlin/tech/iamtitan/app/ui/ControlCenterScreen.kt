package tech.iamtitan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Titan Control Center — the operations hub. Groups the read/inspect + control surfaces
 * (Diagnostics, Config, Advanced ops) under one entry so the Home screen stays to the
 * everyday Chat / Alerts / Settings. Advanced ops appears only when the advanced-mode
 * toggle is on (Settings, app-lock-gated).
 */
@Composable
fun ControlCenterScreen(
    advancedEnabled: Boolean,
    onBack: () -> Unit,
    onDiagnostics: () -> Unit,
    onConfig: () -> Unit,
    onAdvanced: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(TitanInk).systemBarsPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(TitanSurfaceHi)
                    .clickable { onBack() }.padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("‹ Home", color = TitanText, style = MaterialTheme.typography.labelLarge) }
            Spacer(Modifier.width(12.dp))
            Text("Titan Control Center", color = TitanText, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(2.dp))
        CcTile("Diagnostics", "Liveness · host · subsystems · SOL · backups · journal", onDiagnostics)
        CcTile("Config", "Read and edit Titan's configuration", onConfig)
        if (advancedEnabled) {
            CcTile("Advanced ops", "Per-layer restart · API reload · reboot · cleanup", onAdvanced)
        } else {
            Text(
                "Advanced ops is hidden. Turn it on in Settings → Advanced (asks your app lock).",
                color = TitanMuted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun CcTile(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(TitanSurface).clickable { onClick() }.padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TitanText, fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = TitanMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
