package com.xena.ai

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Groq API client using the official OpenAI-compatible Chat Completions endpoint. */
object KonektorGroq {
    private const val TAG = "XENA-GROQ"
    private const val API_URL = "https://api.groq.com/openai/v1/chat/completions"
    // qwen-2.5-coder-32b was shut down; use a currently listed production model.
    const val MODEL = "openai/gpt-oss-20b"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun isConfigured(apiKey: String): Boolean = apiKey.trim().startsWith("gsk_")

    fun chat(
        userMessage: String,
        systemPrompt: String,
        temperature: Float = 0.2f,
        apiKey: String,
        topP: Float = 0.9f,
        maxTokens: Int = 1024
    ): String {
        if (!isConfigured(apiKey)) {
            return "[XENA Groq] API key belum disimpan. Buka Atur → Koneksi Groq dan masukkan key baru dari Groq Console."
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userMessage))
        val payload = JSONObject()
            .put("model", MODEL)
            .put("messages", messages)
            .put("temperature", temperature.coerceIn(0.01f, 2f).toDouble())
            .put("top_p", topP.coerceIn(0.05f, 1f).toDouble())
            .put("max_completion_tokens", maxTokens.coerceIn(64, 4096))
            .put("reasoning_effort", "low")
            .put("stream", false)
        return execute(payload, apiKey)
    }

    fun chatStreaming(
        userMessage: String,
        systemPrompt: String,
        temperature: Float = 0.2f,
        apiKey: String,
        topP: Float = 0.9f,
        maxTokens: Int = 1024,
        onToken: (String) -> Unit
    ): String {
        if (!isConfigured(apiKey)) return "[XENA Groq] API key belum disimpan."
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userMessage))
        val payload = JSONObject()
            .put("model", MODEL).put("messages", messages)
            .put("temperature", temperature.coerceIn(0.01f, 2f).toDouble())
            .put("top_p", topP.coerceIn(0.05f, 1f).toDouble())
            .put("max_completion_tokens", maxTokens.coerceIn(64, 4096))
            .put("reasoning_effort", "low").put("stream", true)
        val request = Request.Builder().url(API_URL).post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("Authorization", "Bearer ${apiKey.trim()}").header("Content-Type", "application/json").build()
        return try {
            val result = StringBuilder()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return execute(payload.put("stream", false), apiKey)
                response.body?.charStream()?.buffered()?.forEachLine { line ->
                    if (!line.startsWith("data:")) return@forEachLine
                    val raw = line.removePrefix("data:").trim()
                    if (raw == "[DONE]") return@forEachLine
                    val token = try { JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("delta")?.optString("content").orEmpty() } catch (_: Exception) { "" }
                    if (token.isNotEmpty()) { result.append(token); onToken(token) }
                }
            }
            result.toString().ifBlank { "[XENA Groq] Respons streaming kosong." }
        } catch (e: Exception) { "[XENA Groq] Streaming gagal: ${e.message ?: "koneksi error"}" }
    }

    fun ping(apiKey: String): Boolean {
        if (!isConfigured(apiKey)) return false
        val payload = JSONObject()
            .put("model", MODEL)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with OK.")))
            .put("max_completion_tokens", 8)
            .put("reasoning_effort", "low")
            .put("stream", false)
        return executeRaw(payload, apiKey).first
    }

    private fun execute(payload: JSONObject, apiKey: String): String {
        val (ok, raw, status) = executeRaw(payload, apiKey)
        if (!ok) {
            val detail = extractError(raw)
            return when (status) {
                0 -> "[XENA Groq] Gagal koneksi: $detail. Periksa internet lalu coba lagi."
                401, 403 -> "[XENA Groq] API key ditolak. Periksa key di Groq Console."
                402 -> "[XENA Groq] Akun atau kuota tidak mengizinkan permintaan ini. Periksa plan dan billing Groq."
                404 -> "[XENA Groq] Model tidak tersedia. Periksa model aktif di Groq Console."
                429 -> "[XENA Groq] Rate limit tercapai. Tunggu lalu coba lagi; batas berbeda menurut plan dan model."
                500, 502, 503, 504 -> "[XENA Groq] Layanan sementara bermasalah (HTTP $status). Coba lagi nanti."
                else -> "[XENA Groq] HTTP $status: $detail"
            }
        }
        return try {
            val choices = JSONObject(raw).optJSONArray("choices")
            val content = choices?.takeIf { it.length() > 0 }
                ?.getJSONObject(0)?.optJSONObject("message")?.optString("content")
            content?.takeIf { it.isNotBlank() } ?: "[XENA Groq] Respons kosong dari model."
        } catch (_: Exception) {
            "[XENA Groq] Respons tidak dapat dibaca."
        }
    }

    private fun executeRaw(payload: JSONObject, apiKey: String): Triple<Boolean, String, Int> {
        val request = Request.Builder()
            .url(API_URL)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Content-Type", "application/json")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) Log.w(TAG, "HTTP ${response.code}: ${extractError(body)}")
                Triple(response.isSuccessful, body, response.code)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Request failed: ${e.message}")
            Triple(false, e.message.orEmpty(), 0)
        }
    }

    private fun extractError(raw: String): String = try {
        val obj = JSONObject(raw)
        obj.optJSONObject("error")?.optString("message")
            ?: obj.optString("error", raw).take(300)
    } catch (_: Exception) {
        raw.take(300)
    }
}
