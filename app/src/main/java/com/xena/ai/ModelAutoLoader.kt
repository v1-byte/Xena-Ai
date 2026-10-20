package com.xena.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

object ModelAutoLoader {
    private const val TAG = "XENA-Model"
    private const val MIN_MODEL_BYTES = 50_000_000L

    data class ModelOption(
        val id: String,
        val name: String,
        val fileName: String,
        val sizeLabel: String,
        val note: String,
        val url: String
    )

    private val catalog = listOf(
        ModelOption(
            "qwen-1.5b-q4",
            "Qwen2.5-Coder 1.5B · Q4_K_M",
            "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            "≈1.1 GB",
            "Paling ringan · cocok untuk RAM 6 GB",
            "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"
        ),
        ModelOption(
            "qwen-3b-q3",
            "Qwen2.5-Coder 3B · Q3_K_M",
            "qwen2.5-coder-3b-instruct-q3_k_m.gguf",
            "≈1.7 GB",
            "Ringan · keseimbangan ukuran dan kemampuan",
            "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q3_k_m.gguf"
        ),
        ModelOption(
            "qwen-7b-q2",
            "Qwen2.5-Coder 7B · Q2_K",
            "qwen2.5-coder-7b-instruct-q2_k.gguf",
            "≈2.8 GB",
            "Lebih berat · kebutuhan RAM lebih tinggi",
            "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q2_k.gguf"
        ),
        ModelOption(
            "qwen-7b-q3",
            "Qwen2.5-Coder 7B · Q3_K_M",
            "qwen2.5-coder-7b-instruct-q3_k_m.gguf",
            "≈3.55 GB",
            "Model 7B yang sudah digunakan sebelumnya",
            "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q3_k_m.gguf"
        )
    )

    private val activeDownloads = ConcurrentHashMap<String, Long>()

    fun recommendedModelsJson(context: Context): String {
        val activePath = AppPrefs.getLastModelPath(context)
        val result = JSONArray()
        catalog.forEach { option ->
            val file = findModel(context, option.id)
            result.put(
                JSONObject()
                    .put("id", option.id)
                    .put("name", option.name)
                    .put("size", option.sizeLabel)
                    .put("note", option.note)
                    .put("installed", file != null)
                    .put("active", file != null && file.absolutePath == activePath)
            )
        }
        return result.toString()
    }

    fun recommendedModel(context: Context, id: String): ModelOption? =
        catalog.firstOrNull { it.id == id }

    fun modelsDirectory(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
            .apply { mkdirs() }

    fun findModel(context: Context, id: String): File? {
        val option = recommendedModel(context, id) ?: return null
        val candidates = mutableListOf(
            File(modelsDirectory(context), option.fileName),
            File(context.filesDir, option.fileName)
        )
        context.getExternalFilesDir(null)?.let { candidates += File(it, option.fileName) }
        if (id == "qwen-7b-q3") {
            candidates += File(context.filesDir, "model.gguf")
            candidates += File("/data/data/com.xena.ai/files/${option.fileName}")
            candidates += File("/data/data/com.dte.ai/files/${option.fileName}")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                candidates += File("/storage/emulated/0/Download/${option.fileName}")
                candidates += File("/storage/emulated/0/Download/model.gguf")
            }
        }
        return candidates.firstOrNull(::isValidModel)
    }

    fun findInstalledModel(context: Context): File? {
        val remembered = AppPrefs.getLastModelPath(context)?.let(::File)
        if (remembered != null && isValidModel(remembered)) return remembered
        return catalog.asSequence()
            .mapNotNull { findModel(context, it.id) }
            .firstOrNull()
            ?: listOf(
                File(context.filesDir, "model.gguf"),
                File("/data/data/com.xena.ai/files/model.gguf")
            ).firstOrNull(::isValidModel)
    }

