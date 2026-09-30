package com.github.yutaplug.playembeds

import android.net.Uri
import android.os.SystemClock
import com.aliucord.Http
import com.aliucord.Utils
import org.json.JSONObject
import java.util.concurrent.Executors

/** Resolves missing FxEmbed video metadata without changing Discord's message model. */
internal class FxVideoResolver {
    private data class Cached(val url: String?, val expires: Long)
    private val cache = LinkedHashMap<String, Cached>()
    private val pending = HashMap<String, MutableList<(String?) -> Unit>>()
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false

    private fun key(url: String?): String? {
        if (url == null) return null
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host?.lowercase() !in
            setOf("fxtwitter.com", "www.fxtwitter.com", "fixupx.com", "www.fixupx.com")) return null
        val match = Regex("^/[^/]+/status/([0-9]+)(?:/video/([1-9][0-9]*))?/?$")
            .matchEntire(uri.path.orEmpty()) ?: return null
        return "${match.groupValues[1]}:${match.groupValues[2].ifEmpty { "1" }}"
    }

    fun cached(url: String?): String? = synchronized(cache) {
        cache[key(url)]?.takeIf { it.expires > SystemClock.elapsedRealtime() }?.url
    }

    fun resolve(url: String?, done: (String?) -> Unit) {
        val key = key(url) ?: return
        synchronized(cache) {
            if (closed) return
            cache[key]?.takeIf { it.expires > SystemClock.elapsedRealtime() }?.let {
                done(it.url)
                return
            }
            pending[key]?.let { it.add(done); return }
            pending[key] = mutableListOf(done)
            worker.execute {
                val video = runCatching {
                    Http.Request("https://api.fxtwitter.com/status/${key.substringBefore(':')}").use { request ->
                        request.setRequestTimeout(15_000)
                        request.setHeader("User-Agent", "Aliucord-PlayEmbeds")
                        request.execute().use { response ->
                            check(response.ok())
                            val data = JSONObject(response.text())
                            check(data.optInt("code") == 200)
                            val videos = data.optJSONObject("tweet")?.optJSONObject("media")?.optJSONArray("videos")
                            val media = videos?.optJSONObject(key.substringAfter(':').toInt() - 1)
                            val source = media?.optString("url")
                            source?.takeIf {
                                media.optString("type") == "video" &&
                                    Uri.parse(it).scheme == "https" && Uri.parse(it).host == "video.twimg.com" &&
                                    Uri.parse(it).path.orEmpty().endsWith(".mp4")
                            }
                        }
                    }
                }.getOrNull()
                Utils.mainThread.post {
                    val callbacks = synchronized(cache) {
                        if (closed) return@post
                        cache[key] = Cached(video, SystemClock.elapsedRealtime() + if (video == null) 60_000 else 600_000)
                        while (cache.size > 64) cache.remove(cache.keys.first())
                        pending.remove(key).orEmpty()
                    }
                    callbacks.forEach { it(video) }
                }
            }
        }
    }

    fun close() {
        synchronized(cache) {
            closed = true
            pending.clear()
            cache.clear()
            worker.shutdownNow()
        }
    }
}
