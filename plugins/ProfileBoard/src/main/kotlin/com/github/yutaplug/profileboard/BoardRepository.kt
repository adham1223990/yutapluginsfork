package com.github.yutaplug.profileboard

import com.aliucord.Http
import com.discord.stores.StoreAuthentication
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.Executors

internal data class BoardGame(val id: String, val comment: String?, val tags: List<String>)
internal const val UNKNOWN_GAME_NAME = "Unknown game"
internal data class BoardWidget(val type: String, val games: List<BoardGame>, val applicationId: String?)
internal data class GameInfo(val name: String, val image: String?)
internal data class BoardData(
    val widgets: List<BoardWidget>,
    val games: Map<String, GameInfo>,
    val applications: Map<String, ApplicationWidget>,
)

internal class BoardRepository {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "ProfileBoardRequests") }
    @Volatile private var closed = false

    fun load(userId: Long, callback: (Result<BoardData>) -> Unit) {
        val account = token() ?: return callback(Result.failure(IllegalStateException("Not signed in")))
        if (!hasText(account)) return callback(Result.failure(IllegalStateException("Not signed in")))
        worker.execute {
            if (closed) return@execute
            val result = runCatching {
                val profile = request("/users/$userId/profile?with_mutual_guilds=false&with_mutual_friends=false", account)
                    as? JSONObject ?: error("Invalid profile response")
                val widgets = parseWidgets(profile.optJSONArray("widgets"))
                val gameIds = LinkedHashSet<String>()
                for (widget in widgets) {
                    for (game in widget.games) gameIds.add(game.id)
                }
                val ids = ArrayList(gameIds)
                val games = LinkedHashMap<String, GameInfo>()
                var start = 0
                while (start < ids.size) {
                    val end = if (start + 25 < ids.size) start + 25 else ids.size
                    // A failed game lookup must not hide a profile's existing widgets.
                    val query = StringBuilder("/games?")
                    var position = start
                    while (position < end) {
                        if (position > start) query.append('&')
                        query.append("game_ids=").append(ids[position])
                        position++
                    }
                    val response = runCatching { request(query.toString(), account) }.getOrNull()
                    val array = when (response) {
                        is JSONArray -> response
                        is JSONObject -> response.optJSONArray("games")
                        else -> null
                    }
                    if (array != null) {
                        var index = 0
                        while (index < array.length()) {
                            val game = array.optJSONObject(index)
                            if (game != null) parseGame(game)?.let { (id, info) -> games[id] = info }
                            index++
                        }
                    }
                    position = start
                    while (position < end) {
                        val id = ids[position]
                        position++
                        if (id in games) continue
                        val game = runCatching { request("/games/$id", account) as? JSONObject }.getOrNull()
                        if (game != null) parseGame(game)?.let { (gameId, info) -> games[gameId] = info }
                    }
                    start = end
                }
                val applications = ApplicationWidgets.load(userId, widgets, ::request, account)
                check(token() == account) { "Account changed" }
                BoardData(widgets, games, applications)
            }
            if (!closed) callback(result)
        }
    }

    fun close() {
        closed = true
        worker.shutdownNow()
    }

    private fun request(route: String, account: String): Any {
        check(token() == account) { "Account changed" }
        return Http.Request.newDiscordRNRequest(route).use { request ->
            request.setRequestTimeout(15_000)
            request.setHeader("Authorization", account)
            RestAPI.AppHeadersProvider.INSTANCE.getFingerprint()?.let { request.setHeader("X-Fingerprint", it) }
            request.execute().use { response ->
                check(response.ok()) { "HTTP ${response.statusCode} for $route" }
                val body = JSONTokener(response.text()).nextValue()
                if (body is JSONObject || body is JSONArray) body else error("Invalid JSON response for $route")
            }
        }
    }

    private fun parseWidgets(array: JSONArray?): List<BoardWidget> {
        val widgets = ArrayList<BoardWidget>()
        if (array == null) return widgets
        var index = 0
        while (index < array.length()) {
            val data = array.optJSONObject(index)?.optJSONObject("data")
            index++
            if (data == null) continue
            val type = data.optString("type")
            if (type != "favorite_games" && type != "played_games" && type != "current_games" &&
                type != "want_to_play_games" && type != "application") continue
            val gameArray = data.optJSONArray("games")
            val games = ArrayList<BoardGame>()
            if (gameArray != null) {
                var position = 0
                while (position < gameArray.length()) {
                    val game = gameArray.optJSONObject(position)
                    position++
                    if (game == null) continue
                    val id = game.text("game_id") ?: continue
                    val tags = ArrayList<String>()
                    val values = game.optJSONArray("tags")
                    if (values != null) {
                        var tagIndex = 0
                        while (tagIndex < values.length()) {
                            val tag = values.optString(tagIndex)
                            if (hasText(tag)) tags.add(tag)
                            tagIndex++
                        }
                    }
                    games.add(BoardGame(id, game.text("comment"), tags))
                }
            }
            val applicationId = data.text("application_id")
            if (games.isNotEmpty() || applicationId != null) widgets.add(BoardWidget(type, games, applicationId))
        }
        return widgets
    }

    private fun parseGame(game: JSONObject): Pair<String, GameInfo>? {
        val id = game.text("id") ?: return null
        val supplemental = game.optJSONObject("supplemental_game_data")
        val name = game.text("name")?.takeIf { it != id }
            ?: supplemental?.text("name")?.takeIf { it != id }
            ?: UNKNOWN_GAME_NAME
        val artwork = supplemental?.optJSONArray("artwork_urls")?.optString(0)
        val cover = supplemental?.text("cover_image_url")
        val icon = supplemental?.text("icon_hash") ?: game.text("icon_hash")
        val mediaCover = game.optJSONObject("media")?.optJSONObject("cover")
        val mediaCoverUrl = when (mediaCover?.optString("type")) {
            "url" -> mediaCover.text("value")
            "hash" -> mediaCover.text("value")?.let { "https://cdn.discordapp.com/app-icons/$id/$it.png?size=256" }
            else -> null
        }
        val image = when {
            mediaCoverUrl?.startsWith("https://") == true -> mediaCoverUrl
            artwork?.startsWith("https://") == true -> artwork
            cover?.startsWith("https://") == true -> cover
            icon != null -> "https://cdn.discordapp.com/app-icons/$id/$icon.png?size=256"
            else -> null
        }
        return id to GameInfo(name, image)
    }

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { hasText(it) && it != "null" }

    private fun hasText(value: String?): Boolean {
        if (value == null) return false
        var index = 0
        while (index < value.length) {
            if (!Character.isWhitespace(value[index])) return true
            index++
        }
        return false
    }

    private fun token(): String? = StoreAuthentication.`access$getAuthState$p`(StoreStream.getAuthentication())?.token
        ?: RestAPI.AppHeadersProvider.INSTANCE.authToken
}
