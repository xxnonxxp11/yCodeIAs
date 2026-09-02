package com.yugahashimoto.andcode.runtime

sealed interface LocalRuntimeStatus {
    data object NotInstalled : LocalRuntimeStatus

    /**
     * [agent] is the agent [step] belongs to, or null for the shared Linux environment.
     *
     * One install provisions every selected agent, so without this the setup guide put every
     * step under the OpenCode heading - including "Installing Claude Code".
     */
    data class Installing(
        val progress: Float?,
        val step: String,
        val agent: LocalAgent? = null,
        val detail: String? = null,
    ) : LocalRuntimeStatus

    data class Stopped(val version: String, val port: Int) : LocalRuntimeStatus

    data class Starting(val version: String, val port: Int) : LocalRuntimeStatus

    data class Updating(
        val currentVersion: String,
        val targetVersion: String,
        val progress: Float?,
        val step: String,
    ) : LocalRuntimeStatus

    data class Ready(val version: String, val port: Int) : LocalRuntimeStatus

    data class Broken(val reason: String) : LocalRuntimeStatus

    data class UnsupportedAbi(val abi: String) : LocalRuntimeStatus
}
