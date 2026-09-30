package me.blacknaut.greennotify

import org.json.JSONObject

data class NotificationItem(
    val id: String,
    val title: String,
    val message: String,
    val reason: String,
    val app: String,
    val link: String,
    val topic: String,
    val image: String,
    val status: String,
    val createdAt: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("message", message).put("reason", reason)
        .put("app", app).put("link", link).put("topic", topic).put("image", image)
        .put("status", status).put("createdAt", createdAt)

    companion object {
        fun fromJson(json: JSONObject): NotificationItem = NotificationItem(
            id = json.optString("id"),
            title = json.optString("title"),
            message = json.optString("message"),
            reason = json.optString("reason"),
            app = json.optString("app"),
            link = json.optString("link"),
            topic = json.optString("topic"),
            image = json.optString("image"),
            status = json.optString("status", "pending"),
            createdAt = json.optLong("createdAt")
        )
    }
}
