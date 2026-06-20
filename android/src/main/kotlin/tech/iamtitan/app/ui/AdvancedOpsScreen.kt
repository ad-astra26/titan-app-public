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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.iamtitan.app.net.AgentStatus
import tech.iamtitan.app.net.NervousSystem
import tech.iamtitan.app.net.ProcessScan

/**
 * advanced layered ops console. Reached only when the
 * advanced-mode toggle is on (Settings, app-lock-gated). Honest capability matrix: L2 workers
 * reload/restart/enable; L3 api zero-downtime reload; L0/L1 collapse to a full restart (no fake
 * granularity); host VPS reboot (primary device + typed phrase); zombie/stale reap (allow-listed,
 * dry-run scan → confirm specific PIDs); arweave_devnet prune; console self-status. Every
 * destructive action is confirm-gated; the privileged routes are independently device-authed.
 */
@Composable
fun AdvancedOpsScreen(
    loading: Boolean,
    ns: NervousSystem?,
    scan: ProcessScan?,
    agentStatus: AgentStatus?,
    banner: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onModuleOp: (String, String) -> Unit,
    onReloadApi: () -> Unit,
    onFullRestart: () -> Unit,
    onReboot: (String) -> Unit,
    onScanProcesses: () -> Unit,
    onReap: (List<Int>) -> Unit,
    onPrune: (Boolean) -> Unit,
) {
    // confirm holds a pending (title, message, action); rebootDialog is the typed-phrase gate.
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var rebootDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(TitanInk).systemBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpsPill("‹ Back", onBack)
            Spacer(Modifier.width(12.dp))
            Text("Advanced ops", color = TitanText, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(if (loading) "Refreshing…" else "Pull to refresh ↓", color = TitanMuted,
                style = MaterialTheme.typography.labelSmall)
        }
        banner?.let {
            Text(it, color = TitanCyan, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        TitanPullRefresh(refreshing = loading, onRefresh = onRefresh) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "⚠ Privileged. These actions affect the running Titan and the VPS. Every action " +
                    "is confirmed before it runs.",
                color = TitanWarn, style = MaterialTheme.typography.bodySmall,
            )

            // ── L2 worker modules ──
            OpsCard("Workers (L2)") {
                val modules = ns?.modules?.filter { !it.name.isNullOrBlank() }.orEmpty()
                if (modules.isEmpty()) {
                    KvMutedOps(if (loading) "Loading…" else "No module roster (Titan unreachable?)")
                } else {
                    modules.sortedBy { it.name }.forEach { m ->
                        val name = m.name!!
                        val running = m.state == "running" || m.state == "booted"
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, color = TitanText, fontWeight = FontWeight.Medium,
                                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(m.state ?: "?", color = if (running) TitanGood else TitanBad,
                                    style = MaterialTheme.typography.labelMedium)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActBtn("reload") { onModuleOp("reload", name) }
                                ActBtn("restart") {
                                    confirm = Confirm("Restart $name?",
                                        "Kill-respawns the worker fresh from disk.") { onModuleOp("restart", name) }
                                }
                                ActBtn("enable") { onModuleOp("enable", name) }
                            }
                        }
                    }
                }
            }

            // ── L3 api ──
            OpsCard("API layer (L3)") {
                KvMutedOps("Zero-downtime reload of the api route layer.")
                ActBtn("Reload API") {
                    confirm = Confirm("Reload API layer?",
                        "Rebuilds the api routes in place. Connections continue; no restart.", onReloadApi)
                }
            }

            // ── L0/L1 ──
            OpsCard("Kernel · Trinity (L0/L1)") {
                KvMutedOps("No granular reload at this layer — a code change here needs a full restart.")
                ActBtn("Full restart", danger = true) {
                    confirm = Confirm("Full Titan restart?",
                        "Restarts the whole Titan (dreaming-aware). Use only when L2/L3 can't do it.", onFullRestart)
                }
            }

            // ── Host: reboot ──
            OpsCard("Host · VPS reboot") {
                KvMutedOps("Reboots the entire VPS. Primary device only; needs the typed phrase.")
                ActBtn("Reboot VPS…", danger = true) { rebootDialog = true }
            }

            // ── Host: zombie/stale reap ──
            OpsCard("Host · stale processes") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Scan finds orphaned helper processes (allow-listed). Titan/kernel/console " +
                        "are never touched.", color = TitanMuted,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
                ActBtn("Scan") { onScanProcesses() }
                if (scan != null) {
                    scan.error?.let { KvOps("error", it, TitanBad) }
                    KvOps("scanned", "${scan.count} procs · ${scan.reapable.size} reapable · ${scan.zombies.size} zombie")
                    val reapables = scan.processes.filter { it.reapable }
                    if (reapables.isEmpty()) {
                        KvMutedOps("Nothing reapable — clean.")
                    } else {
                        reapables.forEach { p ->
                            KvOps("• ${p.pid}", "${p.comm ?: "?"} (${p.classification ?: "?"})", TitanWarn)
                        }
                        val pids = reapables.map { it.pid }
                        ActBtn("Reap ${pids.size} orphan(s)", danger = true) {
                            confirm = Confirm("Reap ${pids.size} process(es)?",
                                "Sends SIGTERM to: ${pids.joinToString(", ")}. Re-checked server-side at kill time.") {
                                onReap(pids)
                            }
                        }
                    }
                }
            }

            // ── Host: arweave_devnet prune ──
            OpsCard("Host · prune devnet cache") {
                KvMutedOps("Keep-newest-5 of the devnet Arweave cache (never touches backups/sovereign data).")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActBtn("Dry run") { onPrune(false) }
                    ActBtn("Prune", danger = true) {
                        confirm = Confirm("Prune devnet cache?",
                            "Deletes all but the newest 5 cache entries.") { onPrune(true) }
                    }
                }
            }

            // ── Console self-status ──
            OpsCard("Console") {
                if (agentStatus == null) KvMutedOps(if (loading) "Loading…" else "—")
                else {
                    KvOps("version", agentStatus.version ?: "?")
                    KvOps("uptime", agentStatus.uptimeSeconds?.let { "${it.toInt()}s" } ?: "—")
                    KvOps("Titan reachable", if (agentStatus.titanReachable) "yes" else "no",
                        if (agentStatus.titanReachable) TitanGood else TitanBad)
                }
            }
            Spacer(Modifier.width(4.dp))
        }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(c.title, style = MaterialTheme.typography.titleSmall) },
            text = { Text(c.message, color = TitanMuted, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = { val a = c.action; confirm = null; a() }) {
                    Text("Confirm", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
    if (rebootDialog) {
        RebootDialog(onDismiss = { rebootDialog = false }, onConfirm = { phrase ->
            rebootDialog = false
            onReboot(phrase)
        })
    }
}

private data class Confirm(val title: String, val message: String, val action: () -> Unit)

@Composable
private fun RebootDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reboot the VPS", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This reboots the entire server and every Titan on it. Type REBOOT to confirm.",
                    color = TitanMuted, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                    placeholder = { Text("REBOOT") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.trim() == "REBOOT") {
                Text("Reboot", color = TitanBad)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OpsPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(TitanSurfaceHi)
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, color = TitanText, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun ActBtn(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (danger) TitanSurfaceHi else TitanSurface)
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (danger) TitanBad else TitanCyan,
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun OpsCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(TitanSurface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = TitanText, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun KvOps(key: String, value: String, valueColor: Color = TitanText) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(key, color = TitanMuted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(120.dp))
        Text(value, color = valueColor, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f))
    }
}

@Composable
private fun KvMutedOps(text: String) =
    Text(text, color = TitanMuted, style = MaterialTheme.typography.bodySmall)
