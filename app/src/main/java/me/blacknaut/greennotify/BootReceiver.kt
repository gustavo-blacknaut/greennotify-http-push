package me.blacknaut.greennotify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Religa o tempo real depois de reiniciar o celular ou de instalar uma atualização do app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs.isConfigured(context) || !Prefs.isRunning(context)) return
        if (Prefs.getMode(context) == Prefs.MODE_REALTIME) {
            ContextCompat.startForegroundService(context, Intent(context, NotifyConnectionService::class.java))
        }
        // O ciclo de 10 min do WorkManager sobrevive ao reboot, mas é reiniciado aqui por garantia.
        EconomyWorker.schedule(context)
    }
}
