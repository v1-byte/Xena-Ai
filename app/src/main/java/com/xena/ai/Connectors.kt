package com.xena.ai

import android.content.Context
import android.util.Log

object Connectors {
    private const val TAG = "XENA-Connector"
    private const val PREF_NAME = "xena_connectors"
    private val allowedIds = setOf("github", "cloudflare", "meta")

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
        )
    )

    fun saveToken(context: Context, id: String, token: String, accountId: String? = null) {
        if (id !in allowedIds || token.isBlank()) return
        AppPrefs.setConnectorSecret(context, id, token.trim())
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString("${id}_account", accountId.orEmpty()).apply()
        Log.d(TAG, "Saved token for $id")
    }

    fun getToken(context: Context, id: String): String? {
        if (id !in allowedIds) return null
        val secure = AppPrefs.getConnectorSecret(context, id)
        if (secure != null) return secure
        // One-time migration from the old plaintext store.
        val legacy = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString("${id}_token", null)
        if (!legacy.isNullOrBlank()) {
            saveToken(context, id, legacy, getAccountId(context, id))
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().remove("${id}_token").apply()
        }
        return legacy
    }

    fun getAccountId(context: Context, id: String): String? {
        if (id !in allowedIds) return null
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString("${id}_account", null)
    }

    fun isConnected(context: Context, id: String): Boolean {
        return !getToken(context, id).isNullOrBlank()
    }

    fun getConnectedList(context: Context): List<String> {
        return all.filter { isConnected(context, it.id) }.map { it.id }
    }

    fun clearToken(context: Context, id: String) {
        if (id !in allowedIds) return
        AppPrefs.clearConnectorSecret(context, id)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("${id}_token")
            .remove("${id}_account")
            .apply()
    }
}
