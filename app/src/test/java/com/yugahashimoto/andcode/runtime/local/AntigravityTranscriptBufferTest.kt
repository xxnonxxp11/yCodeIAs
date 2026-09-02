package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sign-in transcript has to outlive a repaint.
 *
 * agy is a full-screen Bubble Tea program, so each redraw rewrites the whole screen. The buffer was
 * a flat 16,000 characters against a 24x1000 PTY - one repaint was larger than the entire buffer, so
 * the line carrying the beginning of the sign-in URL was dropped as soon as the TUI redrew, and
 * [AntigravityAuthParser.findOAuthUrl] (which anchors on that beginning) found nothing. Sign-in then
 * sat in Starting until the discovery watchdog killed the process 120 seconds later.
 */
class AntigravityTranscriptBufferTest {
    private val screen = AntigravitySandboxLauncher.PTY_ROWS * AntigravitySandboxLauncher.PTY_COLUMNS

    @Test
    fun `the buffer holds more than one full repaint`() {
        assertTrue(
            "a ${AntigravitySandboxLauncher.PTY_ROWS}x${AntigravitySandboxLauncher.PTY_COLUMNS} repaint is $screen chars, " +
                "buffer is ${AntigravityAuthCoordinator.MAX_TRANSCRIPT}",
            AntigravityAuthCoordinator.MAX_TRANSCRIPT >= 2 * screen,
        )
    }

    /** The URL still parses out after a whole screen of repaint lands on top of it. */
    @Test
    fun `the url survives a repaint inside the buffer`() {
        val url =
            "https://accounts.google.com/o/oauth2/auth?response_type=code&client_id=test.apps." +
                "googleusercontent.com&redirect_uri=http%3A%2F%2Flocalhost%3A8080&scope=openid&state=abc123"
        val repaint = buildString { repeat(AntigravitySandboxLauncher.PTY_ROWS) { appendLine("-".repeat(80)) } }
        val transcript = StringBuilder()
        transcript.append(" Open the URL below in your browser:\n").append(url).append('\n')
        transcript.append(repaint)
        if (transcript.length > AntigravityAuthCoordinator.MAX_TRANSCRIPT) {
            transcript.delete(0, transcript.length - AntigravityAuthCoordinator.MAX_TRANSCRIPT)
        }

        assertNotNull(AntigravityAuthParser.findOAuthUrl(transcript.toString()))
    }
}
