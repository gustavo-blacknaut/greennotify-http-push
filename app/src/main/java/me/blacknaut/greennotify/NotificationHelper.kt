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
            .build()

        ctx.getSystemService(NotificationManager::class.java).notify(id, ALERT_ID, notification)
    }

    fun cancel(ctx: Context, id: String) {
        ctx.getSystemService(NotificationManager::class.java).cancel(id, ALERT_ID)
    }

    fun cancelAll(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancelAll()
    }
}
