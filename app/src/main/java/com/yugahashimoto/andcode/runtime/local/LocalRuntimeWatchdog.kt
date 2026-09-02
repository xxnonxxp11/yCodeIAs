package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus

internal class LocalRuntimeWatchdog(
    // Two consecutive readings, not three: a deliberate stop disables auto-restart entirely,
    // so this only ever counts a process that died on its own.
    private val failureThreshold: Int = 2,
) {
    private var consecutiveFailures = 0

    init {
        require(failureThreshold > 0)
    }

    fun observe(status: LocalRuntimeStatus): Boolean {
        if (status is LocalRuntimeStatus.Stopped) {
            consecutiveFailures++
            if (consecutiveFailures >= failureThreshold) {
                consecutiveFailures = 0
                return true
            }
            return false
        }

        consecutiveFailures = 0
        return false
    }
}
