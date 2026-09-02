package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.data.connection.SecureSettingsRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class LocalProviderCredentialStore(
    private val load: () -> Map<String, String>,
    private val save: (Map<String, String>) -> Unit,
    private val loadManagedProviderIds: () -> Set<String> = { load().keys },
    private val saveManagedProviderIds: (Set<String>) -> Unit = {},
    private val json: Json = defaultJson,
) {
    constructor(settings: SecureSettingsRepository, json: Json = defaultJson) : this(
        load = { settings.providerApiKeys() },
        save = { settings.providerApiKeys = it },
        loadManagedProviderIds = {
            if (settings.hasManagedProviderApiKeyIds) {
                settings.managedProviderApiKeyIds
            } else {
                settings.providerApiKeys().keys
            }
        },
        saveManagedProviderIds = { settings.managedProviderApiKeyIds = it },
        json = json,
    )

    fun credentials(): Map<String, String> = load()

    fun managedProviderIds(): Set<String> =
        loadManagedProviderIds()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()

    fun setCredential(
        providerId: String,
        apiKey: String?,
    ) {
        val normalizedId = providerId.trim()
        require(normalizedId.isNotEmpty()) { "Provider id is required" }

        val updatedCredentials = credentials().toMutableMap()
        val normalizedKey = apiKey?.trim().orEmpty()
        if (normalizedKey.isEmpty()) {
            updatedCredentials.remove(normalizedId)
        } else {
            updatedCredentials[normalizedId] = normalizedKey
        }
        val updatedManagedIds = managedProviderIds() + normalizedId

        save(updatedCredentials)
        saveManagedProviderIds(updatedManagedIds)
    }

    fun clearCredential(providerId: String) = setCredential(providerId, null)

    fun unmanageProvider(providerId: String) {
        val normalizedId = providerId.trim()
        if (normalizedId.isEmpty()) return
        saveManagedProviderIds(managedProviderIds() - normalizedId)
    }

    fun hasCredential(providerId: String): Boolean = !credentials()[providerId.trim()].isNullOrBlank()

    fun syncToRuntime(rootfs: File): File {
        val authDir = File(rootfs, "root/.local/share/opencode").apply { mkdirs() }
        val authFile = File(authDir, "auth.json")
        val managedIds = managedProviderIds()
        if (managedIds.isEmpty()) return authFile

        val existingPayload = readExistingPayload(authFile)
        val currentCredentials = credentials()
        val mutableEntries = existingPayload.toMutableMap()
        managedIds.forEach { providerId ->
            val apiKey = currentCredentials[providerId]?.trim().orEmpty()
            if (apiKey.isEmpty()) {
                mutableEntries.remove(providerId)
            } else {
                mutableEntries[providerId] =
                    buildJsonObject {
                        put("type", "api")
                        put("key", apiKey)
                    }
            }
        }
        writeAtomically(authFile, json.encodeToString<JsonObject>(JsonObject(mutableEntries)))
        return authFile
    }

    private fun readExistingPayload(authFile: File): JsonObject {
        if (!authFile.isFile) return JsonObject(emptyMap())
        return runCatching {
            json.parseToJsonElement(authFile.readText()).jsonObject
        }.getOrElse { error ->
            throw IllegalStateException(
                "Existing OpenCode auth.json is invalid and was not modified",
                error,
            )
        }
    }

    private fun writeAtomically(
        destination: File,
        content: String,
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.delete()
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            temporary.setReadable(true, true)
            temporary.setWritable(true, true)
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            destination.setReadable(true, true)
            destination.setWritable(true, true)
        } finally {
            temporary.delete()
        }
    }

    companion object {
        private val defaultJson: Json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
            }

        fun decodeMap(
            raw: String?,
            json: Json = defaultJson,
        ): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            return runCatching {
                json.decodeFromString<Map<String, String>>(raw)
                    .mapKeys { it.key.trim() }
                    .filter { it.key.isNotEmpty() && it.value.isNotBlank() }
                    .mapValues { it.value.trim() }
            }.getOrDefault(emptyMap())
        }

        fun encodeMap(
            map: Map<String, String>,
            json: Json = defaultJson,
        ): String = json.encodeToString(map)
    }
}
