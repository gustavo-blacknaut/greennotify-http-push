package me.blacknaut.greennotify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
     * Aviso fixo, estilo status de VPN: sempre presente enquanto o app está ativo.
     *  - cronômetro de tempo ativo (o próprio sistema desenha, sem gastar bateria);
     *  - estado da rede atual, hora da próxima verificação e consumo de hoje (texto montado só quando algo muda);
     *  - sem pendentes: discreto, "Nenhuma notificação pendente";
     *  - com pendentes: prioridade máxima, no topo, "N notificações pendentes · Última: ...".
     * [alert] faz tocar de novo (lembrete).
     */
    fun pinned(ctx: Context, foreground: Boolean, alert: Boolean): android.app.Notification {
        createChannels(ctx)
        val count = Prefs.getPendingCount(ctx)
        val latest = Prefs.getLatestTitle(ctx)
        val problem = Prefs.getConnStatus(ctx)
        val p = Policy.current(ctx)
        val netName = ctx.getString(if (p.net == NetworkInfo.Net.MOBILE) R.string.net_mobile else R.string.net_wifi)
        val state = when {
            p.net == NetworkInfo.Net.NONE -> ctx.getString(R.string.pinned_no_network)
            p.kind == Policy.REALTIME -> ctx.getString(R.string.pinned_state_realtime, netName, p.pingMin)
            p.kind == Policy.POLLING -> ctx.getString(R.string.pinned_state_polling, netName, p.pollMin)
            else -> ctx.getString(R.string.pinned_state_off, netName)
        }
        val nextAt = Prefs.getNextCheckAt(ctx)
        val next = if (p.kind == Policy.POLLING && nextAt > System.currentTimeMillis())
            ctx.getString(R.string.pinned_next, android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(nextAt))) else ""
        val details = listOf(state, next, todayUsage(ctx)).filter { it.isNotBlank() }
        val since = Prefs.getActiveSince(ctx)

        val open = PendingIntent.getActivity(
            ctx, PINNED_ID, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(ctx, if (count > 0) CHANNEL_PINNED else CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_greennotify)
            .setColor(ContextCompat.getColor(ctx, R.color.green))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setSilent(!alert)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (since > 0) b.setUsesChronometer(true).setWhen(since).setShowWhen(true) else b.setShowWhen(false)

        if (count > 0) {
            val title = ctx.resources.getQuantityString(R.plurals.pinned_count, count, count)
            // Colorida (fundo verde) o título fica na cor do sistema; senão, em destaque verde.
            val colorized = foreground && Build.VERSION.SDK_INT < 36
            val head = if (latest.isNotBlank()) ctx.getString(R.string.pinned_latest, latest) else problem
            b.setContentTitle(if (colorized) title else highlight(ctx, title))
                .setContentText(head.ifBlank { state })
                .setStyle(NotificationCompat.BigTextStyle().bigText((listOf(head) + details).filter { it.isNotBlank() }.joinToString("\n")))
                .setLargeIcon(logo(ctx))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // Android 16+: pede para virar "Live Update" (Samsung: tela de bloqueio/Now Bar).
            // Antes disso, notificação colorida de serviço fica no topo como a do Spotify.
            if (Build.VERSION.SDK_INT >= 36) b.extras.putBoolean("android.requestPromotedOngoing", true)
            else if (foreground) b.setColorized(true)
        } else {
            val line = problem.ifBlank { listOf(state, next).filter { it.isNotBlank() }.joinToString(" · ") }
            b.setContentTitle(ctx.getString(R.string.pinned_none))
                .setContentText(line)
                .setStyle(NotificationCompat.BigTextStyle().bigText(details.joinToString("\n")))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
        }
        if (foreground) b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    /** "Hoje: 12,4 KB no Wi‑Fi · 3,1 KB nos dados" (só as redes com tráfego). */
    private fun todayUsage(ctx: Context): String {
        val wifi = Stats.counters(ctx, NetworkInfo.Net.WIFI, 1).bytes
        val mobile = Stats.counters(ctx, NetworkInfo.Net.MOBILE, 1).bytes
        if (wifi == 0L && mobile == 0L) return ""
        val parts = mutableListOf<String>()
        if (wifi > 0) parts += ctx.getString(R.string.pinned_usage_wifi, Fmt.bytes(wifi))
        if (mobile > 0) parts += ctx.getString(R.string.pinned_usage_mobile, Fmt.bytes(mobile))
        return ctx.getString(R.string.pinned_today, parts.joinToString(" · "))
    }

    /**
     * Exibe uma notificação recebida do servidor. Tocar nela abre o modal com os detalhes daquela
     * notificação. Se tiver imagem, ela é baixada em segundo plano e a notificação é atualizada com ela.
     */
    fun showIncoming(ctx: Context, json: JSONObject) {
        // No modo economia o serviço (que criava os canais) pode nunca ter rodado.
        createChannels(ctx)
        val id = json.optString("id", System.currentTimeMillis().toString())
        val subject = subjectOf(ctx, json)
        val nm = ctx.getSystemService(NotificationManager::class.java)

        val notification = buildIncoming(ctx, json, id, subject, null)
        nm.notify(id, ALERT_ID, notification)
        postGroupSummary(ctx, nm, subject, id to notification)

        val image = json.optString("image")
        if (isWebLink(image)) {
            val app = ctx.applicationContext
            Thread {
                val bmp = ImageLoader.loadSync(image, 1024) ?: return@Thread
                val withPicture = buildIncoming(app, json, id, subject, bmp)
                // Só atualiza se a notificação ainda estiver lá (você pode tê-la dispensado enquanto baixava).
                if (nm.activeNotifications.any { it.tag == id && it.id == ALERT_ID }) nm.notify(id, ALERT_ID, withPicture)
            }.start()
        }
        NotificationBus.newNotification()
    }

    private fun buildIncoming(
        ctx: Context, json: JSONObject, id: String, subject: String, picture: android.graphics.Bitmap?
    ): android.app.Notification {
        val title = json.optString("title").ifBlank { ctx.getString(R.string.default_notification_title) }
        val message = json.optString("message", "")
        val reason = json.optString("reason", "")
        val app = json.optString("app", "")

        val body = StringBuilder()
        if (message.isNotBlank()) body.append(message)
        if (reason.isNotBlank()) {
            if (body.isNotEmpty()) body.append("\n")
            body.append(ctx.getString(R.string.reason_format, reason))
        }
        if (app.isNotBlank()) {
            if (body.isNotEmpty()) body.append("\n")
            body.append(ctx.getString(R.string.origin_format, app))
        }

        // Tocar abre o modal desta notificação (o link fica dentro dele); o texto completo está lá.
        val open = PendingIntent.getActivity(
            ctx, id.hashCode(),
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_NOTIF, json.toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val b = NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_greennotify)
            .setColor(ContextCompat.getColor(ctx, R.color.green))
            .setContentTitle(highlight(ctx, title))
            .setContentText(if (message.isNotBlank()) message else reason)
            .setAutoCancel(true)
            // Canal IMPORTANCE_HIGH + prioridade máxima: aparece como pop-up e fica no topo da lista.
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            // Hora do envio, não da chegada (no modo economia pode chegar minutos depois).
            .setWhen(json.optLong("createdAt").takeIf { it > 0 } ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(open)
            .setGroup(groupKey(subject))
        if (picture != null) {
            b.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(picture)
                    .bigLargeIcon(null as android.graphics.Bitmap?)
                    .setSummaryText(body.toString())
            )
                .setLargeIcon(android.graphics.Bitmap.createScaledBitmap(picture, 128, 128 * picture.height / picture.width.coerceAtLeast(1), true))
                .setOnlyAlertOnce(true)
        } else {
            b.setStyle(NotificationCompat.BigTextStyle().bigText(body.toString())).setLargeIcon(logo(ctx))
        }
        return b.build()
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

/** Formata bytes em pt-BR (mesmo formato da tela de consumo). */
object Fmt {
    fun bytes(b: Long): String {
        val pt = java.util.Locale("pt", "BR")
        return when {
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> String.format(pt, "%.1f KB", b / 1024.0)
            else -> String.format(pt, "%.2f MB", b / 1048576.0)
        }
    }
}
