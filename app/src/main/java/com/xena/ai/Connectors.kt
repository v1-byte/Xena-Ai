package com.xena.ai

import android.content.Context
import android.util.Log

object Connectors {
    private const val TAG = "XENA-Connector"
    private const val PREF_NAME = "xena_connectors"

    data class ConnectorDef(
        val id: String,
        val name: String,
        val icon: String,
        val description: String,
        val tokenLabel: String,
        val tokenHint: String,
        val needsAccountId: Boolean = false
    )

    val all = listOf(
        ConnectorDef(
            id = "github",
            name = "GitHub",
            icon = "🐙",
            description = "Kelola repo v1-byte/Xena-Ai, auto push fix, trigger build APK",
            tokenLabel = "GitHub Personal Access Token (PAT)",
            tokenHint = "ghp_xxxxxxxxxxxxxxxxxxxx"
        ),
        ConnectorDef(
            id = "cloudflare",
            name = "Cloudflare",
            icon = "☁️",
            description = "Deploy Xena, upload model ke R2, manage domain",
            tokenLabel = "Cloudflare API Token",
            tokenHint = "xxxxx",
            needsAccountId = true
        ),
        ConnectorDef(
            id = "meta",
            name = "Meta (Facebook/IG)",
            icon = "📘",
            description = "Baca Ads, Page insights",
            tokenLabel = "Meta Access Token",
            tokenHint = "EAAxxxxxxxxxxxx"
        ),
        ConnectorDef(
            id = "huggingface",
            name = "HuggingFace",
            icon = "🤗",
            description = "Download model GGUF Qwen, gratis tanpa token",
            tokenLabel = "HF Token (opsional, untuk private model)",
            tokenHint = "hf_xxxxxxxx"
        )
    )

    fun saveToken(context: Context, id: String, token: String, accountId: String? = null) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("${id}_token", token).apply()
        if (accountId != null) {
            prefs.edit().putString("${id}_account", accountId).apply()
        }
        Log.d(TAG, "Saved token for $id")
    }

    fun getToken(context: Context, id: String): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString("${id}_token", null)
    }

    fun getAccountId(context: Context, id: String): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString("${id}_account", null)
    }

    fun isConnected(context: Context, id: String): Boolean {
        // HuggingFace boleh tanpa token (public)
        if (id == "huggingface") return true
        return !getToken(context, id).isNullOrBlank()
    }

    fun getConnectedList(context: Context): List<String> {
        return all.filter { isConnected(context, it.id) }.map { it.id }
    }

    fun clearToken(context: Context, id: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("${id}_token")
            .remove("${id}_account")
            .apply()
    }
}
