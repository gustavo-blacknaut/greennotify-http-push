package me.blacknaut.greennotify

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var editServerUrl: EditText
    private lateinit var editDeviceId: EditText
    private lateinit var editApiKey: EditText
    private lateinit var textStatus: TextView

    // O serviço grava running/last_error ao parar sozinho: atualiza a tela mesmo com ela aberta.
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_RUNNING || key == Prefs.KEY_LAST_ERROR) refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editServerUrl = findViewById(R.id.editServerUrl)
        editDeviceId = findViewById(R.id.editDeviceId)
        editApiKey = findViewById(R.id.editApiKey)
        textStatus = findViewById(R.id.textStatus)

        editServerUrl.setText(Prefs.getServerUrl(this))
        editDeviceId.setText(Prefs.getDeviceId(this))
        editApiKey.setText(Prefs.getApiKey(this))
        refreshStatus()

        requestNotificationPermissionIfNeeded()

        findViewById<Button>(R.id.buttonSave).setOnClickListener {
            Prefs.save(
                this,
                editServerUrl.text.toString(),
                editDeviceId.text.toString(),
                editApiKey.text.toString()
            )
            Prefs.setLastError(this, null)
            Toast.makeText(this, R.string.toast_config_saved, Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        findViewById<Button>(R.id.buttonStart).setOnClickListener {
            if (!Prefs.isConfigured(this)) {
                Toast.makeText(this, R.string.toast_save_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val svc = Intent(this, NotifyConnectionService::class.java)
            ContextCompat.startForegroundService(this, svc)
            // start/stop são assíncronos: mostra o estado pedido em vez de consultar o serviço agora.
            showStatus(running = true)
        }

        findViewById<Button>(R.id.buttonStop).setOnClickListener {
            stopService(Intent(this, NotifyConnectionService::class.java))
            showStatus(running = false)
        }

        findViewById<Button>(R.id.buttonNotifications).setOnClickListener {
            if (!Prefs.isConfigured(this)) {
                Toast.makeText(this, R.string.toast_save_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, NotificationsActivity::class.java))
        }
    }

    private fun refreshStatus() {
        if (!Prefs.isConfigured(this)) {
            textStatus.setText(R.string.status_not_configured)
            return
        }
        val lastError = Prefs.getLastError(this)
        if (!NotifyConnectionService.isAlive && lastError != null) {
            textStatus.text = getString(R.string.status_stopped_reason, lastError)
            return
        }
        showStatus(NotifyConnectionService.isAlive)
    }

    private fun showStatus(running: Boolean) {
        textStatus.setText(if (running) R.string.status_running else R.string.status_stopped)
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
