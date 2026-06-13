package tech.iamtitan.app.link

/**
 * The event-channel connection tiers (RFP_titan_app_event_channel §7.2a). A tier is
 * the strategy for draining the Console Agent's per-device queue right now:
 *
 *  - [FOREGROUND]  — an Activity is started; hold a long-poll, no foreground service
 *                    (the Activity already keeps the process un-frozen).
 *  - [ACTIVE_TASK] — a chat send is in flight while backgrounded; hold the long-poll
 *                    behind the short foreground service so the reply isn't dropped.
 *  - [GRACE]       — just-backgrounded; keep the long-poll best-effort (NO foreground
 *                    service) until [GRACE_WINDOW_MS], catching a reply that lands in
 *                    the first few minutes. The OS may freeze the cached process sooner
 *                    (GrapheneOS is aggressive) — that's accepted; WorkManager + the
 *                    opt-in ALWAYS_ON are the reliable paths.
 *  - [DEEP_BG]     — backgrounded past the grace window, not opted-in; no held loop,
 *                    WorkManager owns the ~15-min cadence.
 *  - [ALWAYS_ON]   — the opt-in persistent foreground service holds the long-poll 24/7
 *                    (wired in §7.2b; selected here only when [alwaysOn]).
 */
enum class Tier { FOREGROUND, ACTIVE_TASK, GRACE, DEEP_BG, ALWAYS_ON }

/** How long the best-effort [Tier.GRACE] held long-poll survives after backgrounding. */
const val GRACE_WINDOW_MS: Long = 5 * 60 * 1000L

/**
 * Pure tier selection — no platform dependencies, fully unit-testable (the caller
 * supplies `msSinceBackground` so this stays clock-free and deterministic).
 *
 * Precedence (first match wins): a started Activity always holds the line in the
 * foreground; otherwise an explicit always-connected opt-in holds it; otherwise an
 * in-flight send keeps the line until the reply lands; otherwise a just-backgrounded
 * app keeps a best-effort grace poll until [GRACE_WINDOW_MS]; otherwise WorkManager
 * (deep background).
 */
fun selectTier(
    foreground: Boolean,
    sending: Boolean,
    alwaysOn: Boolean,
    msSinceBackground: Long,
): Tier = when {
    foreground -> Tier.FOREGROUND
    alwaysOn -> Tier.ALWAYS_ON
    sending -> Tier.ACTIVE_TASK
    msSinceBackground < GRACE_WINDOW_MS -> Tier.GRACE
    else -> Tier.DEEP_BG
}

/** Whether [tier] holds a live long-poll on the app process (vs. ceding to WorkManager). */
fun Tier.holdsLongPoll(): Boolean = this != Tier.DEEP_BG
