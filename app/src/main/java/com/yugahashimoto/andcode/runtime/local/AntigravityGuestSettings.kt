package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Owns `~/.gemini/antigravity-cli/settings.json` inside the guest rootfs.
 *
 * The file is built with the JSON serializer rather than a string literal, and it is repaired on
 * every sign-in rather than only at install time: a rootfs written by an older build can carry a
 * malformed file, and the official CLI then logs `settings file is malformed` and silently falls
 * back to defaults - which re-enables the alternate screen buffer and makes the sign-in transcript
 * far harder to read.
 */
object AntigravityGuestSettings {
    private const val RELATIVE_PATH = "root/.gemini/antigravity-cli/settings.json"

    private val json = Json { prettyPrint = true }

    val content: String =
        json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                mapOf(
                    "altScreenMode" to JsonPrimitive("never"),
                    "notifications" to JsonPrimitive(false),
                    "enableTelemetry" to JsonPrimitive(false),
                    "toolPermission" to JsonPrimitive("request-review"),
                    "trustedWorkspaces" to JsonArray(listOf(JsonPrimitive("/workspace"))),
                ),
            ),
        ) + "\n"

    fun write(rootfs: File) {
        val settings = File(rootfs, RELATIVE_PATH)
        settings.parentFile?.mkdirs()
        settings.writeText(content)
    }

    fun repair(runtime: LocalRuntimeInstaller.InstalledRuntime) {
        val rootfs = runtime.antigravityRootfs ?: runtime.rootfs
        runCatching {
            val settings = File(rootfs, RELATIVE_PATH)
            if (!settings.isFile || !isValid(settings.readText())) write(rootfs)
        }
    }

    private fun isValid(raw: String): Boolean = runCatching { Json.parseToJsonElement(raw) is JsonObject }.getOrDefault(false)
}
