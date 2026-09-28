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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notifications)
        title = "Notificações"

        val recycler = findViewById<RecyclerView>(R.id.recyclerNotifications)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = NotificationAdapter(
            mutableListOf(),
            onOpenLink = { link -> openLink(link) },
            onComplete = { item -> completeItem(item) },
            onArchive = { item -> archiveItem(item) }
        )
        recycler.adapter = adapter

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
            Toast.makeText(this, "Configure o servidor na tela principal primeiro", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        load()
    }

    private fun load() {
        swipeRefresh.isRefreshing = true
        ApiClient.listNotifications(this, currentStatus) { response ->
            swipeRefresh.isRefreshing = false
            if (response == null) {
                Toast.makeText(this, "Não consegui falar com o servidor", Toast.LENGTH_SHORT).show()
                return@listNotifications
            }
            val array: JSONArray = response.optJSONArray("notifications") ?: JSONArray()
            val list = (0 until array.length()).map { NotificationItem.fromJson(array.getJSONObject(it)) }
            adapter.submitList(list)
            textEmpty.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun completeItem(item: NotificationItem) {
        ApiClient.complete(this, item.id) { response ->
            if (response?.optBoolean("ok") == true) {
                if (currentStatus == "pending") adapter.removeItem(item.id) else load()
            } else {
                Toast.makeText(this, "Não consegui marcar como concluída", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun archiveItem(item: NotificationItem) {
        ApiClient.move(this, item.id, "archived") { response ->
            if (response?.optBoolean("ok") == true) {
                if (currentStatus != "archived") adapter.removeItem(item.id) else load()
            } else {
                Toast.makeText(this, "Não consegui arquivar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openLink(link: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (e: Exception) {
            Toast.makeText(this, "Link inválido", Toast.LENGTH_SHORT).show()
        }
    }
}
