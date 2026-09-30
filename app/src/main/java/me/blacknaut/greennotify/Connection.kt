package me.blacknaut.greennotify

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Liga/desliga o recebimento conforme a política de cada rede (Wi-Fi e dados móveis). */
object Connection {

    fun start(ctx: Context) {
        Prefs.setLastError(ctx, null)
        if (!Prefs.isRunning(ctx) || Prefs.getActiveSince(ctx) == 0L) Prefs.setActiveSince(ctx, System.currentTimeMillis())
        Prefs.setRunning(ctx, true)
        // O serviço só existe se alguma rede estiver em tempo real; ele mesmo cuida de trocar de rede.
        if (Policy.anyRealtime(ctx)) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, NotifyConnectionService::class.java))
        } else {
            ctx.stopService(Intent(ctx, NotifyConnectionService::class.java))
        }
        // Rodada periódica: consulta nas redes de "consultar" e cuida do aviso fixo e do lembrete.
        EconomyWorker.schedule(ctx)
    }

    fun stop(ctx: Context) {
        EconomyWorker.cancel(ctx)
        ctx.stopService(Intent(ctx, NotifyConnectionService::class.java))
        Prefs.setRunning(ctx, false)
        Prefs.setActiveSince(ctx, 0)
        Prefs.setNextCheckAt(ctx, 0)
        PinnedSummary.clear(ctx)
    }

    fun isActive(ctx: Context): Boolean = Prefs.isRunning(ctx)
}
