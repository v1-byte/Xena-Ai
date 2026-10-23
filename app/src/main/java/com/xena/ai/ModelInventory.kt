package com.xena.ai

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cache daftar model di background agar JavascriptInterface tidak memblokir UI thread
 * (penyebab utama "stuck" saat buka tab Model / Download Manager).
 */
object ModelInventory {
    private const val TAG = "XENA-Inventory"
    private const val TTL_MS = 2_500L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xena-model-inventory").apply { isDaemon = true }
    }
    private val refreshing = AtomicBoolean(false)

    @Volatile private var installedJson: String = "[]"
    @Volatile private var localFilesJson: String = "[]"
    @Volatile private var builtAt: Long = 0L
    @Volatile private var listener: ((String, String) -> Unit)? = null

    fun setListener(cb: ((String, String) -> Unit)?) {
        listener = cb
    }

    /** Langsung kembalikan cache; jadwalkan refresh bila perlu. */
    fun installedJson(context: Context, force: Boolean = false): String {
        schedule(context, force)
        return installedJson
    }

    fun localFilesJson(context: Context, force: Boolean = false): String {
        schedule(context, force)
        return localFilesJson
    }

    fun invalidate() {
        builtAt = 0L
        ModelFileFinder.invalidate()
    }

    fun schedule(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - builtAt < TTL_MS && builtAt != 0L) return
        if (!refreshing.compareAndSet(false, true)) return
        val app = context.applicationContext
        executor.execute {
            try {
                val installed = buildInstalled(app)
                val localFast = buildLocalFast(app)
                installedJson = installed
                localFilesJson = localFast
                builtAt = System.currentTimeMillis()
                try {
                    listener?.invoke(installedJson, localFilesJson)
                } catch (e: Exception) {
                    Log.w(TAG, "listener: ${e.message}")
                }
                // Deep scan (Download folder) — lebih lambat, update cache lagi
                val localDeep = mergeDeep(app, localFast)
                if (localDeep != localFilesJson) {
                    localFilesJson = localDeep
                    builtAt = System.currentTimeMillis()
                    try {
                        listener?.invoke(installedJson, localFilesJson)
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "refresh failed: ${e.message}", e)
            } finally {
                refreshing.set(false)
            }
        }
    }

    private fun buildInstalled(context: Context): String {
        val activePath = try {
            LlamaBridge.loadedPath()
        } catch (_: Throwable) {
            null
        }
        val engineReady = try {
            LlamaBridge.isReady()
        } catch (_: Throwable) {
            false
        }
        val models = JSONArray()
        // Listing cepat: jangan buka header GGUF di sini
        listAppGguf(context).forEach { file ->
            val spec = ModelCatalog.byFileName(file.name)
            models.put(
                JSONObject()
                    .put("id", spec?.id ?: file.name)
                    .put("title", spec?.title ?: file.name)
                    .put("fileName", file.name)
                    .put("sizeBytes", file.length())
                    .put("quantization", spec?.quantization ?: "GGUF")
                    .put("installed", true)
                    .put("active", engineReady && activePath == file.absolutePath)
            )
        }
        return models.toString()
    }

    private fun buildLocalFast(context: Context): String {
        val files = JSONArray()
        val seen = hashSetOf<String>()
        listAppGguf(context).forEach { file ->
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
                    .put("headerValid", file.length() >= 50_000_000L)
                    .put("location", "Folder aplikasi")
            )
        }
        // Partials katalog
        ModelCatalog.all().forEach { model ->
            val partial = File(ModelAutoLoader.modelDirectory(context), "${model.fileName}.part")
            if (!partial.isFile || !seen.add(partial.absolutePath)) return@forEach
            files.put(
                JSONObject()
                    .put("id", model.id)
                    .put("title", model.title)
                    .put("fileName", "${model.fileName}.part")
                    .put("path", partial.absolutePath)
                    .put("kind", "partial")
                    .put("status", "Parsial · bisa dilanjutkan")
                    .put("bytes", partial.length())
                    .put("expectedBytes", model.sizeBytes)
                    .put("quantization", model.quantization)
                    .put("modifiedAt", partial.lastModified())
                    .put("hasResumeValidator", true)
                    .put("location", "Folder aplikasi")
            )
        }
        return files.toString()
    }

    private fun mergeDeep(context: Context, fastJson: String): String {
        return try {
            val files = JSONArray(fastJson)
            val seen = hashSetOf<String>()
            for (i in 0 until files.length()) {
                seen.add(files.getJSONObject(i).optString("path"))
            }
            val discovered = JSONArray(ModelFileFinder.searchJson(context, force = false))
            for (i in 0 until discovered.length()) {
                val item = discovered.getJSONObject(i)
                val path = item.optString("path")
                if (path.isBlank() || !seen.add(path)) continue
                files.put(item)
            }
            files.toString()
        } catch (e: Exception) {
            Log.w(TAG, "deep merge: ${e.message}")
            fastJson
        }
    }

    private fun listAppGguf(context: Context): List<File> {
        val out = linkedMapOf<String, File>()
        val dirs = listOfNotNull(
            ModelAutoLoader.modelDirectory(context),
            context.filesDir,
            context.getExternalFilesDir(null)
        ).distinctBy { it.absolutePath }
        dirs.forEach { dir ->
            try {
                dir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) && it.length() >= 1_000_000L }
                    ?.forEach { out.putIfAbsent(it.absolutePath, it) }
            } catch (_: Exception) {
            }
        }
        return out.values.toList()
    }
}
