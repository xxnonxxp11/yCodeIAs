package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [output] is captured verbatim from `agy models` run on a signed-in real device with the exact
 * official 1.1.7 release this app pins - not the differently-formatted output an unrelated locally
 * installed 1.1.1 build printed, which an earlier version of this parser was built against.
 */
class AntigravityModelsTest {
    private val output =
        """
        gemini-3.6-flash-high
        gemini-3.6-flash-medium
        gemini-3.6-flash-low
        gemini-3.5-flash-high
        gemini-3.5-flash-medium
        gemini-3.5-flash-low
        gemini-3.1-pro-high
        gemini-3.1-pro-low
        claude-sonnet-4-6
        claude-opus-4-6-thinking
        gpt-oss-120b-medium
        """.trimIndent()

    @Test
    fun `parses the real agy models output`() {
        val entries = AntigravityModels.parse(output)
        assertEquals(11, entries.size)
        assertEquals(AntigravityModels.Entry("gemini-3.6-flash", "high"), entries.first())
        assertEquals(AntigravityModels.Entry("gpt-oss-120b", "medium"), entries.last())
    }

    @Test
    fun `a version number ending in a digit is not mistaken for a variant`() {
        val entry = AntigravityModels.parse("claude-sonnet-4-6").single()
        assertEquals(AntigravityModels.Entry("claude-sonnet-4-6", null), entry)
    }

    /** The picker lists whole CLI ids, effort included, so an effort can never be left unselected. */
    @Test
    fun `catalog lists one model per printed line and merges known models`() {
        val catalog = AntigravityModels.catalog(AntigravityModels.parse(output))
        val provider = catalog.all.single()
        assertTrue(provider.models.containsKey("gemini-3.7-flash-high"))
        assertTrue(provider.models.containsKey("gemini-3.7-pro-high"))
        assertTrue(provider.models.containsKey("gemini-3.6-flash-high"))
        assertTrue(provider.models.containsKey("gemini-3.1-pro-low"))
        assertTrue(provider.models.containsKey("claude-sonnet-4-6"))
        assertTrue(provider.models.values.all { it.variants.isEmpty() })
    }

    /** `--model claude-opus-4-6-thinking` is accepted whole; `thinking` is not a `--effort` value. */
    @Test
    fun `a thinking suffix stays part of the model id`() {
        assertEquals(
            AntigravityModels.Entry("claude-opus-4-6-thinking", null),
            AntigravityModels.parse("claude-opus-4-6-thinking").single(),
        )
        val catalog = AntigravityModels.catalog(AntigravityModels.parse(output))
        assertTrue(catalog.all.single().models.containsKey("claude-opus-4-6-thinking"))
    }

    @Test
    fun `falls back to curated known models including gemini 3_7 when nothing was parsed`() {
        val catalog = AntigravityModels.catalog(emptyList())
        val provider = catalog.all.single()
        assertTrue(provider.models.containsKey("gemini-3.7-flash-high"))
        assertTrue(provider.models.containsKey("gemini-3.7-pro-high"))
        assertEquals("gemini-3.7-flash-medium", catalog.default[AntigravityModels.PROVIDER_ID])
    }

    @Test
    fun `a slug without a variant suffix has no variant`() {
        val entries = AntigravityModels.parse("some-custom-model\n")
        assertEquals(AntigravityModels.Entry("some-custom-model", null), entries.single())
    }

    /** `--model gemini-3.1-pro` alone is rejected by the CLI with "requires --effort". */
    @Test
    fun `an effort is sent through its own flag`() {
        // The picker id already carries the effort, so no separate variant is needed or trusted.
        assertEquals(listOf("--model", "gemini-3.1-pro", "--effort", "high"), AntigravityModels.cliArgs("gemini-3.1-pro-high", null))
        assertEquals(
            listOf("--model", "gemini-3.7-flash", "--effort", "medium"),
            AntigravityModels.cliArgs("gemini-3.7-flash-medium", null),
        )
        assertEquals(listOf("--model", "gpt-oss-120b", "--effort", "medium"), AntigravityModels.cliArgs("gpt-oss-120b-medium", null))
    }

    /** Adding `--effort` to a model that has none makes the CLI report a conflict. */
    @Test
    fun `a model with no effort is sent alone`() {
        assertEquals(listOf("--model", "claude-sonnet-4-6"), AntigravityModels.cliArgs("claude-sonnet-4-6", null))
        assertEquals(listOf("--model", "claude-opus-4-6-thinking"), AntigravityModels.cliArgs("claude-opus-4-6-thinking", null))
    }

    @Test
    fun `no model selected omits every flag`() {
        assertEquals(emptyList<String>(), AntigravityModels.cliArgs(null, null))
        assertEquals(emptyList<String>(), AntigravityModels.cliArgs("default", null))
    }

    @Test
    fun `assigns correct context limits to models in catalog`() {
        val catalog = AntigravityModels.catalog(AntigravityModels.parse(output))
        val provider = catalog.all.single()
        val geminiModel = provider.models["gemini-3.7-flash-high"]
        assertEquals(1_048_576L, geminiModel?.limit?.context)

        val claudeModel = provider.models["claude-opus-4-6-thinking"]
        assertEquals(200_000L, claudeModel?.limit?.context)

        val fallbackCatalog = AntigravityModels.catalog(emptyList())
        val fallbackGemini = fallbackCatalog.all.single().models["gemini-3.7-flash-high"]
        assertEquals(1_048_576L, fallbackGemini?.limit?.context)
    }
}
