package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LocalRuntimeManifest(
    @SerialName("schemaVersion") val schemaVersion: Int,
    @SerialName("runtimeVersion") val runtimeVersion: String,
    @SerialName("openCodeVersion") val openCodeVersion: String,
    @SerialName("alpineVersion") val alpineVersion: String,
    @SerialName("port") val port: Int,
    @SerialName("architectures") val architectures: Map<String, LocalRuntimeArchitecture>,
) {
    fun architecture(abi: String): LocalRuntimeArchitecture =
        requireNotNull(architectures[abi]) { "Local runtime does not support ABI $abi" }

    fun validate() {
        require(schemaVersion == 1) { "Unsupported local runtime manifest schema: $schemaVersion" }
        require(runtimeVersion.isNotBlank()) { "Runtime version is missing" }
        require(openCodeVersion.isNotBlank()) { "OpenCode version is missing" }
        require(port in 1024..65535) { "Invalid local OpenCode port: $port" }
        require(architectures.isNotEmpty()) { "Runtime manifest has no architectures" }
        architectures.forEach { (abi, item) -> item.validate(abi) }
    }
}

@Serializable
data class LocalRuntimeArchitecture(
    @SerialName("alpineUrl") val alpineUrl: String,
    @SerialName("alpineSha256") val alpineSha256: String,
    @SerialName("openCodeUrl") val openCodeUrl: String,
    @SerialName("openCodeSha256") val openCodeSha256: String,
) {
    fun validate(abi: String) {
        require(alpineUrl.startsWith("https://")) { "Alpine URL for $abi must use HTTPS" }
        require(openCodeUrl.startsWith("https://")) { "OpenCode URL for $abi must use HTTPS" }
        require(SHA256.matches(alpineSha256)) { "Invalid Alpine SHA-256 for $abi" }
        require(SHA256.matches(openCodeSha256)) { "Invalid OpenCode SHA-256 for $abi" }
    }

    companion object {
        private val SHA256 = Regex("^[a-f0-9]{64}$")
    }
}

class LocalRuntimeManifestReader(
    private val context: Context,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        },
) {
    fun read(): LocalRuntimeManifest {
        val payload = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        return json.decodeFromString<LocalRuntimeManifest>(payload).also { it.validate() }
    }

    companion object {
        private const val ASSET_NAME = "local-runtime-manifest.json"
    }
}
