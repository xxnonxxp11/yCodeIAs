package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityStreamJsonParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parser() = AntigravityStreamJsonParser("session-1", json)

    @Test
    fun `reads the conversation id from init and a model only when one was requested`() {
        val parsed =
            parser().parse(
                """{"event":"init","conversation_id":"68c58873-b6c1-4191-be74-2f31f198a604","init":{"cwd":"/tmp/agy-lab","tools":["list_dir"],"permission_mode":"request-review"}}""",
            )

        assertEquals("68c58873-b6c1-4191-be74-2f31f198a604", parsed.conversationId)
        assertNull(parsed.resolvedModel)

        val withModel =
            parser().parse(
                """{"event":"init","conversation_id":"c1","init":{"cwd":"/w","tools":[],"permission_mode":"always-proceed","model":"gemini-3.6-flash-low"}}""",
            )
        assertEquals("gemini-3.6-flash-low", withModel.resolvedModel)
    }

    @Test
    fun `streams an agent_response delta as a text part delta`() {
        val parser = parser()
        parser.parse("""{"event":"init","conversation_id":"c1","init":{"cwd":"/w","tools":[],"permission_mode":"always-proceed"}}""")

        val parsed =
            parser.parse(
                """{"event":"step_update","step_update":{"conversation_id":"c1","step_index":2,"state":"ACTIVE","step_type":"agent_response","text_delta":"4"}}""",
            )

        val delta = parsed.events.single() as OpenCodeEvent.MessagePartDelta
        assertEquals("4", delta.delta)
        assertEquals("text", delta.field)
        assertTrue(delta.partId.endsWith("-text"))
    }

    @Test
    fun `accumulates the DONE delta so the final text equals the result response`() {
        val parser = parser()
        parser.parse(
            """{"event":"step_update","step_update":{"conversation_id":"c1","step_index":2,"state":"ACTIVE","step_type":"agent_response","text_delta":"4"}}""",
        )
        parser.parse(
            """{"event":"step_update","step_update":{"conversation_id":"c1","step_index":2,"state":"DONE","step_type":"agent_response","text_delta":"\n","duration_seconds":3.3,"usage":{"thinking_tokens":296}}}""",
        )

        val parsed =
            parser.parse(
                """{"event":"result","result":{"conversation_id":"c1","status":"SUCCESS","response":"4\n","duration_seconds":5.5,"num_turns":1,"usage":{}}}""",
            )

        assertTrue(parsed.turnFinished)
        assertEquals("4\n", parsed.finalText)
        val message = parsed.messages.single()
        assertEquals("4\n", message.parts.single { it.type == "text" }.text)
        // Thinking arrives only as a token count in usage; there is no thought text to surface.
        assertTrue(message.parts.none { it.type == "reasoning" })
    }

    @Test
    fun `turns a tool step into a running then completed tool part with a stable id`() {
        val parser = parser()
        val active =
            parser.parse(
                """{"event":"step_update","step_update":{"conversation_id":"c1","step_index":3,"state":"ACTIVE","step_type":"tool","tool_name":"list_dir","tool_info":{"name":"list_dir","parameters":{"DirectoryPath":"/root"}}}}""",
            )

        val running = active.events.single() as OpenCodeEvent.MessagePartUpdated
        assertEquals("tool", running.part.type)
        assertEquals("list_dir", running.part.tool)
        assertEquals(JsonPrimitive("running"), running.part.state?.get("status"))
        assertEquals(
            JsonPrimitive("/root"),
            (running.part.state?.get("input") as? kotlinx.serialization.json.JsonObject)?.get("DirectoryPath"),
        )

        val done =
            parser.parse(
                """{"event":"step_update","step_update":{"conversation_id":"c1","step_index":3,"state":"DONE","step_type":"tool","tool_name":"list_dir","duration_seconds":0.05,"tool_info":{"name":"list_dir","parameters":{"DirectoryPath":"/root"},"output":"a\nb"}}}""",
            )

        val completed = done.events.single() as OpenCodeEvent.MessagePartUpdated
        assertEquals(running.part.id, completed.part.id)
        assertEquals(JsonPrimitive("completed"), completed.part.state?.get("status"))
        assertEquals(JsonPrimitive("a\nb"), completed.part.state?.get("output"))
    }

    @Test
    fun `marks a failed tool step as an error part`() {
        val parsed =
            parser().parse(
                """{"event":"step_update","step_update":{"conversation_id":"c1","step_index":3,"state":"DONE","step_type":"tool","tool_name":"run_command","tool_info":{"name":"run_command","parameters":{},"error":{"type":"command_failed","message":"exit 1"}}}}""",
            )

        val part = (parsed.events.single() as OpenCodeEvent.MessagePartUpdated).part
        assertEquals(JsonPrimitive("error"), part.state?.get("status"))
        assertEquals(JsonPrimitive("exit 1"), part.state?.get("error"))
    }

    @Test
    fun `builds one assistant message whose parts share the streamed message id`() {
        val parser = parser()
        parser.parse("""{"event":"init","conversation_id":"c1","init":{"cwd":"/w","tools":[],"permission_mode":"always-proceed"}}""")
        val delta =
            parser.parse(
                """{"event":"step_update","step_update":{"conversation_id":"c1","step_index":2,"state":"ACTIVE","step_type":"agent_response","text_delta":"hello"}}""",
            )
        parser.parse(
            """{"event":"step_update","step_update":{"conversation_id":"c1","step_index":3,"state":"DONE","step_type":"tool","tool_name":"list_dir","tool_info":{"name":"list_dir","parameters":{},"output":"x"}}}""",
        )

        val result =
            parser.parse(
                """{"event":"result","result":{"conversation_id":"c1","status":"SUCCESS","response":"hello","duration_seconds":1,"num_turns":1,"usage":{}}}""",
            )

        val message = result.messages.single()
        val streamedMessageId = (delta.events.single() as OpenCodeEvent.MessagePartDelta).messageId
        assertEquals(streamedMessageId, message.info.id)
        assertEquals(listOf("text", "tool"), message.parts.map { it.type })
        assertEquals("hello", message.text)
        assertTrue(result.events.last() is OpenCodeEvent.SessionIdle)
    }

    @Test
    fun `prefers the clean result response over the garbled accumulated deltas`() {
        val parser = parser()
        // agy splits text_delta on a UTF-8 boundary, so the accumulated preview carries a
        // replacement char where the kanji 出 was torn; the finished response has it intact.
        parser.parse(
            """{"event":"step_update","step_update":{"conversation_id":"c1","step_index":2,"state":"ACTIVE","step_type":"agent_response","text_delta":"新規サービスの創"}}""",
        )
        parser.parse(
            """{"event":"step_update","step_update":{"conversation_id":"c1","step_index":2,"state":"ACTIVE","step_type":"agent_response","text_delta":"が進む"}}""",
        )

        val parsed =
            parser.parse(
                """{"event":"result","result":{"conversation_id":"c1","status":"SUCCESS","response":"新規サービスの創出が進む","duration_seconds":1,"num_turns":1,"usage":{}}}""",
            )

        assertEquals("新規サービスの創出が進む", parsed.finalText)
        assertEquals("新規サービスの創出が進む", parsed.messages.single().text)
        assertTrue(parsed.messages.single().text.none { it == '\uFFFD' })
    }

    @Test
    fun `surfaces a failed result as an error and then idles without a message`() {
        val parsed =
            parser().parse(
                """{"event":"result","result":{"conversation_id":"","status":"ERROR","response":"","error":"Error: empty prompt. Usage: agy --print \"your prompt here\"","duration_seconds":0,"num_turns":0,"usage":{}}}""",
            )

        assertEquals("Error: empty prompt. Usage: agy --print \"your prompt here\"", parsed.errorMessage)
        assertTrue(parsed.turnFinished)
        assertTrue(parsed.events.first() is OpenCodeEvent.SessionError)
        assertTrue(parsed.events.last() is OpenCodeEvent.SessionIdle)
        assertTrue(parsed.messages.isEmpty())
    }

    @Test
    fun `ignores checkpoint, unknown and user_input steps`() {
        val parser = parser()
        assertTrue(
            parser.parse(
                """{"event":"step_update","step_update":{"conversation_id":"c1","step_index":0,"state":"DONE","step_type":"user_input"}}""",
            ).events.isEmpty(),
        )
        assertTrue(
            parser.parse(
                """{"event":"step_update","step_update":{"conversation_id":"c1","step_index":1,"state":"DONE","step_type":"unknown","duration_seconds":0.0003}}""",
            ).events.isEmpty(),
        )
        assertTrue(
            parser.parse(
                """{"event":"step_update","step_update":{"conversation_id":"c1","step_index":4,"state":"DONE","step_type":"checkpoint","duration_seconds":2.1,"usage":{}}}""",
            ).events.isEmpty(),
        )
    }

    @Test
    fun `parses token usage from result usage`() {
        val parser = parser()
        val parsed =
            parser.parse(
                """{"event":"result","result":{"conversation_id":"c1","status":"SUCCESS","response":"Done","duration_seconds":2.5,"usage":{"input_tokens":1500,"output_tokens":350,"thinking_tokens":120,"cached_tokens":400}}}""",
            )

        assertTrue(parsed.turnFinished)
        val message = parsed.messages.single()
        val tokens = message.info.tokens
        assertEquals(1500L, tokens?.input)
        assertEquals(350L, tokens?.output)
        assertEquals(120L, tokens?.reasoning)
        assertEquals(400L, tokens?.cache?.read)
        assertEquals(1900L, tokens?.contextUsed)

        val messageUpdated = parsed.events.filterIsInstance<OpenCodeEvent.MessageUpdated>().single()
        assertEquals(1900L, messageUpdated.info.tokens?.contextUsed)
    }

    @Test
    fun `ignores a line that is not JSON`() {
        val parsed = parser().parse("not json at all")

        assertTrue(parsed.events.isEmpty())
        assertTrue(parsed.messages.isEmpty())
    }
}
