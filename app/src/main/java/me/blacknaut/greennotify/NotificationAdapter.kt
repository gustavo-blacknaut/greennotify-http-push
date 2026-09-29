package me.blacknaut.greennotify

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class NotificationAdapter(
    private val items: MutableList<NotificationItem>,
    private val onOpenLink: (String) -> Unit,
    private val onComplete: (NotificationItem) -> Unit,
    private val onArchive: (NotificationItem) -> Unit
) : RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR"))

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.textTitle)
        val appDate: TextView = view.findViewById(R.id.textAppDate)
        val message: TextView = view.findViewById(R.id.textMessage)
        val reason: TextView = view.findViewById(R.id.textReason)
        val link: TextView = view.findViewById(R.id.textLink)
        val buttonComplete: Button = view.findViewById(R.id.buttonComplete)
        val buttonArchive: Button = view.findViewById(R.id.buttonArchive)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_notification, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        holder.title.text = item.title
        val date = if (item.createdAt > 0) dateFormat.format(Date(item.createdAt)) else ""
        holder.appDate.text = listOf(item.topic, item.app, date).distinct().filter { it.isNotBlank() }.joinToString(" • ")
        holder.message.visibility = if (item.message.isBlank()) View.GONE else View.VISIBLE
        holder.message.text = item.message
        holder.reason.visibility = if (item.reason.isBlank()) View.GONE else View.VISIBLE
        holder.reason.text = ctx.getString(R.string.reason_format, item.reason)

        if (isWebLink(item.link)) {
            holder.link.visibility = View.VISIBLE
            holder.link.text = item.link
            holder.link.setOnClickListener { onOpenLink(item.link) }
        } else {
            holder.link.visibility = View.GONE
        }

        val done = item.status == "done"
        holder.buttonComplete.isEnabled = !done
        holder.buttonComplete.setText(if (done) R.string.button_completed else R.string.button_complete)
        holder.buttonComplete.setOnClickListener { onComplete(item) }

        val archived = item.status == "archived"
        holder.buttonArchive.isEnabled = !archived
        holder.buttonArchive.setText(if (archived) R.string.button_archived else R.string.button_archive)
        holder.buttonArchive.setOnClickListener { onArchive(item) }
    }

    override fun getItemCount(): Int = items.size

    // Diff síncrono (listas de até algumas centenas de itens): itemCount fica correto logo após a chamada,
    // o que a paginação por offset precisa.
    fun submitList(newItems: List<NotificationItem>) {
        val old = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].id == newItems[n].id
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == newItems[n]
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    fun appendList(more: List<NotificationItem>) {
        val start = items.size
        items.addAll(more)
        notifyItemRangeInserted(start, more.size)
    }

    fun removeItem(id: String) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items.removeAt(idx)
            notifyItemRemoved(idx)
        }
    }
}
