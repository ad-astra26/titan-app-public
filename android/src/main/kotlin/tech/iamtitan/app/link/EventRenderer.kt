package tech.iamtitan.app.link

import android.content.Context
import tech.iamtitan.app.chat.ChatAction
import tech.iamtitan.app.chat.ChatTurn
import tech.iamtitan.app.chat.alertsSessionFor
import tech.iamtitan.app.chat.chatSessionFor
import tech.iamtitan.app.data.ChatStore
import tech.iamtitan.app.net.ConsoleEvent
import tech.iamtitan.app.notify.Notifier

/**
 * Headless rendering of drained events (RFP_titan_app_event_channel §7.2b/§7.3) — the
 * single code path shared by the WorkManager deep-background drain ([EventPollWorker])
 * and the no-Activity ALWAYS_ON loop ([ConnectionManager] when no renderer is bound).
 *
 * Routes by channel (INV-MIS-TWO-CHANNELS): conversational `message`/`reply` persist to the
 * CHAT transcript; `system` (actionable) + `health` persist to the SEPARATE Alerts/Info
 * transcript. Both deduped by seq so opening the app shows them in the right timeline. Posts
 * AOSP notifications (urgent / reply / health / actionable-system). Unknown types ignored.
 *
 * Pure of any FGS / connection concern — the caller decides whether a returned
 * high-urgency event warrants warming the line.
 */
object EventRenderer {

    /** @return true if any rendered event carried `urgency=="high"`. */
    fun render(
        context: Context,
        events: List<ConsoleEvent>,
        deviceId: String,
    ): Boolean {
        val notifier = Notifier(context).apply { ensureChannels() }
        val store = ChatStore(context)
        val chatSession = chatSessionFor(deviceId)
        val alertsSession = alertsSessionFor(deviceId)
        val chat = store.load(chatSession).toMutableList()
        val alerts = store.load(alertsSession).toMutableList()
        var chatChanged = false
        var alertsChanged = false
        var hadUrgent = false
        for (e in events) {
            when (e.type) {
                "message", "reply" -> {
                    val text = e.messageText() ?: continue
                    val id = "evt-${e.seq}"
                    if (chat.none { it.id == id }) {
                        chat.add(ChatTurn(false, text, (e.ts * 1000).toLong(), id = id))
                        chatChanged = true
                    }
                    if (e.urgency == "high") {
                        notifier.notifyUrgent(text); hadUrgent = true
                    } else {
                        notifier.notifyReply(text)
                    }
                }
                "health" -> {
                    val up = e.healthUp()
                    val text = e.healthText() ?: if (up) "Titan recovered." else "Titan is down."
                    val id = "evt-${e.seq}"
                    if (alerts.none { it.id == id }) {
                        alerts.add(ChatTurn(false, text, (e.ts * 1000).toLong(), id = id))
                        alertsChanged = true
                    }
                    notifier.notifyHealth(up, text)
                }
                "system" -> {
                    val text = e.systemText() ?: continue
                    val id = "evt-${e.seq}"
                    if (alerts.none { it.id == id }) {
                        alerts.add(
                            ChatTurn(
                                fromMaker = false, text = text,
                                ts = (e.ts * 1000).toLong(), id = id,
                                actions = e.systemActions().map {
                                    ChatAction(it.id, it.label, it.needsApp)
                                },
                            ),
                        )
                        alertsChanged = true
                    }
                    notifier.notifySystem(text, e.systemActions(), e.seq)
                }
                else -> Unit
            }
        }
        if (chatChanged) store.save(chatSession, chat)
        if (alertsChanged) store.save(alertsSession, alerts)
        return hadUrgent
    }
}
