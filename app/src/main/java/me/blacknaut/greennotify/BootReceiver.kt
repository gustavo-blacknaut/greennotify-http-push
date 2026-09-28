package me.blacknaut.greennotify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            Prefs.isConfigured(context) && Prefs.isRunning(context)
        ) {
            val svc = Intent(context, NotifyConnectionService::class.java)
            ContextCompat.startForegroundService(context, svc)
        }
    }
}
