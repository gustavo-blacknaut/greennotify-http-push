package me.blacknaut.greennotify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.ServiceCompat
import org.json.JSONObject

/**
 * Alarme de verdade (alerta máximo): serviço em primeiro plano que toca o som de ALARME em repetição,
 * vibra, acende a tela e pisca a lanterna até alguém tocar em "Parar alarme" (ou 10 min, por segurança).
 * Não depende do som da notificação, que o sistema pode cortar ou abaixar.
 */
class AlarmService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val json = intent?.getStringExtra(EXTRA_JSON)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val id = json.optString("id", "alarm")
        val notification = NotificationHelper.buildAlarm(this, json, id, ringing = true)
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        lastNotification = notification
        running = true
        AlarmEngine.start(this, id)
        // Tenta abrir a tela vermelha direto (o Android deixa quando o app tem permissão de tela cheia
        // ou "aparecer sobre outros apps"; se não deixar, fica o aviso com o botão).
        runCatching {
            startActivity(Intent(this, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(AlarmActivity.EXTRA_JSON, json.toString()))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        lastNotification = null
        AlarmEngine.stop(this)
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 1003
        const val EXTRA_JSON = "json"
        @Volatile var running = false
        @Volatile var lastNotification: android.app.Notification? = null
    }
}

/** Som + vibração + tela + lanterna. Usado pelo serviço (ou direto, se o Android não deixar abrir o serviço). */
object AlarmEngine {
    private const val MAX_MS = 10 * 60 * 1000L
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var torchOn = false
    private var torchId: String? = null
    var currentId: String? = null
        private set

    private lateinit var appCtx: Context

    fun isRinging() = player != null

    fun start(ctx: Context, id: String) {
        appCtx = ctx.applicationContext
        currentId = id
        if (player != null) return // já tocando: só troca qual notificação é a atual
        main.removeCallbacksAndMessages(null)
        if (Prefs.isAlarmSound(appCtx)) { Alarm.raiseVolume(appCtx); playSound() } else player = MediaPlayer()
        if (Prefs.isAlarmVibrate(appCtx)) vibrate()
        // Acende a tela só depois: com a tela apagada o Android abre a tela cheia vermelha;
        // se acender antes, ele mostra só um aviso no topo.
        main.postDelayed({ if (player != null) wakeScreen() }, 2500)
        startTorch()
        main.postDelayed({ if (player != null) captureVolumeKeys() }, 800)
        main.postDelayed({ Alarm.stop(appCtx, currentId) }, MAX_MS)
    }

    fun stop(ctx: Context) {
        main.removeCallbacksAndMessages(null)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        stopVibration(ctx)
        // Alguns celulares (Samsung) às vezes ignoram o primeiro cancelamento de vibração em repetição.
        main.postDelayed({ stopVibration(ctx) }, 400)
        main.postDelayed({ stopVibration(ctx) }, 1500)
        setTorch(ctx, false)
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        volumeReceiver?.let { r -> runCatching { ctx.applicationContext.unregisterReceiver(r) } }
        volumeReceiver = null
        currentId = null
    }

    private fun playSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(appCtx, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull() ?: MediaPlayer() // sem som disponível: ainda vibra/acende; o objeto marca "tocando"
    }

    private var activeVibrator: Vibrator? = null

    private fun stopVibration(ctx: Context) {
        runCatching { activeVibrator?.cancel() }
        runCatching { vibrator(ctx).cancel() }
        if (Build.VERSION.SDK_INT >= 31) runCatching { ctx.getSystemService(VibratorManager::class.java).cancel() }
    }

    private fun vibrator(ctx: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)

    private fun vibrate() {
        runCatching {
            val pattern = longArrayOf(0, 900, 500, 900, 500)
            val v = vibrator(appCtx)
            activeVibrator = v
            @Suppress("DEPRECATION")
            v.vibrate(
                VibrationEffect.createWaveform(pattern, 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        runCatching {
            val pm = appCtx.getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "GreenNotify:alarm"
            ).apply { acquire(MAX_MS) }
        }
    }

    /**
     * Botões de volume param o alarme em qualquer tela acesa, inclusive a tela de fora do Flip fechado
     * (a tela vermelha abre na tela de dentro). Enquanto toca, qualquer mudança de volume = parar.
     */
    private var volumeReceiver: android.content.BroadcastReceiver? = null

    private fun captureVolumeKeys() {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { main.post { Alarm.stop(appCtx, currentId) } }
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                appCtx, r, android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
            volumeReceiver = r
        }
    }

    // Lanterna piscando (meio segundo acesa, meio apagada). Não precisa de permissão de câmera.
    private fun startTorch() {
        if (!Prefs.isAlarmTorch(appCtx)) return
        val cm = appCtx.getSystemService(CameraManager::class.java) ?: return
        torchId = runCatching {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrNull() ?: return
        val blink = object : Runnable {
            override fun run() {
                if (player == null) return
                setTorch(appCtx, !torchOn)
                main.postDelayed(this, 500)
            }
        }
        main.post(blink)
    }

    private fun setTorch(ctx: Context, on: Boolean) {
        val id = torchId ?: return
        runCatching { ctx.getSystemService(CameraManager::class.java).setTorchMode(id, on) }
        torchOn = on
    }
}
