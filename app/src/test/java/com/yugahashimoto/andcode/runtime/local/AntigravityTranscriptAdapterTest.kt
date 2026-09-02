package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AntigravityTranscriptAdapterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `maps transcript and deduplicates step status`() {
        val file = temporaryFolder.newFile("transcript.jsonl")
        file.writeText(
            "{\"step_index\":1,\"type\":\"USER_INPUT\",\"status\":\"complete\",\"content\":\"hello\"}\n" +
                "{\"step_index\":1,\"type\":\"USER_INPUT\",\"status\":\"complete\",\"content\":\"hello\"}\n" +
                "{\"step_index\":2,\"type\":\"PLANNER_RESPONSE\",\"status\":\"complete\",\"content\":\"world\"}\n",
        )
        val result = AntigravityTranscriptAdapter().parseTranscript("s", file)
        assertEquals(2, result.messages.size)
        assertEquals("user", result.messages.first().info.role)
        assertEquals("world", result.messages.last().text)
    }

    @Test fun `maps permission hook without leaking env`() {
        val events =
            AntigravityTranscriptAdapter().parseHooks(
                "s",
                sequenceOf(
                    "{\"schemaVersion\":1,\"conversationId\":\"c\",\"event\":\"PermissionAsked\",\"toolName\":\"shell\",\"step\":3}",
                ),
            )
        assertEquals(1, events.size)
        assertEquals("shell", (events.first() as com.yugahashimoto.andcode.core.api.OpenCodeEvent.PermissionAsked).request.permission)
    }
}
