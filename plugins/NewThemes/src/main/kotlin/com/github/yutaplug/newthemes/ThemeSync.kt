package com.github.yutaplug.newthemes

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.aliucord.Http
import com.aliucord.Utils
import com.discord.stores.StoreAuthentication
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Serializes reads/writes, rejects old accounts and protects pending choices. */
internal class ThemeSync(
    private val context: Context,
    private val apply: (ThemeChoice) -> Unit,
    private val log: (String, Throwable) -> Unit,
) {
    private class Session(val token: String) {
        var pending: ThemeChoice? = null
        var revision = 0L
        var nextRequest = 0L
        var initialized = false
    }

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "NewThemesSync") }
    private var session: Session? = null
    @Volatile private var closed = false

    fun start() {
        worker.scheduleWithFixedDelay({ runSync() }, 0, 60, TimeUnit.SECONDS)
    }

    fun refresh() {
        if (!closed) worker.execute { runSync() }
    }

    fun choose(theme: ThemeChoice) {
        val userId = StoreStream.getUsers().me.id
        val auth = token()
        val enabled = StoreStream.getUserSettingsSystem().isThemeSyncEnabled
        if (auth != null) synchronized(lock) {
            val current = session?.takeIf { it.token == auth } ?: Session(auth).also { session = it }
            current.initialized = true
            current.revision++
            current.pending = if (enabled) theme else null
        }
        // SharedPreferences initialization and writes stay on the worker.
        worker.execute {
            if (!closed && StoreStream.getUsers().me.id == userId) {
                context.getSharedPreferences("NewThemes", Context.MODE_PRIVATE).edit()
                    .putInt(userId.toString(), theme.wire).apply()
            }
        }
        if (!enabled) return
        if (auth == null) {
            Utils.showToast("Theme changed locally; account authentication is unavailable.", true)
            return
        }
        worker.schedule({ runSync() }, 350, TimeUnit.MILLISECONDS)
    }

    fun syncChanged() {
        synchronized(lock) {
            session?.let { it.pending = null; it.revision++; it.nextRequest = 0 }
        }
        refresh()
    }

    private fun runSync() {
        if (closed) return
        val token = token() ?: return
        val current = synchronized(lock) {
            session?.takeIf { it.token == token } ?: Session(token).also { session = it }
        }
        if (!current.initialized) {
            current.initialized = true
            if (!StoreStream.getUserSettingsSystem().isThemeSyncEnabled) {
                val userId = StoreStream.getUsers().me.id
                val revision = synchronized(lock) { current.revision }
                val prefs = context.getSharedPreferences("NewThemes", Context.MODE_PRIVATE)
                val saved = if (prefs.contains(userId.toString())) ThemeChoice.fromWire(prefs.getInt(userId.toString(), 1)) else null
                if (saved != null) main.post {
                    if (active(current) && StoreStream.getUsers().me.id == userId &&
                        synchronized(lock) { current.revision == revision } &&
                        !StoreStream.getUserSettingsSystem().isThemeSyncEnabled) apply(saved)
                }
            }
        }
        if (!StoreStream.getUserSettingsSystem().isThemeSyncEnabled) return
        val selection = synchronized(lock) {
            val delay = current.nextRequest - System.currentTimeMillis()
            if (delay > 0) {
                if (current.pending != null) worker.schedule({ runSync() }, delay, TimeUnit.MILLISECONDS)
                return
            }
            current.pending to current.revision
        }
        try {
            var settings = decode(request(current))
            val desired = selection.first
            if (desired != null) {
                var attempts = 0
                while (true) {
                    if (!active(current) || !StoreStream.getUserSettingsSystem().isThemeSyncEnabled) return
                    val payload = JSONObject()
                        .put("settings", Base64.encodeToString(ThemeProto.patch(settings, desired.wire), Base64.NO_WRAP))
                        .put("required_data_version", ThemeProto.dataVersion(settings))
                    val response = request(current, payload)
                    if (!response.optBoolean("out_of_date")) break
                    check(attempts++ < 2) { "Appearance changed concurrently; retrying later" }
                    settings = decode(response)
                }
                settings = decode(request(current))
                check(ThemeProto.theme(settings) == desired.wire) { "Discord did not confirm the selected theme" }
            }
            val actual = ThemeChoice.fromWire(ThemeProto.theme(settings)) ?: return
            main.post {
                synchronized(lock) {
                    if (!active(current) || current.revision != selection.second ||
                        !StoreStream.getUserSettingsSystem().isThemeSyncEnabled) return@post
                    current.pending = null
                    apply(actual)
                    val userId = StoreStream.getUsers().me.id
                    worker.execute {
                        if (active(current) && synchronized(lock) { current.revision == selection.second }) {
                            context.getSharedPreferences("NewThemes", Context.MODE_PRIVATE).edit()
                                .putInt(userId.toString(), actual.wire).apply()
                        }
                    }
                }
            }
            synchronized(lock) {
                current.nextRequest = System.currentTimeMillis() + 5000
                if (current.revision != selection.second && current.pending != null) {
                    worker.schedule({ runSync() }, 5000, TimeUnit.MILLISECONDS)
                }
            }
        } catch (error: Throwable) {
            if (!active(current)) return
            log("Theme sync failed", error)
            synchronized(lock) { current.nextRequest = maxOf(current.nextRequest, System.currentTimeMillis() + 60_000) }
            if (selection.first != null) Utils.showToast("Theme sync failed; will retry: ${error.message?.take(120)}", true)
        }
    }

    private fun request(current: Session, payload: JSONObject? = null): JSONObject {
        check(active(current)) { "Account changed" }
        val method = if (payload == null) "GET" else "PATCH"
        val request = try {
            Http.Request.newDiscordRNRequest("/users/@me/settings-proto/1", method)
        } catch (_: LinkageError) {
            Http.Request.newDiscordRequest("/users/@me/settings-proto/1", method)
        }
        return request.use {
            it.setRequestTimeout(15_000)
            it.setHeader("Authorization", current.token)
            it.setHeader("Content-Type", "application/json")
            val response = if (payload == null) it.execute() else it.executeWithBody(payload.toString())
            response.use { response ->
                if (!response.ok()) {
                    if (response.statusCode == 429) {
                        val body = runCatching { it.conn.errorStream.bufferedReader().use { reader -> JSONObject(reader.readText()) } }.getOrNull()
                        val delay = body?.optDouble("retry_after", 60.0) ?: 60.0
                        synchronized(lock) {
                            current.nextRequest = System.currentTimeMillis() +
                                (delay.coerceIn(60.0, 86400.0) * 1000).toLong()
                        }
                    }
                    error("HTTP ${response.statusCode}")
                }
                JSONObject(response.text())
            }
        }
    }

    private fun decode(response: JSONObject): ByteArray = Base64.decode(response.getString("settings"), Base64.DEFAULT)
    private fun token(): String? = StoreAuthentication.`access$getAuthState$p`(StoreStream.getAuthentication())?.token
        ?: RestAPI.AppHeadersProvider.INSTANCE.authToken
    private fun active(current: Session): Boolean = !closed && token() == current.token && synchronized(lock) { session === current }

    fun close() {
        closed = true
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        synchronized(lock) { session = null }
    }
}
