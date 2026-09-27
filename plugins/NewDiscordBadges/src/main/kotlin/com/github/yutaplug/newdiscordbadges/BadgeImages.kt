package com.github.yutaplug.newdiscordbadges

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import android.widget.ImageView
import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** All view/cache state is confined to the main thread; network work never holds a view alive. */
internal class BadgeImages(private val logger: Logger) {
    private val cache = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val bindings = WeakHashMap<ImageView, Any>()
    private val pending = mutableMapOf<String, MutableList<(Bitmap?) -> Unit>>()
    private val failedUntil = mutableMapOf<String, Long>()
    private var generation = 0

    fun unbind(view: ImageView) {
        bindings.remove(view)
    }

    fun bind(view: ImageView, urls: List<String>) {
        val token = Any()
        bindings[view] = token
        view.setImageDrawable(null)
        load(WeakReference(view), token, urls, 0)
    }

    private fun load(view: WeakReference<ImageView>, token: Any, urls: List<String>, index: Int) {
        val target = view.get() ?: return
        if (bindings[target] !== token || index >= urls.size) return
        val url = urls[index]
        cache.get(url)?.let {
            target.setImageBitmap(it)
            return
        }
        if ((failedUntil[url] ?: 0L) > SystemClock.elapsedRealtime()) {
            load(view, token, urls, index + 1)
            return
        }
        val listener: (Bitmap?) -> Unit = { bitmap ->
            val current = view.get()
            if (current != null && bindings[current] === token) {
                if (bitmap != null) current.setImageBitmap(bitmap)
                else load(view, token, urls, index + 1)
            }
        }
        pending[url]?.let {
            it.add(listener)
            return
        }
        pending[url] = mutableListOf(listener)
        val requestGeneration = generation
        Utils.threadPool.execute {
            val bitmap = try {
                Http.Request(url).setRequestTimeout(5000).use { request ->
                    val response = request.execute()
                    check(response.ok()) { "Badge image HTTP ${response.statusCode}" }
                    response.stream().use { BitmapFactory.decodeStream(it) }
                        ?: error("Badge image could not be decoded")
                }
            } catch (error: Exception) {
                logger.warn("Could not load badge image $url", error)
                null
            }
            Utils.mainThread.post {
                if (generation == requestGeneration) {
                    if (bitmap != null) cache.put(url, bitmap)
                    else {
                        if (failedUntil.size >= 128) failedUntil.clear()
                        failedUntil[url] = SystemClock.elapsedRealtime() + 30_000L
                    }
                    pending.remove(url)?.forEach { it(bitmap) }
                }
            }
        }
    }

    fun clear() {
        generation++
        bindings.clear()
        pending.clear()
        failedUntil.clear()
        cache.evictAll()
    }
}
