package me.blacknaut.greennotify

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

/**
 * Arrastar para a direita arquiva, para a esquerda apaga. Enquanto arrasta aparece o fundo
 * colorido com o ícone da ação.
 */
class SwipeActions(
    ctx: Context,
    private val canArchive: () -> Boolean,
    private val onArchive: (position: Int) -> Unit,
    private val onDelete: (position: Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

    private val density = ctx.resources.displayMetrics.density
    private val radius = 20 * density
    private val archiveColor = MaterialColors.getColor(ctx, androidx.appcompat.R.attr.colorPrimary, 0)
    private val onArchiveColor = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnPrimary, 0)
    private val deleteColor = MaterialColors.getColor(ctx, androidx.appcompat.R.attr.colorError, 0)
    private val onDeleteColor = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnError, 0)
    private val archiveIcon = ContextCompat.getDrawable(ctx, R.drawable.ic_archive)!!.mutate()
    private val deleteIcon = ContextCompat.getDrawable(ctx, R.drawable.ic_delete)!!.mutate()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
        if (canArchive()) ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT else ItemTouchHelper.LEFT

    override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

    override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
        val pos = vh.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return
        if (direction == ItemTouchHelper.RIGHT) onArchive(pos) else onDelete(pos)
    }

    override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = 0.35f

    override fun onChildDraw(
        c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
        dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
    ) {
        val v = vh.itemView
        if (dX != 0f) {
            val right = dX > 0
            paint.color = if (right) archiveColor else deleteColor
            val bg = if (right) RectF(v.left.toFloat(), v.top.toFloat(), v.left + dX + radius, v.bottom.toFloat())
                     else RectF(v.right + dX - radius, v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat())
            c.drawRoundRect(bg, radius, radius, paint)

            val icon = if (right) archiveIcon else deleteIcon
            icon.setTint(if (right) onArchiveColor else onDeleteColor)
            val size = (24 * density).toInt()
            val margin = (24 * density).toInt()
            val top = v.top + (v.height - size) / 2
            val left = if (right) v.left + margin else v.right - margin - size
            icon.setBounds(left, top, left + size, top + size)
            if ((right && dX > margin) || (!right && -dX > margin)) icon.draw(c)
        }
        super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
    }
}
