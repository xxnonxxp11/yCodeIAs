package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeMessageError
import com.yugahashimoto.andcode.core.api.OpenCodePart
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatErrorPartTest {
    @Test
    fun `appends the message level error for a failed turn`() {
        val error =
            OpenCodeMessageError(
                name = "APIError",
                data = mapOf("message" to JsonPrimitive("Rate limit exceeded (HTTP 429)")),
            )

        val parts = emptyList<ChatPart>().withMessageError("m1", error)

        val errorPart = parts.single() as ChatPart.Error
        assertEquals("m1-error", errorPart.id)
        assertEquals("Rate limit exceeded (HTTP 429)", errorPart.message)
    }

    @Test
    fun `keeps a failed assistant message with only an error visible in the timeline`() {
        val error =
            OpenCodeMessageError(
                name = "APIError",
                data = mapOf("message" to JsonPrimitive("invalid api key")),
            )
        val message = ChatMessage(id = "m2", isUser = false, parts = emptyList<ChatPart>().withMessageError("m2", error))

        val entries = groupConversationTimeline(listOf(message))

        assertEquals(1, entries.size)
        assertEquals("error:m2-error", entries.single().id)
        assertTrue(entries.single() is TimelineEntry.Error)
    }

    @Test
    fun `surfaces a message level error alongside streamed parts`() {
        val error =
            OpenCodeMessageError(
                name = "APIError",
                data =
                    mapOf(
                        "message" to JsonPrimitive("upstream provider exploded"),
                        "statusCode" to JsonPrimitive(500),
                    ),
            )
        val message =
            ChatMessage(
                id = "m3",
                isUser = false,
                parts =
                    listOf(ChatPart.Text(id = "t1", text = "partial answer"))
                        .withMessageError("m3", error),
            )

        val timeline = groupConversationTimeline(listOf(message))

        assertTrue(timeline.filterIsInstance<TimelineEntry.Error>().isNotEmpty())
        assertTrue(timeline.filterIsInstance<TimelineEntry.Body>().isNotEmpty())
    }

    @Test
    fun `does not flag a user initiated abort as an error`() {
        val error =
            OpenCodeMessageError(
                name = "MessageAbortedError",
                data = mapOf("message" to JsonPrimitive("The operation was aborted.")),
            )

        assertTrue(emptyList<ChatPart>().withMessageError("m4", error).isEmpty())
    }

    @Test
    fun `does not flag a turn that succeeded after retries as an error`() {
        // Retry parts are persisted even when the retry succeeds; they must never render as an
        // error on their own, only the message-level info.error does.
        val retryPart = OpenCodePart(id = "r1", type = "retry")

        assertNull(retryPart.toChatPart())
    }

    @Test
    fun `ignores a message level error without a message`() {
        val error = OpenCodeMessageError(name = "UnknownError", data = null)

        assertTrue(emptyList<ChatPart>().withMessageError("m5", error).isEmpty())
    }
}
