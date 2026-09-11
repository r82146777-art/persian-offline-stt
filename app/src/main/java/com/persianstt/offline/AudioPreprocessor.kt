package com.persianstt.offline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Light preprocess only — do NOT aggressively trim (that kills short words).
 */
object AudioPreprocessor {

    fun prepare(pcm: ShortArray, sampleRate: Int = 16000): ShortArray {
        if (pcm.size < sampleRate / 5) return pcm
        // only normalize if clearly quiet; never destroy short utterances
        return normalizeIfQuiet(pcm)
    }

    private fun normalizeIfQuiet(pcm: ShortArray): ShortArray {
        var peak = 1
        for (s in pcm) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }
        // only boost if peak is low but not silence
        if (peak < 800 || peak > 20000) return pcm
        val gain = 20000f / peak
        if (gain < 1.1f) return pcm
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            out[i] = (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
