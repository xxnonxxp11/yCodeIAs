package com.yugahashimoto.andcode.runtime.local

/**
 * Kills a whole process tree when the JVM only holds a handle to its root.
 *
 * `Process.destroy()`/`destroyForcibly()` signals a single OS process - the native PRoot binary.
 * Confirmed on a real device: killing that process, even with SIGKILL sent directly (not through the
 * JVM), left the guest processes it had exec'd into (`script`, `agy`) alive and reparented to init,
 * indefinitely - six such orphaned sign-in sessions accumulated over repeated attempts and kept
 * contending with every new one. PRoot's `--kill-on-exit` only cleans up when PRoot itself decides to
 * exit on its own terms; it is not a reaction to being killed from outside.
 *
 * [processId], [processTreePostOrder] and [readDirectChildPids] already exist in this package for
 * `LocalRuntimeProcessLauncher`'s equivalent OpenCode/Claude cleanup; this reuses them rather than
 * re-walking `/proc` a second way.
 */
internal fun killAntigravityProcessTree(process: Process) {
    val rootPid = processId(process)
    if (rootPid != null) {
        val pids = processTreePostOrder(rootPid) { pid -> readDirectChildPids(pid) }
        pids.forEach { pid ->
            runCatching { android.os.Process.sendSignal(pid.toInt(), android.os.Process.SIGNAL_KILL) }
            runCatching { android.os.Process.killProcess(pid.toInt()) }
        }
    }
    if (process.isAlive) {
        process.destroyForcibly()
    }
}
