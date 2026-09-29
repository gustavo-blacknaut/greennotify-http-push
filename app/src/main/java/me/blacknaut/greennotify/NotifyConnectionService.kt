package me.blacknaut.greennotify

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Serviço em primeiro plano que mantém uma conexão WebSocket persistente com o servidor
 * GreenNotify (http:// ou ws://, sem HTTPS) para receber notificações em tempo real.
 * Reconecta automaticamente se a conexão cair.
 */
class NotifyConnectionService : Service() {

    private lateinit var client: OkHttpClient
    // Estado só é tocado na main thread: os callbacks do OkHttp são repassados via handler.
    private var webSocket: WebSocket? = null
    private var connectedConfig: String? = null
    private var lastStatus: String? = null
    private var shouldReconnect = true
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reconnectDelayMs = 2000L

    override fun onCreate() {
        super.onCreate()
        isAlive = true
        NotificationHelper.createChannels(this)
        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground antes de qualquer saída: startForegroundService exige a chamada em até 5s.
        // Reusa o último status: um "Iniciar" repetido não reconecta, então não pode voltar a "Conectando…".
        startForeground(NOTIF_ID, buildForegroundNotification(lastStatus ?: getString(R.string.service_connecting)))
        if (!Prefs.isConfigured(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        Prefs.setLastError(this, null)
        Prefs.setRunning(this, true)
        shouldReconnect = true
        // Mesmo config e conexão ativa: ignora o toque repetido. Config nova: troca a conexão.
        if (webSocket == null || connectedConfig != currentConfig()) {
            dropConnection()
            reconnectDelayMs = 2000L
            updateStatus(getString(R.string.service_connecting))
            connect()
        }
        return START_STICKY
    }

    private fun currentConfig(): String =
        listOf(Prefs.getServerUrl(this), Prefs.getDeviceId(this), Prefs.getApiKey(this)).joinToString("\n")

    private fun dropConnection() {
        handler.removeCallbacksAndMessages(null)
        val old = webSocket
        webSocket = null
        connectedConfig = null
        old?.cancel()
    }

    private fun stopForInvalidKey() {
        shouldReconnect = false
        webSocket = null
        connectedConfig = null
        Prefs.setLastError(this, getString(R.string.service_invalid_key))
        NotificationHelper.showServiceStopped(this, getString(R.string.service_invalid_key))
        stopSelf()
    }

    // A notificação do serviço é o aviso fixo (PinnedSummary): logo discreta sem pendentes,
    // painel de prioridade máxima com pendentes. Estado da conexão só aparece se houver problema.
    private fun buildForegroundNotification(status: String): Notification {
        Prefs.setConnStatus(this, status)
        return NotificationHelper.pinned(this, foreground = true, alert = false)
    }

    /** [status] vazio = conectado e tudo certo (não mostra texto nenhum). */
    private fun updateStatus(status: String) {
        lastStatus = status
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildForegroundNotification(status))
    }

    private fun connect() {
        val serverUrl = Prefs.getServerUrl(this)
        val deviceId = Prefs.getDeviceId(this)
        val apiKey = Prefs.getApiKey(this)
        if (serverUrl.isBlank() || deviceId.isBlank() || apiKey.isBlank()) {
            updateStatus(getString(R.string.service_not_configured))
            return
        }

        val baseUrl = ApiClient.toHttpUrl(serverUrl).toHttpUrlOrNull()
        if (baseUrl == null) {
            updateStatus(getString(R.string.error_invalid_server_url))
            return
        }
        val wsUrl = baseUrl.newBuilder()
            .addPathSegment("ws")
            .addQueryParameter("deviceId", deviceId)
            .addQueryParameter("key", apiKey)
            .build()
        val request = Request.Builder().url(wsUrl).build()

        connectedConfig = currentConfig()
        // Cada ping acorda o rádio. Nos dados móveis as operadoras derrubam conexões paradas mais cedo
        // (3 min é seguro); no Wi‑Fi o roteador aguenta mais (5 min). Trocar de rede reconecta e reavalia.
        val ping = if (NetworkInfo.isWifi(this)) WIFI_PING_MINUTES else MOBILE_PING_MINUTES
        val wsClient = client.newBuilder().pingInterval(ping, TimeUnit.MINUTES).build()
        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            // Callbacks de uma conexão já substituída ou derrubada são ignorados.
            private fun onMain(ws: WebSocket, block: () -> Unit) {
                handler.post { if (webSocket === ws) block() }
            }

            override fun onOpen(webSocket: WebSocket, response: Response) = onMain(webSocket) {
                reconnectDelayMs = 2000L
                updateStatus("")
                PinnedSummary.refreshAsync(this@NotifyConnectionService)
            }

            override fun onMessage(webSocket: WebSocket, text: String) = onMain(webSocket) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val text = bytes.utf8()
                onMain(webSocket) { handleMessage(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                onMain(webSocket) {
                    if (code == CLOSE_UNAUTHORIZED) stopForInvalidKey()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onMain(webSocket) {
                this@NotifyConnectionService.webSocket = null
                updateStatus(getString(R.string.service_reconnecting))
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onMain(webSocket) {
                this@NotifyConnectionService.webSocket = null
                updateStatus(getString(R.string.service_disconnected))
                scheduleReconnect()
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "notification" -> {
                    NotificationHelper.showIncoming(this, json)
                    // Conta localmente (sem ir ao servidor); a contagem é conferida a cada reconexão.
                    Prefs.setPending(this, Prefs.getPendingCount(this) + 1, json.optString("title"))
                    PinnedSummary.post(this)
                    val id = json.optString("id")
                    if (id.isNotBlank()) {
                        webSocket?.send(JSONObject().put("type", "ack").put("id", id).toString())
                    }
                }
                "delete" -> {
                    val id = json.optString("id")
                    if (id == "all") NotificationHelper.cancelAll(this)
                    else if (id.isNotBlank()) NotificationHelper.cancel(this, id)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        handler.postDelayed({
            if (shouldReconnect && webSocket == null) connect()
        }, reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(60_000L)
    }

    override fun onDestroy() {
        shouldReconnect = false
        handler.removeCallbacksAndMessages(null)
        isAlive = false
        val ws = webSocket
        webSocket = null
        ws?.close(1000, "service stopped")
        // Ao trocar para o modo economia o serviço é parado, mas o app continua "rodando" (via WorkManager).
        if (Prefs.getMode(this) == Prefs.MODE_REALTIME) Prefs.setRunning(this, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIF_ID = 1001
        const val WIFI_PING_MINUTES = 5L
        const val MOBILE_PING_MINUTES = 3L

        /** Estado real do serviço no processo atual (Prefs.isRunning é a intenção do usuário, usada no boot). */
        @Volatile
        var isAlive = false
            private set
        private const val CLOSE_UNAUTHORIZED = 4001
    }
}
