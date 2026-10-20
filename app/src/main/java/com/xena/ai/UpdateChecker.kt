package com.xena.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cek GitHub Releases dan unduh APK terbaru.
 * Android tidak mengizinkan install diam-diam tanpa privilege sistem —
 * setelah unduh, user tinggal konfirmasi dialog install (1 ketukan).
 */
object UpdateChecker {
    private const val TAG = "XENA-Update"
    private const val REPO = "v1-byte/Xena-Ai"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREF = "xena_update"

    data class ReleaseInfo(
        val tag: String,
        val name: String,
        val body: String,
        val apkUrl: String,
        val apkName: String,
        val versionCodeHint: Int
    )

    fun currentVersionName(context: Context): String {
        return try {
            val p = context.packageManager.getPackageInfo(context.packageName, 0)
            p.versionName ?: "0"
        } catch (_: Exception) {
            "0"
        }
    }

    fun currentVersionCode(context: Context): Long {
        return try {
            val p = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
        } catch (_: Exception) {
            0L
        }
    }

    /** Bandingkan semver sederhana: 1.2.3 > 1.2.2 */
    fun isNewer(remoteTag: String, localName: String): Boolean {
        fun parse(v: String): List<Int> =
            v.trim().removePrefix("v").split(Regex("[^0-9]+"))
                .filter { it.isNotEmpty() }
                .mapNotNull { it.toIntOrNull() }
        val r = parse(remoteTag)
        val l = parse(localName)
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a > b) return true
            if (a < b) return false
        }
        return false
    }

    fun fetchLatest(): ReleaseInfo? {
        return try {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Xena-AI-Updater")
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            val name = json.optString("name", tag)
            val notes = json.optString("body", "")
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl = ""
            var apkName = "xena-update.apk"
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val n = a.optString("name", "")
                if (n.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url", "")
                    apkName = n
                    break
                }
            }
            if (apkUrl.isBlank() || tag.isBlank()) return null
            val codeHint = tag.removePrefix("v").replace(".", "").toIntOrNull() ?: 0
            ReleaseInfo(tag, name, notes, apkUrl, apkName, codeHint)
        } catch (e: Exception) {
            Log.e(TAG, "fetchLatest: ${e.message}")
            null
        }
    }

    fun shouldCheck(context: Context, minIntervalMs: Long = 6 * 60 * 60 * 1000L): Boolean {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val last = prefs.getLong("last_check", 0L)
        return System.currentTimeMillis() - last >= minIntervalMs
    }

    fun markChecked(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putLong("last_check", System.currentTimeMillis()).apply()
    }

    fun downloadApk(context: Context, info: ReleaseInfo, onProgress: ((Int) -> Unit)? = null): File? {
        return try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, info.apkName)
            if (out.exists()) out.delete()
            val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 120000
                setRequestProperty("User-Agent", "Xena-AI-Updater")
                instanceFollowRedirects = true
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0 && onProgress != null) {
                            onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                    output.fd.sync()
                }
            }
            conn.disconnect()
            if (out.exists() && out.length() > 100_000L) out else null
        } catch (e: Exception) {
            Log.e(TAG, "download: ${e.message}")
            null
        }
    }

    fun canRequestInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    fun openInstallPermissionSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        }
    }

    fun installApk(activity: Activity, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "install: ${e.message}")
            activity.runOnUiThread {
                Toast.makeText(activity, "Gagal buka installer: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Cek update di background. Jika ada, unduh lalu tampilkan dialog install.
     * @param silent Jika true, hanya toast saat ada update / selesai unduh.
     */
    fun checkAndUpdate(activity: Activity, force: Boolean = false, silent: Boolean = true) {
        Thread {
            try {
                if (!force && !shouldCheck(activity)) return@Thread
                markChecked(activity)
                val local = currentVersionName(activity)
                val info = fetchLatest()
                if (info == null) {
                    Log.d(TAG, "no release info")
                    return@Thread
                }
                if (!isNewer(info.tag, local) && !force) {
                    Log.d(TAG, "up to date local=$local remote=${info.tag}")
                    if (!silent) {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Sudah versi terbaru ($local)", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@Thread
                }
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        "Update ${info.tag} tersedia — mengunduh…",
                        Toast.LENGTH_LONG
                    ).show()
                    try {
                        activity.webViewEval(
                            "if(typeof window.xenaUpdateProgress==='function'){window.xenaUpdateProgress(0,'Mengunduh ${info.tag}')}"
                        )
                    } catch (_: Exception) {
                    }
                }
                val file = downloadApk(activity, info) { pct ->
                    if (pct % 20 == 0) {
                        activity.runOnUiThread {
                            try {
                                activity.webViewEval(
                                    "if(typeof window.xenaUpdateProgress==='function'){window.xenaUpdateProgress($pct,'Mengunduh $pct%')}"
                                )
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
                if (file == null) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Gagal unduh update", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                activity.runOnUiThread {
                    Toast.makeText(activity, "Unduhan selesai — pasang update", Toast.LENGTH_LONG).show()
                    if (!canRequestInstall(activity)) {
                        Toast.makeText(
                            activity,
                            "Izinkan install dari sumber ini di Settings",
                            Toast.LENGTH_LONG
                        ).show()
                        openInstallPermissionSettings(activity)
                    }
                    installApk(activity, file)
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkAndUpdate: ${e.message}")
            }
        }.start()
    }

    /** Helper: MainActivity exposes webView */
    private fun Activity.webViewEval(js: String) {
        if (this is MainActivity) {
            this.evalJs(js)
        }
    }
}
