package com.xena.ai

import android.content.Context
import android.content.SharedPreferences

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

    /** RAM 6GB defaults */
    fun getNctx(context: Context): Int = getPrefs(context).getInt("n_ctx", 1024).coerceIn(512, 1024)

    fun getNBatch(context: Context): Int = getPrefs(context).getInt("n_batch", 32).coerceIn(16, 32)

    fun getNThreads(context: Context): Int = getPrefs(context).getInt("n_threads", 2).coerceIn(1, 2)

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
}
