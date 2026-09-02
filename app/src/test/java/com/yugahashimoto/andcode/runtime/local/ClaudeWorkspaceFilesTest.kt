package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClaudeWorkspaceFilesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var workspace: File
    private lateinit var files: ClaudeWorkspaceFiles

    @Before
    fun setUp() {
        workspace = temporaryFolder.newFolder("workspace")
        files = ClaudeWorkspaceFiles(workspace)
        write("project/README.md", "# Title\nhello world\n")
        write("project/src/App.kt", "fun main() = Unit\n")
        write("project/.hidden", "secret")
        write("outside.txt", "not in the project")
    }

    @Test
    fun `lists directories before files and reports sandbox paths`() {
        val nodes = files.list("/workspace/project", "")

        assertEquals(listOf("src", ".hidden", "README.md"), nodes.map { it.name })
        assertEquals("directory", nodes[0].type)
        assertEquals("/workspace/project/README.md", nodes.last().absolute)
        assertTrue(nodes[1].ignored)
    }

    @Test
    fun `reads a text file`() {
        val content = files.read("/workspace/project", "README.md")

        assertEquals("text", content.type)
        assertTrue(content.content.orEmpty().contains("hello world"))
    }

    @Test
    fun `reports a file with NUL bytes as binary without returning its contents`() {
        File(workspace, "project/image.bin").writeBytes(byteArrayOf(0x89.toByte(), 0, 1, 2))

        val content = files.read("/workspace/project", "image.bin")

        assertEquals("binary", content.type)
        assertEquals("", content.content)
    }

    @Test
    fun `refuses to read outside the workspace`() {
        assertThrows(IllegalArgumentException::class.java) {
            files.read("/workspace/project", "../outside.txt")
        }
    }

    @Test
    fun `lists nothing for a path that escapes the workspace`() {
        assertTrue(files.list("/workspace/project", "../..").isEmpty())
    }

    @Test
    fun `finds files by name`() {
        assertEquals(listOf("src/App.kt"), files.find("/workspace/project", "app", includeDirectories = false, limit = null))
    }

    @Test
    fun `returns nothing for a blank query`() {
        assertTrue(files.find("/workspace/project", " ", includeDirectories = null, limit = null).isEmpty())
    }

    @Test
    fun `searches file contents and reports where the match is`() {
        val matches = files.search("/workspace/project", "hello")

        val match = matches.single()
        assertEquals("README.md", match.path.text)
        assertEquals(2, match.lineNumber)
        assertEquals(0, match.absoluteOffset)
    }

    @Test
    fun `counts the lines of a file so an untracked change has a size`() {
        assertEquals(2, files.countLines("/workspace/project", "README.md"))
    }

    @Test
    fun `counts nothing for a directory or a path outside the workspace`() {
        assertNull(files.countLines("/workspace/project", "src"))
        assertNull(files.countLines("/workspace/project", "../outside.txt"))
    }

    private fun write(
        path: String,
        content: String,
    ) {
        File(workspace, path).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
    }
}
