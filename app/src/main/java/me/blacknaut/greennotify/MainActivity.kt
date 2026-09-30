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
class MainActivity : AppCompatActivity(), NotificationSheet.Host, ComposeSheet.Host {

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

    private lateinit var folderRow: android.widget.LinearLayout
    private lateinit var textFoldersCount: TextView
    private lateinit var chipCategory: com.google.android.material.chip.Chip
    private lateinit var fab: com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

    private var categories: List<Category> = emptyList()
    /** Pasta aberta (nome da categoria) ou null = todas. */
    private var currentCategory: String? = null
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

        folderRow = findViewById(R.id.folderRow)
        textFoldersCount = findViewById(R.id.textFoldersCount)
        chipCategory = findViewById(R.id.chipCategory)
        chipCategory.setOnCloseIconClickListener { selectCategory(null) }
        chipCategory.setOnClickListener { selectCategory(null) }
        fab = findViewById(R.id.fabCompose)
        fab.setOnClickListener {
            if (Prefs.isConfigured(this)) ComposeSheet.show(this, currentCategory) else openSettings()
        }
        currentCategory = savedInstanceState?.getString(STATE_CATEGORY)

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
                if (dy > 8 && fab.isExtended) fab.shrink() else if (dy < -8 && !fab.isExtended) fab.extend()
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
        currentCategory?.let { chipCategory.text = it; chipCategory.visibility = View.VISIBLE }
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

