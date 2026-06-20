package tech.iamtitan.app.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed views of the Console Agent's diagnostics / config readouts (RFP_titan_mobile_app
 * Phase 2a). Every field name is bound to a verified backend symbol (traced in
 * `titan_console/{host,titan_status,config_api}.py` + `titan_hcl/api/v6.py`); decoding is
 * lenient (`WireJson` has ignoreUnknownKeys) so extra/renamed server fields never crash the
 * app — they're just ignored, and absent fields fall back to the declared defaults.
 */

// ── GET /console/host → read_host_resources() ──
@Serializable
data class HostResources(
    val cpu: HostCpu? = null,
    val memory: HostMem? = null,
    val swap: HostMem? = null,
    val disk: HostDisk? = null,
)

@Serializable
data class HostCpu(
    val count: Int? = null,
    val busy_percent: Double? = null,
    val load1: Double? = null,
    val load1_per_core: Double? = null,
)

@Serializable
data class HostMem(
    val total: Long? = null,
    val used: Long? = null,
    val available: Long? = null,
    val free: Long? = null,
    val percent: Double? = null,
)

@Serializable
data class HostDisk(
    val total: Long? = null,
    val used: Long? = null,
    val free: Long? = null,
    val percent: Double? = null,
)

// ── GET /console/titan-status → titan_status() ──
@Serializable
data class TitanLiveness(
    val titan_id: String? = null,
    val service: String? = null,
    val up: Boolean = false,
    val systemd: Systemd? = null,
    val why_down: String? = null,
    val journal_tail: List<String> = emptyList(),
)

@Serializable
data class Systemd(val active_state: String? = null, val sub_state: String? = null)

// ── GET /console/api/v6/readiness → get_v6_readiness worker-module summary (v6.py) ──
@Serializable
data class NervousSystem(
    val module_count: Int? = null,
    val module_running_count: Int? = null,
    val module_state_summary: ModuleSummary? = null,
    val modules: List<ModuleInfo> = emptyList(),
)

@Serializable
data class ModuleSummary(
    val running: Int = 0,
    val booted: Int = 0,
    val starting: Int = 0,
    @SerialName("unhealthy_or_crashed") val unhealthyOrCrashed: Int = 0,
    @SerialName("not_booted") val notBooted: Int = 0,
)

@Serializable
data class ModuleInfo(
    val name: String? = null,
    val state: String? = null,
    val pid: Int? = null,
)

// ── GET /console/journal?lines=N → {service, lines[]} ──
@Serializable
data class JournalTail(
    val service: String? = null,
    val lines: List<String> = emptyList(),
)

// ── GET /console/config[?section=] → config_api.list_config ──
@Serializable
data class ConfigList(
    val sections: List<String> = emptyList(),
    val entries: List<ConfigEntry> = emptyList(),
)

@Serializable
data class ConfigEntry(
    val file: String? = null,
    val section: String? = null,
    val key: String? = null,
    val dotted: String,
    val value: String? = null,
    val help: String? = null,
    val editable: Boolean = false,
    val lineno: Int? = null,
)

// ── POST /console/config/set {key,value} → config_api.set_config ──
@Serializable
data class SetConfigBody(val key: String, val value: String)

@Serializable
data class SetConfigResult(
    val ok: Boolean = false,
    val value: String? = null,
    val file: String? = null,
    val dotted: String? = null,
    val error: String? = null,
)

/**
 * Plain (non-serialized) views for the two readouts whose backend scalar types are
 * uncertain (`/v6/metabolism/gate-status` is wrapped in `{status, data:{…}}`; `/console/backups`
 * records carry a `ts`/`size_bytes` whose type — epoch vs ISO string — isn't pinned). These
 * are parsed DEFENSIVELY in `ConsoleClient` (shared, where kotlinx.json lives) so the Android
 * layer never imports a JSON type and a single odd scalar can't null the whole card.
 */
data class MetabolismView(val tier: String? = null, val solBalance: Double? = null)

data class BackupView(
    val records: Int = 0,
    val latestType: String? = null,
    val latestTs: String? = null,
    val arweaveEvents: Int? = null,
)

// ── Phase 2b — advanced layered ops (RFP_titan_mobile_app §7.2b). Every field bound to a
//    verified backend symbol in titan_console/{ops,agent,proxy}.py; decoding is lenient. ──

/** Result of an L2/L3 admin-proxy op (module reload/restart/enable, reload-api). The kernel
 *  returns `{status:"ok", data:…}`; proxy_admin may also surface `{error}`/`{titan_down}`. The
 *  `data` payload is intentionally not modelled (ignored). [succeeded] is the UI's verdict. */
@Serializable
data class OpsResult(
    val status: String? = null,
    val ok: Boolean? = null,
    val error: String? = null,
    @SerialName("titan_down") val titanDown: Boolean = false,
    val detail: String? = null,
) {
    val succeeded: Boolean get() = !titanDown && error == null && (status == "ok" || ok == true)
    val message: String get() = error ?: detail ?: status ?: if (succeeded) "ok" else "failed"
}

/** POST /console/ops/reboot → ops.reboot. */
@Serializable
data class RebootResult(
    val ok: Boolean = false,
    val error: String? = null,
    val rebooting: Boolean = false,
)

/** GET /console/ops/processes → ops.scan_processes (ALWAYS a dry run). */
@Serializable
data class ProcessScan(
    @SerialName("dry_run") val dryRun: Boolean = true,
    val count: Int = 0,
    val reapable: List<Int> = emptyList(),
    val zombies: List<Int> = emptyList(),
    val processes: List<ProcInfo> = emptyList(),
    val error: String? = null,
)

@Serializable
data class ProcInfo(
    val pid: Int = 0,
    val ppid: Int = 0,
    val state: String? = null,
    val comm: String? = null,
    val cmdline: String? = null,
    @SerialName("rss_kb") val rssKb: Long = 0,
    val classification: String? = null,
    val reapable: Boolean = false,
    val note: String? = null,
)

/** POST /console/ops/processes/reap → ops.reap_processes. */
@Serializable
data class ReapResult(
    val requested: Int = 0,
    val killed: Int = 0,
    val results: List<ReapItem> = emptyList(),
    val error: String? = null,
)

@Serializable
data class ReapItem(
    val pid: Int = 0,
    val killed: Boolean = false,
    val comm: String? = null,
    val skipped: String? = null,
)

/** POST /console/ops/prune-arweave-devnet → ops.prune_arweave_devnet. */
@Serializable
data class PruneResult(
    val confirm: Boolean = false,
    val exists: Boolean = false,
    val keep: Int = 0,
    val kept: Int = 0,
    @SerialName("reclaimable_bytes") val reclaimableBytes: Long = 0,
    @SerialName("removed_bytes") val removedBytes: Long = 0,
)

/** GET /console/agent-status → ops.agent_status. */
@Serializable
data class AgentStatus(
    val agent: String? = null,
    val version: String? = null,
    @SerialName("titan_id") val titanId: String? = null,
    @SerialName("uptime_seconds") val uptimeSeconds: Double? = null,
    @SerialName("bind_port") val bindPort: Int? = null,
    @SerialName("titan_reachable") val titanReachable: Boolean = false,
)

// ── ops request bodies ──
@Serializable
data class ReapBody(val pids: List<Int>)

@Serializable
data class PruneBody(val keep: Int, val confirm: Boolean)

@Serializable
data class RebootBody(@SerialName("confirm_phrase") val confirmPhrase: String)
