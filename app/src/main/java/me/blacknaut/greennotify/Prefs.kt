package me.blacknaut.greennotify

import android.content.Context

/** Armazena a configuração de conexão (URL do servidor, deviceId e chave de API) localmente. */
object Prefs {
    private const val FILE = "greennotify_prefs"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_RUNNING = "running"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getServerUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_SERVER_URL, "") ?: ""

    fun getDeviceId(ctx: Context): String =
        prefs(ctx).getString(KEY_DEVICE_ID, "") ?: ""

    fun getApiKey(ctx: Context): String =
        prefs(ctx).getString(KEY_API_KEY, "") ?: ""

    fun isRunning(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_RUNNING, false)

    fun save(ctx: Context, serverUrl: String, deviceId: String, apiKey: String) {
        prefs(ctx).edit()
            .putString(KEY_SERVER_URL, serverUrl.trim())
            .putString(KEY_DEVICE_ID, deviceId.trim())
            .putString(KEY_API_KEY, apiKey.trim())
            .apply()
    }

    fun setRunning(ctx: Context, running: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_RUNNING, running).apply()
    }

    fun isConfigured(ctx: Context): Boolean =
        getServerUrl(ctx).isNotBlank() && getDeviceId(ctx).isNotBlank() && getApiKey(ctx).isNotBlank()
}
