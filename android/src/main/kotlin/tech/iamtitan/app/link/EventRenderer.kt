package tech.iamtitan.app.link

import android.content.Context
import tech.iamtitan.app.chat.ChatAction
import tech.iamtitan.app.chat.ChatTurn
import tech.iamtitan.app.data.ChatStore
import tech.iamtitan.app.net.ConsoleEvent
import tech.iamtitan.app.notify.Notifier

/**
 * Headless rendering of drained events (RFP_titan_app_event_channel §7.2b) — the
 * single code path shared by the WorkManager deep-background drain ([EventPollWorker])
 * and the no-Activity ALWAYS_ON loop ([ConnectionManager] when no renderer is bound).
 * It persists `message`/`reply` events to the transcript (deduped by seq, so opening
 * the app shows them in history) and posts AOSP notifications; a `health` event
 * notifies. A `urgency=="high"` message gets the heads-up urgent notification instead
 * of a normal reply. Unknown types are ignored (forward-compatible).
 *
 * Pure of any FGS / connection concern — the caller decides whether a returned
 * high-urgency event warrants warming the line.
 */
object EventRenderer {

    /** @return true if any rendered event carried `urgency=="high"`. */
    fun render(
        context: Context,
        events: List<ConsoleEvent>,
        session: String,
    ): Boolean {
        val notifier = Notifier(context).apply { ensureChannels() }
        val chatStore = ChatStore(context)
        val turns = chatStore.load(session).toMutableList()
        var changed = false
        var hadUrgent = false
        for (e in events) {
            when (e.type) {
                "message", "reply" -> {
                    val text = e.messageText() ?: continue
                    val id = "evt-${e.seq}"
                    if (turns.none { it.id == id }) {
                        turns.add(
                            ChatTurn(
                                fromMaker = false, text = text,
                                ts = (e.ts * 1000).toLong(), id = id,
                            ),
                        )
                        changed = true
                    }
                    if (e.urgency == "high") {
                        notifier.notifyUrgent(text); hadUrgent = true
                    } else {
                        notifier.notifyReply(text)
                    }
                }
                "health" -> notifier.notifyHealth(
                    e.healthUp(),
                    e.healthText() ?: if (e.healthUp()) "Titan recovered." else "Titan is down.",
                )
                "system" -> {
                    // Channel-2 actionable event (RFP §7.3 3a) — a first-person message with
                    // buttons. Persisted as an actionable turn so it renders as a card in the
                    // chat (and is in history on open), AND surfaced as a notification.
                    val text = e.systemText() ?: continue
                    val id = "evt-${e.seq}"
                    if (turns.none { it.id == id }) {
                        turns.add(
                            ChatTurn(
                                fromMaker = false, text = text,
                                ts = (e.ts * 1000).toLong(), id = id,
                                actions = e.systemActions().map {
                                    ChatAction(it.id, it.label, it.needsApp)
                                },
                            ),
                        )
                        changed = true
                    }
                    notifier.notifySystem(text, e.systemActions(), e.seq)
                }
                else -> Unit
            }
        }
        if (changed) chatStore.save(session, turns)
        return hadUrgent
    }
}
