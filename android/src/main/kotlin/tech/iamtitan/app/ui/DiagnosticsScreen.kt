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
import tech.iamtitan.app.net.BackupView
import tech.iamtitan.app.net.HostResources
import tech.iamtitan.app.net.JournalTail
import tech.iamtitan.app.net.MetabolismView
import tech.iamtitan.app.net.NervousSystem
import tech.iamtitan.app.net.TitanLiveness

/**
 * read-only diagnostics console. Renders the signed Console readouts the
 * controller fetched (status / host / subsystem health / metabolism-SOL / backups / journal).
 * Monitor-only per the scope-fence; the advanced per-layer ops live in.
 */
@Composable
fun DiagnosticsScreen(
    loading: Boolean,
    status: TitanLiveness?,
    host: HostResources?,
    ns: NervousSystem?,
    metabolism: MetabolismView?,
    backups: BackupView?,
    journal: JournalTail?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(TitanInk).systemBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton("‹ Back", onBack)
            Spacer(Modifier.width(12.dp))
            Text("Diagnostics", color = TitanText, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(if (loading) "Refreshing…" else "Pull to refresh ↓", color = TitanMuted,
                style = MaterialTheme.typography.labelSmall)
        }
        TitanPullRefresh(refreshing = loading, onRefresh = onRefresh) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Liveness ──
            DiagCard("Status") {
                if (status == null) {
                    KvMuted(if (loading) "Loading…" else "Unreachable")
                } else {
                    val up = status.up
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (up) "● Awake" else "● Down",
                            color = if (up) TitanGood else TitanBad, fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.titleMedium)
                    }
                    status.systemd?.let { Kv("systemd", "${it.active_state}/${it.sub_state}") }
                    status.service?.let { Kv("service", it) }
                    status.why_down?.let { Kv("why down", it, TitanWarn) }
                }
            }
            // ── Host resources ──
            DiagCard("Host") {
                if (host == null) KvMuted(if (loading) "Loading…" else "Unreachable")
                else {
                    host.cpu?.let { c ->
                        Kv("CPU", "${pct(c.busy_percent)} busy · ${c.count ?: "?"} cores")
                        Kv("load", "${num(c.load1)} (1m) · ${num(c.load1_per_core)}/core")
                    }
                    host.memory?.let { Kv("memory", "${pct(it.percent)} · ${gb(it.used)}/${gb(it.total)}") }
                    host.swap?.let { if ((it.total ?: 0L) > 0L) Kv("swap", "${pct(it.percent)} · ${gb(it.used)}/${gb(it.total)}") }
                    host.disk?.let { Kv("disk", "${pct(it.percent)} · ${gb(it.used)}/${gb(it.total)}", diskColor(it.percent)) }
                }
            }
            // ── Subsystem / module health ──
            DiagCard("Subsystem health") {
                if (ns == null) KvMuted(if (loading) "Loading…" else "Unreachable")
                else {
                    val s = ns.module_state_summary
                    Kv("modules", "${ns.module_running_count ?: 0}/${ns.module_count ?: 0} running")
                    if (s != null) {
                        val bad = s.unhealthyOrCrashed + s.notBooted
                        Kv("state", "▸ ${s.running} running · ${s.booted} booted · ${s.starting} starting",
                            if (bad > 0) TitanWarn else TitanMuted)
                        if (bad > 0) Kv("⚠ down", "${s.unhealthyOrCrashed} unhealthy · ${s.notBooted} not-booted", TitanBad)
                    }
                    // Name the non-running modules so a glance tells you WHICH is down.
                    val down = ns.modules.filter { it.state != null && it.state != "running" && it.state != "booted" }
                    if (down.isNotEmpty()) {
                        Kv("offline", down.joinToString(", ") { "${it.name}:${it.state}" }, TitanBad)
                    }
                }
            }
            // ── Metabolism (SOL / energy) ──
            DiagCard("Metabolism · SOL") {
                if (metabolism == null) KvMuted(if (loading) "Loading…" else "Unavailable")
                else {
                    Kv("SOL balance", metabolism.solBalance?.let { "◎ %.6f".format(it) } ?: "—")
                    metabolism.tier?.let { Kv("metabolic tier", it) }
                }
            }
            // ── Backups ──
            DiagCard("Backups") {
                if (backups == null) KvMuted(if (loading) "Loading…" else "Unreachable")
                else {
                    Kv("records", "${backups.records}")
                    backups.latestType?.let { Kv("latest", "$it${backups.latestTs?.let { t -> " · $t" } ?: ""}") }
                    backups.arweaveEvents?.let { Kv("arweave events", "$it") }
                }
            }
            // ── Journal tail ──
            DiagCard("Journal (recent)") {
                val lines = journal?.lines.orEmpty()
                if (lines.isEmpty()) KvMuted(if (loading) "Loading…" else "No recent lines")
                else Text(
                    lines.takeLast(40).joinToString("\n"),
                    color = TitanMuted, style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Normal,
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        }
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(TitanSurfaceHi)
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, color = TitanText, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun DiagCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(TitanSurface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = TitanText, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun Kv(key: String, value: String, valueColor: androidx.compose.ui.graphics.Color = TitanText) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(key, color = TitanMuted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(120.dp))
        Text(value, color = valueColor, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f))
    }
}

@Composable
private fun KvMuted(text: String) =
    Text(text, color = TitanMuted, style = MaterialTheme.typography.bodySmall)

// ── tiny formatters ──
private fun pct(p: Double?): String = if (p == null) "—" else "${p.toInt()}%"
private fun num(d: Double?): String = if (d == null) "—" else ((d * 100).toInt() / 100.0).toString()
private fun gb(bytes: Long?): String =
    if (bytes == null) "—" else "%.1fG".format(bytes / 1_073_741_824.0)
private fun diskColor(p: Double?) = when {
    p == null -> TitanText
    p >= 90 -> TitanBad
    p >= 75 -> TitanWarn
    else -> TitanText
}
