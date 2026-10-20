package com.xena.ai

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object ModelAutoLoader {
    private const val TAG = "XENA-Model"
    private const val PREFERRED_NAME = "qwen2.5-coder-7b-instruct-q3_k_m.gguf"

    fun findInstalledModel(context: Context): File? {
        val possibleFiles = listOf(
            File(context.filesDir, PREFERRED_NAME),
            File(context.filesDir, "model.gguf"),
            File("/data/data/com.xena.ai/files/$PREFERRED_NAME"),
            File("/data/data/com.dte.ai/files/$PREFERRED_NAME"),
            File(context.getExternalFilesDir(null), PREFERRED_NAME),
            File("/storage/emulated/0/Download/$PREFERRED_NAME"),
            File("/storage/emulated/0/Download/model.gguf")
        )
        // Prefer private copy first (readable by native)
        for (file in possibleFiles) {
            if (file.exists() && file.canRead() && file.length() > 2_000_000_000L) return file
        }
        for (file in possibleFiles) {
            if (file.exists() && file.canRead() && file.length() > 50_000_000L) return file
        }
        return null
    }

    /**
     * Pastikan model ada di storage privat app agar native/mmap bisa baca di Android 10+.
     * Jika sumber di Download, salin ke filesDir (butuh ruang ~ukuran model).
     */
    fun ensurePrivateModel(context: Context): File? {
        val found = findInstalledModel(context) ?: return null
        val privateFile = File(context.filesDir, PREFERRED_NAME)
        if (found.absolutePath == privateFile.absolutePath) return found
        if (found.absolutePath.startsWith(context.filesDir.absolutePath)) return found

        // Sudah ada salinan privat valid
        if (privateFile.exists() && privateFile.length() == found.length() && privateFile.canRead()) {
            Log.d(TAG, "using existing private copy ${privateFile.length()}")
            return privateFile
        }

        return try {
            Log.d(TAG, "copying ${found.absolutePath} -> ${privateFile.absolutePath}")
            FileInputStream(found).use { input ->
                FileOutputStream(privateFile).use { output ->
                    val buf = ByteArray(1024 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                    }
                    output.fd.sync()
                }
            }
            if (privateFile.exists() && privateFile.length() > 50_000_000L) privateFile else found
        } catch (e: Exception) {
            Log.e(TAG, "copy failed: ${e.message}")
            // Fallback: coba path asli
            found
        }
    }

    fun isModelReady(context: Context): Boolean = findInstalledModel(context) != null

    fun getModelSizeMB(context: Context): String {
        val file = findInstalledModel(context) ?: return "0 MB"
        val sizeGB = file.length() / (1024.0 * 1024.0 * 1024.0)
        return if (sizeGB >= 1.0) String.format("%.2f GB", sizeGB)
        else String.format("%.0f MB", file.length() / (1024.0 * 1024.0))
    }
}
