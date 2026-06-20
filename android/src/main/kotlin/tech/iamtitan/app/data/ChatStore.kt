package tech.iamtitan.app.data

import tech.iamtitan.app.chat.ChatTurn
import tech.iamtitan.app.chat.decodeTranscript
import tech.iamtitan.app.chat.encodeTranscript
import android.content.Context
import java.io.File

/**
 * Local, app-private transcript persistence — fixes the "chat history gone after
 * the process is killed" quirk. One JSON file per chat session under `filesDir`;
 * the list is capped to [MAX_TURNS] most-recent lines so it cannot grow unbounded.
 *
 * Storage is the app's private files dir (readable only by this app on a
 * non-rooted device); UI access is additionally gated by the app lock. Writes go
 * through a temp file + rename so a crash mid-write can't truncate the history.
 * (At-rest encryption is a deliberate later-hardening option, not v1.)
 *
 * Serialization is delegated to `shared` (`encodeTranscript`/`decodeTranscript`)
 * so the JSON contract is tested cross-language and this class owns only file I/O.
 */
class ChatStore(context: Context) {
    private val dir = File(context.filesDir, "chat").apply { mkdirs() }

    /** Load the persisted transcript for [session]; empty list if none/corrupt. */
    fun load(session: String): List<ChatTurn> {
        val f = fileFor(session)
        if (!f.exists()) return emptyList()
        return runCatching { decodeTranscript(f.readText()) }.getOrElse { emptyList() }
    }

    /** Persist [turns] for [session] (capped to the most-recent [MAX_TURNS]). */
    fun save(session: String, turns: List<ChatTurn>) {
        val trimmed = if (turns.size > MAX_TURNS) turns.takeLast(MAX_TURNS) else turns
        runCatching {
            val tmp = File(dir, "${sessionKey(session)}.json.tmp")
            tmp.writeText(encodeTranscript(trimmed))
            if (!tmp.renameTo(fileFor(session))) {
                fileFor(session).writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    /** Drop the persisted transcript for [session] (e.g. on unpair). */
    fun clear(session: String) {
        fileFor(session).delete()
    }

    /** Mark a system turn's chosen action ( 3a → the in-card "✓ Acknowledged").
     * Used by the headless [tech.iamtitan.app.link.ResponseReceiver] so a notification-button
     * tap is reflected in the transcript when the app is next opened. No-op if absent. */
    fun markResponded(session: String, turnId: String, actionId: String) {
        val turns = load(session)
        val i = turns.indexOfFirst { it.id == turnId }
        if (i < 0 || turns[i].respondedAction != null) return
        save(session, turns.toMutableList().also { it[i] = it[i].copy(respondedAction = actionId) })
    }

    private fun fileFor(session: String): File = File(dir, "${sessionKey(session)}.json")

    private companion object {
        const val MAX_TURNS = 400

        /** Map an arbitrary session id to a safe, bounded filename fragment. */
        fun sessionKey(session: String): String =
            buildString {
                for (c in session.take(80)) {
                    append(if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_')
                }
            }
    }
}
