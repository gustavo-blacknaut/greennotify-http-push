package me.blacknaut.greennotify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject

/**
 * Toque numa notificação com tapAction "link"/"link_done" (ex.: mensagem de ticket do Discord):
 * abre o link direto (o Discord abre no canal) e, com "link_done", já marca como resolvida.
 * Não tem tela: faz o trabalho e fecha.
 */
class TapActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val json = intent.getStringExtra(EXTRA_JSON)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (json != null) handle(this, NotificationItem.fromJson(json))
        finish()
    }

    companion object {
        const val EXTRA_JSON = "json"

        /** Usado também pela lista do app, para o toque no cartão fazer o mesmo que na notificação. */
        fun handle(ctx: Context, item: NotificationItem): Boolean {
            if (!isWebLink(item.link)) return false
            val opened = try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: Exception) {
                Toast.makeText(ctx, R.string.error_invalid_link, Toast.LENGTH_SHORT).show()
                false
            }
            if (item.tapAction == "link_done") {
                NotificationHelper.cancel(ctx, item.id)
                ResolveWorker.enqueue(ctx, item.id)
            }
            return opened
        }
    }
}

/** Marca uma notificação como concluída no servidor; o WorkManager tenta de novo se estiver sem internet. */
class ResolveWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        val ok = ApiClient.postSync(applicationContext, "/move",
            ApiClient.authBody(applicationContext).put("id", id).put("status", "done")) != null
        if (!ok) return if (runAttemptCount < 5) Result.retry() else Result.failure()
        PinnedSummary.refreshSync(applicationContext, alert = false)
        NotificationBus.newNotification()
        return Result.success()
    }

    companion object {
        private const val KEY_ID = "id"

        fun enqueue(ctx: Context, id: String) {
            val request = OneTimeWorkRequestBuilder<ResolveWorker>()
                .setInputData(workDataOf(KEY_ID to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueue(request)
        }
    }
}
