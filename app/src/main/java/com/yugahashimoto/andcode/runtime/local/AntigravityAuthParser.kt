package com.yugahashimoto.andcode.runtime.local

/**
 * Pure parsing of the official agy sign-in TUI transcript.
 *
 * The CLI is a full-screen Bubble Tea program, so the transcript is a stream of redraws rather than
 * append-only text. Every predicate therefore works on the ANSI-stripped text and tolerates the
 * screen being repainted many times.
 */
object AntigravityAuthParser {
    private val ANSI = Regex("\\u001B\\[[;?\\d]*[ -/]*[@-~]|\\u001B\\][^\\u0007]*\\u0007|\\u001B[=>][\\d;]*[a-zA-Z]?")

    /** Only the documented Google endpoint is ever treated as the sign-in URL. */
    private const val OAUTH_PREFIX = "https://accounts.google.com/o/oauth2/auth?"

    /** RFC 3986 unreserved + reserved characters; deliberately excludes whitespace. */
    private val URL_CONTINUATION = Regex("^[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+$")

    private const val LOGIN_MENU_MARKER = "Select login method"
    private val CODE_PROMPT_MARKERS =
        listOf(
            "paste the authorization code below",
            "paste it below",
            "authorization code...",
        )
    private val FAILURE_MARKERS =
        listOf(
            "token exchange failed",
            "Invalid state parameter",
            "no authorization code received",
            "authorization code cannot be empty",
            "Failed to sign in",
        )
    private val SIGNED_IN_MARKERS =
        listOf(
            "Successfully signed in",
            "You are signed in",
            "Signed in as",
        )

    fun stripAnsi(raw: String): String = ANSI.replace(raw, "")

    /** True once the "1. Google OAuth / 2. Use a Google Cloud project" chooser has been painted. */
    fun isLoginMenuVisible(clean: String): Boolean = clean.contains(LOGIN_MENU_MARKER, ignoreCase = true)

    fun isAwaitingCode(clean: String): Boolean = CODE_PROMPT_MARKERS.any { clean.contains(it, ignoreCase = true) }

    fun isFailure(clean: String): Boolean = FAILURE_MARKERS.any { clean.contains(it, ignoreCase = true) }

    fun isSignedIn(clean: String): Boolean = SIGNED_IN_MARKERS.any { clean.contains(it, ignoreCase = true) }

    /** The CLI reports this when it decided a desktop browser could be launched instead. */
    fun isLocalBrowserMode(clean: String): Boolean = clean.contains("local chrome mode", ignoreCase = true)

    /**
     * Extracts the most recently painted OAuth URL.
     *
     * A wide PTY normally keeps the URL on one line, but the CLI hard-wraps it to the terminal
     * width, so any continuation lines are stitched back on. Wrapped fragments are padded with
     * spaces and never contain interior whitespace, which is what separates them from the prose
     * that follows the URL block.
     */
    fun findOAuthUrl(clean: String): String? = lastBlock(clean.lines())?.url

    /**
     * Removes the OAuth URL from text that may reach the UI or a log: it carries the PKCE challenge
     * and the CSRF state parameter. The authorization code is handled separately - the coordinator
     * stops updating the diagnostic transcript once the code field can contain input, so a typed
     * code is never captured in the first place.
     */
    fun redact(clean: String): String {
        val lines = clean.lines().toMutableList()
        var guard = 0
        while (guard++ < MAX_REDACTIONS) {
            val block = lastBlock(lines) ?: break
            val head = lines[block.first]
            lines[block.first] = head.take(head.indexOf(OAUTH_PREFIX)) + REDACTED_URL
            for (index in block.first + 1..block.last) lines[index] = ""
        }
        // A second, content-based pass, because anchoring on the prefix alone is not enough: the
        // coordinator keeps only the tail of the transcript, so once the line carrying
        // `https://accounts.google.com/o/oauth2/auth?` scrolls out of that window the wrapped
        // remainder survives with nothing to anchor to. That is not hypothetical - a failed sign-in
        // put "...%2Fauth%2Fexperimentsandconfigs+openid&state=udbUy49jTO5hAqC6bci_MA" on screen,
        // which is the CSRF state parameter of a live authorization request.
        return lines.joinToString("\n") { line ->
            if (QUERY_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) }) REDACTED_URL else line
        }
    }

    private data class UrlBlock(val first: Int, val last: Int, val url: String)

    /**
     * Locates the last painted URL and the range of lines it occupies. The TUI repaints the whole
     * screen, so the newest copy is the one the running process is actually waiting on.
     */
    private fun lastBlock(lines: List<String>): UrlBlock? {
        for (index in lines.indices.reversed()) {
            val start = lines[index].indexOf(OAUTH_PREFIX)
            if (start < 0) continue
            val head = lines[index].substring(start).trim()
            if (head.contains(' ')) {
                val single = head.substringBefore(' ')
                return single.takeIf { it.length > OAUTH_PREFIX.length }?.let { UrlBlock(index, index, it) }
            }
            val builder = StringBuilder(head)
            var last = index
            for (next in index + 1 until lines.size) {
                val candidate = lines[next].trim()
                if (candidate.isEmpty() || !URL_CONTINUATION.matches(candidate)) break
                builder.append(candidate)
                last = next
            }
            return builder.toString().takeIf { it.length > OAUTH_PREFIX.length }?.let { UrlBlock(index, last, it) }
        }
        return null
    }

    private const val MAX_REDACTIONS = 16
    private const val REDACTED_URL = "<oauth url redacted>"

    /**
     * Query material that identifies a fragment of an authorization request even with no scheme or
     * host left on the line. `state` and `code_challenge` are the parts worth protecting; the rest
     * are here because a line carrying them is part of the same URL.
     */
    private val QUERY_MARKERS =
        listOf(
            "state=",
            "code_challenge",
            "client_id=",
            "redirect_uri=",
            "response_type=",
            "access_type=",
            "scope=",
            "%2F",
        )
}
