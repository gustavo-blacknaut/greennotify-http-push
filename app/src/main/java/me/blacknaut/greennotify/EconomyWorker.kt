package me.blacknaut.greennotify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
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
 *
 * Quem dispara cada rodada é o despertador do Android (AlarmManager), que funciona com o celular dormindo
 * (o WorkManager sozinho era adiado pelo modo soneca e a contagem ficava negativa sem verificar nada).
 * O WorkManager fica só de reserva: a cada 15 min confere se alguma rodada passou da hora e roda ela.
 */
object EconomyCycle {
    const val REMIND_MINUTES = 10L

    /** Próxima rodada: no intervalo da rede em que está consultando; senão, no do lembrete. */
    fun nextMinutes(ctx: Context): Long {
        val p = Policy.current(ctx)
        return if (p.kind == Policy.POLLING) p.pollMin.toLong() else REMIND_MINUTES
    }

    @Synchronized
    fun run(ctx: Context, reminderAllowed: Boolean) {
        if (!Prefs.isConfigured(ctx) || !Prefs.isRunning(ctx)) return
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
                runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, NotifyConnectionService::class.java)) }
        }

        // Lembrete no máximo a cada 10 min, e nunca junto com uma notificação que acabou de tocar.
        val now = System.currentTimeMillis()
        val reminder = reminderAllowed && shownNow == 0 &&
            Prefs.isRemindEnabled(ctx) && Prefs.getPendingCount(ctx) > 0 &&
            now - Prefs.getLastReminderAt(ctx) >= TimeUnit.MINUTES.toMillis(REMIND_MINUTES) - 30_000
        if (reminder) Prefs.setLastReminderAt(ctx, now)

        // A próxima hora precisa estar salva antes de redesenhar o aviso, que mostra a contagem.
        val next = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(nextMinutes(ctx))
        Prefs.setNextCheckAt(ctx, next)
        PollAlarm.set(ctx, next)
        PinnedSummary.post(ctx, alert = reminder)
        Stats.sample(ctx, p.net)
    }

    /** (Re)inicia o ciclo: uma rodada agora, o despertador para as próximas e a reserva do WorkManager. */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        // ciclos de versões anteriores
        wm.cancelUniqueWork("greennotify-economy")
        wm.cancelUniqueWork("greennotify-economy-now")
        wm.cancelUniqueWork("greennotify-checker")
        wm.enqueueUniqueWork(
            EconomyWorker.NOW, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<EconomyWorker>().setInputData(workDataOf(EconomyWorker.KEY_FORCE to true)).build()
        )
        wm.enqueueUniquePeriodicWork(
            EconomyWorker.BACKUP, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<EconomyWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    fun cancel(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(EconomyWorker.NOW)
        wm.cancelUniqueWork(EconomyWorker.BACKUP)
        wm.cancelUniqueWork("greennotify-checker")
        wm.cancelUniqueWork("greennotify-economy")
        wm.cancelUniqueWork("greennotify-economy-now")
        PollAlarm.cancel(ctx)
        Prefs.setNextCheckAt(ctx, 0)
    }
}

/** Rodada imediata (ao ligar) ou reserva periódica (só roda se o despertador passou da hora). */
class EconomyWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val force = inputData.getBoolean(KEY_FORCE, false)
        val next = Prefs.getNextCheckAt(ctx)
        val overdue = next == 0L || System.currentTimeMillis() > next + 60_000
        if (force) EconomyCycle.run(ctx, reminderAllowed = false)
        else if (overdue) EconomyCycle.run(ctx, reminderAllowed = true)
        else PollAlarm.set(ctx, next) // garante que o despertador continua armado
        return Result.success()
    }

    companion object {
        const val NOW = "greennotify-now"
        const val BACKUP = "greennotify-backup"
        const val KEY_FORCE = "force"
        const val REMIND_MINUTES = EconomyCycle.REMIND_MINUTES

        fun nextMinutes(ctx: Context) = EconomyCycle.nextMinutes(ctx)
        fun schedule(ctx: Context) = EconomyCycle.schedule(ctx)
        fun cancel(ctx: Context) = EconomyCycle.cancel(ctx)
    }
}

/**
 * Despertador do Android para a próxima verificação. Com a permissão "Alarmes e lembretes" ele dispara
 * na hora exata, mesmo dormindo; sem ela, o Android pode adiantar ou atrasar alguns minutos.
 * Na soneca profunda o Android limita a cerca de 1 disparo a cada 9 min por app.
 */
object PollAlarm {
    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 4242, Intent(ctx, PollReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    // Redesenho do aviso quando a contagem chega a zero ("Verificando agora…" em vez de negativo).
    // Não acorda o celular: se a tela estiver ligada roda na hora; apagada, espera ela ligar.
    private fun redraw(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 4243, Intent(ctx, PollReceiver::class.java).setAction(PollReceiver.ACTION_REDRAW),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun set(ctx: Context, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
            // Exato (sem acordar): com a tela acesa troca para "verificando agora…" no segundo em que zera.
            if (canExact(ctx)) am.setExact(AlarmManager.RTC, at + 300, redraw(ctx))
            else am.set(AlarmManager.RTC, at + 300, redraw(ctx))
        }
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pending(ctx))
        ctx.getSystemService(AlarmManager::class.java)?.cancel(redraw(ctx))
    }
}

/** Disparo do despertador: faz a rodada (uma requisição) e agenda a próxima. */
class PollReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_REDRAW) {
            if (Prefs.isRunning(ctx)) PinnedSummary.post(ctx)
            return
        }
        if (intent.action == ACTION_DISMISSED) {
            val id = intent.getStringExtra(EXTRA_ID)
            if (id != null && Prefs.isDismissResolves(ctx)) ResolveWorker.enqueue(ctx, id)
            return
        }
        if (intent.action == ACTION_PINNED_DISMISSED) {
            if (Prefs.isRunning(ctx) && Prefs.isPinnedForced(ctx)) PinnedSummary.post(ctx)
            return
        }
        val result = goAsync()
        val app = ctx.applicationContext
        Thread {
            try { EconomyCycle.run(app, reminderAllowed = true) } finally { result.finish() }
        }.start()
    }

    companion object {
        const val ACTION_REDRAW = "me.blacknaut.greennotify.REDRAW"
        const val ACTION_PINNED_DISMISSED = "me.blacknaut.greennotify.PINNED_DISMISSED"
        const val ACTION_DISMISSED = "me.blacknaut.greennotify.DISMISSED"
        const val EXTRA_ID = "id"
    }
}
