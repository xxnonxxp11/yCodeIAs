package com.yugahashimoto.andcode.runtime

import com.yugahashimoto.andcode.R

/**
 * A coding agent that can be installed into the shared Android-local Linux sandbox.
 *
 * Both agents run inside the same Alpine/PRoot rootfs; they differ in how they are provisioned
 * (OpenCode ships as a downloaded binary, Claude Code as an apk package) and in how the app talks
 * to them (a local HTTP server versus a streaming JSON process).
 */
enum class LocalAgent(
    val id: String,
    val displayNameRes: Int,
    val targetId: String,
    /** Marks which agent a row belongs to where the name does not fit - drawer chats, for one. */
    val iconRes: Int,
) {
    OPEN_CODE("opencode", R.string.agent_opencode_name, "local-android", R.drawable.ic_agent_opencode),
    CLAUDE_CODE("claude-code", R.string.agent_claude_code_name, "claude-code-local", R.drawable.ic_agent_claude),
    ANTIGRAVITY("antigravity", R.string.agent_antigravity_name, "antigravity-local", R.drawable.ic_agent_antigravity),
    ;

    companion object {
        fun fromId(id: String): LocalAgent? = entries.firstOrNull { it.id == id }
    }
}
