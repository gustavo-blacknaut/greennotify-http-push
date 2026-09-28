package me.blacknaut.greennotify

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import okhttp3.*
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
    private var webSocket: WebSocket? = null
    private var shouldReconnect = true
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reconnectDelayMs = 2000L

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildForegroundNotification("Conectando..."))
        shouldReconnect = true
        connect()
        Prefs.setRunning(this, true)
        return START_STICKY
    }

    private fun buildForegroundNotification(status: String): Notification {
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("GreenNotify ativo")
            .setContentText(status)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun updateStatus(status: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildForegroundNotification(status))
    }

    private fun connect() {
        val serverUrl = Prefs.getServerUrl(this)
        val deviceId = Prefs.getDeviceId(this)
        val apiKey = Prefs.getApiKey(this)
        if (serverUrl.isBlank() || deviceId.isBlank() || apiKey.isBlank()) {
            updateStatus("Não configurado")
            return
        }

        val wsUrl = toWsUrl(serverUrl) + "/ws?deviceId=$deviceId&key=$apiKey"
        val request = Request.Builder().url(wsUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectDelayMs = 2000L
                updateStatus("Conectado a $deviceId")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                updateStatus("Reconectando...")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                updateStatus("Desconectado")
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
            if (shouldReconnect) connect()
        }, reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(60_000L)
    }

    private fun toWsUrl(httpUrl: String): String {
        var url = httpUrl.trim().trimEnd('/')
        return when {
            url.startsWith("https://") -> "wss://" + url.removePrefix("https://")
            url.startsWith("http://") -> "ws://" + url.removePrefix("http://")
            url.startsWith("ws://") || url.startsWith("wss://") -> url
            else -> "ws://$url"
        }
    }

    override fun onDestroy() {
        shouldReconnect = false
        webSocket?.close(1000, "service stopped")
        Prefs.setRunning(this, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIF_ID = 1001
    }
}
