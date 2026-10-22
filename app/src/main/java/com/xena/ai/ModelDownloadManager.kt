package com.xena.ai

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import kotlin.math.max

class ModelDownloadManager(context: Context) {
    data class Snapshot(
        val state: String,
        val modelId: String,
        val fileName: String,
        val bytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
        val message: String
    ) {
        fun toJson(): String {
            val percent = if (totalBytes > 0) ((bytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else -1
            return JSONObject()
                .put("state", state)
                .put("modelId", modelId)
                .put("fileName", fileName)
                .put("bytes", bytes)
                .put("totalBytes", totalBytes)
                .put("bytesPerSecond", bytesPerSecond)
                .put("percent", if (percent >= 0) percent else JSONObject.NULL)
                .put("message", message)
                .toString()
        }
    }

    private val appContext = context.applicationContext
    private val lock = Object()
    @Volatile private var listener: ((Snapshot) -> Unit)? = null
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var paused = false
    @Volatile private var cancelled = false
    @Volatile private var resumeScheduled = false
    @Volatile private var activeModelId = ""
    @Volatile private var latest = Snapshot("idle", "", "", 0L, 0L, 0L, "")

    fun setListener(callback: ((Snapshot) -> Unit)?) {
        listener = callback
    }

    fun start(modelId: String): Boolean {
        val model = ModelCatalog.get(modelId)
        if (model == null) {
            publish(Snapshot("failed", modelId, "", 0L, 0L, 0L, "Model tidak dikenal"))
            return false
        }

        synchronized(lock) {
            val active = worker
            if (active?.isAlive == true) {
                if (activeModelId == model.id && (paused || latest.state == "paused" || latest.state == "failed")) {
                    return resume()
                }
                publish(Snapshot("failed", model.id, model.fileName, 0L, model.sizeBytes, 0L, "Selesaikan atau batalkan unduhan yang sedang aktif"))
                return false
            }

            val finalFile = modelFile(model)
            if (ModelAutoLoader.isValidGguf(finalFile)) {
                publish(Snapshot("ready", model.id, model.fileName, finalFile.length(), finalFile.length(), 0L, "Model sudah tersedia"))
                return true
            }
            val modelDir = ModelAutoLoader.modelDirectory(appContext)
            if (!modelDir.exists() && !modelDir.mkdirs()) {
                publish(Snapshot("failed", model.id, model.fileName, 0L, model.sizeBytes, 0L, "Folder penyimpanan aplikasi tidak dapat dibuat"))
                return false
            }
            val part = partFile(model)
            val partMeta = readMeta(model)
            val expectedBytes = partMeta.getProperty("total")?.toLongOrNull()?.takeIf { it > 0L } ?: model.sizeBytes
            if (part.length() >= expectedBytes) {
                if (part.length() == expectedBytes && ModelAutoLoader.isValidGguf(part)) {
                    if (finalFile.exists() && !ModelAutoLoader.isValidGguf(finalFile)) finalFile.delete()
                    if (part.renameTo(finalFile) && ModelAutoLoader.isValidGguf(finalFile)) {
                        AppPrefs.saveLastModelPath(appContext, finalFile.absolutePath)
                        metaFile(model).delete()
                        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PENDING_MODEL).apply()
                        publish(Snapshot("ready", model.id, model.fileName, finalFile.length(), finalFile.length(), 0L, "Model GGUF parsial lengkap; siap digunakan"))
                        return true
                    }
                }
                publish(Snapshot("failed", model.id, model.fileName, part.length(), expectedBytes, 0L,
                    "Berkas parsial sudah mencapai ukuran target tetapi verifikasi GGUF gagal. File dipertahankan; tekan Batal hanya jika ingin menghapus dan mengunduh ulang."))
                return false
            }
            val remaining = max(0L, expectedBytes - part.length())
            if (ModelAutoLoader.modelDirectory(appContext).usableSpace < remaining + 64L * 1024 * 1024) {
                publish(Snapshot("failed", model.id, model.fileName, part.length(), model.sizeBytes, 0L, "Ruang penyimpanan tidak cukup. Hapus berkas atau pilih model lebih kecil."))
                return false
            }
            activeModelId = model.id
            cancelled = false
            paused = false
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PENDING_MODEL, model.id).apply()
            worker = Thread({ runDownload(model) }, "Xena-model-${model.id}").apply {
                isDaemon = true
                start()
            }
        }
        return true
    }

