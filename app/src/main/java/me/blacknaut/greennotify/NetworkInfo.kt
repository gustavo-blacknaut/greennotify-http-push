package me.blacknaut.greennotify

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkInfo {
    enum class Net { WIFI, MOBILE, NONE }

    /** Rede ativa agora: Wi‑Fi (ou cabo), dados móveis, ou sem conexão. */
    fun current(ctx: Context): Net {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return Net.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Net.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Net.MOBILE
            else -> Net.NONE
        }
    }
}
