package com.xena.ai

import android.util.Log

object LlamaBridge {
    private const val TAG = "XENA-Llama"
    private var isModelLoaded = false
    @Volatile var lastError: String = "ok"
        private set

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
    external fun nativeLastError(): String

    init {
        try {
            System.loadLibrary("llama")
            Log.d(TAG, "libllama loaded")
        } catch (e: Exception) {
            lastError = "libllama.so gagal load: ${e.message}"
            Log.e(TAG, lastError)
        }
    }

    /**
     * Mulai dengan preset hemat RAM untuk perangkat 6 GB, lalu turunkan jika perlu.
     * mmap ON, mlock OFF agar 6GB phone lebih mungkin berhasil.
     */
    fun loadModel(
        modelPath: String,
        n_ctx: Int = 1024,
        n_batch: Int = 32,
        n_threads: Int = 2,
        useMmap: Boolean = true,
        useMlock: Boolean = false
    ): Boolean {
        val attempts = listOf(
            Triple(n_ctx.coerceAtMost(1024), n_batch.coerceAtMost(32), n_threads.coerceAtMost(2)),
            Triple(512, 16, 2)
        ).distinct()

        for ((ctx, batch, thr) in attempts) {
            try {
                Log.d(TAG, "try load $modelPath ctx=$ctx batch=$batch thr=$thr")
                val ok = nativeLoadModel(modelPath, ctx, batch, thr, useMmap, useMlock)
                lastError = try { nativeLastError() } catch (_: Throwable) { if (ok) "ok" else "native gagal" }
                if (ok) {
                    isModelLoaded = true
                    Log.d(TAG, "loaded ok with ctx=$ctx err=$lastError")
                    return true
                }
                Log.e(TAG, "attempt failed ctx=$ctx: $lastError")
            } catch (e: UnsatisfiedLinkError) {
                lastError = "Native symbol missing: ${e.message}"
                Log.e(TAG, lastError)
                isModelLoaded = false
                return false
            } catch (e: Exception) {
                lastError = "Load exception: ${e.message}"
                Log.e(TAG, lastError)
            }
        }
        isModelLoaded = false
        return false
    }

    fun getSafeSystemPrompt(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null" || raw == "undefined") {
            return "You are Xena-AI, a helpful coding assistant powered by Qwen2.5-Coder. " +
                "Answer correctly and clearly in Indonesian. Provide working code when asked."
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
                    return "[XENA Engine belum siap: $lastError]"
                }
            } catch (_: Throwable) {
                return "[XENA libllama tidak tersedia: $lastError]"
            }
        }
        return try {
            val system = getSafeSystemPrompt(systemPromptRaw)
            val user = getSafeUserMessage(userMessage)
            val fullPrompt =
                "<|im_start|>system\n$system<|im_end|>\n" +
                    "<|im_start|>user\n$user<|im_end|>\n" +
                    "<|im_start|>assistant\n"
            nativeGenerate(fullPrompt, temperature, topP, repeatPenalty)
        } catch (e: Exception) {
            "[XENA Generate error: ${e.message}]"
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
        } catch (_: Throwable) {
        }
        isModelLoaded = false
    }
}
