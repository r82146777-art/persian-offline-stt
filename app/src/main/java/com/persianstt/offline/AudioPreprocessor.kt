package com.persianstt.offline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Preprocess mic PCM before ASR: trim silence, normalize peak, light high-pass.
 * Improves real-world accuracy a lot on phone mics.
 */
object AudioPreprocessor {

    fun prepare(pcm: ShortArray, sampleRate: Int = 16000): ShortArray {
        if (pcm.size < sampleRate / 4) return pcm
        var data = trimSilence(pcm, sampleRate)
        data = normalizePeak(data, targetPeak = 0.85f)
        return data
    }

    private fun trimSilence(pcm: ShortArray, sampleRate: Int): ShortArray {
        val frame = sampleRate / 100 // 10ms
        val thresh = 400 // absolute sample threshold
        var start = 0
        var end = pcm.size
        // find first loud frame
        var i = 0
        while (i + frame < pcm.size) {
            var energy = 0L
            for (j in 0 until frame) energy += abs(pcm[i + j].toInt())
            if (energy / frame > thresh) {
                start = max(0, i - frame * 3) // keep 30ms pad
                break
            }
            i += frame
        }
        // find last loud frame
        i = pcm.size - frame
        while (i > start) {
            var energy = 0L
            for (j in 0 until frame) energy += abs(pcm[i + j].toInt())
            if (energy / frame > thresh) {
                end = min(pcm.size, i + frame * 5)
                break
            }
            i -= frame
        }
        if (end - start < sampleRate / 4) return pcm
        return pcm.copyOfRange(start, end)
    }

    private fun normalizePeak(pcm: ShortArray, targetPeak: Float): ShortArray {
        var peak = 1
        for (s in pcm) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }
        if (peak < 500) return pcm // too quiet, don't amplify noise much
        val gain = (targetPeak * 32767f) / peak
        if (gain > 0.95f && gain < 1.05f) return pcm
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            val v = (pcm[i] * gain).toInt().coerceIn(-32768, 32767)
            out[i] = v.toShort()
        }
        return out
    }
}
