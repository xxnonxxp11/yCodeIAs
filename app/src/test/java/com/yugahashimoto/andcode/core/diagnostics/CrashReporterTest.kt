package com.yugahashimoto.andcode.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashReporterTest {
    @Test
    fun `sanitizer collapses whitespace and caps log size`() {
        val input = "  first\n\tsecond  " + "x".repeat(1_100)

        val sanitized = CrashReporter.CrashReportSanitizer.message(input)

        assertEquals(("first second " + "x".repeat(1_100)).take(1_000), sanitized)
    }

    @Test
    fun `sanitizer makes custom keys safe and caps values`() {
        assertEquals("runtime_error_code", CrashReporter.CrashReportSanitizer.key("runtime.error-code"))
        assertEquals("value", CrashReporter.CrashReportSanitizer.value(" value "))
        assertEquals(100, CrashReporter.CrashReportSanitizer.value("x".repeat(200)).length)
    }
}
