package com.xena.ai

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AppPrefs {
    private const val PREF_NAME = "xena_prefs"

    fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getSafeSystemPrompt(context: Context): String {
        val prefs = getPrefs(context)
        var prompt = prefs.getString("system_prompt", "") ?: ""
        if (prompt.isBlank() || prompt == "null" || prompt == "undefined") {
            prompt =
                "You are Xena-AI, a helpful coding assistant powered by Qwen2.5-Coder. " +
                    "You are an expert programmer created by Xena. Answer correctly and clearly in Indonesian. " +
                    "Provide working code when asked. Be concise but correct."
            prefs.edit().putString("system_prompt", prompt).apply()
        }
        return prompt
    }

    fun saveSystemPrompt(context: Context, prompt: String) {
        getPrefs(context).edit().putString("system_prompt", prompt).apply()
    }

    fun getNctx(context: Context): Int {
        migrateSixGbDefaults(context)
        return getPrefs(context).getInt("n_ctx", 1024)
    }

    fun getNBatch(context: Context): Int {
        migrateSixGbDefaults(context)
        return getPrefs(context).getInt("n_batch", 32)
    }

    fun getNThreads(context: Context): Int {
        migrateSixGbDefaults(context)
        return getPrefs(context).getInt("n_threads", 2)
    }

    fun getTemperature(context: Context): Float = getPrefs(context).getFloat("temperature", 0.2f)

    fun getLastModelPath(context: Context): String? =
        getPrefs(context).getString("last_model_path", null)

    fun saveLastModelPath(context: Context, path: String) {
        getPrefs(context).edit().putString("last_model_path", path).apply()
    }

    fun saveConfig(context: Context, n_ctx: Int, n_batch: Int, n_threads: Int, temperature: Float = 0.2f) {
        getPrefs(context).edit()
            .putInt("n_ctx", n_ctx)
            .putInt("n_batch", n_batch)
            .putInt("n_threads", n_threads)
            .putFloat("temperature", temperature)
            .apply()
    }

    /** v1.4.0 — Server mode (HF Space) default ON for 6GB phones. */
    fun isServerMode(context: Context): Boolean =
        getPrefs(context).getBoolean("server_mode", true)

    fun setServerMode(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean("server_mode", enabled).apply()
    }

    private const val HF_TOKEN_KEY = "hf_inference_token_encrypted"
    private const val HF_KEY_ALIAS = "xena_hf_token_key"

    fun hasHfToken(context: Context): Boolean = getPrefs(context).contains(HF_TOKEN_KEY)

    fun setHfToken(context: Context, token: String) {
        val cleaned = token.trim()
        if (cleaned.isEmpty()) {
            clearHfToken(context)
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateHfKey())
        val encrypted = cipher.doFinal(cleaned.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        getPrefs(context).edit()
            .putString(HF_TOKEN_KEY, Base64.encodeToString(packed, Base64.NO_WRAP))
            .apply()
    }

    fun getHfToken(context: Context): String {
        val encoded = getPrefs(context).getString(HF_TOKEN_KEY, null) ?: return ""
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= 12) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateHfKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    fun clearHfToken(context: Context) {
        getPrefs(context).edit().remove(HF_TOKEN_KEY).apply()
    }

    private fun getOrCreateHfKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(HF_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                HF_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun migrateSixGbDefaults(context: Context) {
        val prefs = getPrefs(context)
        if (prefs.getBoolean("six_gb_defaults_migrated", false)) return
        val editor = prefs.edit()
        if (prefs.getInt("n_ctx", 1024) == 2048) editor.putInt("n_ctx", 1024)
        if (prefs.getInt("n_batch", 32) == 128) editor.putInt("n_batch", 32)
        if (prefs.getInt("n_threads", 2) == 4) editor.putInt("n_threads", 2)
        editor.putBoolean("six_gb_defaults_migrated", true).apply()
    }
}
