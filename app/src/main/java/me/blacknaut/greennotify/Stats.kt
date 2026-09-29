package me.blacknaut.greennotify

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import me.blacknaut.greennotify.NetworkInfo.Net
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Consumo do app, por dia e por rede (Wi‑Fi / dados móveis):
 *  - tráfego REAL: o contador do próprio Android para este app (TrafficStats, em tempo real, inclui cabeçalhos).
 *    Como o Android só dá o total, o app anota a diferença a cada evento (troca de rede, verificação, mensagem)
 *    e atribui à rede em que estava naquele trecho;
 *  - verificações feitas, tempo com a conexão aberta e notificações recebidas.
 */
object Stats {
    private const val KEY = "stats_v1"
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val lock = Any()

    data class Counters(val polls: Int = 0, val wsSeconds: Long = 0, val messages: Int = 0, val bytes: Long = 0)

    private fun field(net: Net, what: String) = (if (net == Net.MOBILE) "m_" else "w_") + what

    private fun add(ctx: Context, net: Net, what: String, amount: Long) {
        if (net == Net.NONE || amount <= 0) return
        synchronized(lock) { addLocked(ctx, net, what, amount) }
    }

    private fun addLocked(ctx: Context, net: Net, what: String, amount: Long) {
        val p = Prefs.raw(ctx)
        val root = try { JSONObject(p.getString(KEY, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val today = dayFmt.format(Date())
        val day = root.optJSONObject(today) ?: JSONObject().also { root.put(today, it) }
        day.put(field(net, what), day.optLong(field(net, what), 0) + amount)
        val keep = root.keys().asSequence().toList().sorted().takeLast(10).toSet()
        for (k in root.keys().asSequence().toList()) if (k !in keep) root.remove(k)
        p.edit().putString(KEY, root.toString()).apply()
    }

    fun addPoll(ctx: Context, net: Net) = add(ctx, net, "polls", 1)
    fun addMessage(ctx: Context, net: Net) = add(ctx, net, "msgs", 1)
    fun addWsSeconds(ctx: Context, net: Net, seconds: Long) = add(ctx, net, "ws", seconds)

    private fun uidTotal(): Long =
        TrafficStats.getUidRxBytes(Process.myUid()).coerceAtLeast(0) + TrafficStats.getUidTxBytes(Process.myUid()).coerceAtLeast(0)

    /**
     * Anota o tráfego desde a última chamada, atribuindo à rede em que o app estava até agora, e passa a
     * contar para [netNow]. Chamar a cada evento relevante (troca de rede, início/fim de verificação, mensagem).
     */
    fun sample(ctx: Context, netNow: Net) {
        synchronized(lock) {
            val p = Prefs.raw(ctx)
            val total = uidTotal()
            val last = p.getLong("ts_last", -1)
            val lastNet = p.getString("ts_net", null)?.let { runCatching { Net.valueOf(it) }.getOrNull() } ?: Net.NONE
            // Contador zera no boot: nesse caso tudo que há nele é novo.
            val delta = when {
                last < 0 -> 0
                total >= last -> total - last
                else -> total
            }
            if (delta > 0) addLocked(ctx, if (lastNet == Net.NONE) netNow else lastNet, "b", delta)
            p.edit().putLong("ts_last", total).putString("ts_net", netNow.name).apply()
        }
    }

    /** Soma dos últimos [days] dias (1 = só hoje). */
    fun counters(ctx: Context, net: Net, days: Int): Counters {
        val root = try { JSONObject(Prefs.raw(ctx).getString(KEY, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val cal = Calendar.getInstance()
        var polls = 0; var ws = 0L; var msgs = 0; var bytes = 0L
        repeat(days) {
            root.optJSONObject(dayFmt.format(cal.time))?.let {
                polls += it.optInt(field(net, "polls")); ws += it.optLong(field(net, "ws"))
                msgs += it.optInt(field(net, "msgs")); bytes += it.optLong(field(net, "b"))
            }
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return Counters(polls, ws, msgs, bytes)
    }
}
