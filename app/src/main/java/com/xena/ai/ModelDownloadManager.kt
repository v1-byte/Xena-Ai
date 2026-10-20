package com.xena.ai

import android.content.Context
import android.util.Log
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
            if (part.length() >= model.sizeBytes) {
                if (part.length() == model.sizeBytes && ModelAutoLoader.isValidGguf(part)) {
                    if (finalFile.exists() && !ModelAutoLoader.isValidGguf(finalFile)) finalFile.delete()
                    if (part.renameTo(finalFile) && ModelAutoLoader.isValidGguf(finalFile)) {
                        AppPrefs.saveLastModelPath(appContext, finalFile.absolutePath)
                        metaFile(model).delete()
                        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PENDING_MODEL).apply()
                        publish(Snapshot("ready", model.id, model.fileName, finalFile.length(), finalFile.length(), 0L, "Model GGUF parsial lengkap; siap digunakan"))
                        return true
                    }
                }
                part.delete()
                metaFile(model).delete()
            }
            val remaining = max(0L, model.sizeBytes - part.length())
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
            if (partial > 0L) return Snapshot("paused", model.id, model.fileName, partial, model.sizeBytes, 0L, "Unduhan parsial tersimpan; tekan Lanjut")
        }
        val installed = ModelAutoLoader.installedModels(appContext)
        return if (installed.isNotEmpty()) {
            val selected = ModelAutoLoader.findInstalledModel(appContext) ?: installed.first()
            val spec = ModelCatalog.byFileName(selected.name)
            Snapshot("ready", spec?.id.orEmpty(), selected.name, selected.length(), selected.length(), 0L, "Model siap digunakan")
        } else Snapshot("idle", "", "", 0L, 0L, 0L, "Tidak ada unduhan aktif")
    }

    private fun runDownload(model: ModelCatalog.Model) {
        val part = partFile(model)
        val meta = readMeta(model)
        var offset = part.length()
        if (offset > 0L && meta.getProperty("etag").isNullOrBlank() && meta.getProperty("lastModified").isNullOrBlank()) {
            // Without an HTTP validator a partial from an earlier file revision is not safe to append.
            part.delete()
            metaFile(model).delete()
            meta.clear()
            offset = 0L
        }
        var restartedWithoutRange = false
        var lastReportAt = System.currentTimeMillis()
        var lastReportBytes = offset
        var speed = 0L
        try {
            while (true) {
                waitIfPaused(model)
                checkCancelled()
                val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 45_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Xena-AI-Android/1.3.6")
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
                    // Range was ignored or the validator changed: never append a full 200 response.
                    connection.disconnect()
                    activeConnection = null
                    if (restartedWithoutRange) throw IOException("Server tidak menerima unduhan lanjutan")
                    part.delete()
                    metaFile(model).delete()
                    meta.clear()
                    offset = 0L
                    restartedWithoutRange = true
                    continue
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
