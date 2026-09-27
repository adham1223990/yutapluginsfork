package com.github.yutaplug.profileeffects

import com.aliucord.Http
import com.aliucord.utils.GsonUtils
import com.aliucord.utils.GsonUtils.fromJson
import com.aliucord.utils.IOUtils
import com.discord.utilities.rest.RestAPI
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.ceil
import b.i.d.g as JsonArray
import b.i.d.k as JsonPrimitive

/** Owns its workers and caches; closing it prevents callbacks from an earlier plugin session. */
internal class ProfileRepository(private val log: (String, Throwable) -> Unit) {
    private val workers = Executors.newFixedThreadPool(2) { Thread(it, "ProfileEffectsRequests") }
    private val profiles = RequestCache<ProfileKey, Profile>(128, ::now)
    private val products = RequestCache<ProductKey, Product>(128, ::now)

    @Volatile private var closed = false

    @Volatile private var retryAt = 0L

    fun load(key: ProfileKey, callback: (LoadResult<Profile>) -> Unit) {
        profiles.request(key, { finish ->
            execute(key.route(), { failure -> finish(failure) }) { body ->
                val skus = ProfileData.products(body)
                val keys = listOfNotNull(skus.effect, skus.frame)
                if (keys.isEmpty()) {
                    finish(LoadResult(Profile()))
                    return@execute
                }
                val lock = Any()
                var remaining = keys.size
                var effect: Product.Effect? = null
                var frame: Product.Frame? = null
                var failed = false
                var ttl = CACHE_TTL
                for (productKey in keys) {
                    products.request(productKey, { complete ->
                        execute("/collectibles-products/${productKey.sku}", complete) { productBody ->
                            complete(LoadResult(ProfileData.product(productKey, productBody)))
                        }
                    }) { result ->
                        synchronized(lock) {
                            when (val product = result.value) {
                                is Product.Effect -> {
                                    effect = product
                                }

                                is Product.Frame -> {
                                    frame = product
                                }

                                null -> {}
                            }
                            failed = failed || result.failed
                            ttl = minOf(ttl, result.ttl)
                            val profile = Profile(effect, frame)
                            if (--remaining == 0) {
                                finish(LoadResult(profile, failed, ttl))
                            } else if (effect != null || frame != null) {
                                // Show whichever product arrives first while the other request finishes.
                                profiles.publish(key, profile)
                            }
                        }
                    }
                }
            }
        }, callback)
    }

    private fun <T> execute(route: String, failure: (LoadResult<T>) -> Unit, success: (Map<*, *>) -> Unit) {
        try {
            workers.execute {
                if (closed) return@execute
                try {
                    val body = requestJson(route)
                    if (!closed) success(body)
                } catch (error: Exception) {
                    if (!closed) {
                        log("Could not load $route", error)
                        failure(LoadResult(null, true, (error as? RateLimit)?.delay ?: FAILURE_TTL))
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            if (!closed) failure(LoadResult(null, true, FAILURE_TTL))
        }
    }

    fun close() {
        closed = true
        profiles.close()
        products.close()
        workers.shutdownNow()
    }

    private class RateLimit(val delay: Long) : Exception("Discord rate limited profile collectibles")

    private fun requestJson(route: String): Map<*, *> {
        val cooldown = retryAt - now()
        if (cooldown > 0) throw RateLimit(cooldown)
        return Http.Request.newDiscordRNRequest(route).use { request ->
            request.setRequestTimeout(15_000)
            RestAPI.AppHeadersProvider.INSTANCE.getFingerprint()?.let { request.setHeader("X-Fingerprint", it) }
            request.execute().use { response ->
                if (response.statusCode == 429) {
                    val errorBody = request.conn.errorStream?.use { IOUtils.readAsText(it) }
                    val body = try {
                        errorBody?.let { jsonValue(GsonUtils.gson.fromJson(it, JsonElement::class.java)) as? Map<*, *> }
                    } catch (_: Exception) {
                        null
                    }
                    val seconds = maxOf(
                        request.conn.getHeaderField("Retry-After")?.toDoubleOrNull() ?: 0.0,
                        body?.get("retry_after")?.toString()?.toDoubleOrNull() ?: 0.0,
                    )
                    val delay = if (seconds.isFinite() && seconds > 0) {
                        ceil(seconds.coerceAtMost(86_400.0) * 1000).toLong() + 100
                    } else {
                        FAILURE_TTL
                    }
                    synchronized(this) { retryAt = maxOf(retryAt, now() + delay) }
                    throw RateLimit(delay)
                }
                check(response.ok()) { "HTTP ${response.statusCode}" }
                val root = GsonUtils.gson.fromJson(response.text(), JsonElement::class.java)
                jsonValue(root) as? Map<*, *> ?: error("Invalid profile collectibles response")
            }
        }
    }

    private fun jsonValue(value: JsonElement?): Any? = when (value) {
        // Retain lazy JSON numbers instead of passing snowflakes through Double.
        is JsonPrimitive -> {
            value.a
        }

        is JsonObject -> {
            val map = LinkedHashMap<String, Any?>()
            for (entry in value.j()) map[entry.key] = jsonValue(entry.value)
            map
        }

        is JsonArray -> {
            val list = ArrayList<Any?>()
            for (element in value) list.add(jsonValue(element))
            list
        }

        else -> {
            null
        }
    }

    private fun now() = System.nanoTime() / 1_000_000L
}
