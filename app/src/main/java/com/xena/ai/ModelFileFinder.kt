package com.xena.ai

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Cari file model GGUF yang sudah terunduh, termasuk folder Download yang masih bisa dibaca. */
object ModelFileFinder {
    private const val TAG = "XENA-Finder"
    private const val CACHE_MS = 4_000L
    private val lock = Any()
    private var cachedAt = 0L
    private var cached = JSONArray()

    fun isCandidate(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        if (n.endsWith(".gguf")) return true
        if (n.contains(".gguf.")) return true
        if (n.endsWith(".importing") && (n.contains("gguf") || n.contains("qwen") || n.contains("rabbit"))) return true
        return n.endsWith(".part") && (n.contains("gguf") || n.contains("qwen") || n.contains("rabbit") || n.contains("llama"))
    }

    fun searchJson(context: Context, force: Boolean = false): String {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (!force && now - cachedAt < CACHE_MS && cached.length() >= 0 && cachedAt != 0L) {
                return cached.toString()
            }
            val array = scan(context)
            cached = array
            cachedAt = now
            return array.toString()
        }
    }

    fun invalidate() {
        synchronized(lock) { cachedAt = 0L }
    }

    private fun scan(context: Context): JSONArray {
        val array = JSONArray()
        val seen = HashSet<String>()
        val appDir = ModelAutoLoader.modelDirectory(context).absolutePath
        for (file in collectFiles(context)) {
            val key = file.absolutePath
            if (!seen.add(key)) continue
            array.put(describeFile(file, appDir))
        }
        if (Build.VERSION.SDK_INT >= 29) {
            queryDownloads(context, seen, array)
        }
        return array
    }

    private fun collectFiles(context: Context): List<File> {
        val found = ArrayList<File>(64)
        val roots = LinkedHashSet<File>()
        roots += ModelAutoLoader.modelDirectory(context)
        roots += context.filesDir
        context.getExternalFilesDir(null)?.let { roots += it }
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { roots += it }
        val publicDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        roots += publicDownload
        roots += File(publicDownload, "Xena-AI")
        roots += File(publicDownload, "Xena-AI/Models")
        roots += File("/storage/emulated/0/Download")
        roots += File("/storage/emulated/0/Download/Xena-AI")
        roots += File("/storage/emulated/0/Download/Xena-AI/Models")
        roots += File("/storage/emulated/0/Documents/Xena-AI")
        for (root in roots) {
            walk(root, 0, found)
            if (found.size >= 250) break
        }
        return found
    }

    private fun walk(dir: File, depth: Int, out: MutableList<File>) {
        if (depth > 3 || out.size >= 250) return
        val children = try {
            if (!dir.isDirectory || !dir.canRead()) return
            dir.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return
        for (child in children) {
            if (out.size >= 250) return
            if (child.isFile && isCandidate(child.name)) out += child
            else if (child.isDirectory && !child.name.startsWith(".")) walk(child, depth + 1, out)
        }
    }

    private fun describeFile(file: File, appDir: String): JSONObject {
        val partial = !file.name.endsWith(".gguf", ignoreCase = true)
        val headerValid = !partial && file.length() >= 50_000_000L && ModelAutoLoader.isValidGguf(file)
        val inApp = file.absolutePath.startsWith(appDir)
        val inDownload = file.absolutePath.contains("/Download")
        val location = when {
            inApp -> "Folder aplikasi"
            inDownload -> "Download"
            else -> "Penyimpanan"
        }
        val status = when {
            partial -> "Parsial · belum selesai"
            headerValid -> "GGUF valid"
            file.length() < 50_000_000L -> "Terlalu kecil / belum selesai"
            else -> "Bukan GGUF valid"
        }
        val spec = ModelCatalog.byFileName(file.name.removeSuffix(".part").removeSuffix(".importing"))
        return JSONObject()
            .put("id", spec?.id ?: file.name)
            .put("title", spec?.title ?: file.name)
            .put("fileName", file.name)
            .put("path", file.absolutePath)
            .put("kind", if (inApp && !partial) "installed" else "download")
            .put("status", status)
            .put("bytes", file.length())
            .put("expectedBytes", spec?.sizeBytes ?: file.length())
            .put("quantization", spec?.quantization ?: "GGUF")
            .put("modifiedAt", file.lastModified())
            .put("headerValid", headerValid)
            .put("location", location)
            .put("importable", headerValid && !inApp)
    }

    private fun queryDownloads(context: Context, seen: MutableSet<String>, array: JSONArray) {
        val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE
        )
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? OR ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%.gguf", "%.GGUF")
        try {
            context.contentResolver.query(uri, projection, selection, args, "${MediaStore.Downloads.DATE_MODIFIED} DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                var count = 0
                while (cursor.moveToNext() && count < 80) {
                    val name = cursor.getString(nameCol) ?: continue
                    if (!isCandidate(name)) continue
                    val id = cursor.getLong(idCol)
                    val item = Uri.withAppendedPath(uri, id.toString())
                    val key = item.toString()
                    if (!seen.add(key)) continue
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val spec = ModelCatalog.byFileName(name)
                    val partial = !name.endsWith(".gguf", ignoreCase = true)
                    array.put(
                        JSONObject()
                            .put("id", spec?.id ?: name)
                            .put("title", spec?.title ?: name)
                            .put("fileName", name)
                            .put("path", key)
                            .put("kind", "download")
                            .put("status", if (partial) "Download · parsial" else "Download · MediaStore")
                            .put("bytes", size)
                            .put("expectedBytes", spec?.sizeBytes ?: size)
                            .put("quantization", spec?.quantization ?: "GGUF")
                            .put("modifiedAt", 0)
                            .put("headerValid", !partial && size >= 50_000_000L)
                            .put("location", "MediaStore Download")
                            .put("importable", !partial && size >= 50_000_000L)
                    )
                    count++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore scan skipped: ${e.message}")
        }
    }
}
