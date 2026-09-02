package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravitySandboxLauncherTest {
    @Test
    fun `pty command sizes the terminal before starting agy`() {
        val command = AntigravitySandboxLauncher.ptyShellCommand(emptyList())
        assertTrue(command.startsWith("stty rows ${AntigravitySandboxLauncher.PTY_ROWS} cols ${AntigravitySandboxLauncher.PTY_COLUMNS}"))
        assertTrue(command.endsWith("exec '/usr/local/bin/agy'"))
    }

    @Test
    fun `pty width keeps the official oauth url on a single line`() {
        // The captured agy 1.1.7 sign-in URL is 521 characters and the CLI indents it by one column.
        assertTrue(AntigravitySandboxLauncher.PTY_COLUMNS > 600)
    }

    @Test
    fun `pty arguments are shell quoted`() {
        val command = AntigravitySandboxLauncher.ptyShellCommand(listOf("--print", "it's a test; rm -rf /"))
        assertTrue(command.endsWith("'/usr/local/bin/agy' '--print' 'it'\\''s a test; rm -rf /'"))
    }

    @Test
    fun `guest settings are valid json with the alt screen disabled`() {
        val parsed = Json.parseToJsonElement(AntigravityGuestSettings.content).jsonObject
        assertEquals("never", parsed["altScreenMode"]?.jsonPrimitive?.content)
        assertEquals("request-review", parsed["toolPermission"]?.jsonPrimitive?.content)
    }
}