    fun pause(): Boolean {
        val model = ModelCatalog.get(activeModelId) ?: return false
        if (worker?.isAlive != true) return false
        resumeScheduled = false
        paused = true
        activeConnection?.disconnect()
        publish(Snapshot("paused", model.id, model.fileName, partFile(model).length(), model.sizeBytes, 0L, "Unduhan dijeda"))
        return true
    }

    fun resume(): Boolean {
        val model = ModelCatalog.get(activeModelId)
            ?: appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PENDING_MODEL, null)?.let(ModelCatalog::get)
            ?: return false
        val active = worker
        if (active?.isAlive == true) {
            synchronized(lock) {
                if (!paused && latest.state !in setOf("paused", "failed")) return true
                if (resumeScheduled) return true
                resumeScheduled = true
                paused = false
                Thread({
                    try { active.join() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    val shouldRestart = synchronized(lock) {
                        if (worker === active) worker = null
                        val pending = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .getString(KEY_PENDING_MODEL, null)
                        val restart = resumeScheduled && !cancelled && pending == model.id &&
                            latest.state in setOf("paused", "failed")
                        resumeScheduled = false
                        restart
                    }
                    if (shouldRestart) start(model.id)
                }, "Xena-model-resume-${model.id}").apply { isDaemon = true; start() }
            }
            return true
        }
        return start(model.id)
    }

