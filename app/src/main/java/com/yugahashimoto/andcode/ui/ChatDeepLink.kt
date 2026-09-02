package com.yugahashimoto.andcode.ui

/**
 * A notification tap asking the app to open a chat.
 *
 * [token] makes every tap a distinct value even when it names the same session twice in a row, so
 * the one-shot handler re-runs instead of seeing an unchanged state and doing nothing.
 */
data class ChatDeepLink(
    val sessionId: String,
    val runtimeId: String?,
    val token: Long,
)
