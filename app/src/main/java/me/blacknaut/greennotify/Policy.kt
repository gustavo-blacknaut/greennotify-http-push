package me.blacknaut.greennotify

import android.content.Context
import me.blacknaut.greennotify.NetworkInfo.Net

/**
 * Como receber em cada tipo de rede, configurável separadamente para Wi‑Fi e dados móveis:
 *  - REALTIME: conexão aberta, chega na hora; pingMin é de quanto em quanto tempo o app manda um sinal de vida;
 *  - POLLING: sem conexão aberta, consulta o servidor a cada pollMin minutos;
 *  - OFF: não recebe nessa rede.
 */
object Policy {
    const val REALTIME = "realtime"
    const val POLLING = "polling"
    const val OFF = "off"

    val KINDS = arrayOf(REALTIME, POLLING, OFF)
    val POLL_OPTIONS = intArrayOf(2, 5, 10, 15, 30, 60)
    val PING_OPTIONS = intArrayOf(1, 2, 3, 5, 10, 15)

    data class Current(val net: Net, val kind: String, val pollMin: Int, val pingMin: Int)

    private fun name(net: Net) = if (net == Net.MOBILE) "mobile" else "wifi"

    // Quem usava a versão anterior (um único modo) mantém o comportamento: economia vira "consultar" nas duas redes.
    private fun legacyKind(ctx: Context): String =
        if (Prefs.raw(ctx).getString("mode", "realtime") == "economy") POLLING else REALTIME

    fun kind(ctx: Context, net: Net): String =
        Prefs.raw(ctx).getString(name(net) + "_kind", null) ?: legacyKind(ctx)

    fun pollMin(ctx: Context, net: Net): Int {
        val p = Prefs.raw(ctx)
        return p.getInt(name(net) + "_poll", p.getInt(name(net) + "_minutes", if (net == Net.MOBILE) 15 else 10))
    }

    fun pingMin(ctx: Context, net: Net): Int = Prefs.raw(ctx).getInt(name(net) + "_ping", 5)

    fun save(ctx: Context, net: Net, kind: String, pollMin: Int, pingMin: Int) {
        Prefs.raw(ctx).edit()
            .putString(name(net) + "_kind", kind)
            .putInt(name(net) + "_poll", pollMin)
            .putInt(name(net) + "_ping", pingMin)
            .apply()
    }

    fun current(ctx: Context, net: Net = NetworkInfo.current(ctx)): Current =
        if (net == Net.NONE) Current(Net.NONE, OFF, 10, 5)
        else Current(net, kind(ctx, net), pollMin(ctx, net), pingMin(ctx, net))

    fun anyRealtime(ctx: Context) = kind(ctx, Net.WIFI) == REALTIME || kind(ctx, Net.MOBILE) == REALTIME
    fun anyPolling(ctx: Context) = kind(ctx, Net.WIFI) == POLLING || kind(ctx, Net.MOBILE) == POLLING

    // ---- Estimativa de custo (baseada na medição feita com o app) ----
    // Cada checagem: 1 acordada do rádio, ~1,15 KB (634 B do app + cabeçalhos TCP/IP).
    // Cada sinal de vida: 1 acordada, ~0,2 KB.
    fun wakesPerDay(kind: String, pollMin: Int, pingMin: Int): Int = when (kind) {
        REALTIME -> 1440 / pingMin
        POLLING -> 1440 / pollMin
        else -> 0
    }

    fun mbPerMonth(kind: String, pollMin: Int, pingMin: Int): Double {
        val kbPerWake = if (kind == POLLING) 1.15 else 0.2
        return wakesPerDay(kind, pollMin, pingMin) * kbPerWake * 30 / 1024
    }

    /** 0 = baixo, 1 = médio, 2 = alto (por acordadas do rádio por dia). */
    fun impact(wakesPerDay: Int) = when {
        wakesPerDay <= 150 -> 0
        wakesPerDay <= 400 -> 1
        else -> 2
    }
}
