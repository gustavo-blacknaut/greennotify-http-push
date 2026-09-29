package me.blacknaut.greennotify

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import me.blacknaut.greennotify.NetworkInfo.Net
import java.util.concurrent.TimeUnit

/**
 * Rodada periódica, decidida pela política da rede ativa:
 *  - rede configurada como "consultar": UMA requisição (/list pendentes) que mostra o que é novo,
 *    confirma a entrega e alimenta o aviso fixo. Repete no intervalo escolhido para aquela rede;
 *  - rede em tempo real: não usa a rede (quem recebe é o WebSocket); só religa o serviço se o sistema o matou;
 *  - rede desligada / sem conexão: não faz nada.
 * O lembrete de pendentes toca no máximo a cada 10 min. O WorkManager só repete de 15 em 15 min por conta
 * própria, então cada rodada agenda a próxima. Com o celular parado e a tela apagada (soneca), o Android
 * pode atrasar as rodadas.
 */
class EconomyWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!Prefs.isConfigured(ctx) || !Prefs.isRunning(ctx)) return Result.success()
        val p = Policy.current(ctx)
        Stats.sample(ctx, p.net)

        var shownNow = 0
        when {
            p.net != Net.NONE && p.kind == Policy.POLLING -> {
                Stats.addPoll(ctx, p.net)
                val response = ApiClient.postSync(
                    ctx, "/list", ApiClient.authBody(ctx).put("status", "pending").put("limit", 100)
                )
                val list = response?.optJSONArray("notifications")
                if (list != null) {
                    for (i in 0 until list.length()) {
                        val n = list.getJSONObject(i)
                        if (n.optBoolean("delivered")) continue
                        NotificationHelper.showIncoming(ctx, n)
                        Stats.addMessage(ctx, p.net)
                        ApiClient.postSync(ctx, "/ack", ApiClient.authBody(ctx).put("id", n.optString("id")))
                        shownNow++
                    }
                    val latest = if (list.length() > 0) list.getJSONObject(0).optString("title") else ""
                    Prefs.setPending(ctx, list.length(), latest)
                }
            }
            p.kind == Policy.REALTIME && !NotifyConnectionService.isAlive ->
                // O sistema matou o serviço de tempo real: religa.
                ContextCompat.startForegroundService(ctx, Intent(ctx, NotifyConnectionService::class.java))
        }

        // Lembrete no máximo a cada 10 min, e nunca junto com uma notificação que acabou de tocar.
        val now = System.currentTimeMillis()
        val reminder = inputData.getBoolean(KEY_REMINDER, false) && shownNow == 0 &&
            Prefs.isRemindEnabled(ctx) && Prefs.getPendingCount(ctx) > 0 &&
            now - Prefs.getLastReminderAt(ctx) >= TimeUnit.MINUTES.toMillis(REMIND_MINUTES) - 30_000
        if (reminder) Prefs.setLastReminderAt(ctx, now)
        PinnedSummary.post(ctx, alert = reminder)
        Stats.sample(ctx, p.net)

        scheduleNext(ctx)
        return Result.success()
    }

    companion object {
        private const val WORK = "greennotify-checker"
        private const val KEY_REMINDER = "reminder"
        const val REMIND_MINUTES = 10L

        private fun request(delayMinutes: Long, reminder: Boolean) =
            OneTimeWorkRequestBuilder<EconomyWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_REMINDER to reminder))
                .build()

        /** Próxima rodada: no intervalo da rede em que está consultando; senão, no do lembrete. */
        fun nextMinutes(ctx: Context): Long {
            val p = Policy.current(ctx)
            return if (p.kind == Policy.POLLING) p.pollMin.toLong() else REMIND_MINUTES
        }

        /** (Re)inicia o ciclo: uma rodada agora e depois conforme o intervalo. */
        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            wm.cancelUniqueWork("greennotify-economy") // ciclos de versões anteriores
            wm.cancelUniqueWork("greennotify-economy-now")
            wm.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request(0, reminder = false))
        }

        private fun scheduleNext(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request(nextMinutes(ctx), reminder = true)
            )
        }

        fun cancel(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            wm.cancelUniqueWork(WORK)
            wm.cancelUniqueWork("greennotify-economy")
            wm.cancelUniqueWork("greennotify-economy-now")
        }
    }
}
