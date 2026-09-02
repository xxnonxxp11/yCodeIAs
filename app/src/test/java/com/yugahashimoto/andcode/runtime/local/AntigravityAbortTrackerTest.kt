package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Confirmed on a real device: sending a message while Antigravity was still "thinking" killed the
 * in-flight `agy` process (SIGKILL, exit code 137) and the app reported that kill as "agy exited with
 * 137" - an error banner for what should have been a silent cancellation. [AntigravityAbortTracker] is
 * what [AntigravityRuntime.send] consults to tell that kind of intentional kill apart from a real
 * crash that happens to share the same exit code.
 */
class AntigravityAbortTrackerTest {
    @Test
    fun `a session marked intentional is reported exactly once`() {
        val tracker = AntigravityAbortTracker()

        tracker.markIntentional("session-1")

        assertTrue(tracker.consumeIntentional("session-1"))
        // Consuming is destructive: a second read must not still see the same kill as intentional,
        // otherwise an unrelated later crash on the same session id would be silently swallowed too.
        assertFalse(tracker.consumeIntentional("session-1"))
    }

    @Test
    fun `a session never marked is not reported as intentional`() {
        val tracker = AntigravityAbortTracker()

        assertFalse(tracker.consumeIntentional("session-1"))
    }

    @Test
    fun `marking one session does not affect another`() {
        val tracker = AntigravityAbortTracker()

        tracker.markIntentional("session-1")

        assertFalse(tracker.consumeIntentional("session-2"))
        assertTrue(tracker.consumeIntentional("session-1"))
    }

    /**
     * The exact race this guards: an abort() call for a turn that was already finishing on its own.
     * The flag must still be consumed even when the caller ends up taking the "turn finished
     * cleanly" path instead of the "process was killed" path, or it would leak into whatever this
     * session id is used for next.
     */
    @Test
    fun `marking survives being read alongside an unrelated completion path`() {
        val tracker = AntigravityAbortTracker()

        tracker.markIntentional("session-1")
        // Simulates AntigravityRuntime.send consuming the flag unconditionally right after
        // process.waitFor(), before it even looks at whether the turn actually finished.
        val wasIntentional = tracker.consumeIntentional("session-1")
        assertTrue(wasIntentional)

        // A later, unrelated kill of the same session id starts from a clean slate.
        assertFalse(tracker.consumeIntentional("session-1"))
    }
}
