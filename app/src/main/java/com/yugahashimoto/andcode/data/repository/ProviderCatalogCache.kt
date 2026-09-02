package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Disk cache for a runtime's provider catalogue.
 *
 * OpenCode's `/provider` response is around 4 MB — 172 providers and 5,700 models — and fetching
 * and parsing it is what made the model picker open late. Only connected providers ever have their
 * models read; the rest are listed by name so the user can connect them. Dropping the models of
 * everything not connected takes the stored copy to roughly 14 KB, which is cheap enough to load
 * before the first frame.
 *
 * The cache is keyed on the runtime and its version, so an OpenCode upgrade discards it. A change
 * to the connected set does too: connecting a provider is exactly when its models start mattering.
 */
class ProviderCatalogCache(
    private val directory: File,
    private val json: Json,
) {
    fun read(
        runtimeId: String,
        version: String,
    ): ProviderCatalog? = entry(runtimeId)?.takeIf { it.version == version }?.catalog

    /**
     * The stored catalogue whatever version wrote it.
     *
     * Used to fill the picker before the runtime has been reached at all: the version cannot be
     * known yet, and a slightly stale list beats an empty one for the seconds the runtime takes to
     * start. The verified copy replaces it as soon as the fetch lands.
     */
    fun readAny(runtimeId: String): ProviderCatalog? = entry(runtimeId)?.catalog

    private fun entry(runtimeId: String): Entry? = runCatching { json.decodeFromString<Entry>(file(runtimeId).readText()) }.getOrNull()

    fun write(
        runtimeId: String,
        version: String,
        catalog: ProviderCatalog,
    ) {
        runCatching {
            directory.mkdirs()
            file(runtimeId).writeText(json.encodeToString(Entry(version, trim(catalog))))
        }
    }

    /**
     * True when [catalog] differs from the cache in its connected providers or in the models those
     * providers offer.
     *
     * Comparing only the connected set is not enough for a runtime whose catalogue comes from
     * querying a CLI rather than from a static list. Antigravity is always connected to exactly one
     * provider, so a catalogue written before sign-in — holding a single placeholder model — matched
     * on `connected` forever and was never replaced by the real list, leaving the picker showing one
     * fake model no matter how many the account actually had.
     */
    fun isStale(
        runtimeId: String,
        version: String,
        catalog: ProviderCatalog,
    ): Boolean {
        val cached = read(runtimeId, version) ?: return true
        if (cached.connected.toSet() != catalog.connected.toSet()) return true
        return modelIdsByConnectedProvider(cached) != modelIdsByConnectedProvider(catalog)
    }

    /** Only connected providers keep their models through [trim], so only those can be compared. */
    private fun modelIdsByConnectedProvider(catalog: ProviderCatalog): Map<String, Set<String>> {
        val connected = catalog.connected.toSet()
        return catalog.all
            .filter { it.id in connected }
            .associate { it.id to it.models.keys.toSet() }
    }

    private fun file(runtimeId: String) = File(directory, "providers-${runtimeId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    private fun trim(catalog: ProviderCatalog): ProviderCatalog {
        val connected = catalog.connected.toSet()
        return catalog.copy(
            all =
                catalog.all.map { provider ->
                    if (provider.id in connected) provider else OpenCodeProvider(provider.id, provider.name)
                },
        )
    }

    @kotlinx.serialization.Serializable
    private data class Entry(
        val version: String,
        val catalog: ProviderCatalog,
    )
}
