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

    /** Acesso interno às preferências (usado por Policy e Stats). */
    fun raw(ctx: Context) = prefs(ctx)

    fun getLastReminderAt(ctx: Context): Long = prefs(ctx).getLong("last_reminder", 0)
    fun setLastReminderAt(ctx: Context, at: Long) {
        prefs(ctx).edit().putLong("last_reminder", at).apply()
    }

    /** Lembrete a cada 10 min enquanto houver pendentes (padrão: ligado). */
    fun isRemindEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("remind", true)

    fun setRemindEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("remind", on).apply()
    }

    // Resumo mostrado no aviso fixo: quantas pendentes, a mais recente e o estado da conexão.
    fun getPendingCount(ctx: Context): Int = prefs(ctx).getInt("pending_count", 0)
    fun getLatestTitle(ctx: Context): String = prefs(ctx).getString("latest_title", "") ?: ""
    fun getConnStatus(ctx: Context): String = prefs(ctx).getString("conn_status", "") ?: ""

    fun setPending(ctx: Context, count: Int, latest: String) {
        prefs(ctx).edit().putInt("pending_count", count).putString("latest_title", latest).apply()
    }

    fun setConnStatus(ctx: Context, status: String) {
        prefs(ctx).edit().putString("conn_status", status).apply()
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
