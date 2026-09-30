package me.blacknaut.greennotify

import org.json.JSONObject

/** Categoria (pasta) da tela inicial. [pending] e [total] vêm contados pelo servidor. */
data class Category(
    val id: String,
    val name: String,
    val image: String,
    val pending: Int,
    val total: Int
) {
    companion object {
        const val MAX = 4

        fun fromJson(json: JSONObject) = Category(
            id = json.optString("id"),
            name = json.optString("name"),
            image = json.optString("image"),
            pending = json.optInt("pending"),
            total = json.optInt("total")
        )
    }
}
