package com.github.yutaplug.imagedescriptions

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/** Stores metadata without retaining models or conflating equal attachment instances. */
internal class WeakIdentityMap<K : Any, V> {
    private class Key<T : Any>(value: T, queue: ReferenceQueue<T>? = null) : WeakReference<T>(value, queue) {
        private val hash = System.identityHashCode(value)

        override fun hashCode() = hash

        override fun equals(other: Any?): Boolean = this === other ||
            (get()?.let { other is Key<*> && it === other.get() } ?: false)
    }

    private val cleared = ReferenceQueue<K>()
    private val values = mutableMapOf<Key<K>, V>()

    @Synchronized operator fun get(key: K): V? {
        reap()
        return values[Key(key)]
    }

    @Synchronized fun put(key: K, value: V) {
        reap()
        values[Key(key, cleared)] = value
    }

    @Synchronized fun clear() {
        values.clear()
        reap()
    }

    private fun reap() {
        while (true) values.remove(cleared.poll() ?: return)
    }
}
