package me.blacknaut.greennotify

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var editServerUrl: EditText
    private lateinit var editDeviceId: EditText
    private lateinit var editApiKey: EditText
    private lateinit var radioMode: RadioGroup
    private lateinit var textStatus: TextView
    private lateinit var buttonBattery: Button
    private lateinit var textHint: TextView

    // O serviço grava running/last_error ao parar sozinho: atualiza a tela mesmo com ela aberta.
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_RUNNING || key == Prefs.KEY_LAST_ERROR) refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets(findViewById(android.R.id.content))

        editServerUrl = findViewById(R.id.editServerUrl)
        editDeviceId = findViewById(R.id.editDeviceId)
        editApiKey = findViewById(R.id.editApiKey)
        radioMode = findViewById(R.id.radioMode)
        textStatus = findViewById(R.id.textStatus)
        buttonBattery = findViewById(R.id.buttonBattery)
        textHint = findViewById(R.id.textHint)

        editServerUrl.setText(Prefs.getServerUrl(this))
        editDeviceId.setText(Prefs.getDeviceId(this))
        editApiKey.setText(Prefs.getApiKey(this))
        radioMode.check(if (Prefs.getMode(this) == Prefs.MODE_ECONOMY) R.id.radioEconomy else R.id.radioRealtime)
        radioMode.setOnCheckedChangeListener { _, _ -> refreshExtras() }
        refreshStatus()

        requestNotificationPermissionIfNeeded()

        findViewById<Button>(R.id.buttonSave).setOnClickListener {
            saveForm()
            Toast.makeText(this, R.string.toast_config_saved, Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        findViewById<Button>(R.id.buttonStart).setOnClickListener {
            saveForm()
            if (!Prefs.isConfigured(this)) {
                Toast.makeText(this, R.string.toast_save_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (Prefs.getMode(this) == Prefs.MODE_ECONOMY) {
                stopService(Intent(this, NotifyConnectionService::class.java))
                EconomyWorker.schedule(this)
                Prefs.setRunning(this, true)
            } else {
                EconomyWorker.cancel(this)
                ContextCompat.startForegroundService(this, Intent(this, NotifyConnectionService::class.java))
            }
            // start/stop são assíncronos: mostra o estado pedido em vez de consultar o serviço agora.
            showStatus(running = true)
        }

        findViewById<Button>(R.id.buttonStop).setOnClickListener {
            EconomyWorker.cancel(this)
            stopService(Intent(this, NotifyConnectionService::class.java))
            Prefs.setRunning(this, false)
            showStatus(running = false)
        }

        buttonBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }

        findViewById<Button>(R.id.buttonNotifications).setOnClickListener {
            if (!Prefs.isConfigured(this)) {
                Toast.makeText(this, R.string.toast_save_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, NotificationsActivity::class.java))
        }
    }

    private fun selectedMode() =
        if (radioMode.checkedRadioButtonId == R.id.radioEconomy) Prefs.MODE_ECONOMY else Prefs.MODE_REALTIME

    private fun saveForm() {
        Prefs.save(this, editServerUrl.text.toString(), editDeviceId.text.toString(), editApiKey.text.toString())
        Prefs.setMode(this, selectedMode())
        Prefs.setLastError(this, null)
    }

    private fun refreshStatus() {
        refreshExtras()
        if (!Prefs.isConfigured(this)) {
            textStatus.setText(R.string.status_not_configured)
            return
        }
        val lastError = Prefs.getLastError(this)
        if (!NotifyConnectionService.isAlive && lastError != null) {
            textStatus.text = getString(R.string.status_stopped_reason, lastError)
            return
        }
        showStatus(NotifyConnectionService.isAlive || (Prefs.getMode(this) == Prefs.MODE_ECONOMY && Prefs.isRunning(this)))
    }

    private fun showStatus(running: Boolean) {
        textStatus.setText(
            when {
                !running -> R.string.status_stopped
                Prefs.getMode(this) == Prefs.MODE_ECONOMY -> R.string.status_economy
                else -> R.string.status_running
            }
        )
    }

    // Botão de bateria e dica da notificação fixa só fazem sentido no tempo real.
    private fun refreshExtras() {
        val realtime = selectedMode() == Prefs.MODE_REALTIME
        textHint.visibility = if (realtime) View.VISIBLE else View.GONE
        buttonBattery.visibility = if (realtime && !isIgnoringBatteryOptimizations()) View.VISIBLE else View.GONE
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    // Sem isso fabricantes como a Samsung colocam o app para "dormir" e a conexão cai.
    // Não gasta bateria por si só: só impede o sistema de matar o serviço.
    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Prefs.registerListener(this, prefsListener)
        refreshStatus()
    }

    override fun onPause() {
        Prefs.unregisterListener(this, prefsListener)
        super.onPause()
    }
}
