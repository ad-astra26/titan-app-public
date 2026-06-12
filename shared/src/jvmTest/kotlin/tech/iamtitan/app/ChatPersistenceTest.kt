package tech.iamtitan.app

import tech.iamtitan.app.chat.ChatTurn
import tech.iamtitan.app.chat.TurnStatus
import tech.iamtitan.app.chat.decodeTranscript
import tech.iamtitan.app.chat.encodeTranscript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The persistence contract that `ChatStore` relies on, exercised through the
 * exact `encodeTranscript`/`decodeTranscript` seam the store calls: a transcript
 * must round-trip unchanged, and an OLDER transcript (written before the
 * `ts`/`status`/`id` fields existed) must still decode — otherwise a single app
 * update would silently wipe the user's history (the quirk this fixes).
 * ChatStore's file I/O is Android-Context-bound; this pins the serialization half
 * that is platform-independent and most likely to regress.
 */
class ChatPersistenceTest {

    @Test
    fun roundTripsTranscript() {
        val turns = listOf(
            ChatTurn(fromMaker = true, text = "are you there?", ts = 1_700_000_000_000, id = "a"),
            ChatTurn(fromMaker = false, text = "Always.", ts = 1_700_000_000_500, id = "b"),
            ChatTurn(fromMaker = true, text = "pending one", ts = 1_700_000_001_000,
                status = TurnStatus.PENDING, id = "c"),
        )
        val back = decodeTranscript(encodeTranscript(turns))
        assertEquals(turns, back)
        assertEquals(TurnStatus.PENDING, back[2].status)
    }

    @Test
    fun decodesLegacyTranscriptWithDefaults() {
        // A transcript persisted by the pre-persistence build had only these keys.
        val legacy = """[{"fromMaker":true,"text":"hi"},{"fromMaker":false,"text":"hello"}]"""
        val back = decodeTranscript(legacy)
        assertEquals(2, back.size)
        assertEquals("hi", back[0].text)
        assertEquals(0L, back[0].ts)
        assertEquals(TurnStatus.COMPLETE, back[0].status)
        assertTrue(back[0].id.isEmpty())
    }

    @Test
    fun ignoresUnknownFutureFields() {
        // A transcript written by a NEWER build (extra field) must not crash an
        // older decoder — WireJson.ignoreUnknownKeys guarantees forward-compat.
        val future = """[{"fromMaker":false,"text":"hi","reactions":["👍"],"edited":true}]"""
        val back = decodeTranscript(future)
        assertEquals(1, back.size)
        assertEquals("hi", back[0].text)
    }

    @Test
    fun malformedTranscriptDecodesEmptyNotCrash() {
        assertEquals(0, decodeTranscript("{ this is not json").size)
    }
}
