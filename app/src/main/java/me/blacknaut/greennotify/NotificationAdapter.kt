package me.blacknaut.greennotify

import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlin.math.abs

class NotificationAdapter(
    private val items: MutableList<NotificationItem>,
    private val onOpenLink: (String) -> Unit,
    /** Toque no cartão ou no botão de informações: abre o modal com os detalhes. */
    private val onOpen: (NotificationItem) -> Unit,
    /** Toque no cartão: igual ao toque na notificação (modal, ou abre o link direto). */
    private val onTap: (NotificationItem) -> Unit,
    /** Concluir (ou reabrir, se já concluída). */
    private val onPrimary: (NotificationItem) -> Unit,
    /** Arquivar (ou restaurar, se já arquivada). */
    private val onSecondary: (NotificationItem) -> Unit
) : RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: TextView = view.findViewById(R.id.textAvatar)
        val title: TextView = view.findViewById(R.id.textTitle)
        val time: TextView = view.findViewById(R.id.textTime)
        val meta: TextView = view.findViewById(R.id.textMeta)
        val message: TextView = view.findViewById(R.id.textMessage)
        val reason: TextView = view.findViewById(R.id.textReason)
        val link: MaterialButton = view.findViewById(R.id.buttonLink)
        val primary: MaterialButton = view.findViewById(R.id.buttonComplete)
        val secondary: MaterialButton = view.findViewById(R.id.buttonArchive)
        val info: MaterialButton = view.findViewById(R.id.buttonInfo)
        val readMore: TextView = view.findViewById(R.id.textReadMore)
        val avatarImage: ImageView = view.findViewById(R.id.imageAvatar)
    }

    private val expanded = HashSet<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_notification, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        val subject = item.topic.ifBlank { item.app }.ifBlank { item.title }

        holder.avatar.text = subject.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "G"
        holder.avatar.backgroundTintList = ColorStateList.valueOf(avatarColor(ctx, subject))

        holder.title.text = item.title.ifBlank { ctx.getString(R.string.default_notification_title) }
        holder.time.text = if (item.createdAt > 0) DateUtils.getRelativeTimeSpanString(
            item.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
        ) else ""

        val meta = listOf(item.category, item.topic, item.app).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        holder.meta.text = meta
        holder.meta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE

        holder.message.text = item.message
        holder.message.visibility = if (item.message.isBlank()) View.GONE else View.VISIBLE
        // Texto grande: mostra 4 linhas e "Ler mais" (o texto completo também está no modal).
        val open = item.id in expanded
        holder.message.maxLines = if (open) Int.MAX_VALUE else COLLAPSED_LINES
        holder.readMore.visibility = View.GONE
        holder.message.post {
            val layout = holder.message.layout
            val long = layout != null && (layout.lineCount > COLLAPSED_LINES || layout.getEllipsisCount(layout.lineCount - 1) > 0)
            holder.readMore.visibility = if (long) View.VISIBLE else View.GONE
            holder.readMore.setText(if (open) R.string.read_less else R.string.read_more)
        }
        holder.readMore.setOnClickListener {
            if (!expanded.add(item.id)) expanded.remove(item.id)
            notifyItemChanged(holder.bindingAdapterPosition)
        }

        // Com imagem, ela ocupa o círculo no lugar da letra.
        holder.avatarImage.setImageDrawable(null)
        holder.avatarImage.tag = item.id
        if (isWebLink(item.displayImage)) {
            holder.avatarImage.visibility = View.VISIBLE
            ImageLoader.load(item.displayImage, 160) { bmp ->
                if (holder.avatarImage.tag != item.id) return@load
                if (bmp != null) holder.avatarImage.setImageBitmap(bmp) else holder.avatarImage.visibility = View.GONE
            }
        } else {
            holder.avatarImage.visibility = View.GONE
        }
        holder.itemView.setOnClickListener { onTap(item) }
        holder.info.setOnClickListener { onOpen(item) }
        TooltipCompat.setTooltipText(holder.info, ctx.getString(R.string.action_details))
        holder.reason.text = ctx.getString(R.string.reason_format, item.reason)
        holder.reason.visibility = if (item.reason.isBlank()) View.GONE else View.VISIBLE

        if (isWebLink(item.link)) {
            holder.link.visibility = View.VISIBLE
            holder.link.setOnClickListener { onOpenLink(item.link) }
        } else {
            holder.link.visibility = View.GONE
        }

        val done = item.status == "done"
        holder.primary.setIconResource(if (done) R.drawable.ic_undo else R.drawable.ic_check)
        setLabel(holder.primary, ctx.getString(if (done) R.string.action_reopen else R.string.action_complete))
        holder.primary.setOnClickListener { onPrimary(item) }

        val archived = item.status == "archived"
        holder.secondary.setIconResource(if (archived) R.drawable.ic_unarchive else R.drawable.ic_archive)
        setLabel(holder.secondary, ctx.getString(if (archived) R.string.action_restore else R.string.action_archive))
        holder.secondary.setOnClickListener { onSecondary(item) }
    }

    private fun setLabel(button: MaterialButton, label: String) {
        button.contentDescription = label
        TooltipCompat.setTooltipText(button, label)
    }

    override fun getItemCount(): Int = items.size

    fun itemAt(position: Int): NotificationItem = items[position]

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

    fun removeItem(id: String): Int {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items.removeAt(idx)
            notifyItemRemoved(idx)
        }
        return idx
    }

    fun insertItem(position: Int, item: NotificationItem) {
        val pos = position.coerceIn(0, items.size)
        items.add(pos, item)
        notifyItemInserted(pos)
    }

    companion object {
        private const val COLLAPSED_LINES = 4

        /** Cor do círculo com a inicial (a mesma para o mesmo assunto, no cartão e na pasta). */
        fun avatarColor(ctx: android.content.Context, subject: String): Int =
            ContextCompat.getColor(ctx, AVATAR_COLORS[abs(subject.hashCode()) % AVATAR_COLORS.size])
        private val AVATAR_COLORS = intArrayOf(
            R.color.avatar_1, R.color.avatar_2, R.color.avatar_3,
            R.color.avatar_4, R.color.avatar_5, R.color.avatar_6
        )
    }
}
