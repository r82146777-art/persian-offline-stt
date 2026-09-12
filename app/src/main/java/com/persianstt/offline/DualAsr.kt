package com.persianstt.offline

import android.content.Context

/** Omnilingual 300M primary offline engine. */
object DualAsr {

    fun pad(pcm: ShortArray, sampleRate: Int = 16000): ShortArray {
        val pre = sampleRate / 5
        val post = sampleRate / 3
        val out = ShortArray(pre + pcm.size + post)
        System.arraycopy(pcm, 0, out, pre, pcm.size)
        return out
    }

    fun transcribe(context: Context, pcm: ShortArray, sampleRate: Int = 16000): Pair<String, String> {
        val prepared = pad(AudioPreprocessor.prepare(pcm, sampleRate), sampleRate)
        var text = ""
        try {
            if (OmnilingualEngine.isReady(context)) {
                OmnilingualEngine.load(context)
                text = OmnilingualEngine.transcribe(prepared, sampleRate)
            }
        } catch (_: Exception) {}
        return text to if (text.isNotBlank()) "Omni" else "none"
    }
}
