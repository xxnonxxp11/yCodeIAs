package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

class GitCredentialHelperShebangTest {
    @Test
    fun `install writes bin-sh shebang not termux path`() {
        val root = createTempDirectory("git-shebang").toFile()
        try {
            val helper = GitCredentialHelper(root) { "token" }
            val file = helper.install()
            val content = file.readText()
            assertTrue(content.startsWith("#!/bin/sh"))
            assertTrue(!content.contains("com.termux"))
        } finally {
            root.deleteRecursively()
        }
    }
}
