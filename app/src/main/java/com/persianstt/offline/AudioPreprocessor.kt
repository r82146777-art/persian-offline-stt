package com.persianstt.offline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * STT audio pipeline (critical for Vosk/Whisper):
 * 1) ensure 16 kHz mono (caller resamples if needed)
 * 2) remove DC
 * 3) soft noise gate + silence attenuate
 * 4) normalize volume (reduce hallucination on quiet/noisy clips)
 * 5) light edge pad is done in DualAsr
 */
object AudioPreprocessor {

    fun prepare(pcm: ShortArray, sampleRate: Int = 16000): ShortArray {
        if (pcm.isEmpty()) return pcm
        var x = pcm
        if (sampleRate != 16000 && sampleRate > 0) {
            x = AudioCapture.to16k(x, sampleRate)
        }
        x = removeDc(x)
        x = softGate(x, 16000)
        x = normalize(x)
        x = trimSilence(x, 16000)
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
        if (peak < 300) return pcm
        val thr = max(200, peak / 45)
        val frame = max(1, sampleRate / 50)
        val out = pcm.copyOf()
        var i = 0
        while (i < out.size) {
            val end = min(out.size, i + frame)
            var e = 0
            for (j in i until end) e = max(e, abs(out[j].toInt()))
            if (e < thr) for (j in i until end) out[j] = (out[j] / 6).toShort()
            i = end
        }
        return out
    }

    /** Peak normalize toward ~0.7 full scale — stable for STT. */
    private fun normalize(pcm: ShortArray): ShortArray {
        var peak = 1
        for (s in pcm) peak = max(peak, abs(s.toInt()))
        if (peak < 400 || peak > 28000) return pcm
        val target = 22000.0
        val gain = (target / peak).coerceIn(0.5, 6.0)
        if (gain in 0.85..1.15) return pcm
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            out[i] = (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Trim long leading/trailing near-silence (source of Whisper hallucinations). */
    private fun trimSilence(pcm: ShortArray, sampleRate: Int): ShortArray {
        if (pcm.size < sampleRate / 2) return pcm
        var peak = 1
        for (s in pcm) peak = max(peak, abs(s.toInt()))
        val thr = max(350, peak / 25)
        val frame = sampleRate / 50
        fun energy(from: Int): Int {
            val end = min(pcm.size, from + frame)
            var e = 0
            for (i in from until end) e = max(e, abs(pcm[i].toInt()))
            return e
        }
        var start = 0
        while (start + frame < pcm.size && energy(start) < thr) start += frame
        var end = pcm.size
        while (end - frame > start && energy(end - frame) < thr) end -= frame
        // keep small pad
        start = (start - frame).coerceAtLeast(0)
        end = (end + frame).coerceAtMost(pcm.size)
        if (end - start < sampleRate / 4) return pcm
        return pcm.copyOfRange(start, end)
    }
}
