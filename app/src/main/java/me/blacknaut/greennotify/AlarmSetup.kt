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
        BATTERY(R.string.alarm_setup_battery),
        EXACT(R.string.alarm_setup_exact),
        OVERLAY(R.string.alarm_setup_overlay),
        PROMOTED(R.string.alarm_setup_promoted)
    }

    fun missing(ctx: Context): List<Item> {
        val out = mutableListOf<Item>()
        if (Build.VERSION.SDK_INT >= 34 && !ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            out += Item.FULL_SCREEN
        }
        if (!ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) out += Item.BATTERY
        // Só importa se alguma rede estiver no modo economia (consultar a cada N min).
        if (Policy.anyPolling(ctx) && !PollAlarm.canExact(ctx)) out += Item.EXACT
        if (!Settings.canDrawOverlays(ctx)) out += Item.OVERLAY
        if (Build.VERSION.SDK_INT >= 36 && !ctx.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()) out += Item.PROMOTED
        return out
    }

    @android.annotation.SuppressLint("BatteryLife")
    fun open(activity: Activity, item: Item) {
        val pkg = Uri.parse("package:" + activity.packageName)
        runCatching {
            when (item) {
                Item.FULL_SCREEN -> activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
                Item.BATTERY -> activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
                Item.EXACT -> if (Build.VERSION.SDK_INT >= 31) activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
                Item.OVERLAY -> activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
                Item.PROMOTED -> runCatching {
                    activity.startActivity(Intent("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS")
                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                }.onFailure {
                    activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                }
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
