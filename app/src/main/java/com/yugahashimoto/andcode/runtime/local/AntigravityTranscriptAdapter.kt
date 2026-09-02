package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.core.api.QuestionRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File

@Serializable
data class AntigravityHookRecord(
    val schemaVersion: Int = 1,
    val conversationId: String? = null,
    val transcriptPath: String? = null,
    val event: String,
    val toolName: String? = null,
    val toolArgs: JsonElement? = null,
    val step: Long? = null,
    val stopReason: String? = null,
)

data class AntigravityTranscriptResult(
    val messages: List<OpenCodeMessage>,
    val events: List<OpenCodeEvent>,
)

/** Converts the stable transcript/hook boundary into the app's provider-neutral model. */
class AntigravityTranscriptAdapter(
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        },
) {
    private val seen = mutableSetOf<String>()

    fun parseTranscript(
        sessionId: String,
        file: File,
    ): AntigravityTranscriptResult {
        val messages = mutableListOf<OpenCodeMessage>()
        val events = mutableListOf<OpenCodeEvent>()
        if (!file.isFile) return AntigravityTranscriptResult(emptyList(), emptyList())
        file.forEachLine { line ->
            val value = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@forEachLine
            val step = value["step_index"]?.toString()?.trim('"')?.toLongOrNull() ?: return@forEachLine
            val type = value["type"]?.toString()?.trim('"') ?: value["source"]?.toString()?.trim('"') ?: return@forEachLine
            val status = value["status"]?.toString()?.trim('"') ?: "complete"
            val key = "$sessionId:$step:$status"
            if (!seen.add(key)) return@forEachLine
            val text = value["content"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() } ?: return@forEachLine
            val role = if (type == "USER_INPUT") "user" else "assistant"
            val messageId = "$sessionId-$step-$status"
            messages +=
                OpenCodeMessage(
                    info =
                        OpenCodeMessageInfo(
                            messageId,
                            sessionId,
                            role,
                            OpenCodeTime(System.currentTimeMillis(), System.currentTimeMillis()),
                            agent = "antigravity",
                        ),
                    parts =
                        listOf(
                            OpenCodePart(id = "$messageId-part", sessionId = sessionId, messageId = messageId, type = "text", text = text),
                        ),
                )
        }
        return AntigravityTranscriptResult(messages, events)
    }

    fun parseHooks(
        sessionId: String,
        lines: Sequence<String>,
    ): List<OpenCodeEvent> =
        buildList {
            lines.forEach { line ->
                val record = runCatching { json.decodeFromString<AntigravityHookRecord>(line) }.getOrNull() ?: return@forEach
                if (record.schemaVersion != 1) return@forEach
                when (record.event) {
                    "PermissionAsked", "PermissionAsked:PreToolUse" -> {
                        val id = "agy-${record.conversationId.orEmpty()}-${record.step ?: 0}"
                        add(
                            OpenCodeEvent.PermissionAsked(
                                PermissionRequest(
                                    id,
                                    sessionId,
                                    record.toolName ?: "tool",
                                    metadata =
                                        record.toolArgs?.let {
                                            mapOf("arguments" to it)
                                        } ?: emptyMap(),
                                ),
                            ),
                        )
                    }
                    "ask_question", "QuestionAsked" -> {
                        val id = "agy-question-${record.conversationId.orEmpty()}-${record.step ?: 0}"
                        add(OpenCodeEvent.QuestionAsked(QuestionRequest(id, sessionId, emptyList())))
                    }
                    "Stop", "PostInvocation" -> add(OpenCodeEvent.SessionIdle(sessionId))
                    else -> add(OpenCodeEvent.Unknown(record.event, line))
                }
            }
        }

    companion object {
        fun hookJson(record: AntigravityHookRecord): String =
            Json { encodeDefaults = true }.encodeToString(AntigravityHookRecord.serializer(), record)
    }
}
