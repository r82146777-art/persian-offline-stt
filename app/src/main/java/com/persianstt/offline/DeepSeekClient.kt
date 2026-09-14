package com.persianstt.offline

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Online helper for smart text fix + emoji (DeepSeek API).
 * STT itself stays offline (Vosk).
 */
object DeepSeekClient {
    private const val TAG = "DeepSeek"
    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-chat"

    fun correctText(input: String): String {
        val prompt =
            "تو یک ویرایشگر فارسی هستی. فقط متن اصلاح‌شده را برگردان؛ بدون توضیح.\n" +
            "غلط املایی، فاصله، نیم‌فاصله و نگارش را درست کن. معنی را عوض نکن.\n\n" +
            "متن:\n$input"
        return chat(prompt, temperature = 0.2).ifBlank { input }
    }

    fun addEmojis(input: String): String {
        val prompt =
            "متن فارسی زیر را بگیر و ایموجی مناسب را به‌صورت هوشمند " +
            "در جای‌جای متن (اول/وسط/آخر) اضافه کن. فقط متن نهایی را برگردان؛ بدون توضیح.\n\n" +
            "متن:\n$input"
        return chat(prompt, temperature = 0.5).ifBlank { input }
    }

    private fun chat(userContent: String, temperature: Double): String {
        var conn: HttpURLConnection? = null
        return try {
            val key = ApiKeyVault.reveal()
            val body = JSONObject().apply {
                put("model", MODEL)
                put("temperature", temperature)
                put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "فقط خروجی نهایی را بنویس."))
                    .put(JSONObject().put("role", "user").put("content", userContent))
                )
            }.toString()

            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 45_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $key")
            }
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                Log.e(TAG, "HTTP $code $resp")
                return ""
            }
            val root = JSONObject(resp)
            val content = root
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content", "")
                .trim()
            // strip accidental markdown fences
            content
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        } catch (e: Exception) {
            Log.e(TAG, "chat fail", e)
            ""
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
