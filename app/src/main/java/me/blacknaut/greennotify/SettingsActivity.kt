package me.blacknaut.greennotify

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import me.blacknaut.greennotify.NetworkInfo.Net
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var editServerUrl: TextInputEditText
    private lateinit var editDeviceId: TextInputEditText
    private lateinit var editApiKey: TextInputEditText
    private lateinit var cardBattery: View
    private lateinit var textHint: View
    private lateinit var textTestResult: TextView
    private lateinit var wifi: NetPanel
    private lateinit var mobile: NetPanel

    /** Um painel de configuração (modo + intervalo + custo estimado) para uma rede. */
    private inner class NetPanel(
        val net: Net, modeId: Int, intervalLayoutId: Int, intervalId: Int, costId: Int
    ) {
        var kind = Policy.kind(this@SettingsActivity, net)
        var pollMin = Policy.pollMin(this@SettingsActivity, net)
        var pingMin = Policy.pingMin(this@SettingsActivity, net)

        private val modeView = findViewById<MaterialAutoCompleteTextView>(modeId)
        private val intervalLayout = findViewById<TextInputLayout>(intervalLayoutId)
        private val intervalView = findViewById<MaterialAutoCompleteTextView>(intervalId)
        private val costView = findViewById<TextView>(costId)
        private val kindLabels = arrayOf(
            getString(R.string.kind_realtime), getString(R.string.kind_polling), getString(R.string.kind_off)
        )

        init {
            modeView.setSimpleItems(kindLabels)
            modeView.setOnItemClickListener { _, _, pos, _ -> kind = Policy.KINDS[pos]; refresh() }
            intervalView.setOnItemClickListener { _, _, pos, _ ->
                if (kind == Policy.REALTIME) pingMin = Policy.PING_OPTIONS[pos] else pollMin = Policy.POLL_OPTIONS[pos]
                refresh()
            }
            refresh()
        }

        fun set(kind: String, pollMin: Int, pingMin: Int) {
            this.kind = kind; this.pollMin = pollMin; this.pingMin = pingMin
            refresh()
        }

        fun refresh() {
            modeView.setText(kindLabels[Policy.KINDS.indexOf(kind)], false)
            when (kind) {
                Policy.REALTIME -> showInterval(R.string.hint_ping, Policy.PING_OPTIONS, pingMin)
                Policy.POLLING -> showInterval(R.string.hint_poll, Policy.POLL_OPTIONS, pollMin)
                else -> intervalLayout.visibility = View.GONE
            }
            costView.text = if (kind == Policy.OFF) getString(R.string.cost_off) else {
                val wakes = Policy.wakesPerDay(kind, pollMin, pingMin)
                val mb = String.format(Locale.forLanguageTag("pt-BR"), "%.1f", Policy.mbPerMonth(kind, pollMin, pingMin))
                val impact = getString(listOf(R.string.impact_low, R.string.impact_mid, R.string.impact_high)[Policy.impact(wakes)])
                getString(R.string.cost_format, wakes, mb, impact)
            }
            refreshExtras()
        }

        private fun showInterval(hint: Int, options: IntArray, current: Int) {
            intervalLayout.visibility = View.VISIBLE
            intervalLayout.hint = getString(hint)
            intervalView.setSimpleItems(options.map { getString(R.string.interval_min, it) }.toTypedArray())
            intervalView.setText(getString(R.string.interval_min, current), false)
        }

        fun save() = Policy.save(this@SettingsActivity, net, kind, pollMin, pingMin)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applySystemBarInsets(findViewById(android.R.id.content))

        editServerUrl = findViewById(R.id.editServerUrl)
        editDeviceId = findViewById(R.id.editDeviceId)
        editApiKey = findViewById(R.id.editApiKey)
        cardBattery = findViewById(R.id.cardBattery)
        textHint = findViewById(R.id.textHint)
        textTestResult = findViewById(R.id.textTestResult)

        editServerUrl.setText(Prefs.getServerUrl(this))
        editDeviceId.setText(Prefs.getDeviceId(this))
        editApiKey.setText(Prefs.getApiKey(this))

        wifi = NetPanel(Net.WIFI, R.id.dropdownModeWifi, R.id.layoutIntervalWifi, R.id.dropdownIntervalWifi, R.id.textCostWifi)
        mobile = NetPanel(Net.MOBILE, R.id.dropdownModeMobile, R.id.layoutIntervalMobile, R.id.dropdownIntervalMobile, R.id.textCostMobile)

        findViewById<View>(R.id.chipPresetRealtime).setOnClickListener {
            wifi.set(Policy.REALTIME, wifi.pollMin, 5); mobile.set(Policy.REALTIME, mobile.pollMin, 5)
        }
        findViewById<View>(R.id.chipPresetHybrid).setOnClickListener {
            wifi.set(Policy.REALTIME, wifi.pollMin, 5); mobile.set(Policy.POLLING, 15, mobile.pingMin)
        }
        findViewById<View>(R.id.chipPresetPolling).setOnClickListener {
            wifi.set(Policy.POLLING, 10, wifi.pingMin); mobile.set(Policy.POLLING, 15, mobile.pingMin)
        }

        val switchRemind = findViewById<MaterialSwitch>(R.id.switchRemind)
        switchRemind.isChecked = Prefs.isRemindEnabled(this)
        switchRemind.setOnCheckedChangeListener { _, on -> Prefs.setRemindEnabled(this, on) }

        findViewById<View>(R.id.buttonBack).setOnClickListener { finish() }
        findViewById<View>(R.id.buttonBattery).setOnClickListener { requestIgnoreBatteryOptimizations() }
        findViewById<View>(R.id.buttonAlarmTest).setOnClickListener { AlarmSetup.test(this) }
        findViewById<MaterialSwitch>(R.id.switchAlarmTorch).apply {
            isChecked = Prefs.isAlarmTorch(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setAlarmTorch(this@SettingsActivity, on) }
        }
        findViewById<View>(R.id.buttonAlarmFix).setOnClickListener {
            AlarmSetup.missing(this).firstOrNull()?.let { AlarmSetup.open(this, it) }
        }
        findViewById<View>(R.id.buttonTest).setOnClickListener { testConnection() }
        findViewById<View>(R.id.buttonUsage).setOnClickListener { startActivity(Intent(this, UsageActivity::class.java)) }
        findViewById<View>(R.id.buttonSave).setOnClickListener { save() }
        refreshExtras()
    }

    override fun onResume() {
        super.onResume()
        refreshExtras()
    }

    private fun anyRealtimeSelected() = wifi.kind == Policy.REALTIME || mobile.kind == Policy.REALTIME

    // Dica e botão de bateria só fazem sentido com alguma rede em tempo real, e enquanto o app não foi liberado.
    private fun refreshExtras() {
        if (!::wifi.isInitialized || !::mobile.isInitialized) return
        val realtime = anyRealtimeSelected()
        textHint.visibility = if (realtime) View.VISIBLE else View.GONE
        val missing = AlarmSetup.missing(this)
        findViewById<TextView>(R.id.textAlarmSetup).text = if (missing.isEmpty()) getString(R.string.alarm_setup_ok)
            else getString(R.string.alarm_setup_missing, missing.joinToString(", ") { getString(it.label) })
        findViewById<View>(R.id.buttonAlarmFix).visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        cardBattery.visibility = if (realtime && !exempt) View.VISIBLE else View.GONE
    }

    private fun saveForm() {
        Prefs.save(this, editServerUrl.text.toString(), editDeviceId.text.toString(), editApiKey.text.toString())
        wifi.save()
        mobile.save()
        Prefs.setLastError(this, null)
    }

    private fun save() {
        val wasActive = Connection.isActive(this)
        saveForm()
        // Se já estava recebendo, aplica a configuração nova na hora (servidor, chave, redes e intervalos).
        if (wasActive && Prefs.isConfigured(this)) Connection.start(this)
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun testConnection() {
        saveForm()
        if (!Prefs.isConfigured(this)) {
            showTest(getString(R.string.test_fill_all), ok = false)
            return
        }
        showTest(getString(R.string.test_running), ok = null)
        ApiClient.listNotifications(this, "pending", 0, null) { _, error ->
            if (isDestroyed) return@listNotifications
            if (error == null) showTest(getString(R.string.test_ok), ok = true) else showTest(error, ok = false)
        }
    }

    private fun showTest(text: String, ok: Boolean?) {
        textTestResult.text = text
        val attr = when (ok) {
            true -> androidx.appcompat.R.attr.colorPrimary
            false -> androidx.appcompat.R.attr.colorError
            null -> com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        textTestResult.setTextColor(MaterialColors.getColor(textTestResult, attr))
    }

    // Sem isso fabricantes como a Samsung colocam o app para "dormir" e a conexão cai.
    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
