package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeModel
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.ProviderCatalog

/**
 * Models offered for Antigravity, read from `agy models`.
 *
 * Unlike Claude Code, the official CLI does enumerate what the signed-in account can actually use.
 * The pinned official release (1.1.7, [AntigravityManifest.VERSION]) prints one lowercase, hyphenated
 * slug per line - `gemini-3.1-pro-high`, `claude-opus-4-6-thinking`, `claude-sonnet-4-6` - confirmed
 * live against a signed-in install of that exact version. Earlier builds of this parser were tested
 * against a different locally-installed `agy` version (1.1.1) that prints `Title Case (Variant)`
 * labels instead; that format does not appear in the version this app actually ships, which is why
 * the model picker previously showed nothing useful.
 */
object AntigravityModels {
    const val PROVIDER_ID = "antigravity"

    /** Shown until `agy models` has run once for a signed-in account. */
    private const val FALLBACK_MODEL = "default"

    /**
     * The reasoning-effort suffixes, and the only ones that split off a base slug.
     *
     * Measured against the CLI: `--model gemini-3.6-flash` alone is rejected with "requires --effort
     * (available: low, medium, high)", so a `-high` suffix belongs in `--effort`, not in the model
     * name. `-thinking` is not one of those values - `--model claude-opus-4-6-thinking` is accepted
     * as-is and adding `--effort` would conflict - so it stays part of the model slug.
     */
    private val EFFORT_SUFFIXES = setOf("high", "medium", "low")

    data class Entry(val base: String, val variant: String?) {
        /** The line the CLI printed, and the id the picker shows. */
        val slug: String get() = if (variant != null) "$base-$variant" else base
    }

    /**
     * Parses one model per non-blank line.
     *
     * A slug's last hyphen-separated segment splits off only when it is a reasoning effort;
     * `claude-sonnet-4-6`'s last segment is `6` and `claude-opus-4-6-thinking`'s is `thinking`, so
     * both stay whole - splitting on every hyphen would cut version numbers like `4-6` apart.
     */
    fun parse(output: String): List<Entry> =
        output.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { slug ->
                val segments = slug.split('-')
                val last = segments.last()
                if (segments.size > 1 && last in EFFORT_SUFFIXES) {
                    Entry(segments.dropLast(1).joinToString("-"), last)
                } else {
                    Entry(slug, null)
                }
            }
            .toList()

    val KNOWN_MODELS: List<Entry> =
        listOf(
            Entry("gemini-3.7-flash", "medium"),
            Entry("gemini-3.7-flash", "low"),
            Entry("gemini-3.7-flash", "high"),
            Entry("gemini-3.7-pro", "high"),
            Entry("gemini-3.7-pro", "medium"),
            Entry("gemini-3.7-pro", "low"),
            Entry("gemini-3.1-pro", "high"),
            Entry("gemini-3.1-pro", "low"),
            Entry("gemini-3.6-flash", "high"),
            Entry("gemini-3.6-flash", "medium"),
            Entry("gemini-3.6-flash", "low"),
            Entry("gemini-3.5-flash", "high"),
            Entry("gemini-3.5-flash", "medium"),
            Entry("gemini-3.5-flash", "low"),
            Entry("claude-opus-4-6-thinking", null),
            Entry("claude-sonnet-4-6", null),
            Entry("claude-3-7-sonnet", null),
        )

    fun modelLimit(slug: String): com.yugahashimoto.andcode.core.api.OpenCodeModelLimit {
        val lower = slug.lowercase()
        return when {
            lower.contains("gemini") -> com.yugahashimoto.andcode.core.api.OpenCodeModelLimit(context = 1_048_576L, output = 65_536L)
            lower.contains("claude") -> com.yugahashimoto.andcode.core.api.OpenCodeModelLimit(context = 200_000L, output = 8_192L)
            else -> com.yugahashimoto.andcode.core.api.OpenCodeModelLimit(context = 1_000_000L, output = 8_192L)
        }
    }

    /**
     * Groups parsed entries by base model name, in the order `agy models` printed them.
     *
     * An empty [entries] list (not signed in yet, or `agy models` failed) falls back to the
     * curated [KNOWN_MODELS] including Gemini 3.7. When [entries] is non-empty, [KNOWN_MODELS]
     * are merged in so that Gemini 3.7 remains readily selectable.
     */
    fun catalog(entries: List<Entry>): ProviderCatalog {
        val combined =
            if (entries.isEmpty()) {
                KNOWN_MODELS
            } else {
                (entries + KNOWN_MODELS).distinctBy { it.slug }
            }
        val models =
            combined.associate { entry ->
                val id = entry.slug
                id to OpenCodeModel(id = id, providerId = PROVIDER_ID, name = id, limit = modelLimit(id))
            }
        return ProviderCatalog(
            all = listOf(OpenCodeProvider(PROVIDER_ID, "Antigravity", models)),
            default = mapOf(PROVIDER_ID to combined.first().slug),
            connected = listOf(PROVIDER_ID),
        )
    }

    /**
     * CLI arguments for [model] (a base id from [catalog]) and [variant].
     *
     * A model that has efforts must carry one: measured against the CLI, `--model gemini-3.6-flash`
     * on its own fails with "requires --effort". A model that has none must not be given one, or the
     * CLI reports a conflict - hence `--effort` is emitted only for a real effort suffix.
     *
     * These must be placed *before* `--print`. `--print` takes the prompt as its value, so any flag
     * after it is swallowed as the prompt instead: `--print --mode accept-edits "hi"` sent the CLI
     * the literal prompt "--mode" and the model answered by explaining the flag.
     */
    fun cliArgs(
        model: String?,
        variant: String?,
    ): List<String> {
        val selected = model?.takeIf(String::isNotBlank) ?: return emptyList()
        if (selected == FALLBACK_MODEL) return emptyList()
        val entry = parse(selected).singleOrNull() ?: Entry(selected, null)
        val effort =
            entry.variant
                ?: variant?.trim()?.lowercase()?.takeIf { it in EFFORT_SUFFIXES }
                ?: if (entry.base.contains("gemini") || entry.base.contains("gpt-oss")) "medium" else null
        return listOf("--model", entry.base) + (effort?.let { listOf("--effort", it) } ?: emptyList())
    }
}
