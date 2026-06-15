package tech.iamtitan.app.link

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.iamtitan.app.MainActivity
import tech.iamtitan.app.chat.chatSessionFor
import tech.iamtitan.app.crypto.DeviceKey
import tech.iamtitan.app.data.ChatStore
import tech.iamtitan.app.data.PairingStore
import tech.iamtitan.app.net.AndroidHttpTransport
import tech.iamtitan.app.net.ConsoleClient
import tech.iamtitan.app.notify.Notifier

/**
 * Headless handler for a Channel-2 action button tap (RFP §7.3 3a, the hybrid-by-stakes
 * UX). A low-stakes action's notification button targets this receiver, which signs +
 * POSTs `/console/events/respond` using the device key's 8-hour auth window — no app open.
 *
 * If the window has lapsed (no Activity here to prompt a biometric), it falls back to
 * launching [MainActivity] carrying the same response intent, so the tap is never silently
 * dropped — the Maker just completes it foreground. (High-stakes `needs_app` actions skip
 * this receiver entirely and open the app directly.)
 */
class ResponseReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifier.ACTION_RESPOND) return
        val seq = intent.getIntExtra(Notifier.EXTRA_SEQ, -1)
        val actionId = intent.getStringExtra(Notifier.EXTRA_ACTION_ID) ?: return
        val label = intent.getStringExtra(Notifier.EXTRA_ACTION_LABEL) ?: actionId
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (sendHeadless(app, seq, actionId)) {
                    // Visual ack (RFP §7.3): clear the lingering notification + reflect the
                    // choice in the transcript so the in-app card shows "✓ Acknowledged".
                    Notifier(app).ackSystem(seq, label)
                    PairingStore(app).deviceId?.let {
                        ChatStore(app).markResponded(chatSessionFor(it), "evt-$seq", actionId)
                    }
                } else {
                    openAppFallback(app, seq, actionId)
                }
            } catch (_: Exception) {
                openAppFallback(app, seq, actionId)
            } finally {
                pending.finish()
            }
        }
    }

    /** @return true iff the signed POST went through (window open + 200). */
    private suspend fun sendHeadless(app: Context, seq: Int, actionId: String): Boolean {
        val store = PairingStore(app)
        if (!store.paired) return false
        val endpoint = store.endpointUrl ?: return false
        // No Activity → the provider throws on a lapsed window; we then fall back to the app.
        val key = DeviceKey.existing(app, store) { error("no activity in receiver") }
            ?: return false
        val client = ConsoleClient(endpoint, AndroidHttpTransport(tlsPin = store.tlsPin))
        return withContext(Dispatchers.IO) {
            client.respond(key, inReplyTo = if (seq >= 0) seq else null,
                           kind = "action", actionId = actionId)
        }
    }

    private fun openAppFallback(app: Context, seq: Int, actionId: String) {
        val intent = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(Notifier.EXTRA_ACTION, Notifier.ACTION_RESPOND)
            .putExtra(Notifier.EXTRA_SEQ, seq)
            .putExtra(Notifier.EXTRA_ACTION_ID, actionId)
        app.startActivity(intent)
    }
}
