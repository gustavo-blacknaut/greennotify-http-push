package me.blacknaut.greennotify

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkInfo {
    /** true no Wi‑Fi/cabo (barato para o rádio); false nos dados móveis ou sem rede. */
    fun isWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
