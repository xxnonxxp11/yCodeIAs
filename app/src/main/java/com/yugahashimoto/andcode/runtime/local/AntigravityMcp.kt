package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.McpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Reads and writes Antigravity's MCP server list.
 *
 * Unlike Claude Code (`claude mcp`) or OpenCode (an HTTP endpoint), `agy` has no MCP subcommand -
 * servers live purely in `~/.gemini/config/mcp_config.json` inside the guest rootfs, documented in
 * the CLI's own bundled `agy-customizations/docs/mcp_servers.md` and confirmed against a real file
 * from a working install. There is no live "connect" concept here: a server is either configured or
 * not, same as Claude Code, so [remove] deletes the entry rather than disabling it.
 */
object AntigravityMcp {
    private const val RELATIVE_PATH = "root/.gemini/config/mcp_config.json"
    private const val SERVERS_KEY = "mcpServers"
    private val json = Json { prettyPrint = true }

    /** A missing or malformed file reads as no servers configured, rather than failing the screen. */
    fun read(rootfs: File): List<McpServer> =
        readServers(rootfs).map { (name, entry) ->
            val url = entry[URL_KEY]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            val command = entry[COMMAND_KEY]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            McpServer(name = name, type = if (url != null) "remote" else "local", command = command, url = url)
        }

    /** @param body `{"name", "url"}` for a remote server or `{"name", "command"}` for a local one. */
    fun add(
        rootfs: File,
        body: JsonObject,
    ): McpServer {
        val name = body["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(name.isNotEmpty()) { "An MCP server needs a name" }
        val url = body["url"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
        val command = body["command"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
        val entry =
            when {
                url != null -> buildJsonObject { put(URL_KEY, url) }
                command != null -> buildJsonObject { put(COMMAND_KEY, command) }
                else -> error("An MCP server needs either a command or a URL")
            }
        val servers = readServers(rootfs).toMutableMap()
        servers[name] = entry
        writeServers(rootfs, servers)
        return McpServer(name = name, type = if (url != null) "remote" else "local", command = command, url = url)
    }

    fun remove(
        rootfs: File,
        name: String,
    ): Boolean {
        val servers = readServers(rootfs).toMutableMap()
        val removed = servers.remove(name) != null
        if (removed) writeServers(rootfs, servers)
        return removed
    }

    private fun readServers(rootfs: File): Map<String, JsonObject> {
        val file = File(rootfs, RELATIVE_PATH)
        if (!file.isFile) return emptyMap()
        val root = runCatching { Json.parseToJsonElement(file.readText()) }.getOrNull() as? JsonObject ?: return emptyMap()
        val servers = root[SERVERS_KEY] as? JsonObject ?: return emptyMap()
        return servers.entries.mapNotNull { (name, value) -> (value as? JsonObject)?.let { name to it } }.toMap()
    }

    /** Atomic write-temp-then-rename, matching how [AntigravityGuestSettings] touches this rootfs. */
    private fun writeServers(
        rootfs: File,
        servers: Map<String, JsonElement>,
    ) {
        val file = File(rootfs, RELATIVE_PATH)
        file.parentFile?.mkdirs()
        val content = json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(SERVERS_KEY to JsonObject(servers)))) + "\n"
        val staged = File(file.parentFile, "${file.name}.tmp-${System.nanoTime()}")
        staged.writeText(content)
        require(staged.renameTo(file)) { "Unable to update the MCP configuration" }
    }

    private const val URL_KEY = "serverUrl"
    private const val COMMAND_KEY = "command"
}
