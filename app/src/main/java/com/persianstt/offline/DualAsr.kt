package com.persianstt.offline

import android.content.Context

/**
 * Runs Shenava + Whisper (fa) and picks the more complete / natural result.
 * Pads short audio with silence so models hear full words.
 */
object DualAsr {

    fun pad(pcm: ShortArray, sampleRate: Int = 16000): ShortArray {
        val pre = sampleRate / 4   // 250ms
        val post = sampleRate / 2  // 500ms
        val out = ShortArray(pre + pcm.size + post)
        System.arraycopy(pcm, 0, out, pre, pcm.size)
        return out
    }

    fun transcribe(context: Context, pcm: ShortArray, sampleRate: Int = 16000): Pair<String, String> {
        val prepared = pad(AudioPreprocessor.prepare(pcm, sampleRate), sampleRate)
        var shenava = ""
        var whisper = ""
        try {
            if (ShenavaEngine.isReady(context)) {
                ShenavaEngine.load(context)
                shenava = ShenavaEngine.transcribe(prepared, sampleRate)
            }
        } catch (_: Exception) {}
        try {
            if (WhisperEngine.isReady(context)) {
                WhisperEngine.load(context, "fa")
                whisper = WhisperEngine.transcribe(prepared, sampleRate)
            }
        } catch (_: Exception) {}

        val best = pick(shenava, whisper)
        val engine = when {
            best.isBlank() -> "none"
            best == whisper && whisper.isNotBlank() && whisper != shenava -> "Whisper"
            best == shenava && shenava.isNotBlank() -> "Shenava"
            else -> "mix"
        }
        return best to engine
    }

    private fun pick(a: String, b: String): String {
        val aa = a.trim()
        val bb = b.trim()
        if (aa.isBlank()) return bb
        if (bb.isBlank()) return aa
        val sa = score(aa)
        val sb = score(bb)
        return if (sb > sa) bb else aa
    }

    /** Prefer multi-word natural Persian over truncated/glued text. */
    private fun score(t: String): Int {
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        var s = t.length
        s += words.size * 4
        // bonus for having spaces (word boundaries)
        if (words.size >= 2) s += 10
        // penalty for very short output when text looks truncated (ends mid-letter rare)
        if (words.size == 1 && t.length <= 3) s -= 5
        // slight penalty if no spaces and long (likely glued CTC)
        if (words.size == 1 && t.length > 8) s -= 8
        return s
    }
}
