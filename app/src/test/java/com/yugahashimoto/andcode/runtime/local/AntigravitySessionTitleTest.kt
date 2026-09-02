package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The chat drawer's name for an Antigravity session.
 *
 * The CLI does not name its conversations, so without this every chat sat in the drawer as
 * "Antigravity" no matter what it was about - the same gap [ClaudeCodeTarget] fills from the first
 * prompt, filled the same way here.
 */
class AntigravitySessionTitleTest {
    @get:Rule val folder = TemporaryFolder()

    private fun target(): AntigravityTarget = AntigravityTarget(AntigravityRuntime(folder.root, { null }))

    @Test
    fun `a new session falls back to the runtime name until it is named`() =
        runBlocking {
            val target = target()
            val session = target.createSession(null, "/workspace")
            assertEquals("Antigravity", session.title)
            assertEquals("Antigravity", target.listSessions(null).single().title)
        }

    @Test
    fun `an explicit title is kept`() =
        runBlocking {
            val target = target()
            val session = target.createSession("Refactor the parser", "/workspace")
            assertEquals("Refactor the parser", session.title)
            assertEquals("Refactor the parser", target.listSessions(null).single().title)
        }

    @Test
    fun `renaming is visible in the session list`() =
        runBlocking {
            val target = target()
            val session = target.createSession(null, "/workspace")
            assertEquals("Fix the login crash", target.renameSession(session.id, "Fix the login crash").title)
            assertEquals("Fix the login crash", target.listSessions(null).single().title)
        }

    /** A title survives a restart, so a reopened chat is not renamed back to the tool. */
    @Test
    fun `a title is persisted across runtime instances`() {
        runBlocking {
            val target = target()
            val session = target.createSession(null, "/workspace")
            target.renameSession(session.id, "Fix the login crash")
        }
        runBlocking {
            assertEquals("Fix the login crash", target().listSessions(null).single().title)
        }
    }
}
