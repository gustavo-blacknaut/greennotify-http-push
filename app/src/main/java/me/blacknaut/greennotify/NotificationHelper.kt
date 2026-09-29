package me.blacknaut.greennotify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

fun isWebLink(link: String): Boolean =
    link.startsWith("http://", ignoreCase = true) || link.startsWith("https://", ignoreCase = true)

object NotificationHelper {
    const val CHANNEL_STATUS = "greennotify_status"
    const val CHANNEL_PINNED = "greennotify_pinned"
    const val CHANNEL_ALERTS = "greennotify_alerts"
    const val PINNED_ID = 1002

    // Notificações recebidas usam o id do servidor como tag: (tag, ALERT_ID) é único, sem colisão de hashCode.
    private const val ALERT_ID = 1
    private const val INTERNAL_TAG_PREFIX = "greennotify:"
    private const val SERVICE_STOPPED_TAG = "greennotify:service-stopped"
    private const val SUMMARY_PREFIX = "greennotify:summary:"
    private const val GROUP_PREFIX = "greennotify.subject."

    private var logoBitmap: android.graphics.Bitmap? = null

    private fun logo(ctx: Context): android.graphics.Bitmap {
        logoBitmap?.let { return it }
        val size = (64 * ctx.resources.displayMetrics.density).toInt()
        val src = android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.greencodes_logo)
        return android.graphics.Bitmap.createScaledBitmap(src, size, size, true).also { logoBitmap = it }
    }

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)

        // Sem pendentes: importância baixa = só a logo na barra de status (como a chave da VPN), sem som.
        val status = NotificationChannel(
            CHANNEL_STATUS, ctx.getString(R.string.channel_status_name), NotificationManager.IMPORTANCE_LOW
        ).apply { description = ctx.getString(R.string.channel_status_desc); setShowBadge(false) }

        // Com pendentes: aviso fixo no topo, com prioridade máxima e lembrete.
        val pinned = NotificationChannel(
            CHANNEL_PINNED, ctx.getString(R.string.channel_pinned_name), NotificationManager.IMPORTANCE_HIGH
        ).apply { description = ctx.getString(R.string.channel_pinned_desc) }

        val alerts = NotificationChannel(
            CHANNEL_ALERTS, ctx.getString(R.string.channel_alerts_name), NotificationManager.IMPORTANCE_HIGH
        ).apply { description = ctx.getString(R.string.channel_alerts_desc) }

        nm.createNotificationChannels(listOf(status, pinned, alerts))
        // Canal antigo (importância mínima, texto "Conectado a ...") não é mais usado.
        nm.deleteNotificationChannel("greennotify_service")
    }

    /**
     * Aviso fixo. Sem pendentes: discreto, só a logo na barra de status e, na gaveta, uma linha
     * (texto de conexão só se algo estiver errado). Com pendentes: prioridade máxima, no topo,
     * "N notificações pendentes · Última: ...". [alert] faz tocar de novo (lembrete).
     */
    fun pinned(ctx: Context, foreground: Boolean, alert: Boolean): android.app.Notification {
        createChannels(ctx)
        val count = Prefs.getPendingCount(ctx)
        val latest = Prefs.getLatestTitle(ctx)
        val problem = Prefs.getConnStatus(ctx)
        val open = PendingIntent.getActivity(
            ctx, PINNED_ID, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(ctx, if (count > 0) CHANNEL_PINNED else CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_greennotify)
            .setColor(ContextCompat.getColor(ctx, R.color.green))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(!alert)
            .setSilent(!alert)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (count > 0) {
            val title = ctx.resources.getQuantityString(R.plurals.pinned_count, count, count)
            // Colorida (fundo verde) o título fica na cor do sistema; senão, em destaque verde.
            val colorized = foreground && Build.VERSION.SDK_INT < 36
            b.setContentTitle(if (colorized) title else highlight(ctx, title))
                .setContentText(if (latest.isNotBlank()) ctx.getString(R.string.pinned_latest, latest) else problem)
                .setLargeIcon(logo(ctx))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // Android 16+: pede para virar "Live Update" (Samsung: tela de bloqueio/Now Bar).
            // Antes disso, notificação colorida de serviço fica no topo como a do Spotify.
            if (Build.VERSION.SDK_INT >= 36) b.extras.putBoolean("android.requestPromotedOngoing", true)
            else if (foreground) b.setColorized(true)
        } else {
            b.setContentTitle(ctx.getString(R.string.pinned_none))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
            if (problem.isNotBlank()) b.setContentText(problem)
        }
        if (foreground) b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    /** Exibe uma notificação recebida do servidor, incluindo o motivo (reason). */
    fun showIncoming(ctx: Context, json: JSONObject) {
        // No modo economia o serviço (que criava os canais) pode nunca ter rodado.
        createChannels(ctx)
        val title = json.optString("title").ifBlank { ctx.getString(R.string.default_notification_title) }
        val message = json.optString("message", "")
        val reason = json.optString("reason", "")
        val app = json.optString("app", "")
        val link = json.optString("link", "")
        val id = json.optString("id", System.currentTimeMillis().toString())
        val subject = subjectOf(ctx, json)

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
            ?: Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            ctx, id.hashCode(), targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_greennotify)
            .setColor(ContextCompat.getColor(ctx, R.color.green))
            .setContentTitle(highlight(ctx, title))
            .setContentText(if (message.isNotBlank()) message else reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyBuilder.toString()))
            .setLargeIcon(logo(ctx))
            .setAutoCancel(true)
            // Canal IMPORTANCE_HIGH + prioridade máxima: aparece como pop-up e fica no topo da lista.
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            // Hora do envio, não da chegada (no modo economia pode chegar minutos depois).
            .setWhen(json.optLong("createdAt").takeIf { it > 0 } ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(pendingIntent)
            .setGroup(groupKey(subject))
            .build()

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(id, ALERT_ID, notification)
        postGroupSummary(ctx, nm, subject, id to notification)
    }

    /** Assunto que junta notificações: o "topic" enviado; sem ele, a origem ("app"). */
    private fun subjectOf(ctx: Context, json: JSONObject): String =
        json.optString("topic").ifBlank { json.optString("app") }.ifBlank { ctx.getString(R.string.subject_general) }

    private fun groupKey(subject: String) = GROUP_PREFIX + subject

    // Título em destaque na cor da marca, como os apps grandes fazem (ex.: Mercado Livre).
    private fun highlight(ctx: Context, text: CharSequence): CharSequence =
        android.text.SpannableString(text).apply {
            setSpan(android.text.style.ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.green_leaf)), 0, length, 0)
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0)
        }

    // Um grupo por assunto, cada um com resumo próprio: tudo do mesmo assunto vira uma pilha só,
    // separada da notificação fixa (o agrupamento automático do Android faria o toque abrir o app).
    private fun postGroupSummary(
        ctx: Context, nm: NotificationManager, subject: String,
        justPosted: Pair<String, android.app.Notification>? = null, justRemoved: String? = null
    ) {
        // activeNotifications é atualizado de forma assíncrona: corrige com o que acabou de entrar/sair.
        val children = childrenOf(nm, groupKey(subject))
            .filter { it.tag != justPosted?.first && it.tag != justRemoved }
            .map { it.notification } + listOfNotNull(justPosted?.second)
        if (children.isEmpty()) {
            nm.cancel(SUMMARY_PREFIX + subject, ALERT_ID)
            return
        }
        val openHistory = PendingIntent.getActivity(
            ctx, (SUMMARY_PREFIX + subject).hashCode(),
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val count = children.size
        val countText = ctx.resources.getQuantityString(R.plurals.summary_count, count, count)
        // O cabeçalho da pilha mostra o summaryText: é ali que o nome do assunto aparece.
        val inbox = NotificationCompat.InboxStyle().setSummaryText(subject)
        children.sortedByDescending { it.`when` }.take(5).forEach { n ->
            val t = n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE) ?: ""
            val m = n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT) ?: ""
            inbox.addLine(if (m.isBlank()) t else android.text.TextUtils.concat(t, "  ", m))
        }
        val summary = NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_greennotify)
            .setColor(ContextCompat.getColor(ctx, R.color.green))
            .setContentTitle(highlight(ctx, subject))
            .setContentText(countText)
            .setLargeIcon(logo(ctx))
            .setStyle(inbox)
            .setGroup(groupKey(subject))
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(openHistory)
            .build()
        nm.notify(SUMMARY_PREFIX + subject, ALERT_ID, summary)
    }

    private fun isReceived(sbn: android.service.notification.StatusBarNotification) =
        sbn.id == ALERT_ID && sbn.tag != null && !sbn.tag.startsWith(INTERNAL_TAG_PREFIX)

    private fun childrenOf(nm: NotificationManager, group: String) =
        nm.activeNotifications.filter { isReceived(it) && it.notification.group == group }

    /** Avisa que o serviço parou sozinho; a notificação fixa do serviço some junto com ele. */
    fun showServiceStopped(ctx: Context, reason: String) {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_greennotify)
            .setColor(ContextCompat.getColor(ctx, R.color.green))
            .setContentTitle(ctx.getString(R.string.service_stopped_title))
            .setContentText(reason)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(SERVICE_STOPPED_TAG, ALERT_ID, notification)
    }

    fun cancel(ctx: Context, id: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val group = nm.activeNotifications.firstOrNull { it.tag == id && it.id == ALERT_ID }?.notification?.group
        nm.cancel(id, ALERT_ID)
        if (group != null) postGroupSummary(ctx, nm, group.removePrefix(GROUP_PREFIX), justRemoved = id)
    }

    /** Remove só as notificações recebidas e seus resumos; a fixa do serviço e o aviso de parada ficam. */
    fun cancelAll(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.activeNotifications
            .filter { it.id == ALERT_ID && it.tag != null && (isReceived(it) || it.tag.startsWith(SUMMARY_PREFIX)) }
            .forEach { nm.cancel(it.tag, ALERT_ID) }
    }
}
