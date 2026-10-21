package com.xena.ai

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Klien ringan ke HuggingFace Space (llama.cpp server OpenAI-compatible).
 * HP hanya kirim prompt JSON — tidak load GGUF lokal.
 */
object KonektorHuggingFace {
    private const val TAG = "XENA-HF"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Base URL Space, tanpa trailing slash. Contoh: https://user-xena-qwen-server.hf.space */
    @Volatile
    var baseUrl: String = ""

    fun isConfigured(): Boolean = baseUrl.trim().isNotEmpty()

    fun chatCompletionsUrl(): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/v1/chat/completions")) base
        else "$base/v1/chat/completions"
    }

    /**
     * Sinkron: kirim chat, return isi jawaban atau pesan error.
     */
    fun chat(
        userMessage: String,
        systemPrompt: String,
        temperature: Float = 0.2f
    ): String {
        if (!isConfigured()) {
            return "[XENA Server] URL HuggingFace belum di-set. " +
                "Buka Atur → set URL Space (contoh https://USERNAME-xena-qwen-server.hf.space)"
        }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userMessage))

        val payload = JSONObject()
            .put("model", "qwen2.5-coder-7b")
            .put("messages", messages)
            .put("temperature", temperature.toDouble())
            .put("stream", false)
            .put("max_tokens", 1024)

        val body = payload.toString().toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(chatCompletionsUrl())
            .post(body)
            .header("Content-Type", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP ${response.code}: $raw")
                    if (response.code == 502 || response.code == 503) {
                        return@use "[XENA Server] Space sedang bangun/sleep. Tunggu 30–60 detik lalu coba lagi."
                    }
                    return@use "[XENA Server] HTTP ${response.code}: ${raw.take(300)}"
                }
                parseContent(raw)
            }
        } catch (e: Exception) {
            Log.e(TAG, "chat failed", e)
            "[XENA Server] Gagal koneksi: ${e.message}. Cek internet / Space RUNNING / cron keep-awake."
        }
    }

    private fun parseContent(raw: String): String {
        return try {
            val obj = JSONObject(raw)
            if (obj.has("choices")) {
                val choices = obj.getJSONArray("choices")
                if (choices.length() > 0) {
                    val c0 = choices.getJSONObject(0)
                    if (c0.has("message")) {
                        return c0.getJSONObject("message").optString("content", raw)
                    }
                    if (c0.has("text")) return c0.optString("text", raw)
                }
            }
            if (obj.has("content")) return obj.optString("content", raw)
            raw.ifBlank { "[XENA Server] respons kosong" }
        } catch (_: Exception) {
            raw.ifBlank { "[XENA Server] parse error" }
        }
    }

    /** Ping root Space (untuk keep-awake / health). */
    fun ping(): Boolean {
        if (!isConfigured()) return false
        return try {
            val req = Request.Builder().url(baseUrl.trim().trimEnd('/') + "/").get().build()
            client.newCall(req).execute().use { it.isSuccessful || it.code in 400..499 }
        } catch (_: Exception) {
            false
        }
    }
}
