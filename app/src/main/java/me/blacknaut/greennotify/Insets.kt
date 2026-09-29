package me.blacknaut.greennotify

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Android 15+ (targetSdk >= 35) desenha o app atrás das barras do sistema.
 * O inset do topo entregue pelo AppCompat já inclui a action bar.
 */
fun applySystemBarInsets(root: View) {
    val top = root.paddingTop
    val bottom = root.paddingBottom
    val left = root.paddingLeft
    val right = root.paddingRight
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
        )
        v.updatePadding(top = top + bars.top, bottom = bottom + bars.bottom, left = left + bars.left, right = right + bars.right)
        insets
    }
}
