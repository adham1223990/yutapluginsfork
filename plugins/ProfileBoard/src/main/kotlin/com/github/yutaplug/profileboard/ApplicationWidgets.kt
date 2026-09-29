package com.github.yutaplug.profileboard

import org.json.JSONArray
import org.json.JSONObject

internal data class WidgetStat(val value: String, val label: String)

internal data class ApplicationWidget(
    val name: String,
    val icon: String?,
    val title: String?,
    val image: String?,
    val subtitles: List<String>,
    val stats: List<WidgetStat>,
)

/** Resolves the same public identity and widget-config fields used by Discord's Board. */
internal object ApplicationWidgets {
    fun load(
        userId: Long,
        widgets: List<BoardWidget>,
        request: (String, String) -> Any,
        token: String,
    ): Map<String, ApplicationWidget> {
        val ids = LinkedHashSet<String>()
        for (widget in widgets) widget.applicationId?.let(ids::add)
        if (ids.isEmpty()) return emptyMap()

        val identities = HashMap<String, JSONObject>()
        val identityResponse = runCatching {
            request("/users/$userId/application-identities?with_profiles=true", token)
        }.getOrNull()
        val identityArray = (identityResponse as? JSONObject)?.optJSONArray("identities")
            ?: identityResponse as? JSONArray
        if (identityArray != null) {
            var index = 0
            while (index < identityArray.length()) {
                val identity = identityArray.optJSONObject(index)
                val id = identity?.text("application_id")
                if (id != null) identities[id] = identity
                index++
            }
        }

        val publicQuery = StringBuilder("/applications/public?")
        var first = true
        for (id in ids) {
            if (!first) publicQuery.append('&')
            publicQuery.append("application_ids=").append(id)
            first = false
        }
        val publicResponse = runCatching { request(publicQuery.toString(), token) }.getOrNull()
        val publicArray = when (publicResponse) {
            is JSONArray -> publicResponse
            is JSONObject -> publicResponse.optJSONArray("applications")
            else -> null
        }
        val applications = HashMap<String, JSONObject>()
        if (publicArray != null) {
            var index = 0
            while (index < publicArray.length()) {
                val application = publicArray.optJSONObject(index)
                val id = application?.text("id")
                if (id != null) applications[id] = application
                index++
            }
        }

        val result = LinkedHashMap<String, ApplicationWidget>()
        for (id in ids) {
            val response = runCatching { request("/applications/$id/widget-configs", token) }.getOrNull()
            val configs = when (response) {
                is JSONArray -> response
                is JSONObject -> response.optJSONArray("configs")
                else -> null
            }
            val config = selectConfig(configs)
            val application = applications[id]
            val identity = identities[id]
            result[id] = parse(id, application, identity, config)
        }
        return result
    }

    private fun selectConfig(configs: JSONArray?): JSONObject? {
        if (configs == null) return null
        var first: JSONObject? = null
        var withTop: JSONObject? = null
        var index = 0
        while (index < configs.length()) {
            val config = configs.optJSONObject(index)
            if (config != null) {
                if (first == null) first = config
                if (config.optJSONObject("surfaces")?.optJSONObject("widget_top") != null) {
                    if (withTop == null) withTop = config
                    if (config.optString("status") == "published") return config
                }
            }
            index++
        }
        return withTop ?: first
    }

