package com.xena.ai

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {
    private val TAG = "XENA-Main"
    private lateinit var statusText: TextView
    private lateinit var topStatus: TextView
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.txtModelStatus)
        topStatus = findViewById(R.id.topStatus)
        bottomNav = findViewById(R.id.bottom_navigation)
        webView = findViewById(R.id.webview)

        // Pastikan system prompt anti-error terisi
        AppPrefs.getSafeSystemPrompt(this)
        Log.d(TAG, "XENA Safe prompt ready")

        setupWebView()
        setupBottomNav()
        autoLoadModelIfExists()
    }

    fun autoLoadModelIfExists() {
        val modelFile = ModelAutoLoader.findInstalledModel(this)
        if (modelFile != null) {
            val size = ModelAutoLoader.getModelSizeMB(this)
            statusText.text = "Auto memuat XENA model $size…"
            topStatus.text = "Loading XENA…"
            AppPrefs.saveLastModelPath(this, modelFile.absolutePath)
            Log.d(TAG, "XENA Auto loading: ${modelFile.absolutePath}")
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
                            statusText.text =
                                "✅ XENA-AI Model siap — ${modelFile.name} ($size) — Auto mode aktif"
                            topStatus.text = "Ready — XENA"
                            bottomNav.selectedItemId = R.id.nav_chat
                            try {
                                webView.evaluateJavascript(
                                    "if(typeof setModelReady==='function'){setModelReady(true)}",
                                    null
                                )
                            } catch (_: Exception) {
                            }
                        } else {
                            statusText.text = "❌ XENA gagal auto load (native/lib?). Tap Model → Muat manual"
                            topStatus.text = "Error"
                            try {
                                webView.evaluateJavascript(
                                    "if(typeof setModelReady==='function'){setModelReady(false)}",
                                    null
                                )
                            } catch (_: Exception) {
                            }
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        statusText.text = "XENA Error auto: ${e.message}"
                        topStatus.text = "Error"
                    }
                }
            }.start()
        } else {
            statusText.text = "XENA Model belum ada. Tab Model → letakkan GGUF di Download / files app"
            topStatus.text = "No Model"
        }
    }

    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            val page = when (item.itemId) {
                R.id.nav_chat -> "chat"
                R.id.nav_model -> "model"
                R.id.nav_konektor -> "konektor"
                R.id.nav_settings -> "settings"
                else -> null
            }
            if (page != null) {
                webView.evaluateJavascript(
                    "if(typeof showPage==='function'){showPage('$page')}",
                    null
                )
                true
            } else false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")
    }

    class WebAppInterface(private val context: MainActivity) {
        @android.webkit.JavascriptInterface
        fun sendPrompt(fullPrompt: String, temp: Float, n_ctx: Int): String {
            val safePrompt = AppPrefs.getSafeSystemPrompt(context)
            val userMsg = extractUserMessage(fullPrompt)
            val t = if (temp > 0f) temp else AppPrefs.getTemperature(context)
            return LlamaBridge.generate(userMsg, safePrompt, t)
        }

        @android.webkit.JavascriptInterface
        fun chat(userMessage: String): String {
            val safePrompt = AppPrefs.getSafeSystemPrompt(context)
            val t = AppPrefs.getTemperature(context)
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
            context.runOnUiThread { context.autoLoadModelIfExists() }
        }

        @android.webkit.JavascriptInterface
        fun loadModelWithConfig(n_ctx: Int, n_batch: Int, n_threads: Int): Boolean {
            AppPrefs.saveConfig(context, n_ctx, n_batch, n_threads)
            val f = ModelAutoLoader.findInstalledModel(context) ?: return false
            return LlamaBridge.loadModel(f.absolutePath, n_ctx, n_batch, n_threads, true, false)
        }

        @android.webkit.JavascriptInterface
        fun isModelReady(): Boolean = LlamaBridge.isReady()

        @android.webkit.JavascriptInterface
        fun getModelInfo(): String {
            val size = ModelAutoLoader.getModelSizeMB(context)
            val path = AppPrefs.getLastModelPath(context) ?: ""
            val ready = LlamaBridge.isReady()
            return """{"ready":$ready,"size":"$size","path":"$path"}"""
        }

        @android.webkit.JavascriptInterface
        fun pickModel(): String = "Letakkan file GGUF di Download atau folder app, lalu Auto Load"


        @android.webkit.JavascriptInterface
        fun saveConnectorToken(id: String, token: String, accountId: String) {
            Connectors.saveToken(context, id, token, if (accountId.isBlank()) null else accountId)
        }

        @android.webkit.JavascriptInterface
        fun getConnectorToken(id: String): String {
            return Connectors.getToken(context, id) ?: ""
        }

        @android.webkit.JavascriptInterface
        fun getConnectorAccountId(id: String): String {
            return Connectors.getAccountId(context, id) ?: ""
        }

        @android.webkit.JavascriptInterface
        fun isConnectorConnected(id: String): Boolean {
            return Connectors.isConnected(context, id)
        }

        @android.webkit.JavascriptInterface
        fun getConnectedConnectors(): String {
            return Connectors.getConnectedList(context).joinToString(",")
        }

        @android.webkit.JavascriptInterface
        fun clearConnectorToken(id: String) {
            Connectors.clearToken(context, id)
        }

        private fun extractUserMessage(fullPrompt: String): String {
            val s = fullPrompt.indexOf("<|im_start|>user")
            val e = fullPrompt.indexOf("<|im_end|>", s)
            return if (s != -1 && e != -1) {
                fullPrompt.substring(s + "<|im_start|>user".length, e).trim()
            } else fullPrompt
        }
    }
}
