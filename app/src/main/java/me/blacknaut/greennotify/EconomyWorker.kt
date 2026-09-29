package me.blacknaut.greennotify

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Rodada periódica.
 *  - Modo economia: UMA requisição (/list pendentes) que serve para mostrar o que é novo e para o
 *    aviso fixo. Intervalo conforme a rede: Wi‑Fi e dados móveis configuráveis separadamente.
 *  - Tempo real: não usa a rede (quem recebe é o WebSocket). A cada 10 min só confere se o serviço
 *    está vivo e toca o lembrete com a contagem já conhecida.
 * O WorkManager só repete de 15 em 15 min, então cada rodada agenda a próxima.
 * Com o celular parado e a tela apagada (soneca), o Android pode atrasar as rodadas.
 */
class EconomyWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!Prefs.isConfigured(ctx) || !Prefs.isRunning(ctx)) return Result.success()
        val economy = Prefs.getMode(ctx) == Prefs.MODE_ECONOMY

        var shownNow = 0
        if (economy) {
            val response = ApiClient.postSync(
                ctx, "/list", ApiClient.authBody(ctx).put("status", "pending").put("limit", 100)
            )
            val list = response?.optJSONArray("notifications")
            if (list != null) {
                for (i in 0 until list.length()) {
                    val n = list.getJSONObject(i)
                    if (n.optBoolean("delivered")) continue
                    NotificationHelper.showIncoming(ctx, n)
                    ApiClient.postSync(ctx, "/ack", ApiClient.authBody(ctx).put("id", n.optString("id")))
                    shownNow++
                }
                val latest = if (list.length() > 0) list.getJSONObject(0).optString("title") else ""
                Prefs.setPending(ctx, list.length(), latest)
            }
        } else if (!NotifyConnectionService.isAlive) {
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

        scheduleNext(ctx)
        return Result.success()
    }

    companion object {
        private const val WORK = "greennotify-checker"
        private const val KEY_REMINDER = "reminder"
        const val REMIND_MINUTES = 10L

        private fun request(delayMinutes: Long, reminder: Boolean, needsNetwork: Boolean) =
            OneTimeWorkRequestBuilder<EconomyWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setInputData(androidx.work.workDataOf(KEY_REMINDER to reminder))
                .apply {
                    if (needsNetwork) setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                }
                .build()

        /** Próximo intervalo: no economia depende da rede; no tempo real é o do lembrete. */
        fun nextMinutes(ctx: Context): Long =
            if (Prefs.getMode(ctx) == Prefs.MODE_ECONOMY) {
                (if (NetworkInfo.isWifi(ctx)) Prefs.getWifiMinutes(ctx) else Prefs.getMobileMinutes(ctx)).toLong()
            } else REMIND_MINUTES

        /** (Re)inicia o ciclo: uma rodada agora e depois conforme o intervalo. */
        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            wm.cancelUniqueWork("greennotify-economy") // ciclos de versões anteriores
            wm.cancelUniqueWork("greennotify-economy-now")
            val economy = Prefs.getMode(ctx) == Prefs.MODE_ECONOMY
            wm.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request(0, reminder = false, needsNetwork = economy))
        }

        private fun scheduleNext(ctx: Context) {
            val economy = Prefs.getMode(ctx) == Prefs.MODE_ECONOMY
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK, ExistingWorkPolicy.APPEND_OR_REPLACE,
                request(nextMinutes(ctx), reminder = true, needsNetwork = economy)
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
