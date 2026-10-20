package com.xena.ai

import android.util.Log

object LlamaBridge {
    private const val TAG = "XENA-Llama"
    private var isModelLoaded = false

    external fun nativeLoadModel(
        modelPath: String,
        n_ctx: Int,
        n_batch: Int,
        n_threads: Int,
        useMmap: Boolean,
        useMlock: Boolean
    ): Boolean

    external fun nativeGenerate(prompt: String, temp: Float, topP: Float, repeatPenalty: Float): String
    external fun nativeIsLoaded(): Boolean
    external fun nativeFreeModel()

    init {
        try {
            System.loadLibrary("llama")
            Log.d(TAG, "libllama loaded")
        } catch (e: Exception) {
            Log.e(TAG, "Load lib error: ${e.message}")
        }
    }

    fun loadModel(
        modelPath: String,
        n_ctx: Int = 2048,
        n_batch: Int = 128,
        n_threads: Int = 4,
        useMmap: Boolean = true,
        useMlock: Boolean = false
    ): Boolean {
        return try {
            Log.d(TAG, "XENA Loading: $modelPath ctx=$n_ctx batch=$n_batch threads=$n_threads")
            val ok = nativeLoadModel(modelPath, n_ctx, n_batch, n_threads, useMmap, useMlock)
            isModelLoaded = ok
            ok
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native lib missing: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Load error: ${e.message}")
            false
        }
    }

    fun getSafeSystemPrompt(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null" || raw == "undefined") {
            return "You are Xena-AI, a helpful coding assistant powered by Qwen2.5-Coder. " +
                "You are an expert programmer created by Xena. Answer correctly and clearly in Indonesian. " +
                "Provide working code when asked."
        }
        return raw.trim()
    }

    fun getSafeUserMessage(raw: String?): String =
        if (raw.isNullOrBlank()) "Hai" else raw.trim()

    fun generate(
        userMessage: String?,
        systemPromptRaw: String?,
        temperature: Float = 0.2f,
        topP: Float = 0.9f,
        repeatPenalty: Float = 1.1f
    ): String {
        if (!isModelLoaded) {
            try {
                if (!nativeIsLoaded()) {
                    return "[XENA Engine belum siap. Tab Model → Muat Model, tunggu 30–60 dtk. Pastikan GGUF di files app / Download dan RAM cukup.]"
                }
            } catch (_: Throwable) {
                return "[XENA Model belum dimuat / native lib belum tersedia]"
            }
        }
        val systemPrompt = getSafeSystemPrompt(systemPromptRaw)
        val userMsg = getSafeUserMessage(userMessage)
        val fullPrompt =
            "<|im_start|>system\n$systemPrompt<|im_end|>\n" +
                "<|im_start|>user\n$userMsg<|im_end|>\n" +
                "<|im_start|>assistant\n"
        return try {
            val res = nativeGenerate(fullPrompt, temperature, topP, repeatPenalty)
            if (res.isBlank()) "[XENA gagal generate, coba set context 2048]" else res
        } catch (e: Exception) {
            "[XENA Error: ${e.message}]"
        }
    }

    fun isReady(): Boolean {
        if (isModelLoaded) return true
        return try {
            nativeIsLoaded()
        } catch (_: Throwable) {
            false
        }
    }

    fun unload() {
        try {
            nativeFreeModel()
            isModelLoaded = false
        } catch (_: Exception) {
        }
    }
}
