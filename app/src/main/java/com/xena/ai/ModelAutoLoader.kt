package com.xena.ai

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

object ModelAutoLoader {
    private const val TAG = "XENA-Model"
    private const val MIN_MODEL_BYTES = 50_000_000L
    private const val COPY_BUFFER_BYTES = 8 * 1024 * 1024
    private val preferredNames = listOf(
        "qwen2.5-coder-7b-instruct-q3_k_m.gguf",
        "qwen2.5-coder-7b-instruct-q2_k.gguf",
        "qwen2.5-coder-3b-instruct-q3_k_m.gguf",
        "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        "qwen2.5-coder-7b-instruct-q4_k_m.gguf"
    )

    /** App-specific external storage; Android removes this folder when the app is uninstalled. */
    fun modelDirectory(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    fun isValidGguf(file: File?): Boolean {
        if (file == null || !file.isFile || !file.canRead() || file.length() < MIN_MODEL_BYTES) return false
        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(8)
                var offset = 0
                while (offset < header.size) {
                    val count = input.read(header, offset, header.size - offset)
                    if (count < 0) return false
                    offset += count
                }
                if (header[0] != 'G'.code.toByte() || header[1] != 'G'.code.toByte() ||
                    header[2] != 'U'.code.toByte() || header[3] != 'F'.code.toByte()
                ) return false
                val version = (header[4].toInt() and 0xff) or
                    ((header[5].toInt() and 0xff) shl 8) or
                    ((header[6].toInt() and 0xff) shl 16) or
                    ((header[7].toInt() and 0xff) shl 24)
                version in 2..3
            }
        } catch (e: Exception) {
            Log.w(TAG, "GGUF validation failed for ${file.name}: ${e.message}")
            false
        }
    }

    fun installedModels(context: Context): List<File> {
        val files = linkedMapOf<String, File>()
        val dirs = listOfNotNull(modelDirectory(context), context.filesDir).distinctBy { it.absolutePath }
        dirs.forEach { dir ->
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
                ?.filter { isValidGguf(it) }
                ?.forEach { files.putIfAbsent(it.absolutePath, it) }
        }
        AppPrefs.getLastModelPath(context)?.let { path ->
            val file = File(path)
            if (isValidGguf(file)) files.putIfAbsent(file.absolutePath, file)
        }
        return files.values.sortedWith(
            compareBy<File> { preferredNames.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
    }

    fun findInstalledModel(context: Context): File? {
        val selected = AppPrefs.getLastModelPath(context)?.let(::File)
        if (isValidGguf(selected)) return selected
        val installed = installedModels(context)
        return installed.firstOrNull { it.name == preferredNames.first() } ?: installed.firstOrNull()
    }

    /**
     * Ensures there is a verified, app-readable model before native load. A readable Downloads
     * file is moved first (rename, no duplicate storage); copy is used only if rename is denied.
     */
    fun ensurePrivateModel(context: Context, onProgress: ((Long, Long) -> Unit)? = null): File? {
        val destinationDir = modelDirectory(context)
        if (!destinationDir.exists() && !destinationDir.mkdirs()) return null

        val selected = AppPrefs.getLastModelPath(context)?.let(::File)
        if (isValidGguf(selected)) {
            if (selected!!.parentFile?.absolutePath == destinationDir.absolutePath) {
                cleanExactDownloadDuplicates(context, selected)
                return selected
            }
            if (selected.parentFile?.absolutePath == context.filesDir.absolutePath) {
                val migrated = moveFileToPrivate(context, selected, onProgress)
                if (migrated != null) {
                    AppPrefs.saveLastModelPath(context, migrated.absolutePath)
                    cleanExactDownloadDuplicates(context, migrated)
                    return migrated
                }
            }
        }

        val privateModels = installedModels(context).filter {
            it.parentFile?.absolutePath == destinationDir.absolutePath
        }
        val installed = privateModels.firstOrNull { it.name == preferredNames.first() } ?: privateModels.firstOrNull()
        if (installed != null) {
            AppPrefs.saveLastModelPath(context, installed.absolutePath)
            cleanExactDownloadDuplicates(context, installed)
            return installed
        }

        val legacy = installedModels(context).firstOrNull { it.parentFile?.absolutePath == context.filesDir.absolutePath }
        if (legacy != null) {
            val migrated = moveFileToPrivate(context, legacy, onProgress)
            if (migrated != null) {
                AppPrefs.saveLastModelPath(context, migrated.absolutePath)
                cleanExactDownloadDuplicates(context, migrated)
                return migrated
            }
        }

        val downloaded = findAccessibleDownload(context)
        val moved = downloaded?.let { moveFileToPrivate(context, it, onProgress) }
        if (moved != null) {
            AppPrefs.saveLastModelPath(context, moved.absolutePath)
            return moved
        }
        return null
    }

    /** Best-effort scan: Android 10+ may hide shared Downloads; SAF import is the fallback. */
    fun findAccessibleDownload(context: Context): File? {
        val folders = listOf(
            File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS),
            File("/storage/emulated/0/Download")
        ).distinctBy { it.absolutePath }
        val candidates = folders.flatMap { dir ->
            try {
                dir.listFiles()?.filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }.orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
        }.filter { isValidGguf(it) }
        return candidates.sortedWith(
            compareBy<File> { preferredNames.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenByDescending { it.length() }
        ).firstOrNull()
    }

    fun moveDownloadedModel(context: Context, onProgress: ((Long, Long) -> Unit)? = null): File? {
        val source = findAccessibleDownload(context) ?: return null
        return moveFileToPrivate(context, source, onProgress)?.also {
            AppPrefs.saveLastModelPath(context, it.absolutePath)
        }
    }

    fun moveFileToPrivate(
        context: Context,
        source: File,
        onProgress: ((Long, Long) -> Unit)? = null
    ): File? {
        if (!isValidGguf(source)) return null
        val dir = modelDirectory(context)
        if (!dir.exists() && !dir.mkdirs()) return null
        val safeName = safeGgufName(source.name) ?: return null
        val destination = File(dir, safeName)
        if (source.absolutePath == destination.absolutePath) return source
        val sourceLength = source.length()

        if (isValidGguf(destination)) {
            if (source.length() == destination.length() && sameContent(source, destination)) {
                if (source.parentFile?.absolutePath != dir.absolutePath) source.delete()
            }
            return destination
        }

        val staging = File(dir, "$safeName.importing")
        if (staging.exists()) staging.delete()

        // A rename on the same storage volume uses no second copy of the multi-GB model.
        if (source.renameTo(staging)) {
            if (isValidGguf(staging) && staging.length() == sourceLength) {
                if (destination.exists()) destination.delete()
                if (staging.renameTo(destination) && isValidGguf(destination)) return destination
            }
            if (staging.exists()) staging.renameTo(source)
            return null
        }

        val required = source.length() + 64L * 1024 * 1024
        if (dir.usableSpace < required) {
            Log.w(TAG, "Not enough free space for safe fallback copy")
            return null
        }
        return try {
            copyStream(FileInputStream(source), staging, source.length(), onProgress)
            if (!isValidGguf(staging) || staging.length() != source.length()) {
                staging.delete()
                return null
            }
            if (destination.exists()) destination.delete()
            if (!staging.renameTo(destination) || !isValidGguf(destination)) {
                staging.delete()
                return null
            }
            source.delete()
            destination
        } catch (e: Exception) {
            staging.delete()
            Log.e(TAG, "Fallback model copy failed: ${e.message}")
            null
        }
    }

    /** Imports a user-selected Downloads/document URI using SAF without broad storage permission. */
    fun importFromUri(
        context: Context,
        uri: Uri,
        onProgress: ((Long, Long) -> Unit)? = null
    ): File? {
        val resolver = context.contentResolver
        var displayName = ""
        var expectedBytes = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex).orEmpty()
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) expectedBytes = cursor.getLong(sizeIndex)
            }
        }
        val safeName = safeGgufName(displayName) ?: return null
        val dir = modelDirectory(context)
        if (!dir.exists() && !dir.mkdirs()) return null
        val requiredBytes = if (expectedBytes > 0L) expectedBytes else MIN_MODEL_BYTES
        if (dir.usableSpace < requiredBytes + 64L * 1024 * 1024) return null
        val finalName = if (File(dir, safeName).exists()) {
            "imported-${System.currentTimeMillis()}-$safeName"
        } else safeName
        val destination = File(dir, finalName)
        val staging = File(dir, "$finalName.importing")
        return try {
            val input = resolver.openInputStream(uri) ?: return null
            val copied = copyStream(input, staging, expectedBytes, onProgress)
            if ((expectedBytes > 0 && copied != expectedBytes) || !isValidGguf(staging)) {
                staging.delete()
                return null
            }
            if (!staging.renameTo(destination) || !isValidGguf(destination)) {
                staging.delete()
                return null
            }
            AppPrefs.saveLastModelPath(context, destination.absolutePath)
            destination
        } catch (e: Exception) {
            staging.delete()
            Log.e(TAG, "SAF model import failed: ${e.message}")
            null
        }
    }

    fun modelFileForId(context: Context, idOrName: String): File? {
        val catalogName = ModelCatalog.get(idOrName)?.fileName
        val name = catalogName ?: safeGgufName(idOrName) ?: return null
        val candidates = listOf(File(modelDirectory(context), name), File(context.filesDir, name))
        return candidates.firstOrNull { isValidGguf(it) }
    }

    fun getModelSizeMB(context: Context): String = getModelSizeMB(findInstalledModel(context))

    fun getModelSizeMB(file: File?): String {
        if (!isValidGguf(file)) return "0 MB"
        val gib = file!!.length() / (1024.0 * 1024.0 * 1024.0)
        return if (gib >= 1.0) String.format(Locale.US, "%.2f GB", gib)
        else String.format(Locale.US, "%.0f MB", file.length() / (1024.0 * 1024.0))
    }

    fun usedModelBytes(context: Context): Long =
        installedModels(context).sumOf { it.length() }

    private fun copyStream(
        input: InputStream,
        destination: File,
        expectedBytes: Long,
        onProgress: ((Long, Long) -> Unit)?
    ): Long {
        input.use { source ->
            FileOutputStream(destination, false).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var copied = 0L
                var lastReport = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    copied += count
                    if (copied - lastReport >= 8L * 1024 * 1024) {
                        onProgress?.invoke(copied, expectedBytes)
                        lastReport = copied
                    }
                }
                output.fd.sync()
                onProgress?.invoke(copied, expectedBytes)
                return copied
            }
        }
    }

    private fun safeGgufName(name: String): String? {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!cleaned.endsWith(".gguf", ignoreCase = true)) return null
        if (cleaned.length !in 6..180 || cleaned == ".gguf") return null
        return cleaned
    }

    private fun cleanExactDownloadDuplicates(context: Context, installed: File) {
        val folders = listOf(File("/storage/emulated/0/Download"))
        folders.forEach { dir ->
            val source = File(dir, installed.name)
            if (source.absolutePath != installed.absolutePath && isValidGguf(source) &&
                source.length() == installed.length() && sameContent(source, installed)
            ) {
                source.delete()
                Log.i(TAG, "Removed verified duplicate Downloads model ${source.name}")
            }
        }
    }

    private fun sameContent(first: File, second: File): Boolean {
        if (first.length() != second.length()) return false
        return try {
            fun digest(file: File): ByteArray {
                val md = MessageDigest.getInstance("SHA-256")
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) md.update(buffer, 0, count)
                    }
                }
                return md.digest()
            }
            MessageDigest.isEqual(digest(first), digest(second))
        } catch (e: Exception) {
            Log.w(TAG, "Duplicate check skipped: ${e.message}")
            false
        }
    }
}
