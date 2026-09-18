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

object DeepSeekClient {
    private const val TAG = "DeepSeek"
    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-chat"

    @Volatile var lastError: String = ""

    /** Empty string means failure — never silently return original input. */
    fun correctText(input: String): String {
        val prompt =
            "فقط متن اصلاح‌شده فارسی را برگردان بدون توضیح.\n" +
            "غلط املایی و فاصله و نیم‌فاصله را درست کن.\n\nمتن:\n$input"
        return chat(prompt, 0.2)
    }

    fun addEmojis(input: String): String {
        val prompt =
            "به متن فارسی ایموجی مناسب در جای‌جای متن اضافه کن. " +
            "فقط متن نهایی را برگردان.\n\nمتن:\n$input"
        return chat(prompt, 0.4)
    }

    private fun chat(userContent: String, temperature: Double): String {
        var conn: HttpURLConnection? = null
        return try {
            val key = ApiKeyVault.reveal()
            val body = JSONObject()
                .put("model", MODEL)
                .put("temperature", temperature)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", "فقط خروجی نهایی."))
                        .put(JSONObject().put("role", "user").put("content", userContent))
                )
                .toString()

            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 40_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $key")
            }
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { w ->
                w.write(body)
                w.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                lastError = when {
                    resp.contains("Insufficient Balance", true) -> "اعتبار API تمام شده"
                    code == 401 -> "کلید API نامعتبر"
                    else -> "خطای شبکه API ($code)"
                }
                Log.e(TAG, "HTTP $code $resp")
                return ""
            }
            lastError = ""
            val content = JSONObject(resp)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content", "")
                .trim()
                .removePrefix("```").removeSuffix("```").trim()
            content
        } catch (e: Exception) {
            lastError = e.message ?: "خطای اتصال"
            Log.e(TAG, "chat fail", e)
            ""
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
