package me.blacknaut.greennotify

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** Criar uma notificação pelo próprio celular (vai para o servidor e volta como qualquer outra). */
class ComposeSheet : BottomSheetDialogFragment() {

    interface Host {
        fun composeCategories(): List<Category>
        fun onComposeSent()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).also { d ->
            (d as BottomSheetDialog).behavior.apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_compose, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val host = activity as? Host ?: return dismissAllowingStateLoss()
        val title = view.findViewById<EditText>(R.id.composeTitle)
        val message = view.findViewById<EditText>(R.id.composeMessage)
        val reason = view.findViewById<EditText>(R.id.composeReason)
        val link = view.findViewById<EditText>(R.id.composeLink)
        val image = view.findViewById<EditText>(R.id.composeImage)
        val category = view.findViewById<AutoCompleteTextView>(R.id.composeCategory)
        val send = view.findViewById<Button>(R.id.composeSend)

        val none = getString(R.string.compose_no_category)
        val names = listOf(none) + host.composeCategories().map { it.name }
        category.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, names))
        category.setText(arguments?.getString(ARG_CATEGORY)?.takeIf { it in names } ?: none, false)

        send.setOnClickListener {
            val t = title.text.toString().trim()
            val m = message.text.toString().trim()
            val l = link.text.toString().trim()
            val img = image.text.toString().trim()
            when {
                t.isBlank() && m.isBlank() -> { title.error = getString(R.string.compose_error_empty); return@setOnClickListener }
                l.isNotBlank() && !isWebLink(l) -> { link.error = getString(R.string.error_invalid_link); return@setOnClickListener }
                img.isNotBlank() && !isWebLink(img) -> { image.error = getString(R.string.error_invalid_link); return@setOnClickListener }
            }
            val body = ApiClient.authBody(requireContext())
                .put("title", t).put("message", m).put("reason", reason.text.toString().trim())
                .put("app", getString(R.string.compose_origin))
            if (l.isNotBlank()) body.put("link", l)
            if (img.isNotBlank()) body.put("image", img)
            category.text.toString().takeIf { it != none && it.isNotBlank() }?.let { body.put("category", it) }

            send.isEnabled = false
            ApiClient.notify(requireContext(), body) { _, error ->
                if (!isAdded) return@notify
                send.isEnabled = true
                if (error != null) {
                    Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), R.string.compose_sent, Toast.LENGTH_SHORT).show()
                    host.onComposeSent()
                    dismiss()
                }
            }
        }
    }

    companion object {
        private const val ARG_CATEGORY = "category"
        const val TAG = "compose-sheet"

        fun show(activity: androidx.appcompat.app.AppCompatActivity, category: String?) {
            val fm = activity.supportFragmentManager
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            ComposeSheet().apply {
                arguments = Bundle().apply { putString(ARG_CATEGORY, category) }
            }.show(fm, TAG)
        }
    }
}
