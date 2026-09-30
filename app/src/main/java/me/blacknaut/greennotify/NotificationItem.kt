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
    val category: String,
    /** Imagem da categoria (vem do servidor): usada quando a notificação não tem imagem própria. */
    val categoryImage: String,
    /** details (modal), link (abre o link) ou link_done (abre o link e marca como concluída). */
    val tapAction: String,
    /** "alarm" = alerta máximo. */
    val priority: String,
    val status: String,
    val createdAt: Long
) {
    /** Imagem mostrada no círculo e na notificação: a própria ou, sem ela, a da categoria. */
    val displayImage: String get() = image.ifBlank { categoryImage }

    /** Toque abre o link direto em vez do modal. */
    val opensLink: Boolean get() = (tapAction == "link" || tapAction == "link_done") && isWebLink(link)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("message", message).put("reason", reason)
        .put("app", app).put("link", link).put("topic", topic).put("image", image).put("category", category).put("categoryImage", categoryImage)
        .put("tapAction", tapAction).put("priority", priority)
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
            category = json.optString("category"),
            categoryImage = json.optString("categoryImage"),
            tapAction = json.optString("tapAction"),
            priority = json.optString("priority"),
            status = json.optString("status", "pending"),
            createdAt = json.optLong("createdAt")
        )
    }
}
