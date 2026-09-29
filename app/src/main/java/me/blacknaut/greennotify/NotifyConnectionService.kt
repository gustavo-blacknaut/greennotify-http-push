package me.blacknaut.greennotify

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import me.blacknaut.greennotify.NetworkInfo.Net
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Serviço em primeiro plano do modo "tempo real". Mantém um WebSocket aberto com o servidor
 * (http:// ou ws://, sem HTTPS) SOMENTE nas redes configuradas como tempo real: ao trocar de Wi‑Fi para
 * dados (ou o contrário) ele reavalia a política daquela rede, abre ou fecha a conexão e, se a nova rede
 * for de "consultar a cada N minutos", entrega o trabalho ao EconomyWorker.
 */
class NotifyConnectionService : Service() {

    private lateinit var client: OkHttpClient
    // Estado só é tocado na main thread: os callbacks do OkHttp são repassados via handler.
    private var webSocket: WebSocket? = null
    private var connectedConfig: String? = null
    private var lastStatus: String? = null
    private var lastPolicyKey: String? = null
    private var callbackRegistered = false
    private var shouldReconnect = true
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reconnectDelayMs = 2000L

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { handler.post { evaluate() } }
        override fun onLost(network: Network) { handler.post { evaluate() } }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { handler.post { evaluate() } }
    }

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
        startForeground(NOTIF_ID, buildForegroundNotification(lastStatus ?: getString(R.string.service_connecting)))
        if (!Prefs.isConfigured(this) || !Policy.anyRealtime(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        Prefs.setLastError(this, null)
        shouldReconnect = true
        if (!callbackRegistered) {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCallback)
            callbackRegistered = true
        }
        evaluate()
        return START_STICKY
    }

    /** Decide, para a rede ativa agora, se deve haver conexão aberta. Idempotente: pode ser chamada à vontade. */
    private fun evaluate() {
        if (!shouldReconnect || !Prefs.isConfigured(this)) return
        val p = Policy.current(this)
        Stats.sample(this, p.net)
        val key = "${p.net}|${p.kind}|${p.pollMin}|${p.pingMin}"
        val wantsSocket = p.net != Net.NONE && p.kind == Policy.REALTIME
        if (!wantsSocket) {
            if (webSocket != null || connectedConfig != null) {
                closeCounting()
                dropConnection()
            }
            updateStatus(if (p.net == Net.NONE) getString(R.string.service_no_network) else "")
            // Rede de "consultar": começa a consultar já e depois no intervalo dela.
            if (key != lastPolicyKey && p.kind == Policy.POLLING) EconomyWorker.schedule(this)
        } else if (webSocket == null || connectedConfig != configKey(p)) {
            closeCounting()
            dropConnection()
            reconnectDelayMs = 2000L
            updateStatus(getString(R.string.service_connecting))
            connect(p)
        }
        lastPolicyKey = key
    }

    private fun currentConfig(): String =
        listOf(Prefs.getServerUrl(this), Prefs.getDeviceId(this), Prefs.getApiKey(this)).joinToString("\n")

    private fun configKey(p: Policy.Current) = currentConfig() + "\n" + p.net + "\n" + p.pingMin

    private fun dropConnection() {
        handler.removeCallbacksAndMessages(null)
        val old = webSocket
        webSocket = null
        connectedConfig = null
        old?.cancel()
    }

    /** Soma ao consumo o tempo que o WebSocket ficou aberto. */
    private fun closeCounting() {
        val since = openSince
        if (since > 0) Stats.addWsSeconds(this, openNet, (System.currentTimeMillis() - since) / 1000)
        openSince = 0
    }

    private fun stopForInvalidKey() {
        shouldReconnect = false
        closeCounting()
        webSocket = null
        connectedConfig = null
        Prefs.setRunning(this, false)
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

    /** [status] vazio = tudo certo (não mostra texto nenhum). */
    private fun updateStatus(status: String) {
        lastStatus = status
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildForegroundNotification(status))
    }

    private fun connect(p: Policy.Current) {
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

        connectedConfig = configKey(p)
        // Cada sinal de vida acorda o rádio: o intervalo é escolhido nas configurações, por tipo de rede.
        val wsClient = client.newBuilder().pingInterval(p.pingMin.toLong(), TimeUnit.MINUTES).build()
        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            // Callbacks de uma conexão já substituída ou derrubada são ignorados.
            private fun onMain(ws: WebSocket, block: () -> Unit) {
                handler.post { if (webSocket === ws) block() }
            }

            override fun onOpen(webSocket: WebSocket, response: Response) = onMain(webSocket) {
                reconnectDelayMs = 2000L
                openSince = System.currentTimeMillis()
                openNet = p.net
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
                closeCounting()
                this@NotifyConnectionService.webSocket = null
                connectedConfig = null
                updateStatus(getString(R.string.service_reconnecting))
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onMain(webSocket) {
                closeCounting()
                this@NotifyConnectionService.webSocket = null
                connectedConfig = null
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
                    Stats.addMessage(this, openNet)
                    Stats.sample(this, openNet)
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
        handler.postDelayed({ evaluate() }, reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(60_000L)
    }

    override fun onDestroy() {
        shouldReconnect = false
        handler.removeCallbacksAndMessages(null)
        isAlive = false
        closeCounting()
        if (callbackRegistered) {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) } catch (_: Exception) {}
            callbackRegistered = false
        }
        val ws = webSocket
        webSocket = null
        ws?.close(1000, "service stopped")
        // Não mexe em Prefs.isRunning: parar o serviço pode ser só uma troca para uma rede de "consultar".
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIF_ID = 1001

        /** Estado real do serviço no processo atual (Prefs.isRunning é a intenção do usuário, usada no boot). */
        @Volatile
        var isAlive = false
            private set

        /** Quando o WebSocket abriu e em qual rede (0 = fechado): a tela de consumo soma o tempo em andamento. */
        @Volatile
        var openSince = 0L
            private set
        @Volatile
        var openNet = Net.NONE
            private set

        private const val CLOSE_UNAUTHORIZED = 4001
    }
}
