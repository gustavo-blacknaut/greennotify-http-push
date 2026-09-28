package me.blacknaut.greennotify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONObject

object NotificationHelper {
    const val CHANNEL_SERVICE = "greennotify_service"
    const val CHANNEL_ALERTS = "greennotify_alerts"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)

        val service = NotificationChannel(
            CHANNEL_SERVICE, "Conexão em segundo plano", NotificationManager.IMPORTANCE_MIN
        ).apply { description = "Mantém a conexão com o servidor GreenNotify ativa" }

        val alerts = NotificationChannel(
            CHANNEL_ALERTS, "Notificações recebidas", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Notificações enviadas pelas suas aplicações" }

        nm.createNotificationChannel(service)
        nm.createNotificationChannel(alerts)
    }

    /** Exibe uma notificação recebida do servidor, incluindo o motivo (reason). */
    fun showIncoming(ctx: Context, json: JSONObject) {
        val title = json.optString("title", "Notificação")
        val message = json.optString("message", "")
        val reason = json.optString("reason", "")
        val app = json.optString("app", "")
        val id = json.optString("id", System.currentTimeMillis().toString())

        val bodyBuilder = StringBuilder()
        if (message.isNotBlank()) bodyBuilder.append(message)
        if (reason.isNotBlank()) {
            if (bodyBuilder.isNotEmpty()) bodyBuilder.append("\n")
            bodyBuilder.append("Motivo: ").append(reason)
        }
        if (app.isNotBlank()) {
            if (bodyBuilder.isNotEmpty()) bodyBuilder.append("\n")
            bodyBuilder.append("Origem: ").append(app)
        }

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(if (message.isNotBlank()) message else reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyBuilder.toString()))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(id.hashCode(), notification)
    }

    fun cancel(ctx: Context, id: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(id.hashCode())
    }

    fun cancelAll(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancelAll()
    }
}
