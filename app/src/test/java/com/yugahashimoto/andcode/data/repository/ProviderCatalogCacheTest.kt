package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.OpenCodeModel
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderCatalogCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var cache: ProviderCatalogCache

    private val catalog =
        ProviderCatalog(
            all =
                listOf(
                    provider("anthropic", "claude-sonnet-5"),
                    provider("openai", "gpt-5"),
                ),
            connected = listOf("anthropic"),
            default = mapOf("anthropic" to "claude-sonnet-5"),
        )

    @Before
    fun setUp() {
        cache = ProviderCatalogCache(temporaryFolder.newFolder("cache"), Json { ignoreUnknownKeys = true })
    }

    @Test
    fun `keeps the models of connected providers and drops the rest`() {
        cache.write("local", "1.0.0", catalog)

        val stored = requireNotNull(cache.read("local", "1.0.0"))
        assertEquals(listOf("anthropic", "openai"), stored.all.map { it.id })
        assertEquals(setOf("claude-sonnet-5"), stored.all[0].models.keys)
        assertTrue(stored.all[1].models.isEmpty())
        // Names survive so a provider can still be found and connected.
        assertEquals("openai", stored.all[1].name)
    }

    @Test
    fun `discards what an older runtime version wrote`() {
        cache.write("local", "1.0.0", catalog)

        assertNull(cache.read("local", "1.0.1"))
        assertNotNull(cache.readAny("local"))
    }

    @Test
    fun `reports nothing for a runtime it has never seen`() {
        assertNull(cache.read("other", "1.0.0"))
        assertNull(cache.readAny("other"))
    }

    @Test
    fun `treats a change to the connected set as stale`() {
        cache.write("local", "1.0.0", catalog)

        assertFalse(cache.isStale("local", "1.0.0", catalog))
        assertTrue(cache.isStale("local", "1.0.0", catalog.copy(connected = listOf("anthropic", "openai"))))
    }

    @Test
    fun `keeps runtimes apart even when their ids need escaping`() {
        cache.write("http://host:1234/", "1.0.0", catalog)
        cache.write("local-android", "1.0.0", catalog.copy(connected = emptyList()))

        assertEquals(listOf("anthropic"), cache.read("http://host:1234/", "1.0.0")?.connected)
        assertEquals(emptyList<String>(), cache.read("local-android", "1.0.0")?.connected)
    }

    private fun provider(
        id: String,
        modelId: String,
    ) = OpenCodeProvider(id = id, name = id, models = mapOf(modelId to OpenCodeModel(id = modelId, providerId = id)))
}
