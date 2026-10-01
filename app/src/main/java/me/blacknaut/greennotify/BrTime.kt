package me.blacknaut.greennotify

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Horários sempre no fuso de São Paulo (Brasília), formato 24h. */
object BrTime {
    private val tz: TimeZone = TimeZone.getTimeZone("America/Sao_Paulo")
    private val pt = Locale.forLanguageTag("pt-BR")

    private fun fmt(pattern: String) = SimpleDateFormat(pattern, pt).apply { timeZone = tz }

    /** "14:32" */
    fun time(ms: Long): String = fmt("HH:mm").format(Date(ms))

    /** "agora", "há 5 min", "há 2 h", "ontem 14:32", "28/09 14:32" */
    fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - ms).coerceAtLeast(0)
        val min = diff / 60_000
        val day = fmt("yyyyMMdd")
        return when {
            min < 1 -> "agora"
            min < 60 -> "há $min min"
            min < 24 * 60 && day.format(Date(ms)) == day.format(Date(now)) -> "há ${min / 60} h"
            day.format(Date(ms)) == day.format(Date(now - 86_400_000)) -> "ontem " + time(ms)
            else -> fmt("dd/MM HH:mm").format(Date(ms))
        }
    }

    /** "01/10/2026 às 14:32" */
    fun dateTime(ms: Long): String = fmt("dd/MM/yyyy 'às' HH:mm").format(Date(ms))
}
