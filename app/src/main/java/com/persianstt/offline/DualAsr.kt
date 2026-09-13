package com.persianstt.offline

import android.content.Context

/** موتور همدل — مرحله ۱: کلمات ساده */
object DualAsr {

    fun pad(pcm: ShortArray, sampleRate: Int = 16000): ShortArray {
        // padding بیشتر تا اول/آخر کلمه نخورد
        val pre = sampleRate / 2   // 0.5s
        val post = sampleRate / 2  // 0.5s
        val out = ShortArray(pre + pcm.size + post)
        System.arraycopy(pcm, 0, out, pre, pcm.size)
        return out
    }

    fun transcribe(context: Context, pcm: ShortArray, sampleRate: Int = 16000): Pair<String, String> {
        if (pcm.size < sampleRate / 4) {
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
        return text to if (text.isNotBlank()) "همدل-۱" else "none"
    }
}