    private fun parse(id: String, application: JSONObject?, identity: JSONObject?, config: JSONObject?): ApplicationWidget {
        val app = application ?: config?.optJSONObject("application")
        val name = config?.text("display_name") ?: app?.text("name") ?: "Game Stats"
        val iconHash = app?.text("icon") ?: app?.text("icon_hash")
        val icon = iconHash?.let { "https://cdn.discordapp.com/app-icons/$id/$it.png?size=64" }
        val profile = identity?.optJSONObject("profile")
            ?: identity?.optJSONArray("profiles")?.optJSONObject(0)
        val values = profileValues(profile)
        val assets = config?.optJSONArray("resolved_assets")
        val top = config?.optJSONObject("surfaces")?.optJSONObject("widget_top")?.optJSONObject("components")
        val bottom = config?.optJSONObject("surfaces")?.optJSONObject("widget_bottom")?.optJSONObject("components")

        val title = componentText(top, "title", "text", values, assets, id)
            ?: profile?.text("username")
        val image = componentImage(top, "hero_image", values, assets, id)
            ?: componentImage(top, "contained_image", values, assets, id)
            ?: mediaUrl(values["featured_played_character_image"])
        val subtitles = ArrayList<String>()
        var subtitleIndex = 1
        while (subtitleIndex <= 3) {
            componentText(top, "subtitle_$subtitleIndex", "text", values, assets, id)?.let(subtitles::add)
            subtitleIndex++
        }
        val stats = ArrayList<WidgetStat>()
        var statIndex = 1
        while (statIndex <= 20) {
            val component = bottom?.optJSONObject("stat_$statIndex")
            if (component != null) {
                val fields = component.optJSONObject("fields")
                val value = resolve(fields?.optJSONObject("value"), values, assets, id)?.text
                val label = resolve(fields?.optJSONObject("label"), values, assets, id)?.text
                if (value != null && label != null) stats.add(WidgetStat(value, label))
            }
            statIndex++
        }
        return ApplicationWidget(name, icon, title, image, subtitles, stats)
    }

    private fun componentText(
        components: JSONObject?, name: String, field: String,
        values: Map<String, Any>, assets: JSONArray?, applicationId: String,
    ): String? = resolve(components?.optJSONObject(name)?.optJSONObject("fields")?.optJSONObject(field),
        values, assets, applicationId)?.text

    private fun componentImage(
        components: JSONObject?, name: String,
        values: Map<String, Any>, assets: JSONArray?, applicationId: String,
    ): String? = resolve(components?.optJSONObject(name)?.optJSONObject("fields")?.optJSONObject("image"),
        values, assets, applicationId)?.image

    private data class Resolved(val text: String? = null, val image: String? = null)

    private fun resolve(
        field: JSONObject?, values: Map<String, Any>, assets: JSONArray?, applicationId: String,
        depth: Int = 0,
    ): Resolved? {
        if (field == null || depth > 3) return null
        val key = field.text("value")
        val value = when (field.optString("value_type")) {
            "custom_string" -> key?.let { Resolved(text = it) }
            "data" -> when (val data = values[key]) {
                is JSONObject -> mediaUrl(data)?.let { Resolved(image = it) }
                is Number -> Resolved(text = data.toString())
                is String -> Resolved(text = data)
                else -> null
            }
            "application_asset" -> findAsset(assets, key)?.text("asset_id")?.let { assetId ->
                Resolved(image = "https://cdn.discordapp.com/app-assets/$applicationId/$assetId.webp?size=512")
            }
            else -> null
        }
        return value ?: resolve(field.optJSONObject("fallback"), values, assets, applicationId, depth + 1)
    }

    private fun findAsset(assets: JSONArray?, key: String?): JSONObject? {
        if (assets == null || key == null) return null
        var index = 0
        while (index < assets.length()) {
            val asset = assets.optJSONObject(index)
            if (asset?.text("key") == key) return asset
            index++
        }
        return null
    }

    private fun profileValues(profile: JSONObject?): Map<String, Any> {
        val values = HashMap<String, Any>()
        if (profile == null) return values
        profile.text("username")?.let { values["username"] = it }
        val primary = profile.optJSONObject("data")?.optJSONObject("primary")
        if (primary != null) {
            val keys = primary.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = primary.opt(key)
                if (value is String || value is Number || value is JSONObject) values[key] = value
            }
        }
        val dynamic = profile.optJSONObject("data")?.optJSONArray("dynamic")
        if (dynamic != null) {
            var index = 0
            while (index < dynamic.length()) {
                val field = dynamic.optJSONObject(index)
                val key = field?.text("name")
                val value = field?.opt("value")
                if (key != null && (value is String || value is Number || value is JSONObject)) values[key] = value
                index++
            }
        }
        return values
    }

    private fun mediaUrl(value: Any?): String? {
        val media = value as? JSONObject ?: return null
        val proxy = media.text("proxy_url")
        if (proxy?.startsWith("https://") == true) return proxy
        val original = media.text("url")
        return original?.takeIf { it.startsWith("https://") }
    }

    private fun JSONObject.text(key: String): String? {
        if (isNull(key)) return null
        val value = optString(key)
        if (value == "null") return null
        var index = 0
        while (index < value.length) {
            if (!Character.isWhitespace(value[index])) return value
            index++
        }
        return null
    }
}
