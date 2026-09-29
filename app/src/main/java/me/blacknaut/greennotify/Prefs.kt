package me.blacknaut.greennotify

import android.content.Context

/** Armazena a configuração de conexão (URL do servidor, deviceId e chave de API) localmente. */
object Prefs {
    private const val FILE = "greennotify_prefs"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_API_KEY = "api_key"
    const val KEY_RUNNING = "running"
    const val KEY_LAST_ERROR = "last_error"
    private const val KEY_MODE = "mode"

    const val MODE_REALTIME = "realtime"
    const val MODE_ECONOMY = "economy"

    /** Tempo real (conexão aberta) ou economia (consulta periódica, sem serviço fixo). */
    fun getMode(ctx: Context): String = prefs(ctx).getString(KEY_MODE, MODE_REALTIME) ?: MODE_REALTIME

    fun setMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString(KEY_MODE, mode).apply()
    }

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

    /** Motivo da última parada automática do serviço (ex: chave inválida), ou null. */
    fun getLastError(ctx: Context): String? = prefs(ctx).getString(KEY_LAST_ERROR, null)

    fun setLastError(ctx: Context, error: String?) {
        prefs(ctx).edit().putString(KEY_LAST_ERROR, error).apply()
    }

    fun registerListener(ctx: Context, listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(ctx).registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(ctx: Context, listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(ctx).unregisterOnSharedPreferenceChangeListener(listener)

    fun isConfigured(ctx: Context): Boolean =
        getServerUrl(ctx).isNotBlank() && getDeviceId(ctx).isNotBlank() && getApiKey(ctx).isNotBlank()
}
