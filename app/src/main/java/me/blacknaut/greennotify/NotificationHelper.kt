package me.blacknaut.greennotify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONObject

fun isWebLink(link: String): Boolean =
    link.startsWith("http://", ignoreCase = true) || link.startsWith("https://", ignoreCase = true)

object NotificationHelper {
    const val CHANNEL_SERVICE = "greennotify_service"
    const val CHANNEL_ALERTS = "greennotify_alerts"

    // Notificações recebidas usam o id do servidor como tag: (tag, ALERT_ID) é único, sem colisão de hashCode.
    private const val ALERT_ID = 1
    private const val SERVICE_STOPPED_TAG = "greennotify:service-stopped"
    private const val SUMMARY_TAG = "greennotify:summary"
    private const val GROUP_ALERTS = "greennotify.alerts"
    private const val SUMMARY_REQUEST = -1

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)

        val service = NotificationChannel(
            CHANNEL_SERVICE, ctx.getString(R.string.channel_service_name), NotificationManager.IMPORTANCE_MIN
        ).apply { description = ctx.getString(R.string.channel_service_desc) }

        val alerts = NotificationChannel(
            CHANNEL_ALERTS, ctx.getString(R.string.channel_alerts_name), NotificationManager.IMPORTANCE_HIGH
        ).apply { description = ctx.getString(R.string.channel_alerts_desc) }

        nm.createNotificationChannel(service)
        nm.createNotificationChannel(alerts)
    }

    /** Exibe uma notificação recebida do servidor, incluindo o motivo (reason). */
    fun showIncoming(ctx: Context, json: JSONObject) {
        val title = json.optString("title").ifBlank { ctx.getString(R.string.default_notification_title) }
        val message = json.optString("message", "")
        val reason = json.optString("reason", "")
        val app = json.optString("app", "")
        val link = json.optString("link", "")
        val id = json.optString("id", System.currentTimeMillis().toString())

        val bodyBuilder = StringBuilder()
        if (message.isNotBlank()) bodyBuilder.append(message)
        if (reason.isNotBlank()) {
            if (bodyBuilder.isNotEmpty()) bodyBuilder.append("\n")
            bodyBuilder.append(ctx.getString(R.string.reason_format, reason))
        }
        if (app.isNotBlank()) {
            if (bodyBuilder.isNotEmpty()) bodyBuilder.append("\n")
            bodyBuilder.append(ctx.getString(R.string.origin_format, app))
        }

        // Sem app que abra o link, o toque cai no histórico em vez de não fazer nada.
        val linkIntent = if (isWebLink(link)) Intent(Intent.ACTION_VIEW, Uri.parse(link)) else null
        val targetIntent = linkIntent?.takeIf { it.resolveActivity(ctx.packageManager) != null }
            ?: Intent(ctx, NotificationsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            ctx, id.hashCode(), targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(if (message.isNotBlank()) message else reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyBuilder.toString()))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_ALERTS)
            .build()

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(id, ALERT_ID, notification)
        postGroupSummary(ctx, nm)
    }

    // Grupo próprio com resumo: várias notificações viram uma pilha expansível e não se misturam
    // com a notificação fixa do serviço (o agrupamento automático do Android faria o toque abrir o app).
    private fun postGroupSummary(ctx: Context, nm: NotificationManager) {
        val count = alertIds(nm).size
        if (count == 0) return
        val openHistory = PendingIntent.getActivity(
            ctx, SUMMARY_REQUEST, Intent(ctx, NotificationsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val summary = NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(ctx.resources.getQuantityString(R.plurals.summary_count, count, count))
            .setGroup(GROUP_ALERTS)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(openHistory)
            .build()
        nm.notify(SUMMARY_TAG, ALERT_ID, summary)
    }

    private fun alertIds(nm: NotificationManager): List<String> =
        nm.activeNotifications.filter {
            it.id == ALERT_ID && it.tag != null && it.tag != SUMMARY_TAG && it.tag != SERVICE_STOPPED_TAG
        }.map { it.tag }

    private fun refreshSummary(ctx: Context, nm: NotificationManager) {
        if (alertIds(nm).isEmpty()) nm.cancel(SUMMARY_TAG, ALERT_ID) else postGroupSummary(ctx, nm)
    }

    /** Avisa que o serviço parou sozinho; a notificação fixa do serviço some junto com ele. */
    fun showServiceStopped(ctx: Context, reason: String) {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(ctx.getString(R.string.service_stopped_title))
            .setContentText(reason)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(SERVICE_STOPPED_TAG, ALERT_ID, notification)
    }

    fun cancel(ctx: Context, id: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(id, ALERT_ID)
        refreshSummary(ctx, nm)
    }

    /** Remove só as notificações recebidas; a fixa do serviço e o aviso de parada ficam. */
    fun cancelAll(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        alertIds(nm).forEach { nm.cancel(it, ALERT_ID) }
        nm.cancel(SUMMARY_TAG, ALERT_ID)
    }
}
