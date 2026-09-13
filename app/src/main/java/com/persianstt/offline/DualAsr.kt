package com.persianstt.offline

import android.content.Context

/** موتور همدل — پد بلند انتها تا حرف/رقم آخر نپرد */
object DualAsr {

    fun pad(pcm: ShortArray, sampleRate: Int = 16000): ShortArray {
        val pre = sampleRate / 4      // 0.25s
        val post = sampleRate * 3 / 4 // 0.75s silence at end — critical for last phonemes
        val out = ShortArray(pre + pcm.size + post)
        System.arraycopy(pcm, 0, out, pre, pcm.size)
        return out
    }

    fun transcribe(context: Context, pcm: ShortArray, sampleRate: Int = 16000): Pair<String, String> {
        if (pcm.size < sampleRate / 5) {
            return "" to "کوتاه"
        }
        val prepared = pad(AudioPreprocessor.prepare(pcm, sampleRate), sampleRate)
        var text = ""
        try {
            if (HamdelEngine.isReady(context)) {
                HamdelEngine.load(context)
                text = HamdelEngine.transcribe(prepared, sampleRate)
            }
        } catch (_: Exception) {}
        return text to if (text.isNotBlank()) "همدل" else "none"
    }
}
