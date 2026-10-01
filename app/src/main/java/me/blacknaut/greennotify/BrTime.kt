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

    /** "01/10/2026 às 14:32" */
    fun dateTime(ms: Long): String = fmt("dd/MM/yyyy 'às' HH:mm").format(Date(ms))
}
