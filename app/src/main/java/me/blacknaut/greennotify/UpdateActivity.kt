package me.blacknaut.greennotify

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

/** Tela "Atualizações": app e servidor, cada um com seu botão. */
class UpdateActivity : AppCompatActivity() {

    private var release: Updater.Release? = null
    private var serverVersion: String? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var appStatus: TextView
    private lateinit var appNotes: TextView
    private lateinit var appButton: Button
    private lateinit var appProgress: LinearProgressIndicator
    private lateinit var serverStatus: TextView
    private lateinit var serverButton: Button
    private lateinit var serverProgress: LinearProgressIndicator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)
        applySystemBarInsets(findViewById(android.R.id.content))
        findViewById<View>(R.id.buttonBack).setOnClickListener { finish() }
        appStatus = findViewById(R.id.textAppStatus)
        appNotes = findViewById(R.id.textAppNotes)
        appButton = findViewById(R.id.buttonUpdateApp)
        appProgress = findViewById(R.id.progressApp)
        serverStatus = findViewById(R.id.textServerStatus)
        serverButton = findViewById(R.id.buttonUpdateServer)
        serverProgress = findViewById(R.id.progressServer)
        appButton.setOnClickListener { updateApp() }
        serverButton.setOnClickListener { askAdminKey() }
        check()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun check() {
        val current = Updater.appVersion(this)
        appStatus.text = getString(R.string.update_checking)
        serverStatus.text = getString(R.string.update_checking)
        appButton.isEnabled = false
        serverButton.isEnabled = false
        Updater.latestRelease { rel, error ->
            if (isDestroyed) return@latestRelease
            release = rel
            Updater.markChecked(this)
            if (rel == null) {
                appStatus.text = getString(R.string.update_error, error ?: "")
                serverStatus.text = appStatus.text
                return@latestRelease
            }
            val hasApp = Updater.newer(rel.version, current)
            appStatus.text = if (hasApp) getString(R.string.update_app_available, current, rel.version)
                else getString(R.string.update_app_latest, current)
            appNotes.text = rel.notes
            appNotes.visibility = if (hasApp && rel.notes.isNotBlank()) View.VISIBLE else View.GONE
            appButton.isEnabled = hasApp && rel.apkUrl != null
            checkServer(rel)
        }
    }

    private fun checkServer(rel: Updater.Release) {
        if (!Prefs.isConfigured(this)) {
            serverStatus.setText(R.string.update_server_not_configured)
            return
        }
        Updater.serverVersion(this) { v ->
            if (isDestroyed) return@serverVersion
            serverVersion = v
            serverStatus.text = when {
                v == null -> getString(R.string.update_server_unknown, rel.version)
                Updater.newer(rel.version, v) -> getString(R.string.update_server_available, v, rel.version)
                else -> getString(R.string.update_server_latest, v)
            }
            serverButton.isEnabled = v == null || Updater.newer(rel.version, v)
        }
    }

    // ---------- App ----------

    private fun updateApp() {
        val rel = release ?: return
        // Android 8+: precisa liberar "instalar apps desconhecidos" para o GreenNotify (uma vez só).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_unknown_title)
                .setMessage(R.string.update_unknown_message)
                .setNegativeButton(R.string.button_cancel, null)
                .setPositiveButton(R.string.update_unknown_open) { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .show()
            return
        }
        appButton.isEnabled = false
        appProgress.visibility = View.VISIBLE
        appProgress.isIndeterminate = false
        Updater.downloadAndInstall(this, rel, progress = { appProgress.setProgressCompat(it, true) }) { error ->
            if (isDestroyed) return@downloadAndInstall
            appProgress.visibility = View.GONE
            appButton.isEnabled = true
            if (error != null) appStatus.text = getString(R.string.update_error, error)
        }
    }

    // ---------- Servidor ----------

    private fun askAdminKey() {
        val view = layoutInflater.inflate(R.layout.dialog_admin_key, null)
        val input = view.findViewById<EditText>(R.id.inputAdminKey)
        val remember = view.findViewById<CheckBox>(R.id.checkRemember)
        val saved = Prefs.raw(this).getString(KEY_ADMIN, null)
        if (saved != null) { input.setText(saved); remember.isChecked = true }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_server_title)
            .setMessage(R.string.update_server_message)
            .setView(view)
            .setNegativeButton(R.string.button_cancel, null)
            .setPositiveButton(R.string.update_server_go) { _, _ ->
                val key = input.text.toString().trim()
                if (key.isBlank()) return@setPositiveButton
                Prefs.raw(this).edit().apply { if (remember.isChecked) putString(KEY_ADMIN, key) else remove(KEY_ADMIN) }.apply()
                updateServer(key)
            }
            .show()
    }

    private fun updateServer(key: String) {
        serverButton.isEnabled = false
        serverProgress.visibility = View.VISIBLE
        serverStatus.setText(R.string.update_server_running)
        Updater.updateServer(this, key) { to, error ->
            if (isDestroyed) return@updateServer
            when {
                error != null -> {
                    serverProgress.visibility = View.GONE
                    serverButton.isEnabled = true
                    serverStatus.text = getString(R.string.update_error, error)
                }
                to == null -> { serverProgress.visibility = View.GONE; release?.let { checkServer(it) } }
                else -> {
                    serverStatus.text = getString(R.string.update_server_restarting, to)
                    waitServer(to, System.currentTimeMillis() + 120_000)
                }
            }
        }
    }

    /** O servidor sai e o painel liga de novo: espera ele voltar já com a versão nova (até 2 min). */
    private fun waitServer(target: String, deadline: Long) {
        handler.postDelayed({
            Updater.serverVersion(this) { v ->
                if (isDestroyed) return@serverVersion
                when {
                    v == target -> {
                        serverProgress.visibility = View.GONE
                        serverVersion = v
                        serverStatus.text = getString(R.string.update_server_done, v)
                    }
                    System.currentTimeMillis() > deadline -> {
                        serverProgress.visibility = View.GONE
                        serverButton.isEnabled = true
                        serverStatus.setText(R.string.update_server_timeout)
                    }
                    else -> waitServer(target, deadline)
                }
            }
        }, 4000)
    }

    companion object {
        private const val KEY_ADMIN = "admin_key"
    }
}
