package com.yugahashimoto.andcode.core.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.system.measureTimeMillis

enum class ConnectionStatus {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
    DISCONNECTED,
    ;

    companion object {
        fun fromLatency(latencyMs: Long): ConnectionStatus =
            when {
                latencyMs < 100L -> EXCELLENT
                latencyMs < 300L -> GOOD
                latencyMs < 1000L -> FAIR
                else -> POOR
            }
    }
}

data class ConnectionQuality(
    val latencyMs: Long = 0L,
    val tokensPerSecond: Double = 0.0,
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
)

class ConnectionQualityMonitor(
    private val scope: CoroutineScope,
    private val smoothingFactor: Double = DEFAULT_SMOOTHING_FACTOR,
) {
    private val _quality = MutableStateFlow(ConnectionQuality())
    val quality: StateFlow<ConnectionQuality> = _quality.asStateFlow()

    private val lock = Any()
    private var smoothedLatencyMs: Double = 0.0
    private var hasLatencySample = false
    private var smoothedTokensPerSecond: Double = 0.0
    private var hasTokenSample = false
    private var streamTokenCount = 0
    private var streamWindowStartNanos = 0L

    fun startMonitoring(healthCheck: suspend () -> Unit) {
        scope.launch {
            while (isActive) {
                probe(healthCheck)
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    fun recordStreamToken() {
        val nowNanos = System.nanoTime()
        synchronized(lock) {
            if (streamTokenCount == 0) {
                streamWindowStartNanos = nowNanos
            }
            streamTokenCount++
            val elapsedSeconds = (nowNanos - streamWindowStartNanos) / NANOS_PER_SECOND
            if (elapsedSeconds < TOKEN_RATE_WINDOW_SECONDS) return
            val instantaneousRate = streamTokenCount / elapsedSeconds
            smoothedTokensPerSecond =
                if (!hasTokenSample) {
                    hasTokenSample = true
                    instantaneousRate
                } else {
                    smoothingFactor * instantaneousRate + (1.0 - smoothingFactor) * smoothedTokensPerSecond
                }
            streamTokenCount = 0
            streamWindowStartNanos = nowNanos
            _quality.update { it.copy(tokensPerSecond = smoothedTokensPerSecond) }
        }
    }

    private suspend fun probe(healthCheck: suspend () -> Unit) {
        var succeeded = false
        val elapsedMs =
            measureTimeMillis {
                try {
                    healthCheck()
                    succeeded = true
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    succeeded = false
                }
            }
        if (succeeded) {
            recordLatency(elapsedMs)
        } else {
            _quality.update { it.copy(status = ConnectionStatus.DISCONNECTED) }
        }
    }

    private fun recordLatency(latencyMs: Long) {
        synchronized(lock) {
            smoothedLatencyMs =
                if (!hasLatencySample) {
                    hasLatencySample = true
                    latencyMs.toDouble()
                } else {
                    smoothingFactor * latencyMs + (1.0 - smoothingFactor) * smoothedLatencyMs
                }
            val roundedLatency = smoothedLatencyMs.toLong()
            _quality.update {
                it.copy(
                    latencyMs = roundedLatency,
                    status = ConnectionStatus.fromLatency(roundedLatency),
                )
            }
        }
    }

    companion object {
        private const val MONITOR_INTERVAL_MS = 30_000L
        private const val DEFAULT_SMOOTHING_FACTOR = 0.3
        private const val TOKEN_RATE_WINDOW_SECONDS = 1.0
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
