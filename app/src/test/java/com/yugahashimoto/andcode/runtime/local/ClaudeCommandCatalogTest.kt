package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClaudeCommandCatalogTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `names nested commands the way the CLI invokes them`() {
        val root = temporaryFolder.newFolder("home")
        write(File(root, "commands/review.md"), "---\ndescription: Review the diff\n---\n\nBody")
        write(File(root, "commands/git/commit.md"), "Body only")

        val commands = ClaudeCommandCatalog.commands(listOf(root))

        assertEquals(listOf("git:commit", "review"), commands.map { it.name })
        assertNull(commands[0].description)
        assertEquals("Review the diff", commands[1].description)
    }

    @Test
    fun `merges personal and project roots without duplicating a name`() {
        val home = temporaryFolder.newFolder("home")
        val project = temporaryFolder.newFolder("project")
        write(File(home, "commands/review.md"), "---\ndescription: Personal\n---\n")
        write(File(project, "commands/review.md"), "---\ndescription: Project\n---\n")
        write(File(project, "commands/ship.md"), "")

        val commands = ClaudeCommandCatalog.commands(listOf(home, project))

        assertEquals(listOf("review", "ship"), commands.map { it.name })
        // The first root wins, matching the order the caller declares precedence in.
        assertEquals("Personal", commands[0].description)
    }

    @Test
    fun `reads skills from their SKILL file`() {
        val root = temporaryFolder.newFolder("home")
        write(File(root, "skills/pdf/SKILL.md"), "---\nname: pdf-tools\ndescription: Work with PDFs\n---\n")
        // A directory without a SKILL.md is not a skill.
        File(root, "skills/empty").mkdirs()

        val skills = ClaudeCommandCatalog.skills(listOf(root))

        assertEquals(1, skills.size)
        assertEquals("pdf-tools", skills[0].name)
        assertEquals("Work with PDFs", skills[0].description)
    }

    @Test
    fun `falls back to the directory name when the front matter has none`() {
        val root = temporaryFolder.newFolder("home")
        write(File(root, "skills/brainstorming/SKILL.md"), "No front matter here")

        assertEquals(listOf("brainstorming"), ClaudeCommandCatalog.skills(listOf(root)).map { it.name })
    }

    @Test
    fun `reports nothing for a root that does not exist`() {
        val missing = File(temporaryFolder.root, "absent")

        assertTrue(ClaudeCommandCatalog.commands(listOf(missing)).isEmpty())
        assertTrue(ClaudeCommandCatalog.skills(listOf(missing)).isEmpty())
    }

    @Test
    fun `stops reading front matter at the closing marker`() {
        val file = temporaryFolder.newFile("command.md")
        file.writeText("---\ndescription: Real\n---\ndescription: In the body\n")

        assertEquals("Real", ClaudeCommandCatalog.frontMatter(file)["description"])
    }

    private fun write(
        file: File,
        content: String,
    ) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}
