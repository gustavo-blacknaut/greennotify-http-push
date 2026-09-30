package me.blacknaut.greennotify

import android.app.Dialog
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.json.JSONObject
import java.util.Date

/** Modal com todos os detalhes de uma notificação (texto completo, imagem, link e ações). */
class NotificationSheet : BottomSheetDialogFragment() {

    interface Host {
        fun onSheetLink(link: String)
        fun onSheetPrimary(item: NotificationItem)
        fun onSheetSecondary(item: NotificationItem)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).also { d ->
            (d as BottomSheetDialog).behavior.apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_notification, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val item = runCatching { NotificationItem.fromJson(JSONObject(requireArguments().getString(ARG_JSON)!!)) }.getOrNull()
        val host = activity as? Host
        if (item == null || host == null) { dismissAllowingStateLoss(); return }

        view.findViewById<TextView>(R.id.sheetTitle).text =
            item.title.ifBlank { getString(R.string.default_notification_title) }
        val meta = listOf(item.topic, item.app).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        view.findViewById<TextView>(R.id.sheetMeta).apply {
            text = meta; visibility = if (meta.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.sheetTime).apply {
            if (item.createdAt > 0) {
                val d = Date(item.createdAt)
                text = getString(R.string.sheet_sent_at,
                    DateFormat.getMediumDateFormat(context).format(d) + " " + DateFormat.getTimeFormat(context).format(d))
            } else visibility = View.GONE
        }
        view.findViewById<TextView>(R.id.sheetMessage).apply {
            text = item.message; visibility = if (item.message.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.sheetReason).apply {
            text = getString(R.string.reason_format, item.reason)
            visibility = if (item.reason.isBlank()) View.GONE else View.VISIBLE
        }

        val image = view.findViewById<ImageView>(R.id.sheetImage)
        if (isWebLink(item.image)) {
            ImageLoader.load(item.image, 1024) { bmp ->
                if (!isAdded || view.parent == null) return@load
                if (bmp != null) { image.setImageBitmap(bmp); image.visibility = View.VISIBLE }
            }
        }

        val link = view.findViewById<Button>(R.id.sheetLink)
        if (isWebLink(item.link)) link.setOnClickListener { host.onSheetLink(item.link); dismiss() }
        else link.visibility = View.GONE

        val done = item.status == "done"
        (view.findViewById<Button>(R.id.sheetPrimary) as com.google.android.material.button.MaterialButton).apply {
            setIconResource(if (done) R.drawable.ic_undo else R.drawable.ic_check)
            setText(if (done) R.string.action_reopen else R.string.action_complete)
            setOnClickListener { host.onSheetPrimary(item); dismiss() }
        }
        val archived = item.status == "archived"
        (view.findViewById<Button>(R.id.sheetSecondary) as com.google.android.material.button.MaterialButton).apply {
            setIconResource(if (archived) R.drawable.ic_unarchive else R.drawable.ic_archive)
            setText(if (archived) R.string.action_restore else R.string.action_archive)
            setOnClickListener { host.onSheetSecondary(item); dismiss() }
        }
    }

    companion object {
        private const val ARG_JSON = "json"
        const val TAG = "notification-sheet"

        fun show(activity: androidx.appcompat.app.AppCompatActivity, item: NotificationItem) {
            val fm = activity.supportFragmentManager
            if (fm.isStateSaved) return
            (fm.findFragmentByTag(TAG) as? NotificationSheet)?.dismissAllowingStateLoss()
            NotificationSheet().apply {
                arguments = Bundle().apply { putString(ARG_JSON, item.toJson().toString()) }
            }.show(fm, TAG)
        }
    }
}
