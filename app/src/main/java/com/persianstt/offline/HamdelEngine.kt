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
            // ONLY download/extract model — no heavy vocab on this path (prevents OOM)
            ShenavaEngine.ensureModel(context, onProgress)
            lastError = ShenavaEngine.lastError
            // vocab later in background
            try { PersianCorrector.ensureLoaded(context.applicationContext) } catch (_: Throwable) {}
        } catch (e: Exception) {
            lastError = e.message ?: ShenavaEngine.lastError
            throw e
        } catch (oom: OutOfMemoryError) {
            lastError = "حافظه کم هنگام آماده‌سازی"
            System.gc()
            throw oom
        }
    }

    fun load(context: Context): Boolean {
        appCtx = context.applicationContext
        try { PersianCorrector.ensureLoaded(context.applicationContext) } catch (_: Throwable) {}
        return try {
            System.gc()
            val ok = ShenavaEngine.load(context)
            lastError = ShenavaEngine.lastError
            ok
        } catch (oom: OutOfMemoryError) {
            lastError = "حافظه کم — برنامه‌های دیگر را ببندید"
            System.gc()
            false
        } catch (e: Exception) {
            lastError = e.message ?: "بارگذاری ناموفق"
            false
        }
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
        if (raw.isBlank()) { lastError = ShenavaEngine.lastError.ifBlank { "خروجی خالی موتور صوتی" }; return "" }
        val fixed = PersianCorrector.fix(appCtx, raw)
        Log.i(TAG, "raw=[$raw] fixed=[$fixed]")
        return fixed
    }
}
