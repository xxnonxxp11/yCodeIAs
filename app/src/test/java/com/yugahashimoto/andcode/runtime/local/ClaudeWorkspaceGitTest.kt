package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeWorkspaceGitTest {
    @Test
    fun `reads the branch and the remote default branch`() {
        val info = ClaudeWorkspaceGit.parseInfo("feature/login\nrefs/remotes/origin/main\n")

        assertEquals("feature/login", info.branch)
        assertEquals("main", info.defaultBranch)
    }

    @Test
    fun `leaves the default branch unset when no remote head is configured`() {
        val info = ClaudeWorkspaceGit.parseInfo("main\n\n")

        assertEquals("main", info.branch)
        assertNull(info.defaultBranch)
    }

    @Test
    fun `reports no branch on a detached head`() {
        assertNull(ClaudeWorkspaceGit.parseInfo("HEAD\n").branch)
    }

    @Test
    fun `maps porcelain status codes onto change kinds`() {
        val output =
            """
             M app/build.gradle.kts
            A  app/src/main/NewFile.kt
             D removed.txt
            ?? untracked.md
            R  old.kt -> new.kt
            """.trimIndent()

        val changes = ClaudeWorkspaceGit.parseStatus(output)

        assertEquals(
            listOf("app/build.gradle.kts", "app/src/main/NewFile.kt", "removed.txt", "untracked.md", "new.kt"),
            changes.map { it.path },
        )
        assertEquals(
            listOf("modified", "added", "deleted", "added", "renamed"),
            changes.map { it.status },
        )
    }

    @Test
    fun `carries per-file line counts into the status list`() {
        val counts = ClaudeWorkspaceGit.parseNumstat("2\t1\tNOTES.md\n-\t-\timage.png\n")
        val changes = ClaudeWorkspaceGit.parseStatus(" M NOTES.md\n M image.png\n M other.txt", counts)

        assertEquals(listOf(2, 0, 0), changes.map { it.added })
        assertEquals(listOf(1, 0, 0), changes.map { it.removed })
        // Binary files report "-" rather than a number and must not be read as a change count.
        assertEquals(0, counts["image.png"]?.first)
    }

    @Test
    fun `reads the new name of a renamed file from numstat`() {
        val counts = ClaudeWorkspaceGit.parseNumstat("1\t1\told.kt => new.kt\n")

        assertEquals(1 to 1, counts["new.kt"])
    }

    @Test
    fun `ignores blank and truncated status lines`() {
        assertTrue(ClaudeWorkspaceGit.parseStatus("\n M \n").isEmpty())
    }

    @Test
    fun `splits a diff per file and counts changed lines`() {
        val output =
            """
            diff --git a/one.txt b/one.txt
            index 111..222 100644
            --- a/one.txt
            +++ b/one.txt
            @@ -1,2 +1,2 @@
            -old
            +new
            +extra
            diff --git a/two.txt b/two.txt
            index 333..444 100644
            --- a/two.txt
            +++ b/two.txt
            @@ -1 +0,0 @@
            -gone
            """.trimIndent()

        val changes = ClaudeWorkspaceGit.parseDiff(output)

        assertEquals(listOf("one.txt", "two.txt"), changes.map { it.path })
        // The +++/--- header lines must not be counted as content changes.
        assertEquals(listOf(2, 0), changes.map { it.added })
        assertEquals(listOf(1, 1), changes.map { it.removed })
        // The changes list reads the decimal pair; leaving it unset showed every file as +0/-0.
        assertEquals(listOf(2.0, 0.0), changes.map { it.additions })
        assertEquals(listOf(1.0, 1.0), changes.map { it.deletions })
        assertTrue(changes[0].patch.orEmpty().startsWith("diff --git a/one.txt"))
    }

    @Test
    fun `treats no output as no changes`() {
        assertTrue(ClaudeWorkspaceGit.parseDiff("").isEmpty())
    }

    @Test
    fun `asks git for the requested amount of context`() {
        assertTrue(ClaudeWorkspaceGit.diffScript(8).contains("-U8"))
        assertTrue(ClaudeWorkspaceGit.diffScript(null).contains("-U3"))
    }

    @Test
    fun `never writes to the repository`() {
        val scripts = listOf(ClaudeWorkspaceGit.INFO_SCRIPT, ClaudeWorkspaceGit.STATUS_SCRIPT, ClaudeWorkspaceGit.diffScript(null))

        scripts.forEach { script ->
            listOf("git add", "git commit", "git checkout", "git reset", "git stash").forEach { mutation ->
                assertTrue("$script must not run $mutation", !script.contains(mutation))
            }
        }
    }
}
