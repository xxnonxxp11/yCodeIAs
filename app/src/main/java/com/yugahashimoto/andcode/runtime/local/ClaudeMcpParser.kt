package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.McpServer

/**
 * Reads and writes Claude Code's MCP server list through its CLI.
 *
 * OpenCode has HTTP endpoints for this; `claude mcp` is the equivalent, and its `list` output is
 * line-oriented rather than JSON. Parsing lives apart from the process plumbing so the shapes it
 * has to survive — plugin-scoped names full of colons, URLs, health markers in several
 * alphabets — can be tested without a device.
 */
object ClaudeMcpParser {
    /** Absolute, because the sandbox shell is not a login shell and PATH omits /usr/bin. */
    private const val CLI = ClaudeCodeInstaller.CLAUDE_BINARY

    const val LIST_SCRIPT = "$CLI mcp list 2>&1"

    /**
     * Parses lines of the form `name: target - <marker> <status>`.
     *
     * The name may itself contain colons (`plugin:firebase:firebase`), so the split is on the first
     * colon *followed by a space* rather than on any colon. Lines without a target, such as the
     * "Checking MCP server health…" banner, are skipped.
     */
    fun parseList(output: String): List<McpServer> =
        output.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .mapNotNull(::parseLine)
            .toList()

    private fun parseLine(line: String): McpServer? {
        val separator = line.lastIndexOf(STATUS_SEPARATOR)
        val head = if (separator > 0) line.take(separator) else line
        val status = if (separator > 0) line.drop(separator + STATUS_SEPARATOR.length).trim() else null
        val colon = head.indexOf(": ").takeIf { it > 0 } ?: return null
        val name = head.take(colon).trim()
        val target = head.drop(colon + 2).trim().ifEmpty { return null }
        // `claude mcp list` labels HTTP servers explicitly and leaves the rest to the target itself.
        val url = target.substringBefore(" (").takeIf { it.startsWith("http://") || it.startsWith("https://") }
        return McpServer(
            name = name,
            status = status?.let(::normalizeStatus),
            type = if (url != null) "remote" else "local",
            command = if (url == null) target else null,
            url = url,
        )
    }

    /** The CLI marks health with a symbol the UI has no use for; the word after it is the state. */
    private fun normalizeStatus(status: String): String = status.dropWhile { !it.isLetter() }.trim().ifEmpty { status }

    fun addScript(
        name: String,
        url: String?,
        command: String?,
    ): String? {
        val safeName = shellQuote(name)
        return when {
            !url.isNullOrBlank() -> "$CLI mcp add --transport http $safeName ${shellQuote(url)} 2>&1"
            // Everything after `--` is the server's own command line, so it is passed through as
            // typed rather than quoted as a single argument.
            !command.isNullOrBlank() -> "$CLI mcp add $safeName -- $command 2>&1"
            else -> null
        }
    }

    fun removeScript(name: String): String = "$CLI mcp remove ${shellQuote(name)} 2>&1"

    fun logoutScript(name: String): String = "$CLI mcp logout ${shellQuote(name)} 2>&1"

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private const val STATUS_SEPARATOR = " - "
}
