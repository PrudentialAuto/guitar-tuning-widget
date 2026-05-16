package com.prudentialauto.guitartuner

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * YIN pitch detection algorithm.
 * de Cheveigné & Kawahara (2002), "YIN, a fundamental frequency estimator for speech and music."
 */
class PitchDetector(private val sampleRate: Int) {

    private val threshold = 0.15f
    private val minFreq = 70f   // slightly below open E2 (82 Hz)
    private val maxFreq = 420f  // slightly above high E4 (330 Hz)

    private val tauMin = (sampleRate / maxFreq).toInt().coerceAtLeast(2)
    private val tauMax = (sampleRate / minFreq).toInt()

    // Pre-allocated to avoid GC pressure inside the recording loop
    private val yinBuffer = FloatArray(tauMax)

    fun detect(buffer: ShortArray, length: Int = buffer.size): Float {
        if (length < tauMax * 2) return -1f

        // Silence gate — skip processing if signal is too quiet
        var sumSq = 0.0
        for (i in 0 until length) sumSq += buffer[i].toDouble() * buffer[i]
        val rms = sqrt(sumSq / length)
        if (rms < 300.0) return -1f   // ~0.01 of full-scale on 16-bit PCM

        // Step 1 + 2: Cumulative mean normalized difference function
        // d[tau] = Σ (x[j] - x[j+tau])²  for j in [0, W)  where W = tauMax
        yinBuffer[0] = 1f
        var runningSum = 0f
        val windowSize = length.coerceAtMost(tauMax * 2)

        for (tau in 1 until tauMax) {
            var sum = 0f
            val limit = windowSize - tau
            for (j in 0 until limit) {
                val diff = buffer[j] - buffer[j + tau]
                sum += diff.toFloat() * diff.toFloat()
            }
            runningSum += sum
            yinBuffer[tau] = if (runningSum == 0f) 0f else sum * tau / runningSum
        }

        // Step 3: Absolute threshold — first local minimum below threshold
        var tau = tauMin
        while (tau < tauMax - 1) {
            if (yinBuffer[tau] < threshold && yinBuffer[tau] <= yinBuffer[tau + 1]) break
            tau++
        }
        if (tau >= tauMax - 1) return -1f

        // Step 4: Parabolic interpolation
        val betterTau: Float = if (tau in 1 until tauMax - 1) {
            val s0 = yinBuffer[tau - 1]
            val s1 = yinBuffer[tau]
            val s2 = yinBuffer[tau + 1]
            val denom = s0 - 2f * s1 + s2
            if (abs(denom) < 1e-10f) tau.toFloat()
            else tau + (s0 - s2) / (2f * denom)
        } else {
            tau.toFloat()
        }

        return if (betterTau > 0f) sampleRate / betterTau else -1f
    }
}
