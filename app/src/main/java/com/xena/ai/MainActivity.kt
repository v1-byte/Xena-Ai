package com.xena.ai

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.os.SystemClock
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.provider.DocumentsContract
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.json.JSONArray
import org.json.JSONObject
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {
    private val TAG = "XENA-Main"
    private lateinit var statusText: TextView
    private lateinit var topStatus: TextView
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var webView: WebView
    private lateinit var modelDownloads: ModelDownloadManager
    private val modelOperationLock = Any()
    @Volatile private var modelLoadInProgress = false
    private val chatSequence = AtomicLong(0L)
    @Volatile private var activeChatId = 0L
    private val chatExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xena-chat").apply { isDaemon = true }
    }
    private var firstResume = true
    @Volatile private var deletePickedSourceAfterImport = false
    @Volatile private var lastAutoPurgeAt = 0L
    @Volatile private var appliedTuneLabel = ""
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val moveSource = deletePickedSourceAfterImport
        deletePickedSourceAfterImport = false
        if (uri != null) importSelectedModel(uri, moveSource)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.txtModelStatus)
        topStatus = findViewById(R.id.topStatus)
        bottomNav = findViewById(R.id.bottom_navigation)
        webView = findViewById(R.id.webview)
        modelDownloads = ModelDownloadManager(this)
        modelDownloads.setListener { snapshot ->
            runOnUiThread { publishTransfer(snapshot) }
        }

        AppPrefs.getSafeSystemPrompt(this)
        AppPrefs.clearLegacyHfConfig(this)
        Log.d(TAG, "XENA Safe prompt ready serverMode=${AppPrefs.isServerMode(this)} groqKey=${AppPrefs.hasGroqApiKey(this)}")

        setupWebView()
        setupBottomNav()
        // UI v1.2 has its own tab bar; hide native bottom nav visual clutter
        bottomNav.visibility = android.view.View.GONE
        prepareModelIfExists()
        // Auto-update dari GitHub Releases (unduh + dialog install)
        webView.postDelayed({
            // force hanya melewati throttle; versi yang sama tidak pernah diunduh ulang.
            UpdateChecker.checkAndUpdate(this@MainActivity, force = true, silent = true)
        }, 2500)
    }

    /** Server mode: skip GGUF. Local mode: migrate/discover without auto-loading weights. */
    private fun prepareModelIfExists() {
        if (AppPrefs.isServerMode(this)) {
            if (AppPrefs.hasGroqApiKey(this)) {
                statusText.text = "Server Mode — Groq siap (tanpa GGUF lokal)"
                topStatus.text = "Server Mode"
            } else {
                statusText.text = "Server Mode ON — simpan Groq API key di Atur"
                topStatus.text = "Set Groq Key"
            }
            return
        }
        statusText.text = "Memeriksa model GGUF…"
        topStatus.text = "XENA model…"
        Thread {
            try {
                val modelFile = synchronized(modelOperationLock) {
                    ModelAutoLoader.ensurePrivateModel(this) { bytes, total ->
                        publishTransfer(
                            ModelDownloadManager.Snapshot(
                                "moving", "", "Model dari Download", bytes, total.coerceAtLeast(0L), 0L,
                                "Memindahkan model ke penyimpanan aplikasi…"
                            )
                        )
                    }
                }
                runOnUiThread {
                    if (modelFile == null) {
                        statusText.text = "Belum ada model. Pilih GGUF dari Download atau unduh katalog."
                        topStatus.text = "Pilih model"
                    } else {
                        statusText.text = "Model tersedia — ${modelFile.name}. Tekan Muat Model untuk memuat ke RAM."
                        topStatus.text = "Model tersedia"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Model preparation failed: ${e.message}", e)
                runOnUiThread {
                    statusText.text = "Model belum siap: ${e.message}"
                    topStatus.text = "Error model"
                }
            }
        }.start()
    }

    fun autoLoadModelIfExists(notifyUi: Boolean = true) {
        statusText.text = "Memeriksa model GGUF…"
        topStatus.text = "XENA model…"
        Thread {
            try {
                val modelFile = synchronized(modelOperationLock) {
                    ModelAutoLoader.ensurePrivateModel(this) { bytes, total ->
                        val totalValue = total.coerceAtLeast(0L)
                        publishTransfer(
                            ModelDownloadManager.Snapshot(
                                "moving", "", "Model dari Download", bytes, totalValue, 0L,
                                "Memindahkan model ke penyimpanan aplikasi…"
                            )
                        )
                    }
                }
                if (modelFile == null) {
                    runOnUiThread {
                        statusText.text = "Belum ada model. Pilih GGUF dari Download atau unduh katalog."
                        topStatus.text = "Pilih model"
                        if (notifyUi) notifyModelReady(false)
                    }
                    return@Thread
                }
                loadModelFile(modelFile, notifyUi)
            } catch (e: Exception) {
                Log.e(TAG, "Auto model preparation failed: ${e.message}", e)
                runOnUiThread {
                    statusText.text = "Model belum siap: ${e.message}"
                    topStatus.text = "Error model"
                    if (notifyUi) notifyModelReady(false)
                }
            }
        }.start()
    }

    private fun loadModelFile(modelFile: File, notifyUi: Boolean, selectedCallback: Boolean = false) {
        val memoryWarning = modelLoadMemoryWarning(modelFile)
        if (memoryWarning != null) {
            publishTransfer(
                ModelDownloadManager.Snapshot(
                    "failed", ModelCatalog.byFileName(modelFile.name)?.id.orEmpty(), modelFile.name,
                    modelFile.length(), modelFile.length(), 0L, memoryWarning
                )
            )
            runOnUiThread {
                statusText.text = memoryWarning
                topStatus.text = "RAM tidak cukup"
                Toast.makeText(this, memoryWarning, Toast.LENGTH_LONG).show()
                if (selectedCallback) notifySelectedModel(false, modelFile.name, memoryWarning)
                if (notifyUi) notifyModelReady(false)
            }
            return
        }
        synchronized(modelOperationLock) {
            if (modelLoadInProgress) {
                if (selectedCallback) notifySelectedModel(false, modelFile.name, "Pemuatan model lain masih berlangsung")
                return
            }
            modelLoadInProgress = true
        }
        runOnUiThread {
            statusText.text = "Memuat ${modelFile.name} ke RAM…"
            topStatus.text = "Loading XENA…"
        }
        Thread {
            var success = false
            var error = ""
            var profileNote = ""
            try {
                success = tunedLoad(modelFile)
                error = if (success) "ok" else LlamaBridge.lastError
                profileNote = if (success && appliedTuneLabel.isNotBlank()) " · $appliedTuneLabel" else ""
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "Model load failed: $error", e)
            } finally {
                synchronized(modelOperationLock) { modelLoadInProgress = false }
            }
            val loaded = success
            val loadError = error
            runOnUiThread {
                statusText.text = if (loaded) "Model siap — ${modelFile.name} (${ModelAutoLoader.getModelSizeMB(modelFile)})$profileNote" else "Gagal memuat model: $loadError"
                topStatus.text = if (loaded) "Ready — XENA" else "Error model"
                if (selectedCallback) notifySelectedModel(loaded, modelFile.name, loadError)
                if (notifyUi) notifyModelReady(loaded)
                if (loaded) publishTransfer(
                    ModelDownloadManager.Snapshot("ready", ModelCatalog.byFileName(modelFile.name)?.id.orEmpty(), modelFile.name, modelFile.length(), modelFile.length(), 0L, "Model siap digunakan")
                )
            }
        }.start()
    }

    /** Keep the low-memory killer from terminating the app while loading large GGUF weights. */
    private fun modelLoadMemoryWarning(modelFile: File): String? {
        val manager = getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return null
        val memory = android.app.ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        val gib = 1024L * 1024L * 1024L
        val modelBytes = modelFile.length()
        if (memory.totalMem <= 6L * gib + gib / 2L && modelBytes >= 3L * gib) {
            return "Model 7B ini terlalu berat untuk perangkat dengan RAM terbatas; pemuatan dihentikan agar aplikasi tidak tertutup. Coba model yang lebih kecil."
        }
        val requiredBytes = modelBytes + gib
        if (memory.availMem < requiredBytes) {
            val required = String.format(java.util.Locale.US, "%.1f", requiredBytes / 1073741824.0)
            val available = String.format(java.util.Locale.US, "%.1f", memory.availMem / 1073741824.0)
            return "RAM bebas belum cukup: perlu sekitar ${required} GB, tersedia ${available} GB. Tutup aplikasi lain atau gunakan model lebih kecil."
        }
        if (memory.lowMemory && modelBytes >= 1_500_000_000L) {
            return "RAM perangkat sedang mepet. Model besar tidak dimuat agar aplikasi tetap ringan. Pakai model 1.5B atau nyalakan Server Mode Groq."
        }
        if (memory.availMem < 900L * 1024L * 1024L && modelBytes >= 700L * 1024L * 1024L) {
            return "RAM bebas di bawah 900 MB. Pemuatan ditahan supaya aplikasi tidak tertutup. Bersihkan RAM atau tutup aplikasi lain."
        }
        return null
    }

    /** Context/batch/thread diturunkan otomatis saat RAM mepet. mlock tetap mati. */
    private fun tunedLoad(modelFile: File): Boolean {
        val tune = RamGuard.tune(this, modelFile.length())
        appliedTuneLabel = tune.label
        val ok = synchronized(modelOperationLock) {
            LlamaBridge.loadModel(
                modelFile.absolutePath,
                tune.nCtx,
                tune.nBatch,
                tune.nThreads,
                true,
                false
            )
        }
        if (ok) AppPrefs.saveLastModelPath(this, modelFile.absolutePath)
        return ok
    }

    fun useInstalledModel(modelIdOrName: String): Boolean {
        val modelFile = ModelAutoLoader.modelFileForId(this, modelIdOrName)
        if (modelFile == null) {
            notifySelectedModel(false, modelIdOrName, "Model GGUF tidak ditemukan atau tidak valid")
            return false
        }
        if (LlamaBridge.isReady() && AppPrefs.getLastModelPath(this) == modelFile.absolutePath) {
            notifySelectedModel(true, modelFile.name, "Model sudah aktif")
            return true
        }
        publishTransfer(ModelDownloadManager.Snapshot("loading", modelIdOrName, modelFile.name, modelFile.length(), modelFile.length(), 0L, "Memuat model ke engine…"))
        loadModelFile(modelFile, notifyUi = false, selectedCallback = true)
        return true
    }

    fun moveModelFromDownloads() {
        Thread {
            val moved = ModelAutoLoader.moveDownloadedModel(this) { bytes, total ->
                publishTransfer(
                    ModelDownloadManager.Snapshot(
                        "moving", "", "Model dari Download", bytes, total.coerceAtLeast(0L), 0L,
                        "Memindahkan model ke penyimpanan aplikasi…"
                    )
                )
            }
            runOnUiThread {
                if (moved != null) {
                    AppPrefs.saveLastModelPath(this, moved.absolutePath)
                    loadModelFile(moved, notifyUi = true)
                } else {
                    Toast.makeText(this, "Pilih file .gguf dari Download. Android dapat membatasi akses langsung ke folder Download.", Toast.LENGTH_LONG).show()
                    deletePickedSourceAfterImport = true
                    modelPicker.launch(arrayOf("*/*"))
                }
            }
        }.start()
    }

    private fun importSelectedModel(uri: Uri, moveSource: Boolean) {
        Thread {
            val imported = ModelAutoLoader.importFromUri(this, uri) { bytes, total ->
                publishTransfer(
                    ModelDownloadManager.Snapshot(
                        if (moveSource) "moving" else "importing", "", "Model pilihan", bytes, total.coerceAtLeast(0L), 0L,
                        if (moveSource) "Memindahkan dan memvalidasi file GGUF…" else "Mengimpor dan memvalidasi file GGUF…"
                    )
                )
            }
            val sourceDeleted = if (imported != null && moveSource) {
                try { DocumentsContract.deleteDocument(contentResolver, uri) } catch (e: Exception) {
                    Log.w(TAG, "Could not remove verified SAF source: ${e.message}")
                    false
                }
            } else false
            runOnUiThread {
                if (imported == null) {
                    publishTransfer(ModelDownloadManager.Snapshot("failed", "", "", 0L, 0L, 0L, "Impor gagal: file harus GGUF valid dan ruang penyimpanan harus cukup"))
                    Toast.makeText(this, "Impor gagal. Pastikan file GGUF valid dan ruang kosong cukup.", Toast.LENGTH_LONG).show()
                } else {
                    evalJs("if(typeof window.xenaModelImported==='function'){window.xenaModelImported(${JSONObject.quote(imported.name)})}")
                    if (moveSource) {
                        val message = if (sourceDeleted) "Model dipindahkan dan divalidasi di penyimpanan aplikasi."
                            else "Model tersimpan dan valid; sumber Download tidak dapat dihapus otomatis."
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                    loadModelFile(imported, notifyUi = true)
                }
            }
        }.start()
    }

    private fun notifySelectedModel(ok: Boolean, name: String, error: String) {
        evalJs("if(typeof window.xenaSelectedModelReady==='function'){window.xenaSelectedModelReady($ok,${JSONObject.quote(name)},${JSONObject.quote(error)})}")
    }

    private fun publishTransfer(snapshot: ModelDownloadManager.Snapshot) {
        evalJs("if(typeof window.xenaModelTransferState==='function'){window.xenaModelTransferState(${snapshot.toJson()})}")
    }

        private fun importModelPicker() {
        deletePickedSourceAfterImport = false
        modelPicker.launch(arrayOf("*/*"))
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoPurgeAt < 10_000L) return
        lastAutoPurgeAt = now
        activeChatId = chatSequence.incrementAndGet()
        Thread {
            val report = synchronized(modelOperationLock) {
                RamGuard.purge(applicationContext, if (::webView.isInitialized) webView else null)
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                statusText.text = report.message
                topStatus.text = "Mode ringan"
                notifyModelReady(false)
                evalJs("if(typeof window.xenaRamPurged==='function'){window.xenaRamPurged(${report.toJson()})}")
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
            return
        }
        // Jika sebelumnya diarahkan ke Settings, lanjutkan installer setelah izin aktif.
        UpdateChecker.resumePendingInstall(this)
        // Cek update tiap kali app kembali aktif (throttle di UpdateChecker)
        webView.postDelayed({
            if (!isFinishing) {
                UpdateChecker.checkAndUpdate(this@MainActivity, force = false, silent = true)
            }
        }, 1500)
    }

    override fun onDestroy() {
        activeChatId = chatSequence.incrementAndGet()
        chatExecutor.shutdownNow()
        if (::modelDownloads.isInitialized) {
            modelDownloads.pause()
            modelDownloads.setListener(null)
        }
        try { webView.removeJavascriptInterface("Android") } catch (_: Throwable) {}
        try { webView.removeJavascriptInterface("AndroidBridge") } catch (_: Throwable) {}
        try { webView.stopLoading(); webView.webChromeClient = null } catch (_: Throwable) {}
        super.onDestroy()
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
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                val uri = Uri.parse(url)
                val allowed = uri.scheme == "file" && uri.host == null &&
                    url.startsWith("file:///android_asset/")
                return !allowed
            }
        }
        webView.webChromeClient = WebChromeClient()
        // UI v1.2 expects window.Android
        webView.addJavascriptInterface(AndroidBridge(this), "Android")
        // Keep legacy name too
        webView.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")
    }


    fun evalJs(js: String) {
        try { runOnUiThread {
            try { if (!isFinishing && !isDestroyed) webView.evaluateJavascript(js, null) } catch (_: Throwable) {}
        } } catch (_: Throwable) {}
    }

    fun isChatActive(id: Long): Boolean = id != 0L && id == activeChatId && !isFinishing && !isDestroyed

    fun emitChatJs(js: String) = evalJs(js)

    fun safeChatError(error: Throwable): String = when {
        error.message?.contains("401") == true -> "Autentikasi provider gagal. Periksa API key."
        error.message?.contains("429") == true -> "Batas penggunaan provider tercapai. Coba lagi nanti."
        error.message?.contains("timeout", true) == true -> "Provider terlalu lama merespons. Coba lagi."
        error is OutOfMemoryError -> "RAM tidak cukup untuk model ini. Aktifkan mode ringan atau gunakan model lebih kecil."
        else -> "Mesin AI gagal memproses permintaan. Coba ulangi."
    }

    class AndroidBridge(private val activity: MainActivity) {
        private val context get() = activity

        @android.webkit.JavascriptInterface
        fun chat(prompt: String, paramsJson: String): Long {
            val chatId = activity.chatSequence.incrementAndGet()
            activity.activeChatId = chatId
            activity.chatExecutor.execute chatTask@{
                try {
                    var temp = AppPrefs.getTemperature(context)
                    var topP = 0.9f
                    var repeatPenalty = 1.1f
                    var maxTokens = 1024
                    try {
                        val o = JSONObject(paramsJson.ifBlank { "{}" })
                        if (o.has("temperature")) temp = o.getDouble("temperature").toFloat()
                        if (o.has("temp")) temp = o.getDouble("temp").toFloat()
                        if (o.has("top_p")) topP = o.getDouble("top_p").toFloat()
                        if (o.has("repeat_penalty")) repeatPenalty = o.getDouble("repeat_penalty").toFloat()
                        if (o.has("max_tokens")) maxTokens = o.getInt("max_tokens")
                    } catch (_: Exception) {
                    }
                    val safePrompt = AppPrefs.getSafeSystemPrompt(context)
                    val userMsg = extractUserMessage(prompt)
                    var streamedByProvider = false
                    val out = if (AppPrefs.isServerMode(context)) {
                        // Server mode: true SSE streaming; token tidak menunggu seluruh respons.
                        streamedByProvider = true
                        KonektorGroq.chatStreaming(userMsg, safePrompt, temp, AppPrefs.getGroqApiKey(context), topP, maxTokens) { token ->
                            if (activity.isChatActive(chatId)) {
                                val part = JSONObject.quote(token)
                                activity.emitChatJs("if(typeof window.xenaToken==='function'){window.xenaToken($chatId,$part)}")
                            }
                        }
                    } else synchronized(activity.modelOperationLock) {
                        // Local offline fallback
                        if (!LlamaBridge.isReady()) {
                            val f = ModelAutoLoader.ensurePrivateModel(context)
                            if (f != null) {
                                val memoryWarning = activity.modelLoadMemoryWarning(f)
                                if (memoryWarning != null) {
                                    activity.publishTransfer(
                                        ModelDownloadManager.Snapshot(
                                            "failed", ModelCatalog.byFileName(f.name)?.id.orEmpty(), f.name,
                                            f.length(), f.length(), 0L, memoryWarning
                                        )
                                    )
                                    return@synchronized "[XENA] $memoryWarning"
                                }
                                val loaded = activity.tunedLoad(f)
                                if (!loaded) {
                                    return@synchronized "[XENA] ${LlamaBridge.lastError}"
                                }
                            }
                        }
                        LlamaBridge.generate(userMsg, safePrompt, temp, topP, repeatPenalty)
                    }
                    if (!activity.isChatActive(chatId)) {
                        return@chatTask
                    }
                    if (!streamedByProvider) {
                        // Local JNI fallback: emit bounded chunks while native generation is synchronous.
                        val chunk = 24
                        var i = 0
                        while (i < out.length && activity.isChatActive(chatId)) {
                            val end = minOf(i + chunk, out.length)
                            val part = JSONObject.quote(out.substring(i, end))
                            activity.emitChatJs("if(typeof window.xenaToken==='function'){window.xenaToken($chatId,$part)}")
                            i = end
                            try { Thread.sleep(12) } catch (_: Exception) {}
                        }
                    }
                    activity.emitChatJs("if(typeof window.xenaDone==='function'){window.xenaDone($chatId)}")
                } catch (e: Throwable) {
                    if (activity.isChatActive(chatId)) {
                        val msg = JSONObject.quote(activity.safeChatError(e))
                        activity.emitChatJs("if(typeof window.xenaError==='function'){window.xenaError($chatId,'engine',$msg,true)};if(typeof window.xenaDone==='function'){window.xenaDone($chatId)}")
                    }
                }
            }
            return chatId
        }

        @android.webkit.JavascriptInterface
        fun stop() {
            val oldId = activity.activeChatId
            activity.activeChatId = activity.chatSequence.incrementAndGet()
            if (oldId != 0L) {
                activity.emitChatJs("if(typeof window.xenaDone==='function'){window.xenaDone($oldId)}")
            }
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
                if (AppPrefs.isServerMode(context)) {
                    val ok = AppPrefs.hasGroqApiKey(context)
                    activity.statusText.text = if (ok)
                        "✅ Server Mode — Groq"
                    else
                        "⚠️ Simpan Groq API key di Atur (server mode)"
                    activity.topStatus.text = if (ok) "Groq Ready" else "Set Groq Key"
                    activity.notifyModelReady(ok)
                } else {
                    activity.autoLoadModelIfExists(notifyUi = true)
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun storageInfo(): String {
            return try {
                val dir = ModelAutoLoader.modelDirectory(activity)
                val freeBytes = dir.usableSpace
                val totalBytes = dir.totalSpace
                val modelBytes = ModelAutoLoader.usedModelBytes(activity)
                val gib = 1024.0 * 1024.0 * 1024.0
                JSONObject()
                    .put("used", String.format(java.util.Locale.US, "%.2f", modelBytes / gib).toDouble())
                    .put("total", String.format(java.util.Locale.US, "%.2f", totalBytes / gib).toDouble())
                    .put("free", String.format(java.util.Locale.US, "%.2f", freeBytes / gib).toDouble())
                    .put("modelUsedBytes", modelBytes)
                    .put("freeBytes", freeBytes)
                    .put("totalBytes", totalBytes)
                    .toString()
            } catch (e: Exception) {
                """{"used":0,"total":0,"free":0,"modelUsedBytes":0,"freeBytes":0,"totalBytes":0}"""
            }
        }

        // ---- legacy / connectors ----
        @android.webkit.JavascriptInterface
        fun sendPrompt(fullPrompt: String, temp: Float, n_ctx: Int): String {
            val safePrompt = AppPrefs.getSafeSystemPrompt(context)
            val userMsg = extractUserMessage(fullPrompt)
            val t = if (temp > 0f) temp else AppPrefs.getTemperature(context)
            if (AppPrefs.isServerMode(context)) {
                return KonektorGroq.chat(userMsg, safePrompt, t, AppPrefs.getGroqApiKey(context))
            }
            return LlamaBridge.generate(userMsg, safePrompt, t)
        }

        @android.webkit.JavascriptInterface
        fun chatSync(userMessage: String): String {
            val safePrompt = AppPrefs.getSafeSystemPrompt(context)
            val t = AppPrefs.getTemperature(context)
            if (AppPrefs.isServerMode(context)) {
                return KonektorGroq.chat(userMessage, safePrompt, t, AppPrefs.getGroqApiKey(context))
            }
            return LlamaBridge.generate(userMessage, safePrompt, t)
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
            activity.loadModelFile(f, notifyUi = false)
            return true
        }

        @android.webkit.JavascriptInterface
        fun isModelReady(): Boolean {
            return if (AppPrefs.isServerMode(context)) {
                AppPrefs.hasGroqApiKey(context)
            } else LlamaBridge.isReady()
        }

        @android.webkit.JavascriptInterface
        fun getLastError(): String {
            return if (AppPrefs.isServerMode(context)) {
                if (AppPrefs.hasGroqApiKey(context)) "ok (Groq mode)"
                else "Groq API key belum disimpan"
            } else LlamaBridge.lastError
        }

        @android.webkit.JavascriptInterface
        fun getModelInfo(): String {
            if (AppPrefs.isServerMode(context)) {
                val ready = AppPrefs.hasGroqApiKey(context)
                return JSONObject()
                    .put("ready", ready)
                    .put("size", "server")
                    .put("name", "Groq ${KonektorGroq.MODEL}")
                    .put("path", "Groq API")
                    .put("mode", "server")
                    .toString()
            }
            val file = ModelAutoLoader.findInstalledModel(context)
            val size = ModelAutoLoader.getModelSizeMB(file)
            val path = file?.absolutePath ?: ""
            val ready = LlamaBridge.isReady()
            return JSONObject().put("ready", ready).put("size", size)
                .put("name", file?.name.orEmpty()).put("path", path).put("mode", "local").toString()
        }

        @android.webkit.JavascriptInterface
        fun getModelCatalog(): String = ModelCatalog.toJson()

        @android.webkit.JavascriptInterface
        fun getInstalledModels(): String {
            val activePath = LlamaBridge.loadedPath()
            val engineReady = LlamaBridge.isReady()
            val models = JSONArray()
            ModelAutoLoader.installedModels(context).forEach { file ->
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

        @android.webkit.JavascriptInterface
        fun getModelTransferState(): String = activity.modelDownloads.currentSnapshot().toJson()

        @android.webkit.JavascriptInterface
        fun getLocalModelFiles(): String = activity.modelDownloads.localFilesJson()

        @android.webkit.JavascriptInterface
        fun rescanModelFiles() {
            ModelFileFinder.invalidate()
        }

        @android.webkit.JavascriptInterface
        fun startModelDownload(modelId: String): Boolean = activity.modelDownloads.start(modelId)

        @android.webkit.JavascriptInterface
        fun pauseModelDownload(): Boolean = activity.modelDownloads.pause()

        @android.webkit.JavascriptInterface
        fun resumeModelDownload(): Boolean = activity.modelDownloads.resume()

        @android.webkit.JavascriptInterface
        fun cancelModelDownload(): Boolean = activity.modelDownloads.cancel()

        @android.webkit.JavascriptInterface
        fun verifyModelDownload(modelId: String): Boolean = activity.modelDownloads.verifyDownload(modelId)

        /** Hapus sisa unduhan parsial/gagal + GGUF rusak. Return JSON ringkasan. */
        @android.webkit.JavascriptInterface
        fun cleanResiduals(): String = activity.modelDownloads.cleanResiduals()

        /**
         * Pembersih RAM: lepas bobot llama.cpp (bukan cuma System.gc), ukur PSS,
         * dan hapus cache yang bukan file model.
         */
        @android.webkit.JavascriptInterface
        fun cleanRam(): String {
            return try {
                val report = synchronized(activity.modelOperationLock) {
                    RamGuard.purge(activity, activity.webView)
                }
                activity.notifyModelReady(false)
                report.toJson()
            } catch (e: Exception) {
                JSONObject()
                    .put("unloaded", false)
                    .put("freedBytes", 0)
                    .put("message", "Gagal: ${e.message}")
                    .toString()
            }
        }

        @android.webkit.JavascriptInterface
        fun getRamStatus(): String = RamGuard.statusJson(context)

        @android.webkit.JavascriptInterface
        fun importFoundModel(path: String): Boolean {
            val cleaned = path.trim()
            if (cleaned.isEmpty()) return false
            Thread {
                val imported = if (cleaned.startsWith("content://")) {
                    val uri = Uri.parse(cleaned)
                    val host = uri.authority.orEmpty()
                    if (!host.contains("media") && !host.contains("downloads")) null
                    else ModelAutoLoader.importFromUri(activity, uri, null)
                } else {
                    val file = File(cleaned)
                    if (!file.isFile || !ModelFileFinder.isCandidate(file.name)) null
                    else ModelAutoLoader.moveFileToPrivate(activity, file, null)
                }
                activity.runOnUiThread {
                    if (imported == null) {
                        Toast.makeText(activity, "File model tidak bisa dipakai. Pastikan GGUF valid.", Toast.LENGTH_LONG).show()
                    } else {
                        ModelFileFinder.invalidate()
                        AppPrefs.saveLastModelPath(activity, imported.absolutePath)
                        activity.loadModelFile(imported, notifyUi = true)
                    }
                }
            }.start()
            return true
        }

        @android.webkit.JavascriptInterface
        fun useModel(modelIdOrName: String): Boolean = activity.useInstalledModel(modelIdOrName)

        @android.webkit.JavascriptInterface
        fun moveModelFromDownloads() {
            activity.moveModelFromDownloads()
        }

        @android.webkit.JavascriptInterface
        fun importModelFromDownloads() {
            activity.runOnUiThread { activity.importModelPicker() }
        }

        @android.webkit.JavascriptInterface
        fun saveConnectorToken(id: String, token: String, accountId: String) {
            Connectors.saveToken(context, id, token, if (accountId.isBlank()) null else accountId)
        }

        @android.webkit.JavascriptInterface
        fun testConnector(id: String): String = ConnectorBroker.test(context, id)

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


        @android.webkit.JavascriptInterface
        fun setServerMode(enabled: Boolean) {
            AppPrefs.setServerMode(context, enabled)
            activity.runOnUiThread {
                activity.statusText.text = if (enabled) "Server Mode: Groq" else "Local GGUF mode"
                activity.topStatus.text = if (enabled) "Server Mode" else "Local"
            }
        }

        @android.webkit.JavascriptInterface
        fun isServerMode(): Boolean = AppPrefs.isServerMode(context)

        @android.webkit.JavascriptInterface
        fun pingGroqServer(): Boolean {
            return KonektorGroq.ping(AppPrefs.getGroqApiKey(context))
        }

        @android.webkit.JavascriptInterface
        fun setGroqApiKey(apiKey: String) {
            AppPrefs.setGroqApiKey(context, apiKey)
        }

        @android.webkit.JavascriptInterface
        fun hasGroqApiKey(): Boolean = AppPrefs.hasGroqApiKey(context)

        @android.webkit.JavascriptInterface
        fun clearGroqApiKey() {
            AppPrefs.clearGroqApiKey(context)
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
