package com.github.yutaplug.imagedescriptions

/** Separates composer drafts from queued messages so retries keep metadata and reselecting files does not. */
internal class DescriptionDrafts {
    private fun <V> boundedMap(): MutableMap<String, V> = object : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>): Boolean = size > 100
    }

    private val drafts = boundedMap<String>()
    private val requests = boundedMap<List<String?>>()

    @Synchronized operator fun get(key: String): String? = drafts[key]

    @Synchronized fun save(key: String, text: String?) {
        if (DescriptionText.hasText(text)) drafts[key] = text!! else drafts.remove(key)
    }

    @Synchronized fun remove(key: String): String? = drafts.remove(key)

    @Synchronized fun snapshot(nonce: String?, keys: List<String>): List<String?> {
        if (nonce != null) requests[nonce]?.let { return ArrayList(it) }
        val result = ArrayList<String?>(keys.size)
        var index = 0
        while (index < keys.size) {
            result.add(drafts.remove(keys[index]))
            index++
        }
        if (nonce != null) requests[nonce] = ArrayList(result)
        return result
    }

    @Synchronized fun forRequest(nonce: String): List<String?>? = requests[nonce]?.let { ArrayList(it) }

    @Synchronized fun clear() {
        drafts.clear()
        requests.clear()
    }
}
