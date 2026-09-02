package com.yugahashimoto.andcode.runtime.local

/**
 * Tells an intentional kill of an Antigravity session's process apart from a real crash.
 *
 * [killAntigravityProcessTree] sends SIGKILL, so a process that was stopped on purpose - the stop
 * button ([AntigravityRuntime.abort]), or [AntigravityRuntime.send] itself replacing a still-running
 * process for the same session - exits with the same code (137, i.e. 128 + SIGKILL) as one that
 * actually died. Without this, every intentional stop was reported as `"agy exited with 137"`,
 * turning a clean cancellation into an error banner - the bug seen on device as a chat send failing
 * with that message whenever it was sent while the previous turn was still "thinking".
 *
 * [markIntentional] is called right before the kill; [consumeIntentional] is called exactly once by
 * the [AntigravityRuntime.send] call whose kill it explains, whether or not that call's process
 * actually ended up dying from it (an abort can race a turn that was already finishing on its own).
 * Consuming unconditionally - not just when the exit code turns out non-zero - is what keeps a stale
 * flag from silently swallowing an unrelated, later, real crash on the same session id.
 */
internal class AntigravityAbortTracker {
    private val intentionallyKilled: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    fun markIntentional(sessionId: String) {
        intentionallyKilled.add(sessionId)
    }

    fun consumeIntentional(sessionId: String): Boolean = intentionallyKilled.remove(sessionId)
}
