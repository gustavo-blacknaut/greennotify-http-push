package me.blacknaut.greennotify

import android.app.NotificationManager
import android.content.Context

/**
 * Aviso fixo com prioridade máxima: "N notificações pendentes · Última: ...". Fica no topo até as
 * pendentes serem resolvidas no app. No tempo real é a própria notificação do serviço; no modo
 * economia é uma notificação fixa separada, que só existe enquanto houver pendentes.
 */
object PinnedSummary {

    fun refreshAsync(ctx: Context) {
        val app = ctx.applicationContext
        Thread { refreshSync(app, alert = false) }.start()
    }

    /** Busca as pendentes no servidor e atualiza o aviso. Com [alert], toca de novo (lembrete). */
    fun refreshSync(ctx: Context, alert: Boolean): Int {
        if (!Prefs.isConfigured(ctx)) return 0
        val response = ApiClient.postSync(
            ctx, "/list", ApiClient.authBody(ctx).put("status", "pending").put("limit", 100)
        )
        if (response != null) {
            val list = response.optJSONArray("notifications")
            val count = list?.length() ?: 0
            val latest = if (count > 0) list!!.getJSONObject(0).optString("title") else ""
            Prefs.setPending(ctx, count, latest)
        }
        post(ctx, alert && Prefs.getPendingCount(ctx) > 0)
        return Prefs.getPendingCount(ctx)
    }

    /** Redesenha o aviso com o que já está salvo (sem ir ao servidor). */
    fun post(ctx: Context, alert: Boolean = false) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        NotificationHelper.createChannels(ctx)
        when {
            NotifyConnectionService.isAlive -> {
                nm.cancel(NotificationHelper.PINNED_ID)
                nm.notify(NotifyConnectionService.NOTIF_ID, NotificationHelper.pinned(ctx, foreground = true, alert = alert))
            }
            Prefs.isRunning(ctx) ->
                nm.notify(NotificationHelper.PINNED_ID, NotificationHelper.pinned(ctx, foreground = false, alert = alert))
            else -> nm.cancel(NotificationHelper.PINNED_ID)
        }
        if (Prefs.isRunning(ctx)) scheduleRefresh(ctx)
    }

    /**
     * Mantém o "Conectado há" e a contagem certos: redesenha a cada minuto, mas só com a tela acesa
     * (alarme sem WAKEUP: com o celular dormindo ele espera, sem gastar bateria).
     */
    private fun scheduleRefresh(ctx: Context) {
        val am = ctx.getSystemService(android.app.AlarmManager::class.java) ?: return
        val pi = android.app.PendingIntent.getBroadcast(
            ctx, 4245, android.content.Intent(ctx, PollReceiver::class.java).setAction(PollReceiver.ACTION_REDRAW),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val next = Prefs.getNextCheckAt(ctx)
        val now = System.currentTimeMillis()
        // O que vier primeiro: o próximo minuto ou a hora da verificação (para não ficar negativo).
        val at = if (next > now + 1000) minOf(now + 60_000, next + 1000) else now + 60_000
        runCatching {
            if (PollAlarm.canExact(ctx)) am.setExact(android.app.AlarmManager.RTC, at, pi) else am.set(android.app.AlarmManager.RTC, at, pi)
        }
    }

    fun clear(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(NotificationHelper.PINNED_ID)
    }
}
