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
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    private lateinit var editServerUrl: TextInputEditText
    private lateinit var editDeviceId: TextInputEditText
    private lateinit var editApiKey: TextInputEditText
    private lateinit var cardRealtime: MaterialCardView
    private lateinit var cardEconomy: MaterialCardView
    private lateinit var cardBattery: View
    private lateinit var textHint: View
    private lateinit var textTestResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applySystemBarInsets(findViewById(android.R.id.content))

        editServerUrl = findViewById(R.id.editServerUrl)
        editDeviceId = findViewById(R.id.editDeviceId)
        editApiKey = findViewById(R.id.editApiKey)
        cardRealtime = findViewById(R.id.cardRealtime)
        cardEconomy = findViewById(R.id.cardEconomy)
        cardBattery = findViewById(R.id.cardBattery)
        textHint = findViewById(R.id.textHint)
        textTestResult = findViewById(R.id.textTestResult)

        editServerUrl.setText(Prefs.getServerUrl(this))
        editDeviceId.setText(Prefs.getDeviceId(this))
        editApiKey.setText(Prefs.getApiKey(this))
        selectMode(Prefs.getMode(this))

        cardRealtime.setOnClickListener { selectMode(Prefs.MODE_REALTIME) }
        cardEconomy.setOnClickListener { selectMode(Prefs.MODE_ECONOMY) }
        findViewById<View>(R.id.buttonBack).setOnClickListener { finish() }
        findViewById<View>(R.id.buttonBattery).setOnClickListener { requestIgnoreBatteryOptimizations() }
        findViewById<View>(R.id.buttonTest).setOnClickListener { testConnection() }
        findViewById<View>(R.id.buttonSave).setOnClickListener { save() }
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryCard()
    }

    private fun selectedMode() = if (cardEconomy.isChecked) Prefs.MODE_ECONOMY else Prefs.MODE_REALTIME

    private fun selectMode(mode: String) {
        cardRealtime.isChecked = mode == Prefs.MODE_REALTIME
        cardEconomy.isChecked = mode == Prefs.MODE_ECONOMY
        val stroke = MaterialColors.getColor(cardRealtime, androidx.appcompat.R.attr.colorPrimary)
        val normal = MaterialColors.getColor(cardRealtime, com.google.android.material.R.attr.colorOutlineVariant)
        val width = resources.displayMetrics.density
        for (card in listOf(cardRealtime, cardEconomy)) {
            card.strokeColor = if (card.isChecked) stroke else normal
            card.strokeWidth = ((if (card.isChecked) 2 else 1) * width).toInt()
        }
        textHint.visibility = if (mode == Prefs.MODE_REALTIME) View.VISIBLE else View.GONE
        refreshBatteryCard()
    }

    // Só no tempo real, e só enquanto o app ainda não foi liberado.
    private fun refreshBatteryCard() {
        val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        cardBattery.visibility = if (selectedMode() == Prefs.MODE_REALTIME && !exempt) View.VISIBLE else View.GONE
    }

    private fun saveForm() {
        Prefs.save(this, editServerUrl.text.toString(), editDeviceId.text.toString(), editApiKey.text.toString())
        Prefs.setMode(this, selectedMode())
        Prefs.setLastError(this, null)
    }

    private fun save() {
        val wasActive = Connection.isActive(this)
        saveForm()
        // Se já estava recebendo, aplica a configuração nova na hora (troca servidor/chave/modo).
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
        ApiClient.listNotifications(this, "pending", 0) { _, error ->
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
