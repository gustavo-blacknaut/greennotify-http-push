package me.blacknaut.greennotify

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Liga/desliga o recebimento conforme o modo escolhido (tempo real ou economia). */
object Connection {

    fun start(ctx: Context) {
        Prefs.setLastError(ctx, null)
        if (Prefs.getMode(ctx) == Prefs.MODE_ECONOMY) {
            ctx.stopService(Intent(ctx, NotifyConnectionService::class.java))
            EconomyWorker.schedule(ctx)
            Prefs.setRunning(ctx, true)
        } else {
            EconomyWorker.cancel(ctx)
            ContextCompat.startForegroundService(ctx, Intent(ctx, NotifyConnectionService::class.java))
        }
    }

    fun stop(ctx: Context) {
        EconomyWorker.cancel(ctx)
        ctx.stopService(Intent(ctx, NotifyConnectionService::class.java))
        Prefs.setRunning(ctx, false)
    }

    fun isActive(ctx: Context): Boolean =
        NotifyConnectionService.isAlive || (Prefs.getMode(ctx) == Prefs.MODE_ECONOMY && Prefs.isRunning(ctx))
}
