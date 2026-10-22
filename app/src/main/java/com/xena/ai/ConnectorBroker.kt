package com.xena.ai

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Native-only connector operations. Tokens never cross the WebView bridge. */
object ConnectorBroker {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun test(context: Context, id: String): String {
        if (id !in setOf("github", "cloudflare", "meta")) return result(false, "invalid_connector", "Konektor tidak dikenal")
        val token = Connectors.getToken(context, id).orEmpty()
        if (token.isBlank()) return result(false, "missing_token", "Token belum disimpan di Keystore")
        return try {
            val request = when (id) {
                "github" -> Request.Builder().url("https://api.github.com/repos/v1-byte/Xena-Ai")
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/vnd.github+json").build()
                "cloudflare" -> Request.Builder().url("https://api.cloudflare.com/client/v4/user/tokens/verify")
                    .header("Authorization", "Bearer $token").build()
                else -> Request.Builder().url("https://graph.facebook.com/v19.0/me")
                    .header("Authorization", "Bearer $token").build()
            }
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val ok = response.isSuccessful && if (id == "cloudflare") JSONObject(body).optBoolean("success", false) else true
                result(ok, if (ok) "ok" else "provider_error", if (ok) "Konektor aktif" else "Provider menolak token (HTTP ${response.code})")
            }
        } catch (_: Exception) {
            result(false, "network", "Tidak dapat menghubungi provider")
        }
    }

    private fun result(ok: Boolean, code: String, message: String): String = JSONObject()
        .put("ok", ok).put("code", code).put("message", message).toString()
}
