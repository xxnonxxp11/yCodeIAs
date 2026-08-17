package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/**
 * Translates one line of `agy --output-format stream-json` into app events.
 *
 * The CLI emits newline-delimited JSON: a top-level `event` field names the kind, `stdout` carries the
 * events and `stderr` carries diagnostics, which is why the runtime keeps the two streams apart - a
 * progress line on stderr would otherwise corrupt the NDJSON. One `--print` invocation streams its
 * whole turn this way, so the bridge stays one-shot; there is no long-lived process to keep alive.
 *
 * A turn is `init` once, then many `step_update`, then `result` once. The streamed text arrives as
 * `agent_response` steps whose `text_delta` has to be accumulated - the terminal `DONE` step still
 * carries a final delta rather than the whole text - and the concatenation of every delta equals
 * `result.response`. Thinking is reported only as a token count in `usage`; no thought text is
 * streamed, so there is nothing to surface as a reasoning part.
 */
class AntigravityStreamJsonParser(
    private val sessionId: String,
    private val json: Json,
) {
    /** What the runtime should do with a parsed line, beyond emitting [events]. */
    data class Parsed(
        val events: List<OpenCodeEvent> = emptyList(),
        val messages: List<OpenCodeMessage> = emptyList(),
        val conversationId: String? = null,
        /** Model id reported by `init`, present only when the run was started with `--model`. */
        val resolvedModel: String? = null,
        /** The accumulated assistant text once the turn finishes; equals `result.response`. */
        val finalText: String? = null,
        val turnFinished: Boolean = false,
        val errorMessage: String? = null,
    )

    private var messageId: String? = null
    private val text = StringBuilder()
    private val toolParts = linkedMapOf<Int, OpenCodePart>()
    private var conversationId: String? = null

    fun parse(line: String): Parsed {
        val root = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return Parsed()
        root.string("conversation_id")?.takeIf { it.isNotBlank() }?.let { conversationId = it }
        return when (root.string("event")) {
            "init" -> Parsed(conversationId = conversationId, resolvedModel = root["init"]?.jsonObject?.string("model"))
            "step_update" -> parseStep(root["step_update"]?.jsonObject ?: return Parsed(conversationId = conversationId))
            "result" -> parseResult(root["result"]?.jsonObject ?: return Parsed(conversationId = conversationId, turnFinished = true))
            else -> Parsed(conversationId = conversationId)
        }
    }

    private fun parseStep(step: JsonObject): Parsed =
        when (step.string("step_type")) {
            "agent_response" -> parseAgentResponse(step)
            "reasoning", "thought", "thinking" -> parseReasoning(step)
            "tool" -> parseTool(step)
            // user_input, checkpoint and the CLI's internal "unknown" step carry no displayable part.
            else -> Parsed(conversationId = conversationId)
        }

    private fun parseAgentResponse(step: JsonObject): Parsed {
        // Both ACTIVE and DONE steps carry a text_delta; DONE is not a terminator but the last chunk.
        val delta = step.string("text_delta").orEmpty()
        if (delta.isEmpty()) return Parsed(conversationId = conversationId)
        val id = stableMessageId()
        text.append(delta)
        return Parsed(
            events = listOf(OpenCodeEvent.MessagePartDelta(sessionId, id, "$id-text", "text", delta)),
            conversationId = conversationId,
        )
    }

    private fun parseReasoning(step: JsonObject): Parsed {
        val delta = step.string("text_delta") ?: step.string("delta") ?: step.string("thought_delta").orEmpty()
        if (delta.isEmpty()) return Parsed(conversationId = conversationId)
        val id = stableMessageId()
        return Parsed(
            events = listOf(OpenCodeEvent.MessagePartDelta(sessionId, id, "$id-reasoning", "text", delta)),
            conversationId = conversationId,
        )
    }

    private fun parseTool(step: JsonObject): Parsed {
        val info = step["tool_info"]?.jsonObject
        val name = info?.string("name") ?: step.string("tool_name") ?: "tool"
        val index = step.int("step_index") ?: toolParts.size
        val id = stableMessageId()
        val partId = "$id-tool-$index"
        val done = step.string("state") == "DONE"
        val error = toolError(info)
        val state =
            buildMap {
                put(
                    "status",
                    JsonPrimitive(
                        if (!done) {
                            "running"
                        } else if (error != null) {
                            "error"
                        } else {
                            "completed"
                        },
                    ),
                )
                put("input", info?.get("parameters") ?: JsonObject(emptyMap()))
                info?.string("output")?.let { put("output", JsonPrimitive(it)) }
                error?.let { put("error", JsonPrimitive(it)) }
            }
        val part =
            OpenCodePart(
                id = partId,
                sessionId = sessionId,
                messageId = id,
                type = "tool",
                tool = name,
                callID = partId,
                state = state,
            )
        toolParts[index] = part
        return Parsed(events = listOf(OpenCodeEvent.MessagePartUpdated(part)), conversationId = conversationId)
    }

    private fun parseResult(result: JsonObject): Parsed {
        val status = result.string("status")
        if (status != null && status != "SUCCESS") {
            val message = result.string("error") ?: result.string("response")?.takeIf { it.isNotBlank() } ?: status
            return Parsed(
                events = listOf(OpenCodeEvent.SessionError(sessionId, message), OpenCodeEvent.SessionIdle(sessionId)),
                conversationId = conversationId,
                turnFinished = true,
                errorMessage = message,
            )
        }
        val id = stableMessageId()
        // The streamed deltas are only for the live preview: agy splits text_delta on UTF-8
        // multibyte boundaries, so a 3-byte kanji that straddles two deltas decodes to replacement
        // chars and the accumulated text comes out garbled. The finished `result.response` is built
        // without that splitting and is clean, so it is the source of truth for the persisted reply.
        // The deltas still drive the on-screen streaming until this MessagePartUpdated overwrites them.
        val finalText = result.string("response")?.takeIf { it.isNotEmpty() } ?: text.toString()
        val textPart = OpenCodePart(id = "$id-text", sessionId = sessionId, messageId = id, type = "text", text = finalText)
        val now = System.currentTimeMillis()
        val usage = result["usage"]?.jsonObject ?: result["stats"]?.jsonObject ?: result["token_usage"]?.jsonObject
        val tokens =
            usage?.let { u ->
                val input = u.long("input_tokens") ?: u.long("prompt_tokens") ?: u.long("input") ?: 0L
                val output = u.long("output_tokens") ?: u.long("completion_tokens") ?: u.long("output") ?: 0L
                val reasoning = u.long("thinking_tokens") ?: u.long("reasoning_tokens") ?: u.long("reasoning") ?: 0L
                val cacheRead = u.long("cached_tokens") ?: u.long("cache_read_tokens") ?: u.long("cache_read") ?: 0L
                val cacheWrite = u.long("cache_write_tokens") ?: u.long("cache_write") ?: 0L
                com.yugahashimoto.andcode.core.api.OpenCodeSessionTokens(
                    input = input,
                    output = output,
                    reasoning = reasoning,
                    cache =
                        if (cacheRead > 0L || cacheWrite > 0L) {
                            com.yugahashimoto.andcode.core.api.OpenCodeCacheTokens(read = cacheRead, write = cacheWrite)
                        } else {
                            null
                        },
                )
            }
        val message =
            OpenCodeMessage(
                OpenCodeMessageInfo(
                    id = id,
                    sessionId = sessionId,
                    role = "assistant",
                    time = OpenCodeTime(now, now, now),
                    agent = "antigravity",
                    tokens = tokens,
                ),
                listOf(textPart) + toolParts.values,
            )
        val messageUpdated = OpenCodeEvent.MessageUpdated(message.info)
        return Parsed(
            events = listOf(OpenCodeEvent.MessagePartUpdated(textPart), messageUpdated, OpenCodeEvent.SessionIdle(sessionId)),
            messages = listOf(message),
            conversationId = conversationId,
            finalText = finalText,
            turnFinished = true,
        )
    }

    private fun toolError(info: JsonObject?): String? {
        val error = info?.get("error") ?: return null
        return (error as? JsonPrimitive)?.contentOrNull
            ?: (error as? JsonObject)?.string("message")
            ?: error.toString()
    }

    private fun stableMessageId(): String = messageId ?: "agy-${UUID.randomUUID()}".also { messageId = it }

    private companion object {
        fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

        fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
    }
}
