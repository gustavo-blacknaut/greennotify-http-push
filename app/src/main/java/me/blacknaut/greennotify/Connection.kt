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
            Prefs.setRunning(ctx, true)
        } else {
            ContextCompat.startForegroundService(ctx, Intent(ctx, NotifyConnectionService::class.java))
        }
        // Nos dois modos: checagem a cada 10 min (busca no economia; aviso fixo e lembrete sempre).
        EconomyWorker.schedule(ctx)
    }

    fun stop(ctx: Context) {
        EconomyWorker.cancel(ctx)
        ctx.stopService(Intent(ctx, NotifyConnectionService::class.java))
        Prefs.setRunning(ctx, false)
        PinnedSummary.clear(ctx)
    }

    fun isActive(ctx: Context): Boolean =
        NotifyConnectionService.isAlive || (Prefs.getMode(ctx) == Prefs.MODE_ECONOMY && Prefs.isRunning(ctx))
}
