package com.persianstt.offline

import android.content.Context

/** Qwen3-ASR primary with silence padding. */
object DualAsr {

    fun pad(pcm: ShortArray, sampleRate: Int = 16000): ShortArray {
        val pre = sampleRate / 4
        val post = sampleRate / 2
        val out = ShortArray(pre + pcm.size + post)
        System.arraycopy(pcm, 0, out, pre, pcm.size)
        return out
    }

    fun transcribe(context: Context, pcm: ShortArray, sampleRate: Int = 16000): Pair<String, String> {
        val prepared = pad(AudioPreprocessor.prepare(pcm, sampleRate), sampleRate)
        var text = ""
        try {
            if (Qwen3Engine.isReady(context)) {
                Qwen3Engine.load(context)
                text = Qwen3Engine.transcribe(prepared, sampleRate)
            }
        } catch (_: Exception) {}
        return text to if (text.isNotBlank()) "Qwen3" else "none"
    }
}
