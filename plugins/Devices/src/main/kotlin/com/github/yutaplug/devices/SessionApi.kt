package com.github.yutaplug.devices

import com.aliucord.Http
import com.discord.stores.StoreAuthentication
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import org.json.JSONArray
import org.json.JSONObject

internal data class DeviceSession(
    val idHash: String,
    val name: String,
    val location: String?,
    val lastUsed: String?,
    val current: Boolean,
)

internal class SessionFailure(val status: Int, val body: JSONObject) : Exception(
    body.optString("message").takeIf(::hasText) ?: "HTTP $status",
)

internal class SessionApi(private val expectedToken: String) {
    fun list(): List<DeviceSession> {
        val body = request("/auth/sessions", "GET")
        val items = body.optJSONArray("user_sessions") ?: body.optJSONArray("sessions")
            ?: error("Discord returned an unrecognized Devices response")
        val currentHash = body.optString("current_session_id_hash")
            .ifEmpty { body.optString("auth_session_id_hash") }
        val sessions = mutableListOf<DeviceSession>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val hash = item.optString("id_hash").ifEmpty {
                item.optString("session_id_hash").ifEmpty { item.optString("id") }
            }
            if (!hasText(hash)) continue
            val info = item.optJSONObject("client_info") ?: JSONObject()
            val os = info.optString("os").takeIf(::hasText)
            val platform = info.optString("platform").takeIf(::hasText)
            val browser = info.optString("browser").takeIf(::hasText)
            val device = info.optString("device").takeIf(::hasText)
            val name = listOfNotNull(device, browser, os, platform).distinct()
                .take(2).joinToString(" · ").ifEmpty { "Unknown device" }
            val location = info.optString("location").takeIf(::hasText)
                ?: item.optString("location").takeIf(::hasText)
            val lastUsed = item.optString("approx_last_used_time").takeIf(::hasText)
                ?: item.optString("last_used").takeIf(::hasText)
            sessions.add(DeviceSession(hash, name, location, lastUsed,
                item.optBoolean("is_current") || item.optBoolean("current") ||
                    (currentHash.isNotEmpty() && hash == currentHash)))
        }
        if (items.length() > 0 && sessions.isEmpty()) error("Discord returned sessions in an unrecognized format")
        return sessions
    }

    fun logout(idHashes: List<String>, password: String? = null, mfaToken: String? = null,
               legacyCode: String? = null) {
        val body = JSONObject().put("session_id_hashes", JSONArray(idHashes))
        if (!password.isNullOrEmpty()) body.put("password", password)
        if (!legacyCode.isNullOrEmpty()) body.put("code", legacyCode)
        request("/auth/sessions/logout", "POST", body, mfaToken)
    }

    fun finishMfa(ticket: String, type: String, code: String): String {
        val body = JSONObject().put("ticket", ticket).put("mfa_type", type).put("data", code)
        return request("/mfa/finish", "POST", body).optString("token")
            .takeIf(::hasText) ?: error("Discord did not return an MFA authorization token")
    }

    private fun request(route: String, method: String, body: JSONObject? = null,
                        mfaToken: String? = null): JSONObject {
        check(token() == expectedToken) { "Discord account changed. Reopen Devices." }
        return Http.Request.newDiscordRequest(route, method).use { request ->
            request.setRequestTimeout(15_000)
            request.setHeader("Authorization", expectedToken)
            request.setHeader("Content-Type", "application/json")
            if (mfaToken != null) request.setHeader("X-Discord-MFA-Authorization", mfaToken)
            val response = if (body == null) request.execute() else request.executeWithBody(body.toString())
            response.use {
                if (!it.ok()) {
                    val error = runCatching {
                        request.conn.errorStream?.bufferedReader()?.use { reader ->
                            JSONObject(reader.readText().take(16_384))
                        }
                    }.getOrNull() ?: JSONObject()
                    throw SessionFailure(it.statusCode, error)
                }
                val text = it.text()
                if (!hasText(text)) JSONObject() else JSONObject(text)
            }
        }
    }

    companion object {
        fun token(): String? =
            StoreAuthentication.`access$getAuthState$p`(StoreStream.getAuthentication())?.token
                ?.takeIf(::hasText)
                ?: RestAPI.AppHeadersProvider.INSTANCE.authToken?.takeIf(::hasText)
    }
}

internal fun hasText(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val character = value[index++]
        if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) return true
    }
    return false
}