    fun cancel(): Boolean {
        val model = ModelCatalog.get(activeModelId)
            ?: appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PENDING_MODEL, null)?.let(ModelCatalog::get)
            ?: return false
        cancelled = true
        paused = false
        resumeScheduled = false
        activeConnection?.disconnect()
        val active = worker
        if (active?.isAlive != true) {
            partFile(model).delete()
            metaFile(model).delete()
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PENDING_MODEL).apply()
            publish(Snapshot("cancelled", model.id, model.fileName, 0L, model.sizeBytes, 0L, "Unduhan dibatalkan"))
        }
        return true
    }

    fun currentSnapshot(): Snapshot {
        if (worker?.isAlive == true) return latest
        val pendingId = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_MODEL, null)
        val model = pendingId?.let(ModelCatalog::get)
        if (model != null) {
            val final = modelFile(model)
            if (ModelAutoLoader.isValidGguf(final)) {
                return Snapshot("ready", model.id, model.fileName, final.length(), final.length(), 0L, "Model siap digunakan")
            }
            val partial = partFile(model).length()
            if (partial > 0L) {
                val meta = readMeta(model)
                val total = meta.getProperty("total")?.toLongOrNull()?.takeIf { it > 0L } ?: model.sizeBytes
                return Snapshot("paused", model.id, model.fileName, partial, total, 0L, "Unduhan parsial tersimpan; tekan Lanjut")
            }
        }
        val installed = ModelAutoLoader.installedModels(appContext)
        return if (installed.isNotEmpty()) {
            val selected = ModelAutoLoader.findInstalledModel(appContext) ?: installed.first()
            val spec = ModelCatalog.byFileName(selected.name)
            Snapshot("ready", spec?.id.orEmpty(), selected.name, selected.length(), selected.length(), 0L, "Model siap digunakan")
        } else Snapshot("idle", "", "", 0L, 0L, 0L, "Tidak ada unduhan aktif")
    }

    fun localFilesJson(): String {
        val files = JSONArray()
        val seen = hashSetOf<String>()
        ModelAutoLoader.installedModels(appContext).forEach { file ->
            if (!seen.add(file.absolutePath)) return@forEach
            val spec = ModelCatalog.byFileName(file.name)
            files.put(
                JSONObject()
                    .put("id", spec?.id ?: file.name)
                    .put("title", spec?.title ?: file.name)
                    .put("fileName", file.name)
                    .put("path", file.absolutePath)
                    .put("kind", "installed")
                    .put("status", "Tersimpan")
                    .put("bytes", file.length())
                    .put("expectedBytes", file.length())
                    .put("quantization", spec?.quantization ?: "GGUF")
                    .put("modifiedAt", file.lastModified())
                    .put("headerValid", ModelAutoLoader.isValidGguf(file))
            )
        }
        ModelCatalog.all().forEach { model ->
            val partial = partFile(model)
            if (!partial.isFile || !seen.add(partial.absolutePath)) return@forEach
            val meta = readMeta(model)
            val expected = meta.getProperty("total")?.toLongOrNull()?.takeIf { it > 0L } ?: model.sizeBytes
            val status = when {
                worker?.isAlive == true && activeModelId == model.id && !paused -> "Sedang diunduh"
                (paused || latest.state == "paused") && latest.modelId == model.id -> "Dijeda"
                partial.length() >= expected -> "Siap diverifikasi"
                else -> "Parsial · bisa dilanjutkan"
            }
            files.put(
                JSONObject()
                    .put("id", model.id)
                    .put("title", model.title)
                    .put("fileName", "${model.fileName}.part")
                    .put("path", partial.absolutePath)
                    .put("kind", "partial")
                    .put("status", status)
                    .put("bytes", partial.length())
                    .put("expectedBytes", expected)
                    .put("quantization", model.quantization)
                    .put("modifiedAt", partial.lastModified())
                    .put("hasResumeValidator", !meta.getProperty("etag").isNullOrBlank() || !meta.getProperty("lastModified").isNullOrBlank())
            )
        }
        return files.toString()
    }

    /** Verify completeness and GGUF header without discarding an incomplete part file. */
    fun verifyDownload(modelId: String? = null): Boolean {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pendingId = prefs.getString(KEY_PENDING_MODEL, null)
        val requestedId = modelId?.takeIf { it.isNotBlank() }
        val model = requestedId?.let(ModelCatalog::get)
            ?: activeModelId.takeIf { it.isNotBlank() }?.let(ModelCatalog::get)
            ?: pendingId?.let(ModelCatalog::get)
            ?: ModelCatalog.all().firstOrNull { partFile(it).isFile || modelFile(it).isFile }
        if (model == null) {
            publish(Snapshot("failed", "", "", 0L, 0L, 0L, "Tidak ada file model/unduhan parsial untuk diverifikasi"))
            return false
        }
        if (worker?.isAlive == true) {
            publish(latest.copy(message = "Jeda unduhan terlebih dahulu, lalu tekan Verifikasi Download"))
            return false
        }

        val finalFile = modelFile(model)
        if (ModelAutoLoader.isValidGguf(finalFile)) {
            prefs.edit().remove(KEY_PENDING_MODEL).apply()
            metaFile(model).delete()
            publish(Snapshot("ready", model.id, model.fileName, finalFile.length(), finalFile.length(), 0L,
                "Verifikasi berhasil: file tersimpan dan header GGUF valid"))
            return true
        }

        val part = partFile(model)
        if (!part.isFile || part.length() <= 0L) {
            publish(Snapshot("failed", model.id, model.fileName, 0L, model.sizeBytes, 0L,
                "File unduhan parsial tidak ditemukan; file model yang terpasang tidak diubah"))
            return false
        }
        val meta = readMeta(model)
        val total = meta.getProperty("total")?.toLongOrNull()?.takeIf { it > 0L } ?: model.sizeBytes
        val size = part.length()
        if (size < total) {
            val canResume = !meta.getProperty("etag").isNullOrBlank() || !meta.getProperty("lastModified").isNullOrBlank()
            val message = if (canResume) {
                "Parsial tersimpan ($size dari $total byte); Lanjut Download akan meminta sisa byte"
            } else {
                "Parsial tersimpan ($size dari $total byte), tetapi server tidak memberi validator resume; file tidak dihapus"
            }
            publish(Snapshot("paused", model.id, model.fileName, size, total, 0L, message))
            return true
        }
        if (size > total) {
            publish(Snapshot("failed", model.id, model.fileName, size, total, 0L,
                "Ukuran parsial melebihi ukuran target; file dipertahankan untuk pemeriksaan"))
            return false
        }
        if (!ModelAutoLoader.isValidGguf(part)) {
            publish(Snapshot("failed", model.id, model.fileName, size, total, 0L,
                "Ukuran cocok tetapi header GGUF tidak valid; file dipertahankan. Tekan Batal hanya jika ingin menghapus dan mengunduh ulang"))
            return false
        }
        if (finalFile.exists() && !finalFile.delete()) {
            publish(Snapshot("failed", model.id, model.fileName, size, total, 0L, "Model lama tidak dapat diganti; file parsial tetap disimpan"))
            return false
        }
        if (!part.renameTo(finalFile)) {
            publish(Snapshot("failed", model.id, model.fileName, size, total, 0L, "Verifikasi berhasil, tetapi file tidak dapat dipindahkan; file parsial tetap disimpan"))
            return false
        }
        metaFile(model).delete()
        AppPrefs.saveLastModelPath(appContext, finalFile.absolutePath)
        prefs.edit().remove(KEY_PENDING_MODEL).apply()
        publish(Snapshot("ready", model.id, model.fileName, size, total, 0L,
            "Verifikasi berhasil: ukuran lengkap dan header GGUF valid"))
        return true
    }

    private fun runDownload(model: ModelCatalog.Model) {
        val part = partFile(model)
        val meta = readMeta(model)
        var offset = part.length()
        var lastReportAt = System.currentTimeMillis()
        var lastReportBytes = offset
        var speed = 0L
        try {
            if (offset > 0L && meta.getProperty("etag").isNullOrBlank() && meta.getProperty("lastModified").isNullOrBlank()) {
                throw IOException("Server tidak memberi validator resume; file parsial dipertahankan dan unduhan tidak dimulai ulang otomatis")
            }
            while (true) {
                waitIfPaused(model)
                checkCancelled()
                val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 45_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Xena-AI-Android/1.3.8")
                    setRequestProperty("Accept", "application/octet-stream")
                    if (offset > 0L) {
                        setRequestProperty("Range", "bytes=$offset-")
                        meta.getProperty("etag")?.takeIf { it.isNotBlank() }?.let { setRequestProperty("If-Range", it) }
                            ?: meta.getProperty("lastModified")?.takeIf { it.isNotBlank() }?.let { setRequestProperty("If-Range", it) }
                    }
                }
                activeConnection = connection
                val status = connection.responseCode
                if (offset > 0L && status == HttpURLConnection.HTTP_OK) {
                    // Do not append a full response or silently delete/restart the saved partial.
                    connection.disconnect()
                    activeConnection = null
                    throw IOException("Server menolak HTTP Range atau file sumber berubah; berkas parsial tetap disimpan, tidak diunduh ulang")
                }
                if (offset > 0L && status != HttpURLConnection.HTTP_PARTIAL) {
                    throw IOException("HTTP $status saat melanjutkan unduhan")
                }
                if (offset == 0L && status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                    throw IOException("HTTP $status saat mengunduh model")
                }

                val contentRange = connection.getHeaderField("Content-Range")
                val rangeMatch = CONTENT_RANGE.find(contentRange.orEmpty())
                if (status == HttpURLConnection.HTTP_PARTIAL) {
                    val rangeStart = rangeMatch?.groupValues?.getOrNull(1)?.toLongOrNull()
                    if (rangeStart != offset) throw IOException("Rentang HTTP tidak cocok; unduhan dibatalkan demi keamanan")
                }
                val contentLength = connection.contentLengthLong
                val rangeTotal = rangeMatch?.groupValues?.getOrNull(3)?.toLongOrNull() ?: -1L
                val total = when {
                    rangeTotal > 0L -> rangeTotal
                    contentLength > 0L && status == HttpURLConnection.HTTP_PARTIAL -> offset + contentLength
                    contentLength > 0L -> contentLength
                    else -> meta.getProperty("total")?.toLongOrNull() ?: model.sizeBytes
                }
                val etag = connection.getHeaderField("ETag")
                val lastModified = connection.getHeaderField("Last-Modified")
                if (!etag.isNullOrBlank()) meta.setProperty("etag", etag)
                if (!lastModified.isNullOrBlank()) meta.setProperty("lastModified", lastModified)
                if (total > 0L) meta.setProperty("total", total.toString())
                writeMeta(model, meta)

                if (paused) {
                    connection.disconnect()
                    activeConnection = null
                    waitIfPaused(model)
                    offset = part.length()
                    continue
                }

                val append = offset > 0L
                val initialBytes = offset
                connection.inputStream.use { input ->
                    FileOutputStream(part, append).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var lastUiAt = System.currentTimeMillis()
                        while (true) {
                            checkCancelled()
                            if (paused) throw PausedTransferException()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            offset += count
                            val now = System.currentTimeMillis()
                            if (now - lastReportAt >= 1000L) {
                                val elapsed = (now - lastReportAt).coerceAtLeast(1L)
                                speed = ((offset - lastReportBytes) * 1000L / elapsed).coerceAtLeast(0L)
                                lastReportAt = now
                                lastReportBytes = offset
                            }
                            if (now - lastUiAt >= 400L) {
                                publish(Snapshot("downloading", model.id, model.fileName, offset, total, speed, "Mengunduh model…"))
                                lastUiAt = now
                            }
                        }
                        output.fd.sync()
                    }
                }
                connection.disconnect()
                activeConnection = null

                if (paused) {
                    publish(Snapshot("paused", model.id, model.fileName, part.length(), total, 0L, "Unduhan dijeda"))
                    waitIfPaused(model)
                    offset = part.length()
                    lastReportAt = System.currentTimeMillis()
                    lastReportBytes = offset
                    continue
                }
                if (total > 0L && part.length() != total) {
                    throw IOException("Unduhan belum lengkap (${part.length()} dari $total byte); tekan Lanjut")
                }
                publish(Snapshot("verifying", model.id, model.fileName, part.length(), total, 0L, "Memeriksa header GGUF…"))
                if (!ModelAutoLoader.isValidGguf(part)) throw IOException("Berkas selesai diunduh tetapi bukan GGUF yang valid")
                val final = modelFile(model)
                if (final.exists() && !ModelAutoLoader.isValidGguf(final)) final.delete()
                if (!part.renameTo(final)) throw IOException("Tidak dapat mengesahkan berkas model di penyimpanan aplikasi")
                metaFile(model).delete()
                AppPrefs.saveLastModelPath(appContext, final.absolutePath)
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PENDING_MODEL).apply()
                publish(Snapshot("ready", model.id, model.fileName, final.length(), final.length(), speed, "Unduhan selesai; GGUF valid dan siap digunakan"))
                return
            }
        } catch (e: PausedTransferException) {
            activeConnection?.disconnect()
            activeConnection = null
            publish(Snapshot("paused", model.id, model.fileName, part.length(), model.sizeBytes, 0L, "Unduhan dijeda"))
        } catch (e: Exception) {
            activeConnection?.disconnect()
            activeConnection = null
            if (cancelled) {
                part.delete()
                metaFile(model).delete()
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PENDING_MODEL).apply()
                publish(Snapshot("cancelled", model.id, model.fileName, 0L, model.sizeBytes, 0L, "Unduhan dibatalkan"))
            } else if (paused) {
                publish(Snapshot("paused", model.id, model.fileName, part.length(), model.sizeBytes, 0L, "Unduhan dijeda; tekan Lanjut"))
            } else {
                Log.e(TAG, "Model download failed: ${e.message}", e)
                publish(Snapshot("failed", model.id, model.fileName, part.length(), model.sizeBytes, 0L, e.message ?: "Unduhan gagal"))
            }
        } finally {
            activeConnection?.disconnect()
            activeConnection = null
            synchronized(lock) {
                worker = null
                paused = false
                cancelled = false
            }
        }
    }

    private fun waitIfPaused(model: ModelCatalog.Model) {
        while (paused && !cancelled) {
            publish(Snapshot("paused", model.id, model.fileName, partFile(model).length(), model.sizeBytes, 0L, "Unduhan dijeda"))
            Thread.sleep(250L)
        }
        checkCancelled()
    }

    private fun checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted) throw IOException("Unduhan dibatalkan")
    }

    /**
     * Hapus sisa unduhan gagal/parsial (.part + .properties) dan berkas GGUF tidak valid.
     * Tidak menghapus model yang sedang aktif atau unduhan yang sedang berjalan.
     * @return JSON ringkasan: deletedCount, freedBytes, details[]
     */
    fun cleanResiduals(): String {
        val details = JSONArray()
        var freed = 0L
        var count = 0
        val dir = ModelAutoLoader.modelDirectory(appContext)
        if (!dir.isDirectory) {
            return JSONObject()
                .put("deletedCount", 0)
                .put("freedBytes", 0L)
                .put("details", details)
                .put("message", "Folder model tidak ditemukan")
                .toString()
        }

        // Jangan ganggu unduhan aktif
        val activePart = if (worker?.isAlive == true && activeModelId.isNotBlank()) {
            ModelCatalog.get(activeModelId)?.let { partFile(it).absolutePath }
        } else null

        val lastPath = AppPrefs.getLastModelPath(appContext)

        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name
            val path = file.absolutePath
            if (path == activePart) return@forEach
            if (path == lastPath && ModelAutoLoader.isValidGguf(file)) return@forEach

            val isPart = name.endsWith(".part")
            val isMeta = name.endsWith(".part.properties")
            val isInvalidGguf = name.endsWith(".gguf", ignoreCase = true) && !ModelAutoLoader.isValidGguf(file)

            if (isPart || isMeta || isInvalidGguf) {
                val size = file.length()
                if (file.delete()) {
                    freed += size
                    count++
                    details.put(
                        JSONObject()
                            .put("name", name)
                            .put("bytes", size)
                            .put("kind", when {
                                isPart -> "partial"
                                isMeta -> "meta"
                                else -> "invalid-gguf"
                            })
                    )
                }
            }
        }

        // Bersihkan pending flag jika tidak ada part lagi
        if (count > 0) {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_PENDING_MODEL).apply()
            if (worker?.isAlive != true) {
                publish(Snapshot("idle", "", "", 0L, 0L, 0L, "Sisa unduhan dibersihkan"))
            }
        }

        return JSONObject()
            .put("deletedCount", count)
            .put("freedBytes", freed)
            .put("details", details)
            .put("message", if (count == 0) "Tidak ada sisa unduhan atau file rusak" else "Berhasil membersihkan $count berkas")
            .toString()
    }

    private fun modelFile(model: ModelCatalog.Model): File = File(ModelAutoLoader.modelDirectory(appContext), model.fileName)
    private fun partFile(model: ModelCatalog.Model): File = File(ModelAutoLoader.modelDirectory(appContext), "${model.fileName}.part")
    private fun metaFile(model: ModelCatalog.Model): File = File(ModelAutoLoader.modelDirectory(appContext), "${model.fileName}.part.properties")

    private fun readMeta(model: ModelCatalog.Model): Properties = Properties().apply {
        try {
            metaFile(model).inputStream().use(::load)
        } catch (_: Exception) {
        }
    }

    private fun writeMeta(model: ModelCatalog.Model, properties: Properties) {
        try {
            val temp = File(metaFile(model).parentFile, "${metaFile(model).name}.tmp")
            temp.outputStream().use { properties.store(it, "Xena model download resume metadata") }
            if (metaFile(model).exists()) metaFile(model).delete()
            temp.renameTo(metaFile(model))
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist resume metadata: ${e.message}")
        }
    }

    private fun publish(snapshot: Snapshot) {
        latest = snapshot
        listener?.invoke(snapshot)
    }

    private class PausedTransferException : IOException("paused")

    companion object {
        private const val TAG = "XENA-Download"
        private const val PREFS = "xena_model_transfer"
        private const val KEY_PENDING_MODEL = "pending_model_id"
        private val CONTENT_RANGE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
    }
}
