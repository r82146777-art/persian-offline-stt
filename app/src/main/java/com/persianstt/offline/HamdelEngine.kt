package com.persianstt.offline

import android.content.Context
import android.util.Log

/**
 * موتور همدل — لایهٔ اختصاصی ما.
 * هستهٔ صوتی: Shenava Koochik (فارسی)
 * لایهٔ متن: PersianCorrector (قواعد خودمان، قابل تقویت تدریجی)
 */
object HamdelEngine {

    private const val TAG = "HamdelEngine"
    @Volatile var lastError: String = ""
    @Volatile private var appCtx: Context? = null

    fun isReady(context: Context): Boolean = ShenavaEngine.isReady(context)

    fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}) {
        try {
            ShenavaEngine.ensureModel(context, onProgress)
            try { PersianCorrector.ensureLoaded(context) } catch (_: Exception) {}
            lastError = ShenavaEngine.lastError
        } catch (e: Exception) {
            lastError = e.message ?: ShenavaEngine.lastError
            throw e
        }
    }

    fun load(context: Context): Boolean {
        appCtx = context.applicationContext
        try { PersianCorrector.ensureLoaded(context) } catch (_: Exception) {}
        val ok = try {
            ShenavaEngine.load(context)
        } catch (e: Exception) {
            lastError = e.message ?: "بارگذاری ناموفق"
            false
        }
        lastError = ShenavaEngine.lastError
        return ok
    }

    fun release() {
        try { ShenavaEngine.release() } catch (_: Exception) {}
    }

    fun transcribe(pcm: ShortArray, sampleRate: Int = 16000): String {
        val raw = try {
            ShenavaEngine.transcribe(pcm, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "acoustic fail", e)
            lastError = e.message ?: "خطای تشخیص"
            ""
        }
        if (raw.isBlank()) return ""
        val fixed = PersianCorrector.fix(appCtx, raw)
        Log.i(TAG, "raw=[$raw] fixed=[$fixed]")
        return fixed
    }
}
