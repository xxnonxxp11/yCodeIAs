package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are trimmed from real `agy 1.1.7` transcripts captured under PRoot on emulator-5554,
 * including the ANSI control sequences the Bubble Tea TUI emits.
 */
class AntigravityAuthParserTest {
    private val url =
        "https://accounts.google.com/o/oauth2/auth?access_type=offline&client_id=1071006060591-" +
            "tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com&code_challenge=" +
            "ysBQb_VqUWCjkzAkakr0PiHUyovIsoXGkXMEQWaFVKk&code_challenge_method=S256&prompt=consent" +
            "&redirect_uri=https%3A%2F%2Fantigravity.google%2Foauth-callback&response_type=code" +
            "&scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fcloud-platform+openid" +
            "&state=rGNljQ3DYOQBW1BbqzHU4Q"

    private fun wrapped(width: Int) = url.chunked(width).joinToString("\n") { " $it" }

    @Test
    fun `strips ansi control sequences`() {
        val raw = "[?1049h[H[2J Select login method:[m"
        assertEquals(" Select login method:", AntigravityAuthParser.stripAnsi(raw))
    }

    @Test
    fun `detects the login chooser`() {
        val clean = " Welcome to the Antigravity CLI. You are currently not signed in.\n Select login method:\n > 1. Google OAuth\n2. Use a Google Cloud project\n"
        assertTrue(AntigravityAuthParser.isLoginMenuVisible(clean))
        assertFalse(AntigravityAuthParser.isAwaitingCode(clean))
        assertNull(AntigravityAuthParser.findOAuthUrl(clean))
    }

    @Test
    fun `reads the url when a wide pty keeps it on one line`() {
        val clean = " Open the URL below in your browser:\n $url    \n After authenticating, copy the code displayed in the browser and paste it below:\n"
        assertEquals(url, AntigravityAuthParser.findOAuthUrl(clean))
        assertTrue(AntigravityAuthParser.isAwaitingCode(clean))
    }

    @Test
    fun `stitches a url hard-wrapped to the terminal width`() {
        val clean =
            " Open the URL below in your browser:\n" +
                " ────────────────────────\n" +
                wrapped(110) + "\n" +
                " ────────────────────────\n" +
                " After authenticating, copy the code displayed in the browser and paste it below:\n"
        assertEquals(url, AntigravityAuthParser.findOAuthUrl(clean))
    }

    @Test
    fun `stops stitching at the local-browser variant prose`() {
        val clean =
            " Your browser should open automatically. If not:\n" +
                wrapped(400) + "\n" +
                " If you aren't automatically redirected, paste the authorization code below:\n"
        assertEquals(url, AntigravityAuthParser.findOAuthUrl(clean))
        assertTrue(AntigravityAuthParser.isAwaitingCode(clean))
    }

    @Test
    fun `prefers the most recent url across tui redraws`() {
        val stale = url.replace("state=rGNljQ3DYOQBW1BbqzHU4Q", "state=OLDOLDOLDOLDOLDOLDOLD")
        val clean = " $stale\n Select login method:\n $url\n"
        assertEquals(url, AntigravityAuthParser.findOAuthUrl(clean))
    }

    @Test
    fun `ignores non-google urls`() {
        assertNull(AntigravityAuthParser.findOAuthUrl(" See https://antigravity.google/docs for help\n"))
    }

    @Test
    fun `classifies failure and success markers`() {
        assertTrue(AntigravityAuthParser.isFailure("consumerOAuth: token exchange failed"))
        assertTrue(AntigravityAuthParser.isFailure("Error: Invalid state parameter"))
        assertFalse(AntigravityAuthParser.isFailure(" Select login method:"))
        assertTrue(AntigravityAuthParser.isSignedIn(" Successfully signed in as someone"))
        assertTrue(AntigravityAuthParser.isLocalBrowserMode("Entering local chrome mode!"))
    }

    @Test
    fun `redacts the url from diagnostics`() {
        val clean = " Open the URL below in your browser:\n" + wrapped(110) + "\n authorization code...\n"
        val redacted = AntigravityAuthParser.redact(clean)
        assertFalse(redacted.contains("code_challenge"))
        assertFalse(redacted.contains("state=rGNljQ3DYOQBW1BbqzHU4Q"))
        assertTrue(redacted.contains("Open the URL below in your browser:"))
    }

    /**
     * The coordinator keeps only the tail of the transcript, so the line carrying the URL's scheme
     * and host scrolls out of that window while the wrapped remainder stays. Anchoring redaction on
     * the prefix alone let that remainder through, and a failed sign-in put the live authorization
     * request's `state` parameter on screen.
     */
    @Test
    fun `redacts a url fragment whose beginning has scrolled away`() {
        val clean =
            "  Antigravity sign-in stopped\n" +
                "2Fauth%2Fexperimentsandconfigs+openid&state=udbUy49jTO5hAqC6bci_MA\n" +
                " After authenticating, copy the code displayed in the browser and paste it below:\n"
        val redacted = AntigravityAuthParser.redact(clean)
        assertFalse(redacted.contains("udbUy49jTO5hAqC6bci_MA"))
        assertFalse(redacted.contains("%2F"))
        assertTrue(redacted.contains("Antigravity sign-in stopped"))
        assertTrue(redacted.contains("After authenticating"))
    }
}
