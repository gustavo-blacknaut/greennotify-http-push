package me.blacknaut.greennotify

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** Cliente HTTP simples para as chamadas POST do servidor GreenNotify. */
object ApiClient {
    private val client = OkHttpClient()
    private val jsonMedia = "application/json".toMediaType()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun toHttpUrl(serverUrl: String): String {
        val url = serverUrl.trim().trimEnd('/')
        return when {
            url.startsWith("http://", true) || url.startsWith("https://", true) -> url
            url.startsWith("ws://", true) -> "http://" + url.substring(5)
            url.startsWith("wss://", true) -> "https://" + url.substring(6)
            else -> "http://$url"
        }
    }

    /** onResult(json, erro): erro vem preenchido em falha de rede ou resposta não-2xx. */
    private fun post(ctx: Context, path: String, body: JSONObject, onResult: (JSONObject?, String?) -> Unit) {
        val serverUrl = Prefs.getServerUrl(ctx)
        if (serverUrl.isBlank()) {
            mainHandler.post { onResult(null, ctx.getString(R.string.error_server_not_configured)) }
            return
        }
        val request = try {
            Request.Builder()
                .url(toHttpUrl(serverUrl) + path)
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
        } catch (e: IllegalArgumentException) {
            mainHandler.post { onResult(null, ctx.getString(R.string.error_invalid_server_url)) }
            return
        }

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onResult(null, ctx.getString(R.string.error_network)) }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                val json = response.use {
                    try {
                        it.body?.string()?.let(::JSONObject)
                    } catch (e: Exception) {
                        null
                    }
                }
                val error = if (response.isSuccessful) null
                    else json?.optString("error")?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.error_http, response.code)
                mainHandler.post { onResult(json, error) }
            }
        })
    }

    /** Versão bloqueante, para rodar em thread de fundo (WorkManager). null = falha de rede ou resposta não-2xx. */
    fun postSync(ctx: Context, path: String, body: JSONObject): JSONObject? {
        val serverUrl = Prefs.getServerUrl(ctx)
        if (serverUrl.isBlank()) return null
        return try {
            val request = Request.Builder()
                .url(toHttpUrl(serverUrl) + path)
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()?.let(::JSONObject)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun authBody(ctx: Context): JSONObject {
        return JSONObject()
            .put("key", Prefs.getApiKey(ctx))
            .put("deviceId", Prefs.getDeviceId(ctx))
    }

    const val PAGE_SIZE = 100

    fun listNotifications(ctx: Context, status: String?, offset: Int, category: String?, onResult: (JSONObject?, String?) -> Unit) {
        val body = authBody(ctx).put("limit", PAGE_SIZE).put("offset", offset)
        if (status != null) body.put("status", status)
        if (!category.isNullOrBlank()) body.put("category", category)
        post(ctx, "/list", body, onResult)
    }

    fun complete(ctx: Context, id: String, onResult: (JSONObject?, String?) -> Unit) {
        post(ctx, "/complete", authBody(ctx).put("id", id), onResult)
    }

    fun move(ctx: Context, id: String, status: String, onResult: (JSONObject?, String?) -> Unit) {
        post(ctx, "/move", authBody(ctx).put("id", id).put("status", status), onResult)
    }

    fun delete(ctx: Context, id: String, onResult: (JSONObject?, String?) -> Unit) {
        post(ctx, "/delete", authBody(ctx).put("id", id), onResult)
    }

    /** Envia uma notificação para este mesmo celular (criada pelo app). [body] já inclui key/deviceId. */
    fun notify(ctx: Context, body: JSONObject, onResult: (JSONObject?, String?) -> Unit) {
        post(ctx, "/notify", body, onResult)
    }

    // ---------- Categorias ----------

    fun listCategories(ctx: Context, onResult: (JSONObject?, String?) -> Unit) {
        post(ctx, "/categories/list", authBody(ctx), onResult)
    }

    fun createCategory(ctx: Context, name: String, image: String, onResult: (JSONObject?, String?) -> Unit) {
        post(ctx, "/categories/create", authBody(ctx).put("name", name).put("image", image), onResult)
    }

    fun updateCategory(ctx: Context, id: String, name: String, image: String, onResult: (JSONObject?, String?) -> Unit) {
        post(ctx, "/categories/update", authBody(ctx).put("id", id).put("name", name).put("image", image), onResult)
    }

    fun deleteCategory(ctx: Context, id: String, withNotifications: Boolean, onResult: (JSONObject?, String?) -> Unit) {
        post(ctx, "/categories/delete", authBody(ctx).put("id", id).put("deleteNotifications", withNotifications), onResult)
    }

    fun clearCategory(ctx: Context, id: String, onResult: (JSONObject?, String?) -> Unit) {
        post(ctx, "/categories/clear", authBody(ctx).put("id", id), onResult)
    }
}
