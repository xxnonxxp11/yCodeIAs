package com.yugahashimoto.andcode.core.security

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object OpenCodeUrl {
    fun normalize(raw: String): Result<HttpUrl> =
        runCatching {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "Endpoint is required" }

            val withScheme =
                if (SCHEME_PATTERN.containsMatchIn(trimmed)) {
                    trimmed
                } else {
                    "http://$trimmed"
                }

            val parsed =
                withScheme.toHttpUrlOrNull()
                    ?: throw IllegalArgumentException("Invalid OpenCode endpoint")

            require(parsed.scheme == "http" || parsed.scheme == "https") {
                "Only HTTP and HTTPS endpoints are supported"
            }
            require(parsed.host.isNotBlank()) { "Endpoint host is required" }

            if (parsed.scheme == "http") {
                require(isTrustedCleartextHost(parsed.host)) {
                    "Cleartext HTTP is allowed only for localhost, LAN, .local, and Tailscale addresses"
                }
            }

            parsed.newBuilder().apply {
                if (!parsed.encodedPath.endsWith('/')) {
                    addPathSegment("")
                }
            }.build()
        }

    internal fun isTrustedCleartextHost(host: String): Boolean {
        val normalized = host.lowercase().removeSurrounding("[", "]")
        if (normalized == "localhost" || normalized.endsWith(".local")) return true
        if (":" in normalized &&
            (normalized == "::1" || normalized.startsWith("fc") || normalized.startsWith("fd"))
        ) {
            return true
        }

        // Every label must itself be a decimal octet. Matching on `mapNotNull` alone would accept
        // hostnames such as `10.0.0.1.attacker.example` as private addresses.
        val labels = normalized.split('.')
        if (labels.size != 4) return false
        val octets =
            labels.map { label ->
                label.takeIf { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) }
                    ?.toIntOrNull()
                    ?: return false
            }
        if (octets.any { it !in 0..255 }) return false

        return when {
            octets[0] == 10 -> true
            octets[0] == 127 -> true
            octets[0] == 169 && octets[1] == 254 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            octets[0] == 100 && octets[1] in 64..127 -> true
            else -> false
        }
    }

    private val SCHEME_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
}
