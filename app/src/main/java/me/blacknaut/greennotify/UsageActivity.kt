package me.blacknaut.greennotify

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.MaterialColors
import me.blacknaut.greennotify.NetworkInfo.Net
import java.util.Locale

/** Consumo do app: tráfego real medido pelo Android + contadores do app + estimativa da configuração atual. */
class UsageActivity : AppCompatActivity() {

    private val pt = Locale.forLanguageTag("pt-BR")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usage)
        applySystemBarInsets(findViewById(android.R.id.content))
        findViewById<View>(R.id.buttonBack).setOnClickListener { finish() }
        findViewById<View>(R.id.buttonSystemBattery).setOnClickListener { openSystemBattery() }
    }

    override fun onResume() {
        super.onResume()
        Stats.sample(this, NetworkInfo.current(this))
        fill(findViewById(R.id.containerToday), 1)
        fill(findViewById(R.id.containerWeek), 7)
        fillPlan(findViewById(R.id.containerPlan))
    }

    private fun fill(container: LinearLayout, days: Int) {
        container.removeAllViews()
        for (net in listOf(Net.WIFI, Net.MOBILE)) {
            val name = getString(if (net == Net.MOBILE) R.string.net_mobile_title else R.string.net_wifi_title)
            heading(container, name)
            val c = Stats.counters(this, net, days)
            // Inclui o tempo da conexão que está aberta agora (ainda não foi somado ao contador).
            val live = if (NotifyConnectionService.openNet == net && NotifyConnectionService.openSince > 0)
                (System.currentTimeMillis() - NotifyConnectionService.openSince) / 1000 else 0L
            row(container, getString(R.string.usage_traffic), formatBytes(c.bytes))
            row(container, getString(R.string.usage_polls), c.polls.toString())
            row(container, getString(R.string.usage_open), formatDuration(c.wsSeconds + live))
            row(container, getString(R.string.usage_messages), c.messages.toString())
        }
    }

    private fun fillPlan(container: LinearLayout) {
        container.removeAllViews()
        for (net in listOf(Net.WIFI, Net.MOBILE)) {
            val name = getString(if (net == Net.MOBILE) R.string.net_mobile_title else R.string.net_wifi_title)
            heading(container, name)
            val kind = Policy.kind(this, net)
            val poll = Policy.pollMin(this, net)
            val ping = Policy.pingMin(this, net)
            val label = when (kind) {
                Policy.REALTIME -> getString(R.string.plan_realtime, ping)
                Policy.POLLING -> getString(R.string.plan_polling, poll)
                else -> getString(R.string.kind_off)
            }
            row(container, getString(R.string.usage_mode), label)
            val wakes = Policy.wakesPerDay(kind, poll, ping)
            row(container, getString(R.string.usage_wakes), wakes.toString())
            row(container, getString(R.string.usage_month), String.format(pt, "%.1f MB", Policy.mbPerMonth(kind, poll, ping)))
            row(container, getString(R.string.usage_impact),
                getString(listOf(R.string.impact_low, R.string.impact_mid, R.string.impact_high)[Policy.impact(wakes)]))
        }
    }

    private fun heading(container: LinearLayout, text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
            setPadding(0, if (container.childCount == 0) 0 else dp(14), 0, dp(4))
        })
    }

    private fun row(container: LinearLayout, label: String, value: String) {
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
            addView(TextView(context).apply {
                text = label
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = value
                gravity = Gravity.END
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            })
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun formatBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format(pt, "%.1f KB", b / 1024.0)
        else -> String.format(pt, "%.2f MB", b / 1024.0 / 1024.0)
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 -> getString(R.string.duration_hm, h, m)
            m > 0 -> getString(R.string.duration_m, m)
            else -> getString(R.string.duration_zero)
        }
    }

    // A bateria em mAh só o sistema mede; leva direto para a tela dele.
    private fun openSystemBattery() {
        try {
            startActivity(Intent("android.intent.action.POWER_USAGE_SUMMARY"))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
}
