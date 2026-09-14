package com.persianstt.offline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Stage 1 — Audio pre-processing before ASR:
 * - target 16 kHz mono PCM
 * - simple noise gate (cut near-silence hiss)
 * - soft peak normalize for quiet speech
 * - light DC removal
 */
object AudioPreprocessor {

    fun prepare(pcm: ShortArray, sampleRate: Int = 16000): ShortArray {
        if (pcm.isEmpty()) return pcm
        var x = removeDc(pcm)
        x = noiseGate(x, sampleRate)
        x = normalizeIfQuiet(x)
        return x
    }

    private fun removeDc(pcm: ShortArray): ShortArray {
        var sum = 0L
        for (s in pcm) sum += s
        val mean = (sum / pcm.size).toInt()
        if (abs(mean) < 5) return pcm
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            out[i] = (pcm[i] - mean).coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Gate samples below threshold (reduces hiss before/after speech). */
    private fun noiseGate(pcm: ShortArray, sampleRate: Int): ShortArray {
        // threshold ~ relative to local peak
        var peak = 1
        for (s in pcm) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }
        if (peak < 500) return pcm // almost silence — leave as is
        val thr = max(350, peak / 25)
        val frame = max(1, sampleRate / 100) // 10ms
        val out = pcm.copyOf()
        var i = 0
        while (i < out.size) {
            val end = min(out.size, i + frame)
            var e = 0
            for (j in i until end) e = max(e, abs(out[j].toInt()))
            if (e < thr) {
                for (j in i until end) out[j] = 0
            }
            i = end
        }
        return out
    }

    private fun normalizeIfQuiet(pcm: ShortArray): ShortArray {
        var peak = 1
        for (s in pcm) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }
        if (peak < 600 || peak > 22000) return pcm
        val gain = 22000f / peak
        if (gain < 1.15f) return pcm
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            out[i] = (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
