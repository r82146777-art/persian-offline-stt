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
 * Uses Google Gemini (key format AQ.…).
 * Class name kept for compatibility with existing call sites.
 */
object DeepSeekClient {
    private const val TAG = "GeminiAI"
    private const val MODEL = "gemini-3.6-flash"

    @Volatile var lastError: String = ""

    fun correctText(input: String): String {
        val prompt =
            "تو ویرایشگر فارسی هستی. فقط متن اصلاح‌شده را برگردان؛ بدون توضیح و بدون نقل‌قول.\n" +
            "غلط املایی، فاصله و نیم‌فاصله را درست کن. معنی را عوض نکن.\n\nمتن:\n$input"
        return generate(prompt)
    }

    fun addEmojis(input: String): String {
        val prompt =
            "به متن فارسی زیر ایموجی مناسب در جای‌جای متن (اول/وسط/آخر) اضافه کن.\n" +
            "فقط متن نهایی را برگردان؛ بدون توضیح.\n\nمتن:\n$input"
        return generate(prompt)
    }

    private fun generate(prompt: String): String {
        var conn: HttpURLConnection? = null
        return try {
            val key = ApiKeyVault.reveal()
            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=" +
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
                    JSONObject().put("temperature", 0.2).put("maxOutputTokens", 1024)
                )
                .toString()

            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { w ->
                w.write(body); w.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                lastError = when {
                    code == 400 || code == 403 -> "کلید یا دسترسی API نامعتبر"
                    code == 429 -> "محدودیت تعداد درخواست"
                    else -> "خطای Gemini ($code)"
                }
                Log.e(TAG, "HTTP $code $resp")
                return ""
            }
            lastError = ""
            val root = JSONObject(resp)
            val candidates = root.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val parts = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val text = parts.getJSONObject(i).optString("text", "")
                if (text.isNotBlank()) sb.append(text)
            }
            sb.toString().trim()
                .removePrefix("```").removeSuffix("```").trim()
        } catch (e: Exception) {
            lastError = e.message ?: "خطای اتصال"
            Log.e(TAG, "generate fail", e)
            ""
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
