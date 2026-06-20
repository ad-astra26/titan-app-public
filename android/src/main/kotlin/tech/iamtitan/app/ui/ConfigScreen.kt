package tech.iamtitan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.iamtitan.app.net.ConfigEntry
import tech.iamtitan.app.net.SetConfigResult

/**
 * config browser + editor. Lists every config key (value + help + source file),
 * filterable by section. Editable keys (server-guarded `editable:true`) open an edit dialog;
 * the write is signed via the Maker's configured app-lock/biometric window (every command is
 * authenticated). Non-editable keys render read-only with a lock hint.
 */
@Composable
fun ConfigScreen(
    loading: Boolean,
    sections: List<String>,
    entries: List<ConfigEntry>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSave: (String, String, (SetConfigResult) -> Unit) -> Unit,
) {
    var selectedSection by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ConfigEntry?>(null) }
    var banner by remember { mutableStateOf<String?>(null) }

    val shown = entries.filter { selectedSection == null || it.section == selectedSection }

    Column(modifier = Modifier.fillMaxSize().background(TitanInk).systemBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConfigPill("‹ Back", onBack)
            Spacer(Modifier.width(12.dp))
            Text("Config", color = TitanText, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            ConfigPill(if (loading) "…" else "↻", onRefresh)
        }
        banner?.let {
            Text(it, color = TitanWarn, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        // Section filter chips.
        if (sections.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionChip("all", selectedSection == null) { selectedSection = null }
                sections.forEach { s -> SectionChip(s, selectedSection == s) { selectedSection = s } }
            }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (loading) "Loading config…" else "No keys.",
                    color = TitanMuted, style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { "${it.file}:${it.dotted}:${it.lineno}" }) { e ->
                ConfigRow(e) { if (e.editable) editing = e }
            }
            item { Spacer(Modifier.width(4.dp)) }
        }
    }

    editing?.let { entry ->
        EditConfigDialog(
            entry = entry,
            onDismiss = { editing = null },
            onSave = { newValue ->
                val target = entry
                editing = null
                banner = "Saving ${target.dotted}…"
                onSave(target.dotted, newValue) { r ->
                    banner = if (r.ok) "✓ ${target.dotted} = ${r.value}" else "⚠ ${r.error ?: "save failed"}"
                }
            },
        )
    }
}

@Composable
private fun ConfigRow(e: ConfigEntry, onEdit: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(TitanSurface)
            .let { if (e.editable) it.clickable { onEdit() } else it }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.dotted, color = TitanText, fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(if (e.editable) "edit ›" else "🔒",
                color = if (e.editable) TitanCyan else TitanMuted,
                style = MaterialTheme.typography.labelMedium)
        }
        Text(e.value ?: "—", color = TitanGood, style = MaterialTheme.typography.bodySmall)
        e.help?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = TitanMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EditConfigDialog(entry: ConfigEntry, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(entry.value ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.dotted, style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entry.help?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = TitanMuted, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) {
                Text("Save", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfigPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(TitanSurfaceHi)
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, color = TitanText, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun SectionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) TitanCyan else TitanSurfaceHi)
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (selected) TitanInk else TitanText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}
