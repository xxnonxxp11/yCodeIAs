package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AntigravityMcpTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun configFile(rootfs: File) = File(rootfs, "root/.gemini/config/mcp_config.json")

    /** Captured verbatim from a real `~/.gemini/config/mcp_config.json` on a working install. */
    private val realConfig =
        """
        {
          "mcpServers": {
            "chrome-devtools-mcp": {
              "command": "/Users/yu-ga/.hermes/node/bin/chrome-devtools-mcp",
              "args": [
                "--browser-url=http://127.0.0.1:9222"
              ]
            }
          }
        }
        """.trimIndent()

    @Test
    fun `reads a real mcp_config json`() {
        val rootfs = tmp.newFolder("rootfs")
        configFile(rootfs).apply { parentFile?.mkdirs() }.writeText(realConfig)
        val servers = AntigravityMcp.read(rootfs)
        assertEquals(1, servers.size)
        val server = servers.single()
        assertEquals("chrome-devtools-mcp", server.name)
        assertEquals("local", server.type)
        assertEquals("/Users/yu-ga/.hermes/node/bin/chrome-devtools-mcp", server.command)
        assertNull(server.url)
    }

    @Test
    fun `a missing file reads as no servers`() {
        val rootfs = tmp.newFolder("rootfs")
        assertTrue(AntigravityMcp.read(rootfs).isEmpty())
    }

    @Test
    fun `a malformed file reads as no servers instead of crashing`() {
        val rootfs = tmp.newFolder("rootfs")
        configFile(rootfs).apply { parentFile?.mkdirs() }.writeText("{ not json")
        assertTrue(AntigravityMcp.read(rootfs).isEmpty())
    }

    @Test
    fun `adds a local server and round-trips it`() {
        val rootfs = tmp.newFolder("rootfs")
        val body =
            buildJsonObject {
                put("name", "my-tool")
                put("command", "my-tool-binary")
            }
        val added = AntigravityMcp.add(rootfs, body)
        assertEquals("my-tool", added.name)
        assertEquals("local", added.type)
        assertEquals(listOf("my-tool"), AntigravityMcp.read(rootfs).map { it.name })
    }

    @Test
    fun `adds a remote server`() {
        val rootfs = tmp.newFolder("rootfs")
        val body =
            buildJsonObject {
                put("name", "remote-tool")
                put("url", "https://mcp.example.com/sse")
            }
        val added = AntigravityMcp.add(rootfs, body)
        assertEquals("remote", added.type)
        assertEquals("https://mcp.example.com/sse", added.url)
        assertEquals("https://mcp.example.com/sse", AntigravityMcp.read(rootfs).single().url)
    }

    @Test
    fun `adding preserves servers already in the file`() {
        val rootfs = tmp.newFolder("rootfs")
        configFile(rootfs).apply { parentFile?.mkdirs() }.writeText(realConfig)
        AntigravityMcp.add(
            rootfs,
            buildJsonObject {
                put("name", "second")
                put("command", "second-binary")
            },
        )
        assertEquals(setOf("chrome-devtools-mcp", "second"), AntigravityMcp.read(rootfs).map { it.name }.toSet())
    }

    @Test
    fun `removes a server`() {
        val rootfs = tmp.newFolder("rootfs")
        configFile(rootfs).apply { parentFile?.mkdirs() }.writeText(realConfig)
        assertTrue(AntigravityMcp.remove(rootfs, "chrome-devtools-mcp"))
        assertTrue(AntigravityMcp.read(rootfs).isEmpty())
    }

    @Test
    fun `removing an unknown server is a no-op`() {
        val rootfs = tmp.newFolder("rootfs")
        assertEquals(false, AntigravityMcp.remove(rootfs, "nothing-here"))
    }
}