    /**
     * Model di filesDir dan Android/data/<package>/files adalah milik app dan dapat
     * dibaca native/mmap tanpa menyalin lagi. Pada Android lama, coba pindahkan file
     * Downloads yang bisa diakses langsung; Android baru memakai pemilih dokumen.
     */
    fun ensurePrivateModel(context: Context): File? {
        val found = findInstalledModel(context) ?: return null
        val appExternal = context.getExternalFilesDir(null)?.absolutePath
        if (found.absolutePath.startsWith(context.filesDir.absolutePath) ||
            (appExternal != null && found.absolutePath.startsWith(appExternal))
        ) return found

        return try {
            var target = File(modelsDirectory(context), found.name)
            if (target.exists() && target.length() == found.length() && isValidModel(target)) {
                found.delete()
                return target
            }
            if (target.exists()) {
                val dot = found.name.lastIndexOf('.')
                val stem = if (dot > 0) found.name.substring(0, dot) else found.name
                val ext = if (dot > 0) found.name.substring(dot) else ".gguf"
                target = File(modelsDirectory(context), "${stem}_moved_${System.currentTimeMillis()}$ext")
            }
            Log.d(TAG, "moving ${found.absolutePath} -> ${target.absolutePath}")
            if (found.renameTo(target) && isValidModel(target)) return target

            val partial = File(target.parentFile, "${target.name}.partial")
            partial.delete()
            FileInputStream(found).use { input ->
                FileOutputStream(partial).use { output ->
                    val buf = ByteArray(8 * 1024 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                    }
                    output.fd.sync()
                }
            }
            if (!isValidModel(partial)) {
                partial.delete()
                return found
            }
            if (!partial.renameTo(target)) {
                partial.delete()
                return found
            }
            found.delete()
            target
        } catch (e: Exception) {
            Log.e(TAG, "move failed: ${e.message}")
            found
        }
    }

