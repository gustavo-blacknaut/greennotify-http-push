package me.blacknaut.greennotify

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONArray

class NotificationsActivity : AppCompatActivity() {

    private lateinit var adapter: NotificationAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var textEmpty: TextView
    private var currentStatus = "pending"
    private var loading = false
    private var hasMore = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notifications)
        setTitle(R.string.title_notifications)

        val recycler = findViewById<RecyclerView>(R.id.recyclerNotifications)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = NotificationAdapter(
            mutableListOf(),
            onOpenLink = { link -> openLink(link) },
            onComplete = { item -> completeItem(item) },
            onArchive = { item -> archiveItem(item) }
        )
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !rv.canScrollVertically(1)) loadMore()
            }
        })

        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.setOnRefreshListener { load() }
        textEmpty = findViewById(R.id.textEmpty)

        findViewById<android.widget.Button>(R.id.buttonFilterPending).setOnClickListener {
            currentStatus = "pending"; load()
        }
        findViewById<android.widget.Button>(R.id.buttonFilterDone).setOnClickListener {
            currentStatus = "done"; load()
        }
        findViewById<android.widget.Button>(R.id.buttonFilterArchived).setOnClickListener {
            currentStatus = "archived"; load()
        }

        if (!Prefs.isConfigured(this)) {
            Toast.makeText(this, R.string.toast_configure_first, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        load()
    }

    private fun load() = fetchPage(reset = true)

    private fun loadMore() {
        if (hasMore) fetchPage(reset = false)
    }

    // offset = itens já exibidos: os concluídos/arquivados saem da lista local e do filtro no servidor juntos.
    private fun fetchPage(reset: Boolean) {
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
                Toast.makeText(this, error ?: getString(R.string.error_invalid_response), Toast.LENGTH_LONG).show()
                return@listNotifications
            }
            val array: JSONArray = response.optJSONArray("notifications") ?: JSONArray()
            val list = (0 until array.length()).map { NotificationItem.fromJson(array.getJSONObject(it)) }
            hasMore = list.size == ApiClient.PAGE_SIZE
            if (reset) adapter.submitList(list) else adapter.appendList(list)
            textEmpty.visibility = if (adapter.itemCount == 0) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun completeItem(item: NotificationItem) {
        ApiClient.complete(this, item.id) { response, error ->
            if (isDestroyed) return@complete
            if (error == null && response?.optBoolean("ok") == true) {
                if (currentStatus == "pending") adapter.removeItem(item.id) else load()
            } else {
                Toast.makeText(this, error ?: getString(R.string.error_complete), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun archiveItem(item: NotificationItem) {
        ApiClient.move(this, item.id, "archived") { response, error ->
            if (isDestroyed) return@move
            if (error == null && response?.optBoolean("ok") == true) {
                if (currentStatus != "archived") adapter.removeItem(item.id) else load()
            } else {
                Toast.makeText(this, error ?: getString(R.string.error_archive), Toast.LENGTH_LONG).show()
            }
        }
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
}
