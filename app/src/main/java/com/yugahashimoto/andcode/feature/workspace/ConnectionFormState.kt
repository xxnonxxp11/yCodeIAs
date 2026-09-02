package com.yugahashimoto.andcode.feature.workspace

import com.yugahashimoto.andcode.core.security.OpenCodeUrl
import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import java.util.UUID

data class ConnectionFormState(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val baseUrl: String = "",
    val username: String = "opencode",
    val password: String = "",
    val allowInsecureLan: Boolean = false,
    val isTesting: Boolean = false,
    val testMessage: String? = null,
    val testSucceeded: Boolean = false,
) {
    private val parsedUrl
        get() = OpenCodeUrl.normalize(baseUrl).getOrNull()

    val normalizedUrl: String?
        get() = parsedUrl?.toString()

    /**
     * [OpenCodeUrl.normalize] only ever returns an `http` URL for loopback, RFC1918, link-local,
     * Tailscale CGNAT and `.local` hosts, and rejects everything else that is not https. A valid
     * endpoint is therefore already a safe one, and no separate cleartext opt-in is required.
     */
    val canSave: Boolean
        get() = name.isNotBlank() && parsedUrl != null

    fun toProfile(): ConnectionProfile {
        val url = requireNotNull(parsedUrl) { "Endpoint is not a valid OpenCode URL" }
        return ConnectionProfile(
            id = id,
            name = name.trim(),
            baseUrl = url.toString(),
            username = username.trim().ifBlank { "opencode" },
            password = password.takeIf { it.isNotBlank() },
            // `opencode serve` on a PC is plain HTTP on the LAN. normalize() has already limited
            // cleartext to private address space, so record the allowance here instead of asking
            // the user to tick a box before the connection can be saved at all.
            allowInsecureLan = allowInsecureLan || url.scheme == "http",
        )
    }

    companion object {
        fun from(profile: ConnectionProfile): ConnectionFormState =
            ConnectionFormState(
                id = profile.id,
                name = profile.name,
                baseUrl = profile.baseUrl,
                username = profile.username,
                password = profile.password.orEmpty(),
                allowInsecureLan = profile.allowInsecureLan,
                testSucceeded = true,
            )
    }
}
