package me.blacknaut.greennotify

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import org.json.JSONArray

/** Tela inicial: estado da conexão no topo e a lista de notificações logo abaixo. */
class MainActivity : AppCompatActivity(), NotificationSheet.Host {

    private lateinit var adapter: NotificationAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var emptyState: View
    private lateinit var textEmptyTitle: TextView
    private lateinit var textEmptySubtitle: TextView
    private lateinit var statusDot: View
    private lateinit var textHeaderStatus: TextView
    private lateinit var imageStatus: ImageView
    private lateinit var textStatusTitle: TextView
    private lateinit var textStatusSubtitle: TextView
    private lateinit var buttonToggle: MaterialButton

    private var currentStatus = "pending"
    private var loading = false
    private var hasMore = true

    // O serviço grava running/last_error sozinho: atualiza o cartão mesmo com a tela aberta.
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_RUNNING || key == Prefs.KEY_LAST_ERROR) refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets(findViewById(android.R.id.content))

        statusDot = findViewById(R.id.statusDot)
        textHeaderStatus = findViewById(R.id.textHeaderStatus)
        imageStatus = findViewById(R.id.imageStatus)
        textStatusTitle = findViewById(R.id.textStatusTitle)
        textStatusSubtitle = findViewById(R.id.textStatusSubtitle)
        buttonToggle = findViewById(R.id.buttonToggle)
        emptyState = findViewById(R.id.emptyState)
        textEmptyTitle = findViewById(R.id.textEmptyTitle)
        textEmptySubtitle = findViewById(R.id.textEmptySubtitle)

        findViewById<View>(R.id.buttonSettings).setOnClickListener { openSettings() }
        findViewById<View>(R.id.buttonUsage).setOnClickListener { startActivity(Intent(this, UsageActivity::class.java)) }
        buttonToggle.setOnClickListener { onToggle() }

        findViewById<ChipGroup>(R.id.chipsFilter).setOnCheckedStateChangeListener { _, ids ->
            currentStatus = when (ids.firstOrNull()) {
                R.id.chipDone -> "done"
                R.id.chipArchived -> "archived"
                else -> "pending"
            }
            load()
        }

        recycler = findViewById(R.id.recyclerNotifications)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = NotificationAdapter(
            mutableListOf(),
            onOpenLink = { openLink(it) },
            onOpen = { NotificationSheet.show(this, it) },
            onPrimary = { item -> changeStatus(item, if (item.status == "done") "pending" else "done") },
            onSecondary = { item -> changeStatus(item, if (item.status == "archived") "pending" else "archived") }
        )
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !rv.canScrollVertically(1) && hasMore) fetchPage(reset = false)
            }
        })
        ItemTouchHelper(SwipeActions(this,
            canArchive = { currentStatus != "archived" },
            onArchive = { pos -> swipeArchive(pos) },
            onDelete = { pos -> swipeDelete(pos) }
        )).attachToRecyclerView(recycler)

        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.setColorSchemeColors(MaterialColors.getColor(recycler, androidx.appcompat.R.attr.colorPrimary))
        swipeRefresh.setOnRefreshListener { load() }
        // O filho direto é um FrameLayout: sem isso o "puxar para atualizar" dispara no meio da lista.
        // Também só puxa para atualizar com o cabeçalho (logo/status) totalmente visível.
        val appBar = findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.appBar)
        var appBarOffset = 0
        appBar.addOnOffsetChangedListener { _, offset -> appBarOffset = offset }
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> recycler.canScrollVertically(-1) || appBarOffset != 0 }

        requestNotificationPermissionIfNeeded()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Tocar numa notificação do celular abre direto o modal daquela notificação. */
    private fun handleIntent(intent: Intent?) {
        val json = intent?.getStringExtra(EXTRA_NOTIF) ?: return
        intent.removeExtra(EXTRA_NOTIF)
        val item = runCatching { NotificationItem.fromJson(org.json.JSONObject(json)) }.getOrNull() ?: return
        NotificationSheet.show(this, item)
    }

    override fun onResume() {
        super.onResume()
        Prefs.registerListener(this, prefsListener)
        refreshStatus()
        load()
        PinnedSummary.refreshAsync(this)
        // Chegou notificação com a tela aberta: a lista atualiza sozinha (só no topo, para não pular).
        NotificationBus.listener = {
            if (!isDestroyed) {
                if (!recycler.canScrollVertically(-1)) load() else Snackbar.make(recycler, R.string.snack_new, Snackbar.LENGTH_LONG)
                    .setAction(R.string.snack_show) { recycler.scrollToPosition(0); load() }.show()
            }
        }
    }

    override fun onPause() {
        NotificationBus.listener = null
        Prefs.unregisterListener(this, prefsListener)
        super.onPause()
    }

    // ---------- Cartão de status ----------

    private fun onToggle() {
        when {
            !Prefs.isConfigured(this) -> openSettings()
            Connection.isActive(this) -> { Connection.stop(this); refreshStatus() }
            else -> { Connection.start(this); showStatus(active = true) }
        }
    }

    private fun refreshStatus() = showStatus(Connection.isActive(this))

    private fun showStatus(active: Boolean) {
        val error = Prefs.getLastError(this)
        val ok = MaterialColors.getColor(statusDot, androidx.appcompat.R.attr.colorPrimary)
        val bad = MaterialColors.getColor(statusDot, androidx.appcompat.R.attr.colorError)
        val off = ContextCompat.getColor(this, R.color.status_off)
        val p = Policy.current(this)
        val net = getString(if (p.net == NetworkInfo.Net.MOBILE) R.string.net_mobile else R.string.net_wifi)

        when {
            !Prefs.isConfigured(this) -> render(R.drawable.ic_settings, R.string.card_setup_title,
                getString(R.string.card_setup_subtitle), R.string.button_configure, R.drawable.ic_settings,
                getString(R.string.header_setup), off, filled = true)
            active && p.net == NetworkInfo.Net.NONE -> render(R.drawable.ic_power_off, R.string.card_nonet_title,
                getString(R.string.card_nonet_subtitle), R.string.button_stop, R.drawable.ic_stop,
                getString(R.string.header_nonet), off, filled = false)
            active && p.kind == Policy.POLLING -> render(R.drawable.ic_battery, R.string.card_economy_title,
                getString(R.string.card_economy_subtitle, net, p.pollMin), R.string.button_stop, R.drawable.ic_stop,
                getString(R.string.header_polling, p.pollMin), ok, filled = false)
            active && p.kind == Policy.OFF -> render(R.drawable.ic_power_off, R.string.card_off_title,
                getString(R.string.card_off_subtitle, net), R.string.button_stop, R.drawable.ic_stop,
                getString(R.string.header_off), off, filled = false)
            active -> render(R.drawable.ic_bolt, R.string.card_realtime_title,
                getString(R.string.card_realtime_subtitle, net), R.string.button_stop, R.drawable.ic_stop,
                getString(R.string.header_realtime), ok, filled = false)
            error != null -> render(R.drawable.ic_power_off, R.string.card_error_title,
                error, R.string.button_start, R.drawable.ic_play, getString(R.string.header_error), bad, filled = true)
            else -> render(R.drawable.ic_power_off, R.string.card_stopped_title,
                getString(R.string.card_stopped_subtitle), R.string.button_start, R.drawable.ic_play,
                getString(R.string.header_stopped), off, filled = true)
        }
    }

    private fun render(
        icon: Int, title: Int, subtitle: String, button: Int, buttonIcon: Int,
        header: String, dotColor: Int, filled: Boolean
    ) {
        imageStatus.setImageResource(icon)
        textStatusTitle.setText(title)
        textStatusSubtitle.text = subtitle
        buttonToggle.setText(button)
        buttonToggle.setIconResource(buttonIcon)
        textHeaderStatus.text = header
        statusDot.backgroundTintList = ColorStateList.valueOf(dotColor)
        // Iniciar/Configurar em destaque; Parar mais discreto.
        val primary = MaterialColors.getColor(buttonToggle, androidx.appcompat.R.attr.colorPrimary)
        val onPrimary = MaterialColors.getColor(buttonToggle, com.google.android.material.R.attr.colorOnPrimary)
        val surface = MaterialColors.getColor(buttonToggle, com.google.android.material.R.attr.colorSurface)
        buttonToggle.backgroundTintList = ColorStateList.valueOf(if (filled) primary else surface)
        val fg = if (filled) onPrimary else primary
        buttonToggle.setTextColor(fg)
        buttonToggle.iconTint = ColorStateList.valueOf(fg)
    }

    // ---------- Lista ----------

    private fun load() = fetchPage(reset = true)

    // offset = itens já exibidos: os que mudam de status saem da lista local e do filtro no servidor juntos.
    private fun fetchPage(reset: Boolean) {
        if (!Prefs.isConfigured(this)) {
            swipeRefresh.isRefreshing = false
            adapter.submitList(emptyList())
            updateEmpty()
            return
        }
        if (loading && !reset) return
        loading = true
        val status = currentStatus
        val offset = if (reset) 0 else adapter.itemCount
        swipeRefresh.isRefreshing = true
        ApiClient.listNotifications(this, status, offset) { response, error ->
            if (isDestroyed || status != currentStatus) return@listNotifications
            loading = false
            swipeRefresh.isRefreshing = false
            if (error != null || response == null) {
                Snackbar.make(recycler, error ?: getString(R.string.error_network), Snackbar.LENGTH_LONG).show()
                updateEmpty()
                return@listNotifications
            }
            val array: JSONArray = response.optJSONArray("notifications") ?: JSONArray()
            val list = (0 until array.length()).map { NotificationItem.fromJson(array.getJSONObject(it)) }
            hasMore = list.size == ApiClient.PAGE_SIZE
            if (reset) adapter.submitList(list) else adapter.appendList(list)
            updateEmpty()
        }
    }

    private fun updateEmpty() {
        val empty = adapter.itemCount == 0
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        if (!empty) return
        textEmptyTitle.setText(
            when (currentStatus) {
                "done" -> R.string.empty_done_title
                "archived" -> R.string.empty_archived_title
                else -> R.string.empty_pending_title
            }
        )
        textEmptySubtitle.setText(
            when {
                !Prefs.isConfigured(this) -> R.string.empty_setup_subtitle
                currentStatus == "pending" -> R.string.empty_pending_subtitle
                else -> R.string.empty_other_subtitle
            }
        )
    }

    private fun changeStatus(item: NotificationItem, target: String) {
        ApiClient.move(this, item.id, target) { response, error ->
            if (isDestroyed) return@move
            if (error == null && response?.optBoolean("ok") == true) {
                adapter.removeItem(item.id)
                if (target != "pending") NotificationHelper.cancel(this, item.id)
                updateEmpty()
                PinnedSummary.refreshAsync(this)
            } else {
                Snackbar.make(recycler, error ?: getString(R.string.error_action), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun swipeArchive(pos: Int) {
        val item = adapter.itemAt(pos)
        adapter.removeItem(item.id)
        updateEmpty()
        ApiClient.move(this, item.id, "archived") { _, error ->
            if (isDestroyed) return@move
            if (error != null) {
                adapter.insertItem(pos, item); updateEmpty()
                Snackbar.make(recycler, error, Snackbar.LENGTH_LONG).show()
                return@move
            }
            NotificationHelper.cancel(this, item.id)
            PinnedSummary.refreshAsync(this)
            Snackbar.make(recycler, R.string.snack_archived, Snackbar.LENGTH_LONG)
                .setAction(R.string.snack_undo) {
                    ApiClient.move(this, item.id, item.status) { _, _ -> if (!isDestroyed) load() }
                }.show()
        }
    }

    // Só apaga no servidor quando o aviso some sem "Desfazer": um toque errado não perde nada.
    private fun swipeDelete(pos: Int) {
        val item = adapter.itemAt(pos)
        adapter.removeItem(item.id)
        updateEmpty()
        Snackbar.make(recycler, R.string.snack_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.snack_undo) { adapter.insertItem(pos, item); updateEmpty() }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(bar: Snackbar?, event: Int) {
                    if (event == DISMISS_EVENT_ACTION) return
                    NotificationHelper.cancel(applicationContext, item.id)
                    ApiClient.delete(applicationContext, item.id) { _, _ -> PinnedSummary.refreshAsync(applicationContext) }
                }
            }).show()
    }

    private fun openLink(link: String) {
        if (!isWebLink(link)) {
            Toast.makeText(this, R.string.error_invalid_link, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_invalid_link, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Ações do modal ----------

    override fun onSheetLink(link: String) = openLink(link)
    override fun onSheetPrimary(item: NotificationItem) = changeStatus(item, if (item.status == "done") "pending" else "done")
    override fun onSheetSecondary(item: NotificationItem) = changeStatus(item, if (item.status == "archived") "pending" else "archived")

    companion object {
        const val EXTRA_NOTIF = "notification_json"
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}
