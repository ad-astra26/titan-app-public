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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.iamtitan.app.chat.ChatTurn

/**
 * The Channel-2 (Alerts & Info) timeline ( / ) — Titan's
 * system decisions (actionable cards + acknowledgment), health, and ops alerts, kept
 * SEPARATE from the conversational chat. Auto-scrolls to the latest on open.
 */
@Composable
fun AlertsScreen(
    alerts: List<ChatTurn>,
    onBack: () -> Unit,
    onAction: (Int, String, String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(TitanInk).systemBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(TitanSurfaceHi)
                    .clickable { onBack() }.padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("‹ Home", color = TitanText, style = MaterialTheme.typography.labelLarge) }
            Spacer(Modifier.width(12.dp))
            Text("Alerts & info", color = TitanText, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium)
        }
        if (alerts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing yet — Titan's alerts and decisions will appear here.",
                    color = TitanMuted, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(32.dp))
            }
            return@Column
        }
        val listState = rememberLazyListState()
        LaunchedEffect(alerts.size) {
            if (alerts.isNotEmpty()) listState.scrollToItem(alerts.size - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            items(alerts) { item -> AlertCard(item, onAction) }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun AlertCard(turn: ChatTurn, onAction: (Int, String, String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(TitanSurface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(turn.text, color = TitanText, style = MaterialTheme.typography.bodyMedium)
        if (turn.actions.isNotEmpty()) {
            val responded = turn.respondedAction
            if (responded != null) {
                val label = turn.actions.firstOrNull { it.id == responded }?.label ?: responded
                Text("✓ $label", color = TitanCyan, style = MaterialTheme.typography.labelMedium)
            } else {
                val seq = turn.id.removePrefix("evt-").toIntOrNull() ?: -1
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    turn.actions.forEach { a ->
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(TitanCyan)
                                .clickable { onAction(seq, a.id, a.label) }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        ) {
                            Text(a.label, color = TitanInk,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
