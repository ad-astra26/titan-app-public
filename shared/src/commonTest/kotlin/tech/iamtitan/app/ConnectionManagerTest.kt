package tech.iamtitan.app

import tech.iamtitan.app.link.GRACE_WINDOW_MS
import tech.iamtitan.app.link.Tier
import tech.iamtitan.app.link.holdsLongPoll
import tech.iamtitan.app.link.selectTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Truth table for the pure tier selector. This
 * is the platform-free core of `ConnectionManager`; the Android wiring (loop, FGS,
 * WorkManager) builds on these decisions, so locking them here is the cheapest place
 * to keep the state machine honest.
 */
class ConnectionManagerTest {

    @Test
    fun foreground_always_wins() {
        // A started Activity holds the line regardless of every other input.
        assertEquals(Tier.FOREGROUND, selectTier(foreground = true, sending = false, alwaysOn = false, msSinceBackground = 0))
        assertEquals(Tier.FOREGROUND, selectTier(foreground = true, sending = true, alwaysOn = true, msSinceBackground = Long.MAX_VALUE))
    }

    @Test
    fun alwaysOn_holds_when_backgrounded() {
        // Opt-in beats grace/deep-bg/sending once backgrounded.
        assertEquals(Tier.ALWAYS_ON, selectTier(foreground = false, sending = false, alwaysOn = true, msSinceBackground = Long.MAX_VALUE))
        assertEquals(Tier.ALWAYS_ON, selectTier(foreground = false, sending = true, alwaysOn = true, msSinceBackground = 0))
    }

    @Test
    fun sending_holds_line_when_backgrounded_without_optin() {
        // A reply in flight keeps the line even past the grace window (the short-FGS case).
        assertEquals(Tier.ACTIVE_TASK, selectTier(foreground = false, sending = true, alwaysOn = false, msSinceBackground = GRACE_WINDOW_MS + 1))
        assertEquals(Tier.ACTIVE_TASK, selectTier(foreground = false, sending = true, alwaysOn = false, msSinceBackground = 0))
    }

    @Test
    fun grace_window_then_deep_bg() {
        // Just-backgrounded → best-effort grace; past the window → WorkManager.
        assertEquals(Tier.GRACE, selectTier(foreground = false, sending = false, alwaysOn = false, msSinceBackground = 0))
        assertEquals(Tier.GRACE, selectTier(foreground = false, sending = false, alwaysOn = false, msSinceBackground = GRACE_WINDOW_MS - 1))
        assertEquals(Tier.DEEP_BG, selectTier(foreground = false, sending = false, alwaysOn = false, msSinceBackground = GRACE_WINDOW_MS))
        assertEquals(Tier.DEEP_BG, selectTier(foreground = false, sending = false, alwaysOn = false, msSinceBackground = Long.MAX_VALUE))
    }

    @Test
    fun only_deep_bg_cedes_to_workmanager() {
        assertTrue(Tier.FOREGROUND.holdsLongPoll())
        assertTrue(Tier.ACTIVE_TASK.holdsLongPoll())
        assertTrue(Tier.GRACE.holdsLongPoll())
        assertTrue(Tier.ALWAYS_ON.holdsLongPoll())
        assertFalse(Tier.DEEP_BG.holdsLongPoll())
    }
}
