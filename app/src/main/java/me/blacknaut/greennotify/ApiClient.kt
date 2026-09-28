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

    private fun toHttpUrl(serverUrl: String): String {
        var url = serverUrl.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        return url
    }

    private fun post(ctx: Context, path: String, body: JSONObject, onResult: (JSONObject?) -> Unit) {
        val serverUrl = Prefs.getServerUrl(ctx)
        if (serverUrl.isBlank()) {
            mainHandler.post { onResult(null) }
            return
        }
        val request = Request.Builder()
            .url(toHttpUrl(serverUrl) + path)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onResult(null) }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                val text = response.body?.string()
                val json = try {
                    if (text != null) JSONObject(text) else null
                } catch (e: Exception) {
                    null
                }
                response.close()
                mainHandler.post { onResult(json) }
            }
        })
    }

    private fun authBody(ctx: Context): JSONObject {
        return JSONObject()
            .put("key", Prefs.getApiKey(ctx))
            .put("deviceId", Prefs.getDeviceId(ctx))
    }

    fun listNotifications(ctx: Context, status: String?, onResult: (JSONObject?) -> Unit) {
        val body = authBody(ctx)
        if (status != null) body.put("status", status)
        post(ctx, "/list", body, onResult)
    }

    fun complete(ctx: Context, id: String, onResult: (JSONObject?) -> Unit) {
        post(ctx, "/complete", authBody(ctx).put("id", id), onResult)
    }

    fun move(ctx: Context, id: String, status: String, onResult: (JSONObject?) -> Unit) {
        post(ctx, "/move", authBody(ctx).put("id", id).put("status", status), onResult)
    }

    fun delete(ctx: Context, id: String, onResult: (JSONObject?) -> Unit) {
        post(ctx, "/delete", authBody(ctx).put("id", id), onResult)
    }
}
