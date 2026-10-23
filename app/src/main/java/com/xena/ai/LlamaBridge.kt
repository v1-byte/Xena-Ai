package com.xena.ai

import android.util.Log
import java.io.File

object LlamaBridge {
    private const val TAG = "XENA-Llama"
    @Volatile private var isModelLoaded = false
    @Volatile private var loadedModelPath: String? = null
    @Volatile var lastError: String = "ok"
        private set
    @Volatile var libraryOk: Boolean = false
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
    external fun nativeCancelGeneration()
    external fun nativeLastError(): String

    init {
        try {
            System.loadLibrary("llama")
            libraryOk = true
            Log.d(TAG, "libllama loaded")
        } catch (e: LinkageError) {
            libraryOk = false
            lastError = "libllama.so gagal load: native library tidak kompatibel dengan perangkat ini"
            Log.e(TAG, lastError, e)
        } catch (e: Exception) {
            libraryOk = false
            lastError = "libllama.so gagal load: ${e.message}"
            Log.e(TAG, lastError)
        }
    }

    /**
     * Coba beberapa preset context + mmap on/off.
     * Banyak HP gagal hanya karena mmap di storage app atau context terlalu besar.
     */
    @Synchronized
    fun loadModel(
        modelPath: String,
        n_ctx: Int = 1024,
        n_batch: Int = 32,
        n_threads: Int = 2,
        useMmap: Boolean = true,
        useMlock: Boolean = false
    ): Boolean {
        if (!libraryOk) {
            lastError = "libllama.so tidak tersedia. Install ulang APK arm64-v8a."
            return false
        }
        val file = File(modelPath)
        if (!file.isFile) {
            lastError = "File model tidak ada: $modelPath"
            return false
        }
        if (file.length() < 1_000_000L) {
            lastError = "File model terlalu kecil (${file.length()} byte) — unduhan belum selesai?"
            return false
        }

        // Lepas model lama dulu agar RAM longgar
        try {
            nativeFreeModel()
        } catch (_: Throwable) {
        }
        isModelLoaded = false
        loadedModelPath = null
        try {
            System.gc()
        } catch (_: Throwable) {
        }

        val ctxList = listOf(
            n_ctx.coerceIn(256, 2048),
            768,
            512,
            384,
            256
        ).distinct()
        val batchList = listOf(n_batch.coerceIn(8, 64), 24, 16, 8).distinct()
        val thrList = listOf(n_threads.coerceIn(1, 4), 2, 1).distinct()
        // mmap true dulu (hemat RAM), lalu false (lebih kompatibel di sebagian storage)
        val mmapModes = listOf(useMmap, !useMmap).distinct()

        for (mmap in mmapModes) {
            for (ctx in ctxList) {
                for (batch in batchList.take(2)) {
                    for (thr in thrList.take(2)) {
                        try {
                            Log.d(TAG, "try load $modelPath ctx=$ctx batch=$batch thr=$thr mmap=$mmap")
                            val ok = nativeLoadModel(modelPath, ctx, batch, thr, mmap, false)
                            lastError = try {
                                nativeLastError()
                            } catch (_: Throwable) {
                                if (ok) "ok" else "native gagal tanpa pesan"
                            }
                            if (ok) {
                                isModelLoaded = true
                                loadedModelPath = modelPath
                                Log.d(TAG, "loaded ok ctx=$ctx mmap=$mmap err=$lastError")
                                return true
                            }
                            Log.e(TAG, "fail ctx=$ctx mmap=$mmap: $lastError")
                        } catch (e: UnsatisfiedLinkError) {
                            lastError = "Native symbol hilang: ${e.message}"
                            Log.e(TAG, lastError)
                            isModelLoaded = false
                            return false
                        } catch (e: Exception) {
                            lastError = "Load exception: ${e.message}"
                            Log.e(TAG, lastError)
                        }
                    }
                }
            }
        }
        isModelLoaded = false
        loadedModelPath = null
        if (lastError.isBlank() || lastError == "ok") {
            lastError = "Semua percobaan load gagal. Tutup app lain, pakai model lebih kecil, atau nyalakan Server Mode Groq."
        }
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

    fun loadedPath(): String? = loadedModelPath

    fun unload() {
        try {
            nativeFreeModel()
        } catch (_: Throwable) {
        }
        isModelLoaded = false
        loadedModelPath = null
    }

    fun cancelGeneration() {
        try {
            nativeCancelGeneration()
        } catch (_: Throwable) {
        }
    }

    /** Diagnosa singkat untuk UI / log. */
    fun diagnose(modelPath: String? = null): String {
        val sb = StringBuilder()
        sb.append("lib=").append(if (libraryOk) "ok" else "FAIL")
        sb.append(" ready=").append(isReady())
        sb.append(" err=").append(lastError)
        if (!modelPath.isNullOrBlank()) {
            val f = File(modelPath)
            sb.append(" file=").append(if (f.isFile) "yes" else "no")
            sb.append(" size=").append(if (f.isFile) f.length() else 0)
        }
        loadedModelPath?.let { sb.append(" loaded=").append(File(it).name) }
        return sb.toString()
    }
}
