package me.blacknaut.greennotify

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Checagem a cada 10 minutos, nos dois modos:
 *  - modo economia: busca o que ainda não foi entregue, mostra e confirma;
 *  - sempre: atualiza o aviso fixo de pendentes e, se ainda houver pendentes, toca o lembrete.
 * O WorkManager só repete de 15 em 15 min, então cada rodada agenda a próxima para daqui a 10.
 * Em modo soneca profunda (celular parado, tela apagada) o Android pode atrasar as rodadas.
 */
class EconomyWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!Prefs.isConfigured(ctx) || !Prefs.isRunning(ctx)) return Result.success()

        var shownNow = 0
        if (Prefs.getMode(ctx) == Prefs.MODE_ECONOMY) {
            val response = ApiClient.postSync(ctx, "/list", ApiClient.authBody(ctx).put("status", "pending"))
            val list = response?.optJSONArray("notifications")
            if (list != null) for (i in 0 until list.length()) {
                val n = list.getJSONObject(i)
                if (n.optBoolean("delivered")) continue
                NotificationHelper.showIncoming(ctx, n)
                ApiClient.postSync(ctx, "/ack", ApiClient.authBody(ctx).put("id", n.optString("id")))
                shownNow++
            }
        }

        // Lembrete só nas rodadas periódicas e só se nada novo acabou de tocar (evita som duplo).
        val reminder = inputData.getBoolean(KEY_REMINDER, false) && shownNow == 0 && Prefs.isRemindEnabled(ctx)
        PinnedSummary.refreshSync(ctx, alert = reminder)

        scheduleNext(ctx)
        return Result.success()
    }

    companion object {
        private const val WORK = "greennotify-checker"
        private const val KEY_REMINDER = "reminder"
        const val INTERVAL_MINUTES = 10L

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** (Re)inicia o ciclo: uma checagem agora e depois de 10 em 10 minutos. */
        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            wm.cancelUniqueWork("greennotify-economy") // ciclo antigo de 15 min (versões anteriores)
            wm.cancelUniqueWork("greennotify-economy-now")
            wm.enqueueUniqueWork(
                WORK, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<EconomyWorker>().setConstraints(network).build()
            )
        }

        private fun scheduleNext(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<EconomyWorker>()
                    .setInitialDelay(INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .setInputData(workDataOf(KEY_REMINDER to true))
                    .setConstraints(network)
                    .build()
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
