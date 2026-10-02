package com.github.yutaplug.reportform

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/** Unlike WeakHashMap, equal profile/view objects must keep independent lifetimes and values. */
internal class WeakIdentityMap<K : Any, V> {
    private val queue = ReferenceQueue<K>()
    private val values = HashMap<Key<K>, V>()

    private class Key<K : Any>(value: K, queue: ReferenceQueue<K>? = null) : WeakReference<K>(value, queue) {
        private val identity = System.identityHashCode(value)

        override fun hashCode() = identity

        override fun equals(other: Any?): Boolean = this === other ||
            (other is Key<*> && get() != null && get() === other.get())
    }

    private fun prune() {
        while (true) values.remove(queue.poll() ?: return)
    }

    @Synchronized
    operator fun get(key: K): V? {
        prune()
        return values[Key(key)]
    }

    @Synchronized
    operator fun set(key: K, value: V) {
        prune()
        values[Key(key, queue)] = value
    }

    @Synchronized
    fun clear() {
        values.clear()
        while (queue.poll() != null) {
            // Drain references without relying on Discord's stripped Unit singleton.
        }
    }
}
