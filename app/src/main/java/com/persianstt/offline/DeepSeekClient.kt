package com.persianstt.offline

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Google Gemini with retry on 503. Falls back empty → caller uses offline AI.
 */
object DeepSeekClient {
    private const val TAG = "GeminiAI"
    private val MODELS = listOf(
        "gemini-3.6-flash",
        "gemini-2.5-flash-lite",
        "gemini-flash-latest",
        "gemini-2.0-flash-lite"
    )

    @Volatile var lastError: String = ""

    fun correctText(input: String): String {
        val prompt =
            "فقط متن اصلاح‌شده فارسی را برگردان. بدون توضیح، بدون پیشوند.\n" +
            "اگر متن از قبل درست است عیناً همان را برگردان.\n" +
            "غلط املایی و فاصله را درست کن؛ معنی را عوض نکن.\n\n$input"
        return generate(prompt)
    }

    fun addEmojis(input: String): String {
        val prompt =
            "به این متن فارسی فقط ایموجی مناسب اضافه کن؛ خود جملات را عوض نکن.\n" +
            "فقط متن نهایی را برگردان.\n\n$input"
        return generate(prompt)
    }

    private fun generate(prompt: String): String {
        lastError = ""
        val key = try { ApiKeyVault.reveal() } catch (_: Exception) { "" }
        if (key.isBlank()) {
            lastError = "کلید نیست"
            return ""
        }
        for (model in MODELS) {
            for (attempt in 1..3) {
                val r = callOnce(model, key, prompt)
                if (r != null) {
                    if (r.isNotBlank()) {
                        lastError = ""
                        return r
                    }
                    // blank success? try next
                    break
                }
                // null = retryable (503)
                try { Thread.sleep((400L * attempt)) } catch (_: Exception) {}
            }
        }
        if (lastError.isBlank()) lastError = "Gemini در دسترس نیست"
        return ""
    }

    /** null = retry, "" = hard fail for this model, non-empty = success */
    private fun callOnce(model: String, key: String, prompt: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=" +
                    URLEncoder.encode(key, "UTF-8")
            val body = JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt))
                        )
                    )
                )
                .put(
                    "generationConfig",
                    JSONObject().put("temperature", 0.1).put("maxOutputTokens", 512)
                )
                .toString()

            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 35_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { w ->
                w.write(body); w.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            when (code) {
                in 200..299 -> {
                    val parts = JSONObject(resp)
                        .optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                    if (parts == null || parts.length() == 0) return ""
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) {
                        sb.append(parts.getJSONObject(i).optString("text", ""))
                    }
                    sb.toString().trim()
                        .removePrefix("```").removeSuffix("```").trim()
                        .replace(Regex("^(متن اصلاح[‌ ]*شده[:：]?\\s*)"), "")
                }
                503, 429, 500 -> {
                    lastError = "سرور شلوغ ($code) — تلاش مجدد"
                    null // retry
                }
                else -> {
                    lastError = "خطای Gemini ($code)"
                    Log.e(TAG, "HTTP $code $resp")
                    "" // next model
                }
            }
        } catch (e: Exception) {
            lastError = e.message ?: "خطای شبکه"
            Log.e(TAG, "call fail $model", e)
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
