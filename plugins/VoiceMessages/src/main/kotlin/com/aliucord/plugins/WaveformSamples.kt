package com.aliucord.plugins

/** Stores chronological peaks with bounded memory, retaining the beginning of long recordings. */
internal class WaveformSamples {
    private val waves = ArrayList<Int>()
    private var stride = 1
    private var pendingCount = 0
    private var pendingPeak = 0

    fun reset() {
        waves.clear()
        stride = 1
        pendingCount = 0
        pendingPeak = 0
    }

    fun add(wave: Int) {
        pendingPeak = maxOf(pendingPeak, wave.coerceIn(1, 255))
        pendingCount++
        if (pendingCount < stride) return
        waves.add(pendingPeak)
        pendingCount = 0
        pendingPeak = 0
        if (waves.size >= 2048) {
            val reduced = waves.chunked(2).map { it.maxOrNull() ?: 1 }
            waves.clear()
            waves.addAll(reduced)
            stride *= 2
        }
    }

    fun bytes(): ByteArray {
        val samples = waves.toMutableList()
        if (pendingCount > 0) samples.add(pendingPeak)
        if (samples.isEmpty()) samples.add(1)
        val count = minOf(256, samples.size)
        return ByteArray(count) { index ->
            val start = index * samples.size / count
            val end = (index + 1) * samples.size / count
            (samples.subList(start, end).maxOrNull() ?: 1).toByte()
        }
    }
}
