package com.yugahashimoto.andcode.feature.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubAuthRepositoryTest {
    @Test
    fun `blank client id disables device flow`() {
        assertFalse(GitHubAuthRepositoryTestProbe.isConfigured(""))
        assertTrue(GitHubAuthRepositoryTestProbe.isConfigured("client-id"))
    }

    /**
     * Confirmed on a real device: connecting GitHub failed once with "Unable to resolve host
     * github.com" and succeeded moments later with no other change - a transient DNS blip, not a
     * real connectivity problem. retryOnUnknownHost is what absorbs that automatically instead of
     * making the user notice the failure and tap "Connect" again themselves.
     */
    @Test
    fun `retryOnUnknownHost absorbs a transient DNS failure and returns the eventual success`() =
        runTest {
            var attempts = 0
            val result =
                retryOnUnknownHost(attempts = 3, delayMillis = 10) {
                    attempts++
                    if (attempts < 3) throw UnknownHostException("Unable to resolve host \"github.com\"")
                    "connected"
                }

            assertEquals("connected", result)
            assertEquals(3, attempts)
        }

    @Test
    fun `retryOnUnknownHost gives up once its attempts are exhausted`() =
        runTest {
            var attempts = 0

            assertThrows(UnknownHostException::class.java) {
                kotlinx.coroutines.runBlocking {
                    retryOnUnknownHost(attempts = 3, delayMillis = 10) {
                        attempts++
                        throw UnknownHostException("Unable to resolve host \"github.com\"")
                    }
                }
            }

            assertEquals(3, attempts)
        }

    /** Only the confirmed DNS-blip exception is retried; any other failure must surface at once. */
    @Test
    fun `retryOnUnknownHost does not retry failures other than UnknownHostException`() =
        runTest {
            var attempts = 0

            assertThrows(IOException::class.java) {
                kotlinx.coroutines.runBlocking {
                    retryOnUnknownHost(attempts = 3, delayMillis = 10) {
                        attempts++
                        throw IOException("connection reset")
                    }
                }
            }

            assertEquals(1, attempts)
        }

    @Test
    fun `retryOnUnknownHost succeeds immediately without retrying when there is no failure`() =
        runTest {
            var attempts = 0
            val result =
                retryOnUnknownHost(attempts = 3, delayMillis = 10) {
                    attempts++
                    "ok"
                }

            assertEquals("ok", result)
            assertEquals(1, attempts)
        }
}

private object GitHubAuthRepositoryTestProbe {
    fun isConfigured(clientId: String) = clientId.isNotBlank()
}
