package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Test

class AntigravityDiagnosticsTest {
    @Test fun `classifies VA39 and LSE as unsupported`() {
        assertEquals(
            AntigravityDiagnostics.Classification.VA39_LSE_UNSUPPORTED,
            AntigravityDiagnostics.classify("arm64-v8a", "illegal instruction: VA39 LSE", true, null),
        )
    }

    @Test fun `classifies a verified version as ready`() {
        assertEquals(
            AntigravityDiagnostics.Classification.READY,
            AntigravityDiagnostics.classify("x86_64", "", true, "1.1.7"),
        )
    }
}
