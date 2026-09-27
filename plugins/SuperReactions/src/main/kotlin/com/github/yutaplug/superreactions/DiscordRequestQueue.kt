package com.github.yutaplug.superreactions

import com.aliucord.Http
import com.aliucord.utils.GsonUtils
import com.aliucord.utils.GsonUtils.fromJson
import com.aliucord.utils.IOUtils
import com.discord.utilities.rest.RestAPI
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** Serializes HTTP requests without occupying a worker while a bucket cools down. */
internal class DiscordRequestQueue(private val transport: (String, String) -> Response = ::executeDiscord) :
    Closeable {
    data class Response(
        val status: Int,
        val body: String,
        val headers: Map<String, String> = emptyMap(),
        val rateLimit: Map<*, *>? = null,
    ) {
        fun ok() = status in 200..299
    }

    private class Job(
        val route: String,
        val method: String,
        var priority: Int,
        val success: (Response) -> Unit,
        val failure: (Throwable) -> Unit,
    ) {
        val channel = route.split('/').getOrElse(2) { "" }

        // All emojis/messages in a channel can share a reaction bucket.
        val routeKey = "$channel:$method:" + if (route.contains("/reactions/")) "reactions" else "messages"
        var retryAt = 0L
        var retries = 0
    }

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "SuperReactionsRequests") }
    private val jobs = mutableListOf<Job>()
    private val buckets = mutableMapOf<String, String>()
    private val limits = mutableMapOf<String, Long>()
    private var wakeup: ScheduledFuture<*>? = null
    private var globalLimit = 0L
    private var dispatching = false
    private var closed = false

    @Synchronized fun enqueue(
        route: String,
        method: String,
        priority: Int,
        success: (Response) -> Unit,
        failure: (Throwable) -> Unit,
    ) {
        if (closed) return
        jobs.add(Job(route, method, priority, success, failure))
        schedule()
    }

    @Synchronized fun prioritize(route: String, method: String) {
        jobs.filter { it.route == route && it.method == method }.forEach { it.priority = ACTION }
        schedule()
    }

    private fun availableAt(job: Job) = maxOf(
        globalLimit,
        job.retryAt,
        limits[job.routeKey] ?: 0L,
        limits[buckets[job.routeKey] ?: job.routeKey] ?: 0L,
    )

    private fun schedule() {
        if (closed || dispatching || jobs.isEmpty()) return
        val earliest = jobs.minOf(::availableAt)
        wakeup?.cancel(false)
        wakeup = worker.schedule(::dispatch, maxOf(0L, earliest - now()), TimeUnit.MILLISECONDS)
    }

    private fun dispatch() {
        val job = synchronized(this) {
            wakeup = null
            if (closed) return
            val time = now()
            val next = jobs.filter { availableAt(it) <= time }.minByOrNull { it.priority }
            if (next == null) {
                schedule()
                return
            }
            jobs.remove(next)
            dispatching = true
            next
        }
        try {
            val response = transport(job.route, job.method)
            synchronized(this) {
                if (closed) return
                recordLimits(job, response)
                if (response.status == 429 && job.retries++ < 2) {
                    jobs.add(job)
                    return
                }
            }
            if (response.status ==
                429
            ) {
                job.failure(IllegalStateException("Discord is still rate limited; try again shortly"))
            } else {
                job.success(response)
            }
        } catch (error: Throwable) {
            synchronized(this) { if (closed) return }
            job.failure(error)
        } finally {
            synchronized(this) {
                dispatching = false
                schedule()
            }
        }
    }

    private fun recordLimits(job: Job, response: Response) {
        response.headers["X-RateLimit-Bucket"]?.let { buckets[job.routeKey] = "${job.channel}:$it" }
        val bucketKey = buckets[job.routeKey] ?: job.routeKey
        if (response.headers["X-RateLimit-Remaining"] == "0") {
            val reset = delay(response.headers["X-RateLimit-Reset-After"])
            if (reset > 0) limit(bucketKey, now() + reset)
        }
        if (response.status != 429) return
        var retry = delay(response.headers["Retry-After"])
        var global = response.headers["X-RateLimit-Global"].equals("true", ignoreCase = true) ||
            response.headers["X-RateLimit-Scope"] == "global"
        response.rateLimit?.let {
            retry = maxOf(retry, delay(it["retry_after"]))
            global = global || it["global"] == true
        }
        if (retry <= 0) retry = 5_000L
        job.retryAt = now() + retry
        limit(bucketKey, job.retryAt)
        if (global) globalLimit = maxOf(globalLimit, job.retryAt)
    }

    private fun limit(key: String, until: Long) {
        limits[key] = maxOf(limits[key] ?: 0L, until)
    }

    @Synchronized override fun close() {
        closed = true
        jobs.clear()
        wakeup?.cancel(false)
        worker.shutdownNow()
    }

    companion object {
        const val ACTION = 0
        const val METADATA = 1
        const val USERS = 2

        fun delay(seconds: Any?): Long {
            val value = seconds?.toString()?.toDoubleOrNull() ?: return 0L
            if (!value.isFinite() || value < 0) return 0L
            // Never shorten Discord's cooldown (some limits exceed a minute).
            return minOf(Long.MAX_VALUE / 4.0, ceil(value * 1000.0) + 100.0).toLong()
        }

        private fun now() = System.nanoTime() / 1_000_000L

        private fun executeDiscord(route: String, method: String): Response =
            Http.Request.newDiscordRNRequest(route, method).use { request ->
                request.setRequestTimeout(15_000)
                RestAPI.AppHeadersProvider.INSTANCE.getFingerprint()?.let { request.setHeader("X-Fingerprint", it) }
                val response = request.execute()
                // Response.text() asserts success; read error bodies directly for retry_after.
                val body = when {
                    response.statusCode == 204 -> ""
                    response.ok() -> response.text()
                    else -> request.conn.errorStream?.use { IOUtils.readAsText(it) } ?: ""
                }
                val headers = listOf(
                    "X-RateLimit-Bucket",
                    "X-RateLimit-Remaining",
                    "X-RateLimit-Reset-After",
                    "X-RateLimit-Global",
                    "X-RateLimit-Scope",
                    "Retry-After",
                ).mapNotNull { name -> request.conn.getHeaderField(name)?.let { name to it } }
                    .toMap()
                val rateLimit = if (response.statusCode == 429) {
                    try {
                        GsonUtils.gson.fromJson(body, Map::class.java)
                    } catch (_: RuntimeException) {
                        null
                    }
                } else {
                    null
                }
                Response(response.statusCode, body, headers, rateLimit)
            }
    }
}
