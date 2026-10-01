package me.blacknaut.greennotify

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * Alerta máximo (priority "alarm", ex.: bot parou de responder): tela cheia vermelha, mesmo com o
 * celular bloqueado. O som é o do canal de alarme, repetindo sem parar até você tocar em "Parar alarme".
 */
class AlarmActivity : AppCompatActivity() {

    private var json: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm)
        bind(intent)
    }

    // Qualquer botão de volume para o alarme (como nos despertadores).
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            val id = json?.optString("id")
            Alarm.stop(this, id)
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
    }

    private fun bind(intent: Intent) {
        json = intent.getStringExtra(EXTRA_JSON)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val item = json?.let { NotificationItem.fromJson(it) }
        findViewById<TextView>(R.id.alarmTitle).text = item?.title?.ifBlank { null } ?: getString(R.string.alarm_default_title)
        findViewById<TextView>(R.id.alarmMessage).text = listOf(item?.message, item?.reason?.let { if (it.isBlank()) null else getString(R.string.reason_format, it) })
            .filter { !it.isNullOrBlank() }.joinToString("\n")
        findViewById<TextView>(R.id.alarmOrigin).text = listOf(item?.category, item?.app).filter { !it.isNullOrBlank() }.distinct().joinToString(" · ")

        findViewById<Button>(R.id.alarmStop).setOnClickListener {
            Alarm.stop(this, item?.id)
            finish()
        }
        findViewById<Button>(R.id.alarmDetails).setOnClickListener {
            Alarm.stop(this, item?.id)
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { json?.let { putExtra(MainActivity.EXTRA_NOTIF, it.toString()) } })
            // Com o celular bloqueado, pede o desbloqueio para abrir o app.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
            }
            finish()
        }
    }

    companion object {
        const val EXTRA_JSON = "json"
    }
}

/** "Parar alarme" direto na notificação; e, se a notificação for arrastada enquanto toca, ela volta. */
class AlarmStopReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID)
        if (intent.action == ACTION_REPOST) {
            if (!AlarmEngine.isRinging()) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val n = AlarmService.lastNotification
            if (AlarmService.running && n != null) nm.notify(AlarmService.NOTIF_ID, n)
            else {
                val json = intent.getStringExtra(EXTRA_JSON)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
                nm.notify(id, 1, NotificationHelper.buildAlarm(ctx, json, id ?: "alarm"))
            }
            return
        }
        Alarm.stop(ctx, id)
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_JSON = "json"
        const val ACTION_REPOST = "me.blacknaut.greennotify.ALARM_REPOST"
    }
}

/**
 * Volume do alarme: "alto, mas não no máximo". Enquanto toca, o volume de alarme do celular sobe para
 * pelo menos 80% (se já estiver acima, fica como está). Ao parar, volta ao que era.
 */
object Alarm {
    fun raiseVolume(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val current = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val target = (max * Prefs.getAlarmVolume(ctx) / 100f).toInt().coerceIn(1, max)
        val prefs = Prefs.raw(ctx)
        // Guarda o volume original só na primeira vez (dois alarmes seguidos não perdem o valor certo).
        if (!prefs.contains(KEY_SAVED)) prefs.edit().putInt(KEY_SAVED, current).apply()
        // Independe do volume do celular (toque/mídia): só o volume de ALARME sobe, e volta depois.
        runCatching { if (am.isStreamMute(AudioManager.STREAM_ALARM)) am.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0) }
        if (current != target) runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0) }
    }

    fun restoreVolume(ctx: Context) {
        val prefs = Prefs.raw(ctx)
        if (!prefs.contains(KEY_SAVED)) return
        val saved = prefs.getInt(KEY_SAVED, -1)
        prefs.edit().remove(KEY_SAVED).apply()
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        if (saved >= 0) runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) }
    }

    /**
     * Começa o alarme. O normal é um serviço em primeiro plano (o Android não mata no meio). Se o sistema
     * não deixar abrir o serviço agora (app em segundo plano e com otimização de bateria), toca do mesmo
     * jeito direto daqui, com a notificação no lugar.
     */
    fun start(ctx: Context, json: JSONObject) {
        val id = json.optString("id", "alarm")
        // O mesmo alerta chegando de novo (tempo real + verificação, reenvio) não volta a tocar depois de parado.
        if (id != "teste-alarme" && wasStopped(ctx, id)) return
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                ctx, Intent(ctx, AlarmService::class.java).putExtra(AlarmService.EXTRA_JSON, json.toString())
            )
        } catch (e: Exception) {
            ctx.getSystemService(NotificationManager::class.java)
                .notify(id, 1, NotificationHelper.buildAlarm(ctx, json, id))
            AlarmEngine.start(ctx, id)
        }
    }

    private fun wasStopped(ctx: Context, id: String): Boolean =
        (Prefs.raw(ctx).getString(KEY_STOPPED, "") ?: "").split(',').contains(id)

    private fun markStopped(ctx: Context, id: String) {
        val list = (Prefs.raw(ctx).getString(KEY_STOPPED, "") ?: "").split(',').filter { it.isNotBlank() && it != id }
        Prefs.raw(ctx).edit().putString(KEY_STOPPED, (list.takeLast(30) + id).joinToString(",")).apply()
    }

    /** Para som, vibração, lanterna e tela, devolve o volume. A notificação continua pendente no app. */
    fun stop(ctx: Context, id: String?) {
        (id ?: AlarmEngine.currentId)?.let { markStopped(ctx, it) }
        AlarmEngine.stop(ctx)
        ctx.stopService(Intent(ctx, AlarmService::class.java))
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(AlarmService.NOTIF_ID)
        if (id != null) NotificationHelper.cancel(ctx, id)
        nm.activeNotifications.filter { it.notification.channelId == NotificationHelper.CHANNEL_ALARM }
            .forEach { nm.cancel(it.tag, it.id) }
        restoreVolume(ctx)
    }

    private const val KEY_SAVED = "alarm_saved_volume"
    private const val KEY_STOPPED = "alarm_stopped_ids"
}