    private fun load() {
        fetchPage(reset = true)
        loadCategories()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CATEGORY, currentCategory)
    }

    // ---------- Categorias (pastas) ----------

    private fun loadCategories() {
        if (!Prefs.isConfigured(this)) {
            categories = emptyList()
            renderFolders()
            return
        }
        ApiClient.listCategories(this) { response, _ ->
            if (isDestroyed || response == null) return@listCategories
            val array = response.optJSONArray("categories") ?: JSONArray()
            categories = (0 until array.length()).map { Category.fromJson(array.getJSONObject(it)) }
            // A pasta aberta foi apagada/renomeada em outro lugar: volta para todas.
            val open = currentCategory
            if (open != null && categories.none { it.name.equals(open, ignoreCase = true) }) selectCategory(null)
            renderFolders()
        }
    }

    private fun renderFolders() {
        folderRow.removeAllViews()
        textFoldersCount.text = getString(R.string.folders_count, categories.size, Category.MAX)
        for (c in categories) folderRow.addView(folderView(c))
        if (Prefs.isConfigured(this) && categories.size < Category.MAX) folderRow.addView(addFolderView())
        // Espaços vazios mantêm as pastas do mesmo tamanho (4 colunas).
        val margin = (4 * resources.displayMetrics.density).toInt()
        repeat(Category.MAX - folderRow.childCount) {
            folderRow.addView(android.widget.Space(this), android.widget.LinearLayout.LayoutParams(0, 1, 1f).apply {
                marginStart = margin; marginEnd = margin
            })
        }
    }

    private fun folderView(c: Category): View {
        val v = layoutInflater.inflate(R.layout.item_folder, folderRow, false)
        val card = v as com.google.android.material.card.MaterialCardView
        val letter = v.findViewById<TextView>(R.id.folderLetter)
        val image = v.findViewById<ImageView>(R.id.folderImage)
        val badge = v.findViewById<TextView>(R.id.folderBadge)
        v.findViewById<TextView>(R.id.folderName).text = c.name

        letter.text = c.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
        letter.backgroundTintList = ColorStateList.valueOf(NotificationAdapter.avatarColor(this, c.name))
        if (isWebLink(c.image)) {
            ImageLoader.load(c.image, 160) { bmp ->
                if (bmp != null) { image.setImageBitmap(bmp); image.visibility = View.VISIBLE }
            }
        }
        badge.visibility = if (c.pending > 0) View.VISIBLE else View.GONE
        badge.text = if (c.pending > 99) "99+" else c.pending.toString()

        val selected = c.name.equals(currentCategory, ignoreCase = true)
        card.strokeWidth = if (selected) (2 * resources.displayMetrics.density).toInt() else 0
        card.setCardBackgroundColor(MaterialColors.getColor(card,
            if (selected) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorSurfaceContainerLow))

        card.setOnClickListener { selectCategory(if (selected) null else c.name) }
        card.setOnLongClickListener { showFolderMenu(card, c); true }
        card.contentDescription = c.name
        return v
    }

    private fun addFolderView(): View {
        val v = layoutInflater.inflate(R.layout.item_folder, folderRow, false)
        val letter = v.findViewById<TextView>(R.id.folderLetter)
        letter.text = "+"
        letter.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(letter, androidx.appcompat.R.attr.colorPrimary))
        v.findViewById<TextView>(R.id.folderName).setText(R.string.category_add)
        v.setOnClickListener { showCategoryDialog(null) }
        v.contentDescription = getString(R.string.category_new)
        return v
    }

    private fun selectCategory(name: String?) {
        if (name == currentCategory) return
        currentCategory = name
        chipCategory.text = name ?: ""
        chipCategory.visibility = if (name == null) View.GONE else View.VISIBLE
        renderFolders()
        fetchPage(reset = true)
    }

    private fun showFolderMenu(anchor: View, c: Category) {
        val menu = androidx.appcompat.widget.PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.category_menu_edit)
        menu.menu.add(0, 2, 1, R.string.category_menu_compose)
        menu.menu.add(0, 3, 2, R.string.category_menu_clear).isEnabled = c.total > 0
        menu.menu.add(0, 4, 3, R.string.category_menu_delete)
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> showCategoryDialog(c)
                2 -> ComposeSheet.show(this, c.name)
                3 -> confirmClearCategory(c)
                4 -> confirmDeleteCategory(c)
            }
            true
        }
        menu.show()
    }

    /** Criar ([existing] = null) ou editar nome/imagem de uma categoria. */
    private fun showCategoryDialog(existing: Category?) {
        val view = layoutInflater.inflate(R.layout.dialog_category, null)
        val name = view.findViewById<android.widget.EditText>(R.id.inputCategoryName)
        val image = view.findViewById<android.widget.EditText>(R.id.inputCategoryImage)
        val preview = view.findViewById<ImageView>(R.id.categoryPreview)
        existing?.let { name.setText(it.name); image.setText(it.image) }

        val showPreview = Runnable {
            val url = image.text.toString().trim()
            preview.setImageResource(R.drawable.ic_folder)
            if (isWebLink(url)) ImageLoader.load(url, 160) { bmp -> if (bmp != null && image.text.toString().trim() == url) preview.setImageBitmap(bmp) }
        }
        showPreview.run()
        image.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                preview.removeCallbacks(showPreview)
                preview.postDelayed(showPreview, 600)
            }
        })

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.category_new else R.string.category_edit)
            .setView(view)
            .setNegativeButton(R.string.button_cancel, null)
            .setPositiveButton(R.string.button_save, null)
            .create()
        dialog.setOnShowListener {
            val save = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val n = name.text.toString().trim()
                val img = image.text.toString().trim()
                if (n.isBlank()) { name.error = getString(R.string.category_error_name); return@setOnClickListener }
                if (img.isNotBlank() && !isWebLink(img)) { image.error = getString(R.string.error_invalid_link); return@setOnClickListener }
                save.isEnabled = false
                val done: (org.json.JSONObject?, String?) -> Unit = { _, error ->
                    if (!isDestroyed) {
                        save.isEnabled = true
                        if (error != null) {
                            name.error = error
                        } else {
                            dialog.dismiss()
                            // Renomeou a pasta aberta: continua nela com o nome novo.
                            if (existing != null && existing.name.equals(currentCategory, true)) {
                                currentCategory = n; chipCategory.text = n
                            }
                            Snackbar.make(recycler, R.string.category_saved, Snackbar.LENGTH_SHORT).show()
                            load()
                        }
                    }
                }
                if (existing == null) ApiClient.createCategory(this, n, img, done)
                else ApiClient.updateCategory(this, existing.id, n, img, done)
            }
        }
        dialog.show()
    }

    private fun confirmClearCategory(c: Category) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.category_clear_title, c.name))
            .setMessage(resources.getQuantityString(R.plurals.category_clear_message, c.total, c.total))
            .setNegativeButton(R.string.button_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                ApiClient.clearCategory(this, c.id) { response, error ->
                    if (isDestroyed) return@clearCategory
                    if (error != null) { Snackbar.make(recycler, error, Snackbar.LENGTH_LONG).show(); return@clearCategory }
                    Snackbar.make(recycler, resources.getQuantityString(R.plurals.category_cleared, response?.optInt("removed") ?: 0, response?.optInt("removed") ?: 0), Snackbar.LENGTH_SHORT).show()
                    load()
                    PinnedSummary.refreshAsync(this)
                }
            }
            .show()
    }

    private fun confirmDeleteCategory(c: Category) {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val check = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = resources.getQuantityString(R.plurals.category_delete_also, c.total, c.total)
            isEnabled = c.total > 0
        }
        val box = android.widget.FrameLayout(this).apply { setPadding(pad - pad / 3, pad / 3, pad, 0); addView(check) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.category_delete_title, c.name))
            .setMessage(R.string.category_delete_message)
            .setView(box)
            .setNegativeButton(R.string.button_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                ApiClient.deleteCategory(this, c.id, check.isChecked) { _, error ->
                    if (isDestroyed) return@deleteCategory
                    if (error != null) { Snackbar.make(recycler, error, Snackbar.LENGTH_LONG).show(); return@deleteCategory }
                    Snackbar.make(recycler, R.string.category_deleted, Snackbar.LENGTH_SHORT).show()
                    if (c.name.equals(currentCategory, true)) selectCategory(null)
                    load()
                    PinnedSummary.refreshAsync(this)
                }
            }
            .show()
    }

    // ---------- Criar notificação ----------

    override fun composeCategories(): List<Category> = categories
    override fun onComposeSent() = load()

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
        val category = currentCategory
        val offset = if (reset) 0 else adapter.itemCount
        swipeRefresh.isRefreshing = true
        ApiClient.listNotifications(this, status, offset, category) { response, error ->
            if (isDestroyed || status != currentStatus || category != currentCategory) return@listNotifications
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
            // Nova no topo: o RecyclerView ficaria parado no item que era o primeiro; volta pro topo.
            val atTop = !recycler.canScrollVertically(-1)
            if (reset) adapter.submitList(list) else adapter.appendList(list)
            if (reset && atTop) recycler.scrollToPosition(0)
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
        private const val STATE_CATEGORY = "category"
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
