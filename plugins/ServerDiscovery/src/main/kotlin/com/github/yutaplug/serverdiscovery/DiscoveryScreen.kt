package com.github.yutaplug.serverdiscovery

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Http
import com.aliucord.Utils
import com.discord.stores.StoreAuthentication
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.images.MGImages
import com.discord.utilities.rest.RestAPI
import com.discord.widgets.guilds.join.GuildJoinHelperKt
import com.facebook.drawee.view.SimpleDraweeView
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.Executors

/** A native client-side view over Discord's discoverable-guilds API. */
internal class DiscoveryScreen(
    private val context: Context,
    private val onClosed: () -> Unit,
) {
    private data class Category(val id: Int?, val name: String)

    private data class Guild(
        val id: Long,
        val name: String,
        val description: String?,
        val icon: String?,
        val members: Int,
        val online: Int,
    )

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val dialog = Dialog(context)
    private val adapter = GuildAdapter()
    private val primary = color("colorBackgroundPrimary", 0xFF313338.toInt())
    private val secondary = color("colorBackgroundSecondary", 0xFF2B2D31.toInt())
    private val normal = color("colorTextNormal", 0xFFF2F3F5.toInt())
    private val muted = color("colorTextMuted", 0xFFB5BAC1.toInt())
    private val brand = 0xFF5865F2.toInt()
    private val categories = mutableListOf(Category(null, "All"))
    private var selectedCategory: Int? = null
    private var query = ""
    private var offset = 0
    private var total = Int.MAX_VALUE
    private var loading = false
    private var generation = 0
    private var closed = false
    private var searchTask: Runnable? = null
    private lateinit var categoryStrip: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var discoveryContent: View
    private var previewContent: View? = null
    private lateinit var results: RecyclerView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(createContent())
        dialog.setOnDismissListener {
            closed = true
            generation++
            searchTask?.let(main::removeCallbacks)
            worker.shutdownNow()
            onClosed()
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && previewContent != null) {
                hidePreview()
                true
            } else false
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(primary))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            statusBarColor = primary
            navigationBarColor = primary
        }
        loadPage()
        loadCategories()
    }

    fun dismiss() = dialog.dismiss()

    private fun createContent(): View {
        content = FrameLayout(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(primary)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        header.addView(TextView(context).apply {
            text = "Discover Servers"
            textSize = 23f
            setTextColor(normal)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        header.addView(TextView(context).apply {
            text = "✕"
            textSize = 23f
            gravity = Gravity.CENTER
            contentDescription = "Close discovery"
            setTextColor(muted)
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)

        val search = EditText(context).apply {
            hint = "Search communities"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 16f
            setTextColor(normal)
            setHintTextColor(muted)
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(secondary, 9)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchTask?.let(main::removeCallbacks)
                    val newQuery = trimWhitespace(s?.toString().orEmpty())
                    searchTask = Runnable {
                        if (newQuery != query) {
                            query = newQuery
                            resetResults()
                        }
                    }.also { main.postDelayed(it, 350) }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            setMargins(dp(16), 0, dp(16), dp(12))
        })

        categoryStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        root.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(categoryStrip)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        renderCategories()

        status = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(muted)
            visibility = View.GONE
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        root.addView(status)

        results = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@DiscoveryScreen.adapter
            clipToPadding = false
            setPadding(dp(8), dp(4), dp(8), dp(16))
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val manager = recyclerView.layoutManager as LinearLayoutManager
                    if (dy > 0 && manager.findLastVisibleItemPosition() >= this@DiscoveryScreen.adapter.itemCount - 4) loadPage()
                }
            })
        }
        root.addView(results, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        progress = ProgressBar(context).apply { visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(12)
        })
        discoveryContent = root
        content.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return content
    }

    private fun renderCategories() {
        categoryStrip.removeAllViews()
        categories.forEach { category ->
            val selected = category.id == selectedCategory
            categoryStrip.addView(TextView(context).apply {
                text = category.name
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(if (selected) 0xFFFFFFFF.toInt() else normal)
                background = rounded(if (selected) brand else secondary, 18)
                setPadding(dp(16), 0, dp(16), 0)
                isClickable = true
                setOnClickListener {
                    if (selectedCategory != category.id) {
                        selectedCategory = category.id
                        renderCategories()
                        resetResults()
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                marginEnd = dp(8)
            })
        }
    }

    private fun loadCategories() {
        worker.execute {
            val loaded = runCatching {
                val language = Uri.encode(Locale.getDefault().toLanguageTag())
                val response = getJson("/discovery/categories?primary_only=true&locale=$language")
                val array = when (response) {
                    is JSONArray -> response
                    is JSONObject -> response.optJSONArray("categories") ?: JSONArray()
                    else -> JSONArray()
                }
                mutableListOf<Category>().apply {
                    add(Category(null, "All"))
                    var i = 0
                    while (i < array.length()) {
                        val item = array.optJSONObject(i++) ?: continue
                        val id = item.optInt("id", -1).takeIf { it >= 0 } ?: continue
                        val nameValue = item.opt("name")
                        val name = when (nameValue) {
                            is JSONObject -> nameValue.optString("default")
                            is String -> nameValue
                            else -> ""
                        }
                        if (hasText(name)) add(Category(id, name))
                    }
                }
            }.getOrNull() ?: return@execute
            main.post {
                if (!closed) {
                    categories.clear()
                    categories.addAll(loaded)
                    renderCategories()
                }
            }
        }
    }

    private fun resetResults() {
        generation++
        offset = 0
        total = Int.MAX_VALUE
        loading = false
        adapter.clear()
        status.visibility = View.GONE
        results.scrollToPosition(0)
        loadPage()
    }

    private fun loadPage() {
        if (closed || loading || offset >= total) return
        loading = true
        progress.visibility = View.VISIBLE
        status.visibility = View.GONE
        val requestGeneration = generation
        val requestOffset = offset
        val requestQuery = query
        val requestCategory = selectedCategory
        worker.execute {
            val result = runCatching {
                val route = if (!hasText(requestQuery)) {
                    "/discoverable-guilds?limit=$PAGE_SIZE&offset=$requestOffset" +
                        (requestCategory?.let { "&categories=$it" } ?: "")
                } else {
                    "/discoverable-guilds/search?query=${Uri.encode(requestQuery.take(100))}" +
                        "&limit=$PAGE_SIZE&offset=$requestOffset" +
                        (requestCategory?.let { "&category_id=$it" } ?: "")
                }
                val body = getJson(route) as JSONObject
                val array = body.optJSONArray("guilds") ?: JSONArray()
                val guilds = mutableListOf<Guild>().apply {
                    var i = 0
                    while (i < array.length()) {
                        val item = array.optJSONObject(i++) ?: continue
                        val id = item.optString("id").toLongOrNull() ?: continue
                        val name = item.optString("name").takeIf(::hasText) ?: continue
                        add(Guild(
                            id,
                            name,
                            item.optString("description").takeIf { hasText(it) && it != "null" },
                            item.optString("icon").takeIf { hasText(it) && it != "null" },
                            item.optInt("approximate_member_count"),
                            item.optInt("approximate_presence_count"),
                        ))
                    }
                }
                guilds to body.optInt("total", -1)
            }
            main.post {
                if (closed || requestGeneration != generation) return@post
                loading = false
                progress.visibility = View.GONE
                result.onSuccess { (guilds, count) ->
                    adapter.append(guilds)
                    offset = requestOffset + guilds.size
                    total = if (count >= 0) count else if (guilds.size < PAGE_SIZE) offset else Int.MAX_VALUE
                    if (guilds.isEmpty() && adapter.itemCount == 0) showStatus("No servers found")
                    else if (offset < total && guilds.isNotEmpty()) {
                        results.post { if (!closed && !results.canScrollVertically(1)) loadPage() }
                    }
                }.onFailure { error ->
                    showStatus("Could not load servers. Tap to retry.\n${error.message.orEmpty().take(120)}")
                }
            }
        }
    }

    private fun showStatus(message: String) {
        status.text = message
        status.visibility = View.VISIBLE
        status.setOnClickListener { loadPage() }
    }

    private fun getJson(route: String): Any {
        val token = StoreAuthentication.`access$getAuthState$p`(StoreStream.getAuthentication())?.token
            ?.takeIf(::hasText)
            ?: RestAPI.AppHeadersProvider.INSTANCE.authToken?.takeIf(::hasText)
            ?: error("Sign in to Discord to browse servers")
        return Http.Request.newDiscordRequest(route).use { request ->
            request.setRequestTimeout(15_000)
            request.setHeader("Authorization", token)
            request.execute().use { response ->
                if (!response.ok()) error("HTTP ${response.statusCode}")
                val text = response.text()
                var index = 0
                while (index < text.length && Character.isWhitespace(text[index])) index++
                if (index < text.length && text[index] == '[') JSONArray(text) else JSONObject(text)
            }
        }
    }

    private fun showGuild(guild: Guild) {
        if (previewContent != null) return
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(primary)
        }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        header.addView(TextView(context).apply {
            text = "‹"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(normal)
            contentDescription = "Back to discovery"
            setOnClickListener { hidePreview() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(context).apply {
            text = "Server Preview"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(normal)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(header)

        val scroll = ScrollView(context).apply { isFillViewport = true }
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val avatar = FrameLayout(context)
        val initial = TextView(context).apply {
            text = guild.name.take(1).uppercase(Locale.getDefault())
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(normal)
            background = rounded(brand, 40)
        }
        avatar.addView(initial, FrameLayout.LayoutParams(dp(80), dp(80)))
        guild.icon?.let { iconHash ->
            avatar.addView(SimpleDraweeView(context).apply {
                MGImages.setRoundingParams(this, dp(40).toFloat(), false, null, null, null)
                setImageURI("https://cdn.discordapp.com/icons/${guild.id}/$iconHash.png?size=256")
            }, FrameLayout.LayoutParams(dp(80), dp(80)))
        }
        details.addView(avatar, LinearLayout.LayoutParams(dp(80), dp(80)).apply { bottomMargin = dp(20) })
        details.addView(TextView(context).apply {
            text = guild.name
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(normal)
        })
        guild.description?.let { description ->
            details.addView(TextView(context).apply {
                text = description
                textSize = 16f
                setTextColor(muted)
                setPadding(0, dp(12), 0, 0)
            })
        }
        val format = NumberFormat.getIntegerInstance()
        details.addView(TextView(context).apply {
            text = "${format.format(guild.members)} members  ·  ${format.format(guild.online)} online"
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(20), 0, 0)
        })
        scroll.addView(details)
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
        }
        val joinButton = TextView(context).apply {
            text = "Join Server"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(brand, 8)
            isClickable = true
        }
        joinButton.setOnClickListener {
            joinButton.isEnabled = false
            joinButton.text = "Joining…"
            GuildJoinHelperKt.joinGuild(context, guild.id, false, null, null, null, DiscoveryScreen::class.java,
                null, { _ ->
                    joinButton.isEnabled = true
                    joinButton.text = "Join Server"
                }, null) { _ ->
                openServer(guild.id)
            }
        }
        actions.addView(joinButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        actions.addView(TextView(context).apply {
            text = "View Server"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(normal)
            background = rounded(secondary, 8)
            isClickable = true
            setOnClickListener {
                openServer(guild.id)
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        page.addView(actions)

        discoveryContent.visibility = View.GONE
        previewContent = page
        content.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun openServer(guildId: Long) {
        // Lurking can open Discord's welcome sheet even when it has no channels to show.
        StoreStream.getGuildWelcomeScreens().markWelcomeScreenShown(guildId)
        dismiss()
        StoreStream.getLurking().startLurkingAndNavigate(guildId, null, context)
    }

    private fun hidePreview() {
        previewContent?.let(content::removeView)
        previewContent = null
        discoveryContent.visibility = View.VISIBLE
    }

    private fun color(attribute: String, fallback: Int): Int {
        val id = Utils.getResId(attribute, "attr")
        return if (id != 0) ColorCompat.getThemedColor(context, id) else fallback
    }

    private fun hasText(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) return true
        }
        return false
    }

    private fun trimWhitespace(value: String): String {
        var first = 0
        var last = value.length
        while (first < last && Character.isWhitespace(value[first])) first++
        while (last > first && Character.isWhitespace(value[last - 1])) last--
        return value.substring(first, last)
    }

    private fun rounded(fill: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private inner class GuildAdapter : RecyclerView.Adapter<GuildAdapter.Holder>() {
        private val items = mutableListOf<Guild>()

        inner class Holder(val root: LinearLayout, val icon: SimpleDraweeView, val initial: TextView,
                           val name: TextView, val description: TextView, val stats: TextView) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), rounded(secondary, 12), null)
            }
            val avatar = android.widget.FrameLayout(context)
            val initial = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 24f
                setTextColor(normal)
                background = rounded(brand, 26)
            }
            avatar.addView(initial, android.widget.FrameLayout.LayoutParams(dp(52), dp(52)))
            val icon = SimpleDraweeView(context).apply {
                MGImages.setRoundingParams(this, dp(26).toFloat(), false, null, null, null)
            }
            avatar.addView(icon, android.widget.FrameLayout.LayoutParams(dp(52), dp(52)))
            root.addView(avatar, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(12) })
            val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val name = TextView(context).apply {
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(normal)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            text.addView(name)
            val description = TextView(context).apply {
                textSize = 13f
                setTextColor(muted)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            text.addView(description)
            val stats = TextView(context).apply {
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(4), 0, 0)
            }
            text.addView(stats)
            root.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return Holder(root, icon, initial, name, description, stats)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val guild = items[position]
            holder.name.text = guild.name
            holder.description.text = guild.description.orEmpty()
            holder.description.visibility = if (guild.description == null) View.GONE else View.VISIBLE
            holder.initial.text = guild.name.take(1).uppercase(Locale.getDefault())
            holder.icon.visibility = if (guild.icon == null) View.GONE else View.VISIBLE
            if (guild.icon != null) {
                holder.icon.setImageURI("https://cdn.discordapp.com/icons/${guild.id}/${guild.icon}.png?size=128")
            }
            val format = NumberFormat.getIntegerInstance()
            holder.stats.text = "${format.format(guild.members)} members · ${format.format(guild.online)} online"
            holder.root.setOnClickListener { showGuild(guild) }
            holder.root.contentDescription = "${guild.name}, ${holder.stats.text}"
            holder.root.layoutParams = (holder.root.layoutParams as? RecyclerView.LayoutParams
                ?: RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)).apply {
                setMargins(dp(8), dp(4), dp(8), dp(4))
            }
        }

        override fun getItemCount() = items.size

        fun clear() {
            val size = items.size
            items.clear()
            if (size > 0) notifyItemRangeRemoved(0, size)
        }

        fun append(incoming: List<Guild>) {
            val start = items.size
            items.addAll(incoming)
            if (incoming.isNotEmpty()) notifyItemRangeInserted(start, incoming.size)
        }
    }

    private companion object {
        const val PAGE_SIZE = 24
    }
}
