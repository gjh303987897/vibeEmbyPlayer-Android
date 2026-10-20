package com.vibeplayer.app.player

import android.os.Process
import android.os.SystemClock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

data class PlaybackPerformance(val cpuPercent: Float? = null, val gpuPercent: Float? = null)

/** Samples only while a visible player subscribes; never starts a shell or requests root. */
@Singleton
class PlaybackPerformanceMonitor @Inject constructor() {
    fun samples(cpuEnabled: Boolean, gpuEnabled: Boolean) = flow {
        if (!cpuEnabled && !gpuEnabled) return@flow
        val gpu = if (gpuEnabled) File("/sys/class/kgsl/kgsl-3d0/gpubusy") else null
        val canReadGpu = gpu?.canRead() == true
        var cpu = if (cpuEnabled) Process.getElapsedCpuTime() else 0L
        var elapsed = if (cpuEnabled) SystemClock.elapsedRealtime() else 0L
        val cores = if (cpuEnabled) Runtime.getRuntime().availableProcessors().coerceAtLeast(1) else 1
        while (currentCoroutineContext().isActive) {
            delay(2_000)
            val nextCpu = if (cpuEnabled) Process.getElapsedCpuTime() else 0L
            val nextElapsed = if (cpuEnabled) SystemClock.elapsedRealtime() else 0L
            val gpuPercent = if (canReadGpu) runCatching {
                gpu!!.bufferedReader().use { parseGpuBusy(it.readLine().orEmpty()) }
            }.getOrNull() else null
            val cpuPercent = if (cpuEnabled) cpuUsage(cpu, nextCpu, elapsed, nextElapsed, cores) else null
            emit(PlaybackPerformance(cpuPercent, gpuPercent))
            cpu = nextCpu
            elapsed = nextElapsed
        }
    }.flowOn(Dispatchers.IO)
}

internal fun cpuUsage(cpu: Long, nextCpu: Long, elapsed: Long, nextElapsed: Long, cores: Int): Float? {
    if (nextCpu < cpu || nextElapsed <= elapsed || cores <= 0) return null
    return ((nextCpu - cpu).toDouble() / (nextElapsed - elapsed) / cores * 100).toFloat().coerceIn(0f, 100f)
}

/** KGSL reports busy and total microseconds for its sample window, not cumulative counters. */
internal fun parseGpuBusy(text: String): Float? {
    val fields = text.trim().split(Regex("\\s+"))
    if (fields.size != 2) return null
    val busy = fields[0].toLongOrNull() ?: return null
    val total = fields[1].toLongOrNull() ?: return null
    if (total <= 0 || busy !in 0..total) return null
    return (busy.toDouble() / total * 100).toFloat()
}
