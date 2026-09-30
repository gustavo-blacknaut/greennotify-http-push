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
    }

    fun clear(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(NotificationHelper.PINNED_ID)
    }
}
