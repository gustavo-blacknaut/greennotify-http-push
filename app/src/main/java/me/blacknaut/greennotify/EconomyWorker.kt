package me.blacknaut.greennotify

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Modo economia: sem conexão aberta nem serviço fixo. A cada ~15 min (mínimo do Android; o próprio
 * sistema agrupa com outras tarefas para poupar bateria) busca o que ainda não foi entregue,
 * mostra e confirma. O WorkManager mantém o agendamento depois de reiniciar o celular.
 */
class EconomyWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!Prefs.isConfigured(ctx)) return Result.success()
        val response = ApiClient.postSync(ctx, "/list", ApiClient.authBody(ctx).put("status", "pending"))
            ?: return Result.retry()
        val list = response.optJSONArray("notifications") ?: return Result.success()
        for (i in 0 until list.length()) {
            val n = list.getJSONObject(i)
            if (n.optBoolean("delivered")) continue
            NotificationHelper.showIncoming(ctx, n)
            ApiClient.postSync(ctx, "/ack", ApiClient.authBody(ctx).put("id", n.optString("id")))
        }
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "greennotify-economy"
        private const val NOW = "greennotify-economy-now"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Agenda a checagem periódica e já faz uma agora. */
        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            wm.enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<EconomyWorker>(15, TimeUnit.MINUTES).setConstraints(network).build()
            )
            wm.enqueueUniqueWork(
                NOW, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<EconomyWorker>().setConstraints(network).build()
            )
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(ctx).cancelUniqueWork(NOW)
        }
    }
}
