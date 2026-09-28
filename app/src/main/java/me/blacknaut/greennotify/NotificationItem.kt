package me.blacknaut.greennotify

import org.json.JSONObject

data class NotificationItem(
    val id: String,
    val title: String,
    val message: String,
    val reason: String,
    val app: String,
    val link: String,
    val status: String,
    val createdAt: Long
) {
    companion object {
        fun fromJson(json: JSONObject): NotificationItem = NotificationItem(
            id = json.optString("id"),
            title = json.optString("title"),
            message = json.optString("message"),
            reason = json.optString("reason"),
            app = json.optString("app"),
            link = json.optString("link"),
            status = json.optString("status", "pending"),
            createdAt = json.optLong("createdAt")
        )
    }
}
