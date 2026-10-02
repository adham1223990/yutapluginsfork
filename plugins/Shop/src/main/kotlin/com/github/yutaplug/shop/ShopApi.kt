package com.github.yutaplug.shop

import com.aliucord.Http
import com.discord.utilities.rest.RestAPI
import org.json.JSONArray
import org.json.JSONObject

internal object ShopApi {
    // Collectibles schema: https://docs.discord.food/resources/collectibles
    fun categories(token: String): List<JSONObject> {
        return Http.Request
            .newDiscordRequest(
                "/collectibles-categories/v2?include_bundles=true&variants_return_style=1",
                "GET",
            ).use { request ->
                request.setHeader("Authorization", token)
                request.setRequestTimeout(15_000)
                request.execute().use { response ->
                    check(response.ok()) { "Discord returned HTTP ${response.statusCode}" }
                    val categories = JSONObject(response.text()).getJSONArray("categories")
                    objects(categories).filter { products(it).isNotEmpty() }
                }
            }
    }

    fun token(): String? = RestAPI.AppHeadersProvider.INSTANCE.authToken?.takeIf(::hasText)

    fun products(category: JSONObject): List<JSONObject> = objects(category.optJSONArray("products"))

    fun items(product: JSONObject): List<JSONObject> {
        val items = objects(product.optJSONArray("items")).toMutableList()
        objects(product.optJSONArray("bundled_products")).forEach { items.addAll(items(it)) }
        objects(product.optJSONArray("variants")).forEach { items.addAll(items(it)) }
        return items.distinctBy { it.optString("sku_id").ifEmpty { it.toString() } }
    }

    fun typeName(item: JSONObject): String = when (item.optInt("type", -1)) {
        0 -> "Avatar decoration"
        1 -> "Profile effect"
        2 -> "Nameplate"
        3 -> "Profile frame"
        1000 -> "Bundle"
        2000 -> "Variants"
        else -> "Collectible"
    }

    fun itemName(item: JSONObject): String = item.optString("name").takeIf(::hasText)
        ?: item.optString("title").takeIf(::hasText)
        ?: item.optString("label").takeIf(::hasText)
        ?: typeName(item)

    fun preview(product: JSONObject): String? {
        image(product.optJSONObject("preview_assets")?.optString("fg_static"))?.let { return it }
        for (item in items(product)) itemImage(item)?.let { return it }
        return image(product.optJSONObject("preview_assets")?.optString("bg_static"))
    }

    fun itemImage(item: JSONObject): String? {
        image(item.optJSONObject("assets")?.optString("static_image_url"))?.let { return it }
        return when (item.optInt("type", -1)) {
            0 -> decorationImage(item)
            1 -> image(item.optString("staticFrameSrc")) ?: image(item.optString("thumbnailPreviewSrc"))
            2 -> nameplateImage(item)
            else -> null
        }
    }

    private fun nameplateImage(item: JSONObject): String? {
        var path = item.optString("asset")
        if (path.endsWith("/")) path = path.substring(0, path.length - 1)
        if (path.isEmpty() || !path.matches(Regex("[a-zA-Z0-9_/-]+"))) return null
        // Nameplates provide a directory, not an avatar-decoration hash.
        // https://docs.discord.food/reference#cdn-endpoints
        return "https://cdn.discordapp.com/assets/collectibles/$path/static.png"
    }

    fun summary(product: JSONObject): String {
        val summary = product.optString("summary")
        if (!summary.contains("{joinedItems}")) return summary
        val names = objects(product.optJSONArray("bundled_products"))
            .map { it.optString("name") }
            .filter(::hasText)
        val itemNames = names.ifEmpty {
            objects(product.optJSONArray("items"))
                .map { it.optString("label") }
                .filter(::hasText)
        }
        val included = if (itemNames.isEmpty()) "Collectibles" else itemNames.distinct().joinToString(", ")
        return summary.replace("{joinedItems}", included)
    }

    fun objects(array: JSONArray?): List<JSONObject> {
        val items = mutableListOf<JSONObject>()
        var index = 0
        while (index < (array?.length() ?: 0)) {
            array?.optJSONObject(index)?.let(items::add)
            index++
        }
        return items
    }

    // Discord's obfuscated Kotlin runtime cannot use the stdlib's IntIterator casts.
    private fun hasText(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) return true
        }
        return false
    }

    fun image(value: String?): String? = value?.takeIf { it.startsWith("https://") }

    fun decorationImage(item: JSONObject): String? = image(item.optJSONObject("assets")?.optString("static_image_url"))
        ?: item
            .optString("asset")
            .takeIf { it.matches(Regex("[a-zA-Z0-9_]+")) }
            ?.let { "https://cdn.discordapp.com/avatar-decoration-presets/$it.png?size=256" }
}
