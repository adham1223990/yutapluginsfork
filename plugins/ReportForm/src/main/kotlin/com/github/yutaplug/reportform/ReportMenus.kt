package com.github.yutaplug.reportform

import com.aliucord.Http
import com.aliucord.utils.GsonUtils
import com.aliucord.utils.GsonUtils.fromJson
import com.discord.api.report.MenuAPIResponse
import com.discord.api.report.ReportNode
import org.json.JSONArray
import org.json.JSONObject

internal class ReportMenus {
    class Session {
        val answers = mutableMapOf<Int, MutableMap<String, List<String>>>()
    }

    class Extras(val raw: JSONObject, val session: Session)

    private val nodes = WeakIdentityMap<ReportNode, Extras>()

    fun get(node: ReportNode): Extras? = nodes[node]

    fun clear() = nodes.clear()

    fun fetch(): MenuAPIResponse {
        val raw = Http.Request.newDiscordRNRequest("/reporting/menu/message").use { request ->
            request.setRequestTimeout(15_000)
            request.execute().use {
                it.assertOk()
                JSONObject(it.text())
            }
        }
        require(raw.getString("name") == "message") { "Unexpected report menu" }
        val native = JSONObject(raw.toString())
        val rawNodes = raw.getJSONObject("nodes")
        val nativeNodes = native.getJSONObject("nodes")
        val keys = nativeNodes.keys()
        while (keys.hasNext()) {
            val node = nativeNodes.getJSONObject(keys.next())
            val supported = JSONArray()
            val elements = node.optJSONArray("elements") ?: JSONArray()
            for (i in 0 until elements.length()) {
                val element = elements.getJSONObject(i)
                if (element.optBoolean("skip_if_unlocalized") && !element.optBoolean("is_localized", true)) continue
                when (element.optString("type")) {
                    "checkbox", "message_preview", "breadcrumbs", "success", "block_users" -> supported.put(element)
                }
            }
            node.put("elements", supported)
            if (node.isNull("children")) node.put("children", JSONArray())
            if (node.optBoolean("is_auto_submit")) node.put("button", JSONObject().put("type", "submit"))
        }
        val menu = GsonUtils.gsonRestApi.fromJson(native.toString(), MenuAPIResponse::class.java)
        require(menu.c().containsKey(menu.d()) && menu.c().containsKey(menu.e())) { "Incomplete report menu" }
        val session = Session()
        for ((id, node) in menu.c()) nodes[node] = Extras(rawNodes.getJSONObject(id.toString()), session)
        return menu
    }
}
