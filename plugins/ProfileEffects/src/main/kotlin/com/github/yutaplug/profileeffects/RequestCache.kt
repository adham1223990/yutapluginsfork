package com.github.yutaplug.profileeffects

internal const val CACHE_TTL = 10 * 60 * 1000L
internal const val FAILURE_TTL = 15_000L

internal data class LoadResult<T>(
    val value: T?,
    val failed: Boolean = false,
    val ttl: Long = CACHE_TTL,
    val complete: Boolean = true,
)

/** Deduplicates requests and delivers their result to every subscriber, including cached failures. */
internal class RequestCache<K, V>(private val capacity: Int, private val now: () -> Long) {
    private data class Entry<V>(val result: LoadResult<V>, val expires: Long)

    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private val pending = HashMap<K, MutableList<(LoadResult<V>) -> Unit>>()
    private var closed = false

    fun request(key: K, loader: ((LoadResult<V>) -> Unit) -> Unit, callback: (LoadResult<V>) -> Unit) {
        val waiters: MutableList<(LoadResult<V>) -> Unit>
        val cached: LoadResult<V>?
        synchronized(this) {
            if (closed) return
            val entry = entries[key]
            val time = now()
            cached = entry?.takeIf { time < it.expires }?.let { it.result.copy(ttl = it.expires - time) }
            if (cached == null) {
                val existing = pending[key]
                if (existing != null) {
                    existing.add(callback)
                    return
                }
                waiters = arrayListOf(callback)
                pending[key] = waiters
            } else {
                waiters = ArrayList()
            }
        }
        if (cached != null) {
            callback(cached)
            return
        }
        loader { fetched ->
            val result: LoadResult<V>
            val subscribers: List<(LoadResult<V>) -> Unit>
            synchronized(this) {
                // Closing or replacing a request invalidates its completion token.
                if (closed || pending[key] !== waiters) return@loader
                pending.remove(key)
                val previous = entries[key]?.result?.value
                result = if (fetched.failed && fetched.value == null) fetched.copy(value = previous) else fetched
                entries[key] = Entry(result, now() + result.ttl)
                while (entries.size > capacity) {
                    val iterator = entries.entries.iterator()
                    iterator.next()
                    iterator.remove()
                }
                subscribers = ArrayList(waiters)
            }
            for (subscriber in subscribers) subscriber(result)
        }
    }

    fun publish(key: K, value: V) {
        val subscribers = synchronized(this) {
            if (closed) return
            pending[key]?.let { ArrayList(it) } ?: return
        }
        for (subscriber in subscribers) subscriber(LoadResult(value, complete = false))
    }

    @Synchronized fun close() {
        closed = true
        entries.clear()
        pending.clear()
    }
}
