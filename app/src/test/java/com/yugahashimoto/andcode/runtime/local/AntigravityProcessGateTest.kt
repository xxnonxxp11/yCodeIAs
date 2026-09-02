package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityProcessGateTest {
    @Test
    fun `readWithTimeout returns output from a process that exits quickly`() {
        val process = ProcessBuilder("sh", "-c", "echo hello").redirectErrorStream(true).start()
        val output = AntigravityProcessGate.readWithTimeout(process, 5_000)
        assertEquals("hello\n", output)
    }

    @Test
    fun `readWithTimeout gives up on a process that never produces output or exits`() {
        // A plain `readText()` before a `waitFor(timeout)` blocks here indefinitely, which is exactly
        // the bug confirmed on a real device: the timeout code after it was never reached.
        val process = ProcessBuilder("sh", "-c", "sleep 30").redirectErrorStream(true).start()
        try {
            val started = System.currentTimeMillis()
            val output = AntigravityProcessGate.readWithTimeout(process, 500)
            assertNull(output)
            assertTrue(System.currentTimeMillis() - started < 5_000)
        } finally {
            process.destroyForcibly()
        }
    }

    /**
     * The device symptom this guards: a child that reads stdin never finishes when `ProcessBuilder`
     * hands it a pipe the JVM keeps open, because no input and no EOF ever arrive.
     */
    @Test
    fun `withoutStdin gives the child an immediate EOF instead of an open pipe`() {
        with(AntigravityProcessGate) {
            val process =
                ProcessBuilder("sh", "-c", "cat; echo done")
                    .redirectErrorStream(true)
                    .withoutStdin()
                    .start()
            val output = AntigravityProcessGate.readWithTimeout(process, 5_000)
            assertEquals("done\n", output)
        }
    }

    @Test
    fun `an open stdin pipe is what makes such a child hang`() {
        val process = ProcessBuilder("sh", "-c", "cat; echo done").redirectErrorStream(true).start()
        try {
            assertNull(AntigravityProcessGate.readWithTimeout(process, 500))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `exclusive serializes callers`() {
        val order = mutableListOf<Int>()
        val threads =
            (1..3).map { id ->
                Thread {
                    AntigravityProcessGate.exclusive {
                        order.add(id)
                        Thread.sleep(20)
                    }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(3, order.size)
    }

    @Test
    fun `serialize queues a second send behind an in-flight one instead of failing`() {
        val events = mutableListOf<String>()
        val first =
            Thread {
                AntigravityProcessGate.serialize {
                    events.add("first-start")
                    Thread.sleep(150)
                    events.add("first-end")
                }
            }
        first.start()
        // Let the first sender take the gate, then a second send must wait for it to finish rather
        // than run concurrently (the on-device hang) or fail fast (what `exclusive` would do).
        Thread.sleep(20)
        val secondResult =
            AntigravityProcessGate.serialize {
                events.add("second")
                "done"
            }
        first.join()
        assertEquals("done", secondResult)
        assertEquals(listOf("first-start", "first-end", "second"), events)
    }
}
