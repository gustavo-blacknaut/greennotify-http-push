package me.blacknaut.greennotify

import android.os.Handler
import android.os.Looper

/** Avisa a tela aberta que chegou notificação nova, para a lista atualizar sozinha. */
object NotificationBus {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var listener: (() -> Unit)? = null

    fun newNotification() {
        main.post { listener?.invoke() }
    }
}
