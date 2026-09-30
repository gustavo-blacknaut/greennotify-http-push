package me.blacknaut.greennotify

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Baixa imagens de notificações (http/https), com limite de tamanho e redução para poupar memória. */
object ImageLoader {
    private const val MAX_BYTES = 6 * 1024 * 1024
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : android.util.LruCache<String, Bitmap>(8) {}

    fun loadSync(url: String, maxDim: Int): Bitmap? {
        if (!isWebLink(url)) return null
        cache.get(url)?.let { return it }
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = true
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
                out.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            bmp?.also { cache.put(url, it) }
        } catch (e: Exception) {
            null
        }
    }

    /** Baixa em segundo plano e entrega o resultado na thread principal. */
    fun load(url: String, maxDim: Int, done: (Bitmap?) -> Unit) {
        pool.execute {
            val bmp = loadSync(url, maxDim)
            main.post { done(bmp) }
        }
    }
}
