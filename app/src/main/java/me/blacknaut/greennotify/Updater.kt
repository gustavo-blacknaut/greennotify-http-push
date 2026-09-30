package me.blacknaut.greennotify

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Atualização com 1 clique. O app e o servidor vêm dos releases públicos do GitHub:
 *  - app: baixa o APK do release mais novo e abre o instalador do Android (mesma assinatura = atualiza por cima);
 *  - servidor: pede para o próprio servidor baixar os arquivos novos e reiniciar (/admin/update, com a ADMIN_KEY).
 */
object Updater {
    const val REPO = "gustavo-blacknaut/greennotify-http-push"
    private val http = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())

    data class Release(val version: String, val notes: String, val apkUrl: String?, val apkSize: Long)

    fun appVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** true se [a] for mais nova que [b] ("1.3.0" > "1.2.9"). */
    fun newer(a: String, b: String): Boolean {
        val pa = a.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until 3) {
            val x = pa.getOrElse(i) { 0 }; val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun latestRelease(done: (Release?, String?) -> Unit) = Thread {
        val result = runCatching {
            val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("GitHub respondeu ${r.code}")
                val j = JSONObject(r.body!!.string())
                val assets = j.optJSONArray("assets")
                var url: String? = null; var size = 0L
                for (i in 0 until (assets?.length() ?: 0)) {
                    val a = assets!!.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); size = a.optLong("size") }
                }
                Release(j.optString("tag_name").removePrefix("v"), j.optString("body"), url, size)
            }
        }
        main.post { done(result.getOrNull(), result.exceptionOrNull()?.message) }
    }.start()

    /** Versão do servidor configurado (GET /health). null = não respondeu ou é antigo demais para dizer. */
    fun serverVersion(ctx: Context, done: (String?) -> Unit) = Thread {
        val v = runCatching {
            val req = Request.Builder().url(ApiClient.toHttpUrl(Prefs.getServerUrl(ctx)) + "/health").build()
            http.newCall(req).execute().use { r -> JSONObject(r.body!!.string()).optString("version").ifBlank { null } }
        }.getOrNull()
        main.post { done(v) }
    }.start()

    /** Pede ao servidor para se atualizar. done(versãoNova, erro). */
    fun updateServer(ctx: Context, adminKey: String, done: (String?, String?) -> Unit) = Thread {
        val result = runCatching {
            val body = JSONObject().put("adminKey", adminKey).toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(ApiClient.toHttpUrl(Prefs.getServerUrl(ctx)) + "/admin/update").post(body).build()
            http.newCall(req).execute().use { r ->
                val j = runCatching { JSONObject(r.body!!.string()) }.getOrNull()
                when {
                    r.code == 404 -> error(ctx.getString(R.string.update_server_too_old))
                    !r.isSuccessful -> error(j?.optString("error")?.ifBlank { null } ?: "HTTP ${r.code}")
                    j?.optBoolean("updated") == true -> j.optString("to")
                    else -> null // já estava na última
                }
            }
        }
        main.post { done(result.getOrNull(), result.exceptionOrNull()?.message) }
    }.start()

    /** Baixa o APK (progress 0..100) e abre o instalador do Android. */
    fun downloadAndInstall(ctx: Context, release: Release, progress: (Int) -> Unit, done: (String?) -> Unit) {
        val url = release.apkUrl ?: return done(ctx.getString(R.string.update_no_apk))
        val app = ctx.applicationContext
        Thread {
            val error = runCatching {
                val dir = File(app.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "GreenNotify-${release.version}.apk")
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("download falhou (HTTP ${r.code})")
                    val body = r.body!!
                    val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
                    body.byteStream().use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024); var read = 0L; var last = -1
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); read += n
                                val pct = if (total > 0) (read * 100 / total).toInt() else 0
                                if (pct != last) { last = pct; main.post { progress(pct) } }
                            }
                        }
                    }
                }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
                app.startActivity(Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            }.exceptionOrNull()?.message
            main.post { done(error) }
        }.start()
    }

    // ---------- Aviso automático (no máximo 1 vez a cada 6 h, só uma requisição pequena ao GitHub) ----------

    fun shouldCheck(ctx: Context): Boolean =
        System.currentTimeMillis() - Prefs.raw(ctx).getLong("update_checked_at", 0) > TimeUnit.HOURS.toMillis(6)

    fun markChecked(ctx: Context) {
        Prefs.raw(ctx).edit().putLong("update_checked_at", System.currentTimeMillis()).apply()
    }
}
