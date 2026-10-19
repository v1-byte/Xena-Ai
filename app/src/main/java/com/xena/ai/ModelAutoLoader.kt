package com.xena.ai

import android.content.Context
import java.io.File

object ModelAutoLoader {
    fun findInstalledModel(context: Context): File? {
        val possibleFiles = listOf(
            File(context.filesDir, "qwen2.5-coder-7b-instruct-q3_k_m.gguf"),
            File(context.filesDir, "model.gguf"),
            File("/data/data/com.xena.ai/files/qwen2.5-coder-7b-instruct-q3_k_m.gguf"),
            File("/data/data/com.dte.ai/files/qwen2.5-coder-7b-instruct-q3_k_m.gguf"), // migrasi DT-E-AI
            File(context.getExternalFilesDir(null), "qwen2.5-coder-7b-instruct-q3_k_m.gguf"),
            File("/storage/emulated/0/Download/qwen2.5-coder-7b-instruct-q3_k_m.gguf")
        )
        for (file in possibleFiles) {
            if (file.exists() && file.length() > 2_000_000_000L) return file
        }
        // Juga terima model lebih kecil (untuk uji)
        for (file in possibleFiles) {
            if (file.exists() && file.length() > 50_000_000L) return file
        }
        return null
    }

    fun isModelReady(context: Context): Boolean = findInstalledModel(context) != null

    fun getModelSizeMB(context: Context): String {
        val file = findInstalledModel(context) ?: return "0 MB"
        val sizeGB = file.length() / (1024.0 * 1024.0 * 1024.0)
        return if (sizeGB >= 1.0) String.format("%.2f GB", sizeGB)
        else String.format("%.0f MB", file.length() / (1024.0 * 1024.0))
    }
}
