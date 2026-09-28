package me.blacknaut.greennotify

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
    private var webSocket: WebSocket? = null
    private var shouldReconnect = true
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reconnectDelayMs = 2000L

    override fun onCreate() {
        super.onCreate()
        isAlive = true
        NotificationHelper.createChannels(this)
        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground antes de qualquer saída: startForegroundService exige a chamada em até 5s.
        startForeground(NOTIF_ID, buildForegroundNotification(getString(R.string.service_connecting)))
        if (!Prefs.isConfigured(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        shouldReconnect = true
        if (webSocket == null) connect()
        Prefs.setRunning(this, true)
        return START_STICKY
    }

    private fun buildForegroundNotification(status: String): Notification {
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.service_title))
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

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectDelayMs = 2000L
                updateStatus(getString(R.string.service_connected, deviceId))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (code == CLOSE_UNAUTHORIZED) shouldReconnect = false
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                clearIfCurrent(webSocket)
                updateStatus(getString(R.string.service_reconnecting))
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                clearIfCurrent(webSocket)
                if (code == CLOSE_UNAUTHORIZED) {
                    updateStatus(getString(R.string.service_invalid_key))
                    return
                }
                updateStatus(getString(R.string.service_disconnected))
                scheduleReconnect()
            }
        })
    }

    private fun clearIfCurrent(ws: WebSocket) {
        if (webSocket === ws) webSocket = null
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
            if (shouldReconnect && webSocket == null) connect()
        }, reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(60_000L)
    }

    override fun onDestroy() {
        shouldReconnect = false
        handler.removeCallbacksAndMessages(null)
        isAlive = false
        webSocket?.close(1000, "service stopped")
        Prefs.setRunning(this, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIF_ID = 1001

        /** Estado real do serviço no processo atual (Prefs.isRunning é a intenção do usuário, usada no boot). */
        @Volatile
        var isAlive = false
            private set
        private const val CLOSE_UNAUTHORIZED = 4001
    }
}
