package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeMcpParserTest {
    @Test
    fun `reads remote and local servers from the list output`() {
        val output =
            """
            Checking MCP server health…

            claude.ai Notion: https://mcp.notion.com/mcp - ✔ Connected
            plugin:firebase:firebase: npx -y firebase-tools mcp --dir . - ✔ Connected
            store: https://example.test/api/mcp (HTTP) - ✘ Failed to connect
            """.trimIndent()

        val servers = ClaudeMcpParser.parseList(output)

        assertEquals(listOf("claude.ai Notion", "plugin:firebase:firebase", "store"), servers.map { it.name })
        assertEquals(listOf("remote", "local", "remote"), servers.map { it.type })
        assertEquals("https://mcp.notion.com/mcp", servers[0].url)
        assertEquals("npx -y firebase-tools mcp --dir .", servers[1].command)
        assertNull(servers[1].url)
        // The transport label is not part of the URL.
        assertEquals("https://example.test/api/mcp", servers[2].url)
    }

    @Test
    fun `strips the health symbol from the status`() {
        val servers = ClaudeMcpParser.parseList("a: https://a.test/mcp - ✔ Connected\nb: cmd - ⏸ Pending approval")

        assertEquals(listOf("Connected", "Pending approval"), servers.map { it.status })
    }

    @Test
    fun `skips lines that name no server`() {
        assertTrue(ClaudeMcpParser.parseList("Checking MCP server health…\n\nNo MCP servers configured.").isEmpty())
    }

    @Test
    fun `prefers the URL transport when both fields are filled in`() {
        val script = ClaudeMcpParser.addScript("demo", "https://demo.test/mcp", "npx demo")

        assertTrue(script.orEmpty().contains("--transport http"))
        assertTrue(script.orEmpty().contains("'https://demo.test/mcp'"))
    }

    @Test
    fun `passes a stdio command through after the separator`() {
        assertTrue(ClaudeMcpParser.addScript("demo", null, "npx -y server --flag").orEmpty().endsWith("-- npx -y server --flag 2>&1"))
    }

    @Test
    fun `refuses a server with neither a command nor a URL`() {
        assertNull(ClaudeMcpParser.addScript("demo", null, ""))
    }

    @Test
    fun `quotes names so they cannot end the command`() {
        val script = ClaudeMcpParser.removeScript("evil'; rm -rf /; echo '")

        assertTrue(script.contains("""'evil'\''; rm -rf /; echo '\'''"""))
    }
}
