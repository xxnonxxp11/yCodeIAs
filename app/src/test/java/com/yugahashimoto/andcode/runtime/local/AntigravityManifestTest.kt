package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AntigravityManifestTest {
    @Test fun `selects pinned official assets by ABI`() {
        assertEquals("agy_cli_linux_arm64.tar.gz", AntigravityManifest.assetFor("arm64-v8a").name)
        assertEquals("agy_cli_linux_x64.tar.gz", AntigravityManifest.assetFor("x86_64").name)
        assertEquals("0d6d488851745e80e69b8935d063e742945811b47111994b1a6dbd27df3010d5", AntigravityManifest.arm64.sha256)
    }

    @Test fun `rejects unsupported ABI`() {
        assertThrows(IllegalStateException::class.java) { AntigravityManifest.assetFor("armeabi-v7a") }
    }
}
