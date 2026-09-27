package com.github.yutaplug.superreactions

/** One authoritative message snapshot, independent of paginated reaction users. */
internal class ReactionMetadata {
    val bursts = mutableMapOf<String, Int>()
    val normal = mutableMapOf<String, Int>()
    val colors = mutableMapOf<String, Any>()
    val owned = mutableSetOf<String>()

    companion object {
        fun parse(message: Map<*, *>?): ReactionMetadata {
            requireNotNull(message) { "Invalid message metadata" }
            val result = ReactionMetadata()
            val reactions = message["reactions"] ?: return result
            require(reactions is List<*>) { "Invalid reaction metadata" }
            for (raw in reactions) {
                val reaction = raw as? Map<*, *> ?: continue
                val emoji = reaction["emoji"] as? Map<*, *> ?: continue
                val key = (emoji["id"] ?: emoji["name"])?.toString() ?: continue
                val details = reaction["count_details"] as? Map<*, *>
                var bursts = maxOf(count(details?.get("burst")), count(reaction["burst_count"]))
                if (reaction["me_burst"] == true) {
                    bursts = maxOf(1, bursts)
                    result.owned.add(key)
                }
                val normal = if (details?.containsKey("normal") == true) {
                    count(details["normal"])
                } else {
                    maxOf(0, count(reaction["count"]) - bursts)
                }
                put(result.normal, key, normal)
                if (bursts > 0) put(result.bursts, key, bursts)
                reaction["burst_colors"]?.let { result.colors[key] = it }
            }
            return result
        }

        fun eventType(event: Map<*, *>): Boolean? = (event["burst"] as? Boolean)
            ?: (event["type"] as? Number)?.let { it.toInt() == 1 }

        private fun count(value: Any?) = (value as? Number)?.toInt()?.coerceAtLeast(0) ?: 0

        private fun put(counts: MutableMap<String, Int>, key: String, value: Int) {
            counts[key] = value
            counts[key.replace("\uFE0F", "")] = value
        }
    }
}
