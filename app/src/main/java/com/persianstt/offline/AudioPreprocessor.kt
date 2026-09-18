package com.persianstt.offline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Stronger but safe audio prep for Vosk 16kHz:
 * DC remove, soft noise gate, gentle boost, no aggressive trim.
 */
object AudioPreprocessor {

    fun prepare(pcm: ShortArray, sampleRate: Int = 16000): ShortArray {
        if (pcm.isEmpty()) return pcm
        var x = removeDc(pcm)
        x = softGate(x, sampleRate)
        x = boostSpeech(x)
        return x
    }

    private fun removeDc(pcm: ShortArray): ShortArray {
        var sum = 0L
        for (s in pcm) sum += s
        val mean = (sum / pcm.size).toInt()
        if (abs(mean) < 3) return pcm
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) out[i] = (pcm[i] - mean).coerceIn(-32768, 32767).toShort()
        return out
    }

    private fun softGate(pcm: ShortArray, sampleRate: Int): ShortArray {
        var peak = 1
        for (s in pcm) peak = max(peak, abs(s.toInt()))
        if (peak < 400) return pcm
        val thr = max(250, peak / 40)
        val frame = max(1, sampleRate / 50) // 20ms
        val out = pcm.copyOf()
        var i = 0
        while (i < out.size) {
            val end = min(out.size, i + frame)
            var e = 0
            for (j in i until end) e = max(e, abs(out[j].toInt()))
            if (e < thr) for (j in i until end) out[j] = (out[j] / 4).toShort()
            i = end
        }
        return out
    }

    private fun boostSpeech(pcm: ShortArray): ShortArray {
        var sumSq = 0.0
        var peak = 1
        for (s in pcm) {
            val v = s.toInt()
            sumSq += v.toDouble() * v
            peak = max(peak, abs(v))
        }
        val rms = sqrt(sumSq / pcm.size)
        if (peak < 500 || peak > 24000) return pcm
        // target rms ~ 2500
        val target = 2800.0
        val gain = (target / max(rms, 1.0)).coerceIn(1.0, 4.0)
        if (gain < 1.15) return pcm
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            out[i] = (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
