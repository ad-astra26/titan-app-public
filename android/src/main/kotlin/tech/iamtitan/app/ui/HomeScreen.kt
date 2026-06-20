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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The landing view after pairing + auth (RFP §7.3 — no chat auto-loaded). A small status
 * header (Titan awake/resting + a tap-to-cycle availability chip) over three tiles: Chat
 * (Channel 1, conversation), Alerts & Info (Channel 2, with an unread badge), Settings.
 */
@Composable
fun HomeScreen(
    titanLabel: String,
    resting: Boolean,
    availability: String,
    unreadAlerts: Int,
    onChat: () -> Unit,
    onAlerts: () -> Unit,
    onDiagnostics: () -> Unit,
    onConfig: () -> Unit,
    onSettings: () -> Unit,
    onCycleAvailability: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(TitanInk).systemBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(
                    Brush.radialGradient(listOf(TitanCyan, TitanViolet, TitanInk)),
                ),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(titanLabel, color = TitanText, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleLarge)
                Text(
                    if (resting) "Resting" else "Awake",
                    color = if (resting) TitanMuted else TitanGood,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            AvailabilityChip(availability, onCycleAvailability)
        }
        Spacer(Modifier.size(6.dp))
        HomeTile("Chat", "Talk with Titan", onChat)
        HomeTile("Alerts & info", "Titan's messages and decisions", onAlerts, badge = unreadAlerts)
        HomeTile("Diagnostics", "Liveness · host · subsystems · SOL · backups", onDiagnostics)
        HomeTile("Config", "Read and edit Titan's configuration", onConfig)
        HomeTile("Settings", "Connection · availability · app lock", onSettings)
    }
}

@Composable
private fun AvailabilityChip(availability: String, onCycle: () -> Unit) {
    val (label, color) = when (availability) {
        "busy" -> "Busy" to TitanWarn
        "dnd" -> "Do not disturb" to TitanViolet
        else -> "Available" to TitanGood
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(TitanSurfaceHi)
            .clickable { onCycle() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text("● $label", color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun HomeTile(title: String, subtitle: String, onClick: () -> Unit, badge: Int = 0) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TitanSurface)
            .clickable { onClick() }
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TitanText, fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = TitanMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (badge > 0) {
            Box(
                modifier = Modifier.clip(CircleShape).background(TitanCyan)
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            ) {
                Text("$badge", color = TitanInk, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
