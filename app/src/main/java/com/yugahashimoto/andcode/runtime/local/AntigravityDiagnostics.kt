package com.yugahashimoto.andcode.runtime.local

data class AntigravityDiagnostics(
    val abi: String,
    val gcompatInstalled: Boolean,
    val officialAssetSha256: String,
    val version: String?,
    val stderrTail: String,
    val classification: Classification,
) {
    enum class Classification { READY, VA39_LSE_UNSUPPORTED, GLIBC_INCOMPATIBLE, NOT_INSTALLED, UNKNOWN }

    companion object {
        fun classify(
            abi: String,
            stderr: String,
            installed: Boolean,
            version: String?,
        ): Classification {
            if (!installed) return Classification.NOT_INSTALLED
            val lower = stderr.lowercase()
            if ("va39" in lower || "lse" in lower) return Classification.VA39_LSE_UNSUPPORTED
            if ("glibc" in lower || "ld-linux" in lower) return Classification.GLIBC_INCOMPATIBLE
            return if (!version.isNullOrBlank()) Classification.READY else Classification.UNKNOWN
        }
    }
}
