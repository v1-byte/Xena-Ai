package com.xena.ai

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Looper
import android.webkit.WebView
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pelepas RAM yang terukur, bukan placebo System.gc().
 * Bobot GGUF ada di native llama.cpp (mmap + KV). Yang mengembalikan RAM ke sistem
 * adalah llama_free + llama_model_free + malloc_trim, lalu cache WebView yang aman.
 */
object RamGuard {
    data class Tune(
        val nCtx: Int,
        val nBatch: Int,
        val nThreads: Int,
        val label: String,
        val light: Boolean
    )

    data class Report(
        val unloaded: Boolean,
        val beforePssKb: Int,
        val afterPssKb: Int,
        val freedBytes: Long,
        val cacheBytes: Long,
        val availMb: Int,
        val light: Boolean,
        val message: String
    ) {
        fun toJson(): String = JSONObject()
            .put("unloaded", unloaded)
            .put("beforePssKb", beforePssKb)
            .put("afterPssKb", afterPssKb)
            .put("beforePssMb", beforePssKb / 1024)
            .put("afterPssMb", afterPssKb / 1024)
            .put("freedBytes", freedBytes)
            .put("cacheBytes", cacheBytes)
            .put("availMb", availMb)
            .put("light", light)
            .put("message", message)
            .toString()
    }

    fun tune(context: Context, modelBytes: Long): Tune {
        val mem = memory(context)
        val userCtx = AppPrefs.getNctx(context).coerceIn(512, 2048)
        val userBatch = AppPrefs.getNBatch(context).coerceIn(16, 512)
        val userThreads = AppPrefs.getNThreads(context).coerceIn(1, 8)
        val totalGb = mem.totalBytes / 1_073_741_824.0
        val availGb = mem.availBytes / 1_073_741_824.0
        val heavy = modelBytes >= 2_600_000_000L
        val veryTight = mem.lowMemory || availGb < 1.15
        val tight = veryTight || availGb < 1.9 || (totalGb <= 6.7 && heavy)
        return when {
            veryTight -> Tune(512, 16, 1, "Darurat · context 512 · 1 thread", true)
            tight -> Tune(
                minOf(userCtx, 768),
                minOf(userBatch, 24),
                minOf(userThreads, 2),
                "Ringan · RAM mepet",
                true
            )
            totalGb < 8.0 -> Tune(
                minOf(userCtx, 1024),
                minOf(userBatch, 32),
                minOf(userThreads, 2),
                "Seimbang · RAM 6 GB",
                false
            )
            else -> Tune(userCtx, userBatch, minOf(userThreads, 6), "Penuh", false)
        }
    }

    fun statusJson(context: Context): String {
        val mem = memory(context)
        val tune = tune(context, 0L)
        val availMb = (mem.availBytes / (1024 * 1024)).toInt()
        val totalMb = (mem.totalBytes / (1024 * 1024)).toInt()
        return JSONObject()
            .put("totalMb", totalMb)
            .put("availMb", availMb)
            .put("pssMb", mem.pssKb / 1024)
            .put("lowMemory", mem.lowMemory)
            .put("light", tune.light || mem.lowMemory)
            .put("label", if (mem.lowMemory) "RAM mepet · ${tune.label}" else tune.label)
            .put("loaded", LlamaBridge.isReady())
            .put("recommendedCtx", tune.nCtx)
            .toString()
    }

    /**
     * Selalu panggil nativeFreeModel (aman jika model tidak dimuat), kembalikan halaman
     * native ke OS, hapus cache aplikasi yang bukan file model, lalu ukur PSS.
     */
    fun purge(context: Context, webView: WebView?): Report {
        val before = memory(context)
        val wasLoaded = LlamaBridge.isReady()
        LlamaBridge.unload()
        clearWebView(webView)
        val cacheBytes = wipeReclaimableCache(context)
        try {
            Thread.sleep(180)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        Runtime.getRuntime().gc()
        val after = memory(context)
        val pssDropBytes = (before.pssKb - after.pssKb).coerceAtLeast(0).toLong() * 1024L
        val availGain = (after.availBytes - before.availBytes).coerceAtLeast(0L)
        // PSS/available memory already reflect reclaimed native/cache pages; report
        // cache bytes separately instead of adding overlapping measurements twice.
        val freed = maxOf(pssDropBytes, availGain).coerceAtLeast(0L)
        val availMb = (after.availBytes / (1024 * 1024)).toInt()
        val light = after.lowMemory || availMb < 1900
        val beforeMb = before.pssKb / 1024
        val afterMb = after.pssKb / 1024
        val message = when {
            wasLoaded && pssDropBytes >= 32L * 1024 * 1024 ->
                "Model dilepas dari RAM. Proses $beforeMb MB → $afterMb MB. " +
                    "Cache ${fmt(cacheBytes)} dihapus. RAM bebas perangkat $availMb MB."
            wasLoaded ->
                "Model native dilepas (llama_free). Proses $beforeMb MB → $afterMb MB. " +
                    "RAM bebas perangkat $availMb MB."
            cacheBytes > 0L ->
                "Tidak ada model di RAM. Cache ${fmt(cacheBytes)} dihapus. RAM bebas perangkat $availMb MB."
            else ->
                "Tidak ada model atau cache yang bisa dilepas. RAM bebas perangkat $availMb MB."
        } + if (light) " Mode ringan aktif." else ""
        return Report(wasLoaded, before.pssKb, after.pssKb, freed, cacheBytes, availMb, light, message)
    }

    private data class Mem(val totalBytes: Long, val availBytes: Long, val lowMemory: Boolean, val pssKb: Int)

    private fun memory(context: Context): Mem {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val pss = try {
            am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))[0].totalPss
        } catch (_: Exception) {
            0
        }
        return Mem(info.totalMem, info.availMem, info.lowMemory, pss)
    }

    private fun clearWebView(webView: WebView?) {
        if (webView == null) return
        val clear = Runnable {
            try {
                webView.clearCache(true)
                @Suppress("DEPRECATION")
                webView.freeMemory()
            } catch (_: Throwable) {
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clear.run()
            return
        }
        val latch = CountDownLatch(1)
        webView.post {
            clear.run()
            latch.countDown()
        }
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Hanya cacheDir / externalCache. Folder model di files tidak disentuh. */
    private fun wipeReclaimableCache(context: Context): Long {
        var freed = 0L
        freed += wipeChildren(context.cacheDir)
        freed += wipeChildren(context.externalCacheDir)
        val app = context.applicationContext
        if (app !== context && app !is Activity) {
            freed += wipeChildren(app.cacheDir)
        }
        return freed
    }

    private fun wipeChildren(dir: File?): Long {
        if (dir == null || !dir.isDirectory) return 0L
        var freed = 0L
        val children = dir.listFiles() ?: return 0L
        for (child in children) {
            val name = child.name.lowercase(Locale.ROOT)
            if (name.endsWith(".gguf") || name.endsWith(".part") || name.contains("gguf")) continue
            val size = bytesOf(child)
            if (child.deleteRecursively()) freed += size
        }
        return freed
    }

    private fun bytesOf(file: File): Long {
        if (file.isFile) return file.length()
        val kids = file.listFiles() ?: return 0L
        var sum = 0L
        for (kid in kids) sum += bytesOf(kid)
        return sum
    }

    private fun fmt(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(Locale.US, "%.2f GB", mb / 1024.0)
        else String.format(Locale.US, "%.0f MB", mb)
    }
}
