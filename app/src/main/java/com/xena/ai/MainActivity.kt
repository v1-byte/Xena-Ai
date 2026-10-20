package com.xena.ai

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {
    private val TAG = "XENA-Main"
    private val PICK_MODEL_REQUEST = 9421
    private lateinit var statusText: TextView
    private lateinit var topStatus: TextView
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var webView: WebView
    @Volatile private var stopRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.txtModelStatus)
        topStatus = findViewById(R.id.topStatus)
        bottomNav = findViewById(R.id.bottom_navigation)
        webView = findViewById(R.id.webview)

        AppPrefs.getSafeSystemPrompt(this)
        Log.d(TAG, "XENA Safe prompt ready")

        setupWebView()
        setupBottomNav()
        // UI v1.2 has its own tab bar; hide native bottom nav visual clutter
        bottomNav.visibility = android.view.View.GONE
        autoLoadModelIfExists(notifyUi = false)
        // Cek update saat startup; pemasangan tetap dikonfirmasi Android.
        webView.postDelayed({
            UpdateChecker.checkAndUpdate(this@MainActivity, force = false, silent = true)
        }, 2500)
    }

    fun autoLoadModelIfExists(notifyUi: Boolean = true) {
        val modelFile = ModelAutoLoader.ensurePrivateModel(this)
        if (modelFile != null) {
            loadModelFile(modelFile, notifyUi)
        } else {
            statusText.text = "Model belum ada. Unduh model atau pilih file GGUF."
            topStatus.text = "No Model"
            if (notifyUi) notifyModelReady(false)
        }
    }

    private fun loadModelFile(modelFile: File, notifyUi: Boolean) {
        val size = ModelAutoLoader.getModelSizeMB(this, modelFile)
        statusText.text = "Memuat model ${modelFile.name} ($size)…"
        topStatus.text = "Loading XENA…"
        AppPrefs.saveLastModelPath(this, modelFile.absolutePath)
        Log.d(TAG, "XENA loading model: ${modelFile.absolutePath}")
        Thread {
            try {
                val success = LlamaBridge.loadModel(
                    modelFile.absolutePath,
                    AppPrefs.getNctx(this),
                    AppPrefs.getNBatch(this),
                    AppPrefs.getNThreads(this),
                    true,
                    false
                )
                runOnUiThread {
                    if (success) {
                        statusText.text = "✅ XENA-AI Model siap — ${modelFile.name} ($size)"
                        topStatus.text = "Ready — XENA"
                    } else {
                        statusText.text = "❌ Model gagal dimuat: ${LlamaBridge.lastError}"
                        topStatus.text = "Error"
                    }
                    if (notifyUi) notifyModelReady(success)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "XENA Error: ${e.message}"
                    topStatus.text = "Error"
                    if (notifyUi) notifyModelReady(false)
                }
            }
        }.start()
    }

    private fun openModelPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        try {
            startActivityForResult(intent, PICK_MODEL_REQUEST)
        } catch (e: Exception) {
            Toast.makeText(this, "Pemilih file tidak tersedia: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_MODEL_REQUEST || resultCode != RESULT_OK) return
        val resultData = data ?: return
        val uri = resultData.data ?: return
        val flags = resultData.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            // Some document providers grant a temporary permission only.
        }
        Toast.makeText(this, "Menyalin model ke folder aplikasi…", Toast.LENGTH_LONG).show()
        Thread {
            try {
                val file = ModelAutoLoader.importModelFromUri(this, uri) { copied, total ->
                    val percent = if (total > 0L) ((copied * 100L) / total).toInt().coerceIn(0, 99) else 0
                    runOnUiThread {
                        evalJs(
                            "if(typeof window.xenaModelImportProgress==='function'){window.xenaModelImportProgress($percent)}"
                        )
                    }
                }
                runOnUiThread {
                    evalJs(
                        "if(typeof window.xenaModelImportFinished==='function'){window.xenaModelImportFinished(${JSONObject.quote(file.name)})}"
                    )
                    loadModelFile(file, notifyUi = true)
                    AlertDialog.Builder(this)
                        .setTitle("Model berhasil diimpor")
                        .setMessage("Model sudah disimpan di folder aplikasi. Hapus file sumber dari Downloads untuk menghemat ruang?")
                        .setNegativeButton("Simpan file sumber", null)
                        .setPositiveButton("Hapus sumber") { _, _ ->
                            val deleted = try {
                                DocumentsContract.deleteDocument(contentResolver, uri)
                            } catch (_: Exception) {
                                false
                            }
                            Toast.makeText(
                                this,
                                if (deleted) "File sumber dihapus" else "Izin hapus tidak tersedia; hapus file sumber lewat Files",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        .show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "import model failed: ${e.message}")
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Gagal mengimpor model: ${e.message ?: "kesalahan file"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun downloadRecommendedModel(id: String) {
        val started = ModelAutoLoader.startDownload(
            this,
            id,
            onProgress = { percent, speed ->
                val safeId = JSONObject.quote(id)
                runOnUiThread {
                    evalJs(
                        "if(typeof window.xenaModelDownloadProgress==='function'){window.xenaModelDownloadProgress($safeId,$percent,$speed)}"
                    )
                }
            },
            onComplete = { success, message ->
                val safeId = JSONObject.quote(id)
                val safeMessage = JSONObject.quote(message)
                runOnUiThread {
                    evalJs(
                        "if(typeof window.xenaModelDownloadComplete==='function'){window.xenaModelDownloadComplete($safeId,$success,$safeMessage)}"
                    )
                }
            }
        )
        if (!started) {
            evalJs(
                "if(typeof window.xenaModelDownloadComplete==='function'){window.xenaModelDownloadComplete(${JSONObject.quote(id)},false,'Unduhan model sedang berjalan atau tidak tersedia')}"
            )
        }
    }

    private fun useRecommendedModel(id: String) {
        val file = ModelAutoLoader.findModel(this, id)
        if (file == null) {
            Toast.makeText(this, "Model belum diunduh", Toast.LENGTH_SHORT).show()
            return
        }
        loadModelFile(file, notifyUi = true)
    }

    private fun notifyModelReady(ok: Boolean) {
        try {
            webView.evaluateJavascript(
                "if(typeof window.xenaModelReady==='function'){window.xenaModelReady(${ok})}",
                null
            )
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        UpdateChecker.resumePendingInstall(this)
        // Cek update tiap kali app kembali aktif (throttle di UpdateChecker)
        webView.postDelayed({
            if (!isFinishing) {
                UpdateChecker.checkAndUpdate(this@MainActivity, force = false, silent = true)
            }
        }, 1500)
    }

    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { true }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        // UI v1.2 expects window.Android
        webView.addJavascriptInterface(AndroidBridge(this), "Android")
        // Keep legacy name too
        webView.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")
    }


    fun evalJs(js: String) {
        try {
            webView.evaluateJavascript(js, null)
        } catch (_: Exception) {
        }
    }

    class AndroidBridge(private val activity: MainActivity) {
        private val context get() = activity

        @android.webkit.JavascriptInterface
        fun chat(prompt: String, paramsJson: String) {
            activity.stopRequested = false
            Thread {
                try {
                    // Auto-load engine if file exists but native not ready
                    if (!LlamaBridge.isReady()) {
                        val f = ModelAutoLoader.ensurePrivateModel(context)
                        if (f != null) {
                            LlamaBridge.loadModel(
                                f.absolutePath,
                                AppPrefs.getNctx(context),
                                AppPrefs.getNBatch(context),
                                AppPrefs.getNThreads(context),
                                true,
                                false
                            )
                        }
                    }
                    var temp = AppPrefs.getTemperature(context)
                    try {
                        val o = JSONObject(paramsJson.ifBlank { "{}" })
                        if (o.has("temperature")) temp = o.getDouble("temperature").toFloat()
                        if (o.has("temp")) temp = o.getDouble("temp").toFloat()
                    } catch (_: Exception) {
                    }
                    val safePrompt = AppPrefs.getSafeSystemPrompt(context)
                    val userMsg = extractUserMessage(prompt)
                    val out = LlamaBridge.generate(userMsg, safePrompt, temp)
                    if (activity.stopRequested) {
                        activity.runOnUiThread {
                            activity.webView.evaluateJavascript(
                                "if(typeof window.xenaDone==='function'){window.xenaDone()}",
                                null
                            )
                        }
                        return@Thread
                    }
                    // Stream-ish: send whole answer as tokens in chunks for UI
                    val chunk = 24
                    var i = 0
                    while (i < out.length && !activity.stopRequested) {
                        val end = minOf(i + chunk, out.length)
                        val part = JSONObject.quote(out.substring(i, end))
                        activity.runOnUiThread {
                            activity.webView.evaluateJavascript(
                                "if(typeof window.xenaToken==='function'){window.xenaToken($part)}",
                                null
                            )
                        }
                        i = end
                        try { Thread.sleep(12) } catch (_: Exception) {}
                    }
                    activity.runOnUiThread {
                        activity.webView.evaluateJavascript(
                            "if(typeof window.xenaDone==='function'){window.xenaDone()}",
                            null
                        )
                    }
                } catch (e: Exception) {
                    val err = JSONObject.quote("[XENA Error: ${e.message}]")
                    activity.runOnUiThread {
                        activity.webView.evaluateJavascript(
                            "if(typeof window.xenaToken==='function'){window.xenaToken($err)};" +
                                "if(typeof window.xenaDone==='function'){window.xenaDone()}",
                            null
                        )
                    }
                }
            }.start()
        }

        @android.webkit.JavascriptInterface
        fun stop() {
            activity.stopRequested = true
        }

        @android.webkit.JavascriptInterface
        fun checkUpdate(force: Boolean) {
            activity.runOnUiThread {
                UpdateChecker.checkAndUpdate(activity, force = force, silent = false)
            }
        }

        @android.webkit.JavascriptInterface
        fun getAppVersion(): String {
            return UpdateChecker.currentVersionName(context)
        }

        @android.webkit.JavascriptInterface
        fun loadModel() {
            activity.runOnUiThread {
                activity.autoLoadModelIfExists(notifyUi = true)
            }
        }

        @android.webkit.JavascriptInterface
        fun getRecommendedModels(): String = ModelAutoLoader.recommendedModelsJson(context)

        @android.webkit.JavascriptInterface
        fun downloadModel(modelId: String) {
            activity.runOnUiThread { activity.downloadRecommendedModel(modelId) }
        }

        @android.webkit.JavascriptInterface
        fun cancelModelDownload(modelId: String) {
            activity.runOnUiThread {
                ModelAutoLoader.cancelDownload(context, modelId)
            }
        }

        @android.webkit.JavascriptInterface
        fun useModel(modelId: String) {
            activity.runOnUiThread { activity.useRecommendedModel(modelId) }
        }

        @android.webkit.JavascriptInterface
        fun pickModelFile() {
            activity.runOnUiThread { activity.openModelPicker() }
        }

        @android.webkit.JavascriptInterface
        fun storageInfo(): String {
            return try {
                val storageRoot = activity.getExternalFilesDir(null) ?: activity.filesDir
                val free = storageRoot.usableSpace.toDouble() / (1024 * 1024 * 1024)
                val total = storageRoot.totalSpace.toDouble() / (1024 * 1024 * 1024)
                val used = (total - free).coerceAtLeast(0.0)
                JSONObject()
                    .put("used", String.format(java.util.Locale.US, "%.2f", used).toDouble())
                    .put("total", String.format(java.util.Locale.US, "%.2f", total).toDouble())
                    .toString()
            } catch (e: Exception) {
                """{"used":0,"total":10}"""
            }
        }

        // ---- legacy / connectors ----
        @android.webkit.JavascriptInterface
        fun sendPrompt(fullPrompt: String, temp: Float, n_ctx: Int): String {
            val safePrompt = AppPrefs.getSafeSystemPrompt(context)
            val userMsg = extractUserMessage(fullPrompt)
            val t = if (temp > 0f) temp else AppPrefs.getTemperature(context)
            return LlamaBridge.generate(userMsg, safePrompt, t)
        }

        @android.webkit.JavascriptInterface
        fun chatSync(userMessage: String): String {
            val safePrompt = AppPrefs.getSafeSystemPrompt(context)
            return LlamaBridge.generate(userMessage, safePrompt, AppPrefs.getTemperature(context))
        }

        @android.webkit.JavascriptInterface
        fun saveSystemPrompt(prompt: String) {
            AppPrefs.saveSystemPrompt(context, prompt)
        }

        @android.webkit.JavascriptInterface
        fun getSystemPrompt(): String = AppPrefs.getSafeSystemPrompt(context)

        @android.webkit.JavascriptInterface
        fun saveConfig(n_ctx: Int, n_batch: Int, n_threads: Int) {
            AppPrefs.saveConfig(context, n_ctx, n_batch, n_threads)
        }

        @android.webkit.JavascriptInterface
        fun getConfig(): String {
            return """{"n_ctx":${AppPrefs.getNctx(context)},"n_batch":${AppPrefs.getNBatch(context)},"n_threads":${AppPrefs.getNThreads(context)},"temperature":${AppPrefs.getTemperature(context)}}"""
        }

        @android.webkit.JavascriptInterface
        fun autoLoadModel() {
            activity.runOnUiThread { activity.autoLoadModelIfExists(notifyUi = true) }
        }

        @android.webkit.JavascriptInterface
        fun loadModelWithConfig(n_ctx: Int, n_batch: Int, n_threads: Int): Boolean {
            AppPrefs.saveConfig(context, n_ctx, n_batch, n_threads)
            val f = ModelAutoLoader.ensurePrivateModel(context) ?: return false
            return LlamaBridge.loadModel(f.absolutePath, n_ctx, n_batch, n_threads, true, false)
        }

        @android.webkit.JavascriptInterface
        fun isModelReady(): Boolean = LlamaBridge.isReady()

        @android.webkit.JavascriptInterface
        fun getLastError(): String = LlamaBridge.lastError

        @android.webkit.JavascriptInterface
        fun getModelInfo(): String {
            val size = ModelAutoLoader.getModelSizeMB(context)
            val path = AppPrefs.getLastModelPath(context) ?: ""
            val ready = LlamaBridge.isReady()
            return """{"ready":$ready,"size":"$size","path":"$path"}"""
        }

        @android.webkit.JavascriptInterface
        fun saveConnectorToken(id: String, token: String, accountId: String) {
            Connectors.saveToken(context, id, token, if (accountId.isBlank()) null else accountId)
        }

        @android.webkit.JavascriptInterface
        fun getConnectorToken(id: String): String = Connectors.getToken(context, id) ?: ""

        @android.webkit.JavascriptInterface
        fun getConnectorAccountId(id: String): String = Connectors.getAccountId(context, id) ?: ""

        @android.webkit.JavascriptInterface
        fun isConnectorConnected(id: String): Boolean = Connectors.isConnected(context, id)

        @android.webkit.JavascriptInterface
        fun getConnectedConnectors(): String =
            Connectors.getConnectedList(context).joinToString(",")

        @android.webkit.JavascriptInterface
        fun clearConnectorToken(id: String) {
            Connectors.clearToken(context, id)
        }

        private fun extractUserMessage(fullPrompt: String): String {
            val s = fullPrompt.indexOf("<|im_start|>user")
            val e = fullPrompt.indexOf("<|im_end|>", s)
            return if (s != -1 && e != -1) {
                fullPrompt.substring(s + "<|im_start|>user".length, e).trim()
            } else fullPrompt.trim()
        }
    }
}