    fun importModelFromUri(
        context: Context,
        uri: Uri,
        onProgress: (Long, Long) -> Unit
    ): File {
        val metadata = querySource(context, uri)
        val originalName = metadata.first
        if (!originalName.endsWith(".gguf", ignoreCase = true)) {
            throw IllegalArgumentException("Pilih file dengan ekstensi .gguf")
        }
        val safeName = originalName
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeLast(120)
        var target = File(modelsDirectory(context), safeName)
        if (target.exists()) {
            val dot = safeName.lastIndexOf('.')
            val stem = if (dot > 0) safeName.substring(0, dot) else safeName
            val ext = if (dot > 0) safeName.substring(dot) else ".gguf"
            target = File(modelsDirectory(context), "${stem}_${System.currentTimeMillis()}$ext")
        }
        val partial = File(target.parentFile, "${target.name}.partial")
        partial.delete()
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Tidak bisa membaca file yang dipilih")
            input.use { source ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(8 * 1024 * 1024)
                    var copied = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        onProgress(copied, metadata.second)
                    }
                    output.fd.sync()
                }
            }
            if (!isValidModel(partial)) {
                throw IllegalArgumentException("File GGUF terlalu kecil atau unduhannya belum lengkap")
            }
            if (!partial.renameTo(target)) {
                throw IllegalStateException("Tidak bisa menyimpan model ke folder aplikasi")
            }
            AppPrefs.saveLastModelPath(context, target.absolutePath)
            return target
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
    }

    fun sourceDisplayName(context: Context, uri: Uri): String = querySource(context, uri).first

    fun startDownload(
        context: Context,
        modelId: String,
        onProgress: (Int, Double) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ): Boolean {
        val option = recommendedModel(context, modelId) ?: return false
        if (activeDownloads.containsKey(modelId)) return false
        val existing = findModel(context, modelId)
        if (existing != null) {
            onComplete(true, "Model sudah ada")
            return true
        }
        val target = File(modelsDirectory(context), option.fileName)
        if (target.exists()) target.delete()

        try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(option.url))
                .setTitle(option.name)
                .setDescription("Model disimpan di folder aplikasi Xena-AI")
                .setMimeType("application/octet-stream")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(context, "models", option.fileName)
            val downloadId = manager.enqueue(request)
            activeDownloads[modelId] = downloadId

            Thread {
                var previousBytes = 0L
                var previousAt = System.currentTimeMillis()
                try {
                    while (activeDownloads[modelId] == downloadId) {
                        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
                        var status = -1
                        var reason = 0
                        var bytes = 0L
                        var total = -1L
                        var foundRow = false
                        cursor?.use {
                            if (it.moveToFirst()) {
                                foundRow = true
                                status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                                reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                bytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                                total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            }
                        }
                        if (!foundRow) {
                            activeDownloads.remove(modelId, downloadId)
                            onComplete(false, "Status unduhan tidak ditemukan. Coba mulai lagi.")
                            return@Thread
                        }
                        val now = System.currentTimeMillis()
                        val elapsed = (now - previousAt).coerceAtLeast(1L)
                        val speed = ((bytes - previousBytes).coerceAtLeast(0L) * 1000.0) /
                            elapsed / (1024.0 * 1024.0)
                        previousBytes = bytes
                        previousAt = now

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            activeDownloads.remove(modelId, downloadId)
                            if (isValidModel(target)) {
                                onProgress(100, speed)
                                onComplete(true, "Unduhan selesai")
                            } else {
                                target.delete()
                                onComplete(false, "File unduhan tidak lengkap atau bukan model GGUF yang valid")
                            }
                            return@Thread
                        }
                        if (status == DownloadManager.STATUS_FAILED) {
                            activeDownloads.remove(modelId, downloadId)
                            target.delete()
                            onComplete(false, "Unduhan gagal (kode $reason). Periksa koneksi dan ruang penyimpanan.")
                            return@Thread
                        }
                        if (status == DownloadManager.STATUS_RUNNING ||
                            status == DownloadManager.STATUS_PENDING ||
                            status == DownloadManager.STATUS_PAUSED
                        ) {
                            val percent = if (total > 0L) {
                                ((bytes * 100L) / total).toInt().coerceIn(0, 99)
                            } else 0
                            onProgress(percent, speed)
                        }
                        Thread.sleep(1000L)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "download monitor failed: ${e.message}")
                    activeDownloads.remove(modelId, downloadId)
                    onComplete(false, "Tidak bisa memantau unduhan: ${e.message ?: "kesalahan sistem"}")
                }
            }.start()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "download start failed: ${e.message}")
            onComplete(false, "Tidak bisa memulai unduhan: ${e.message ?: "kesalahan sistem"}")
            return true
        }
    }

    fun cancelDownload(context: Context, modelId: String): Boolean {
        val downloadId = activeDownloads.remove(modelId) ?: return false
        return try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.remove(downloadId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "cancel download failed: ${e.message}")
            false
        }
    }

    fun isModelReady(context: Context): Boolean = findInstalledModel(context) != null

    fun getModelSizeMB(context: Context, modelFile: File? = null): String {
        val file = modelFile ?: findInstalledModel(context) ?: return "0 MB"
        val sizeGB = file.length() / (1024.0 * 1024.0 * 1024.0)
        return if (sizeGB >= 1.0) String.format("%.2f GB", sizeGB)
        else String.format("%.0f MB", file.length() / (1024.0 * 1024.0))
    }

    private fun isValidModel(file: File): Boolean =
        if (!file.isFile || !file.canRead() || file.length() <= MIN_MODEL_BYTES) {
            false
        } else {
            try {
                FileInputStream(file).use { input ->
                    val header = ByteArray(4)
                    input.read(header) == 4 && String(header, Charsets.US_ASCII) == "GGUF"
                }
            } catch (_: Exception) {
                false
            }
        }

    private fun querySource(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
        var size = -1L
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0) name = cursor.getString(nameColumn) ?: name
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        }
        return name to size
    }
}
