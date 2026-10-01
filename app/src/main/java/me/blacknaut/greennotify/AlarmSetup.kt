package me.blacknaut.greennotify

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** O que falta liberar no Android para o alarme funcionar por completo. */
object AlarmSetup {
    enum class Item(val label: Int) {
        FULL_SCREEN(R.string.alarm_setup_fullscreen),
        BATTERY(R.string.alarm_setup_battery)
    }

    fun missing(ctx: Context): List<Item> {
        val out = mutableListOf<Item>()
        if (Build.VERSION.SDK_INT >= 34 && !ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            out += Item.FULL_SCREEN
        }
        if (!ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) out += Item.BATTERY
        return out
    }

    @android.annotation.SuppressLint("BatteryLife")
    fun open(activity: Activity, item: Item) {
        val pkg = Uri.parse("package:" + activity.packageName)
        runCatching {
            when (item) {
                Item.FULL_SCREEN -> activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
                Item.BATTERY -> activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
            }
        }
    }

    /** Toca um alarme de teste (mesmo caminho de um alerta de verdade). */
    fun test(ctx: Context) {
        Alarm.start(ctx, org.json.JSONObject()
            .put("id", "teste-alarme")
            .put("title", ctx.getString(R.string.alarm_test_title))
            .put("message", ctx.getString(R.string.alarm_test_message))
            .put("priority", "alarm"))
    }
}
