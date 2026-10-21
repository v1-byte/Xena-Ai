package com.xena.ai

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Klien Hugging Face Inference Providers via OpenAI-compatible Chat Completions API. */
object KonektorHuggingFace {
    private const val TAG = "XENA-HF"
    private const val API_URL = "https://router.huggingface.co/v1/chat/completions"
    private const val MODEL = "Qwen/Qwen2.5-Coder-7B-Instruct"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun isConfigured(token: String): Boolean = token.trim().startsWith("hf_")

    fun chat(userMessage: String, systemPrompt: String, temperature: Float = 0.2f, token: String): String {
        if (!isConfigured(token)) {
            return "[XENA HF] Token Hugging Face belum disimpan. Buka Atur → Koneksi Hugging Face Inference dan masukkan token baru."
        }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userMessage))
        val payload = JSONObject()
            .put("model", MODEL)
            .put("messages", messages)
            .put("temperature", temperature.coerceIn(0f, 2f).toDouble())
            .put("max_tokens", 512)
            .put("stream", false)

        val request = Request.Builder()
            .url(API_URL)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Content-Type", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP ${response.code}: ${raw.take(500)}")
                    return@use when (response.code) {
                        401, 403 -> "[XENA HF] Token ditolak atau tidak memiliki izin Inference Providers. Buat token baru dengan izin Inference."
                        402 -> "[XENA HF] Saldo/kuota inference tidak tersedia. Periksa pengaturan Inference Providers Hugging Face."
                        404 -> "[XENA HF] Model atau provider tidak tersedia pada endpoint. Periksa dukungan model di Hugging Face."
                        429 -> "[XENA HF] Batas permintaan tercapai. Tunggu sebentar lalu coba lagi."
                        503 -> "[XENA HF] Provider/model sedang tidak tersedia. Coba lagi nanti."
                        else -> "[XENA HF] HTTP ${response.code}: ${extractError(raw)}"
                    }
                }
                parseContent(raw)
            }
        } catch (e: Exception) {
            Log.e(TAG, "chat failed", e)
            "[XENA HF] Gagal koneksi: ${e.message}. Periksa internet dan coba lagi."
        }
    }

    fun ping(token: String): Boolean {
        if (!isConfigured(token)) return false
        val payload = JSONObject()
            .put("model", MODEL)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
            .put("max_tokens", 1)
            .put("stream", false)
        val request = Request.Builder()
            .url(API_URL)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Content-Type", "application/json")
            .build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "ping failed: ${e.message}")
            false
        }
    }

    private fun parseContent(raw: String): String = try {
        val choices = JSONObject(raw).optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            choices.getJSONObject(0).optJSONObject("message")?.optString("content")
                ?.takeIf { it.isNotBlank() } ?: "[XENA HF] Respons kosong dari provider."
        } else extractError(raw)
    } catch (_: Exception) {
        raw.ifBlank { "[XENA HF] Respons kosong." }
    }

    private fun extractError(raw: String): String = try {
        val obj = JSONObject(raw)
        obj.optString("error", raw).take(400)
    } catch (_: Exception) {
        raw.take(400)
    }
}
