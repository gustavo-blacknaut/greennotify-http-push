package me.blacknaut.greennotify

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class NotificationAdapter(
    private val items: MutableList<NotificationItem>,
    private val onOpenLink: (String) -> Unit,
    private val onComplete: (NotificationItem) -> Unit,
    private val onArchive: (NotificationItem) -> Unit
) : RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.textTitle)
        val appDate: TextView = view.findViewById(R.id.textAppDate)
        val message: TextView = view.findViewById(R.id.textMessage)
        val reason: TextView = view.findViewById(R.id.textReason)
        val link: TextView = view.findViewById(R.id.textLink)
        val buttonComplete: android.widget.Button = view.findViewById(R.id.buttonComplete)
        val buttonArchive: android.widget.Button = view.findViewById(R.id.buttonArchive)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_notification, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        val date = if (item.createdAt > 0) dateFormat.format(Date(item.createdAt)) else ""
        holder.appDate.text = listOf(item.app, date).filter { it.isNotBlank() }.joinToString(" • ")
        holder.message.visibility = if (item.message.isBlank()) View.GONE else View.VISIBLE
        holder.message.text = item.message
        holder.reason.visibility = if (item.reason.isBlank()) View.GONE else View.VISIBLE
        holder.reason.text = "Motivo: ${item.reason}"

        if (item.link.isNotBlank()) {
            holder.link.visibility = View.VISIBLE
            holder.link.text = item.link
            holder.link.setOnClickListener { onOpenLink(item.link) }
        } else {
            holder.link.visibility = View.GONE
        }

        holder.buttonComplete.isEnabled = item.status != "done"
        holder.buttonComplete.text = if (item.status == "done") "Concluída" else "Concluir"
        holder.buttonComplete.setOnClickListener { onComplete(item) }

        holder.buttonArchive.isEnabled = item.status != "archived"
        holder.buttonArchive.text = if (item.status == "archived") "Arquivada" else "Arquivar"
        holder.buttonArchive.setOnClickListener { onArchive(item) }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<NotificationItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun removeItem(id: String) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items.removeAt(idx)
            notifyItemRemoved(idx)
        }
    }
}
