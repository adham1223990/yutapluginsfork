package com.github.yutaplug.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.api.guild.Guild as ApiGuild
import com.discord.stores.StoreStream
import com.discord.utilities.captcha.CaptchaHelper
import com.discord.utilities.color.ColorCompat
import com.discord.views.CheckedSetting
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.widgets.channels.list.WidgetChannelListModel
import com.discord.widgets.channels.list.WidgetChannelsList
import com.discord.widgets.channels.list.WidgetChannelsListAdapter
import com.discord.widgets.channels.list.items.ChannelListItem
import com.discord.widgets.channels.list.items.ChannelListItemCategory
import com.discord.widgets.channels.list.items.ChannelListItemStageVoiceChannel
import com.discord.widgets.channels.list.items.ChannelListItemTextChannel
import com.discord.widgets.channels.list.items.ChannelListItemThread
import com.discord.widgets.channels.list.items.ChannelListItemVoiceChannel
import com.discord.widgets.channels.list.items.ChannelListItemVoiceUser
import com.discord.widgets.guilds.join.GuildJoinHelperKt
import com.discord.widgets.guilds.profile.WidgetGuildProfileSheet
import com.discord.widgets.guilds.profile.WidgetGuildProfileSheetViewModel
import androidx.cardview.widget.CardView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executors

@AliucordPlugin
class Onboarding : Plugin() {
    private val main = Handler(Looper.getMainLooper())
    private var worker = Executors.newSingleThreadExecutor()
    private val cached = mutableMapOf<Long, OnboardingConfig?>()
    private val cacheTime = mutableMapOf<Long, Long>()
    private val pending = mutableSetOf<Long>()
    private val waiters = mutableMapOf<Long, MutableList<(OnboardingConfig?) -> Unit>>()
    private val failures = mutableMapOf<Long, Long>()
    private val originalLists = WeakHashMap<WidgetChannelsListAdapter, List<ChannelListItem>>()
    private val manualOptIns = mutableMapOf<Long, MutableMap<Long, Boolean>>()
    private val buttons = mutableListOf<WeakReference<View>>()
    private val buttonGuilds = WeakHashMap<View, Long>()
    private var screen: OnboardingScreen? = null
    private var accountId: Long? = null
    private var running = false
    private var generation = 0

    override fun start(context: Context) {
        if (worker.isShutdown) worker = Executors.newSingleThreadExecutor()
        running = true
        generation++
        patcher.patch(
            WidgetChannelsList::class.java,
            "configureUI",
            arrayOf(WidgetChannelListModel::class.java),
            Hook { frame ->
                syncAccount()
                val model = frame.args[0] as WidgetChannelListModel
                val guildId = model.selectedGuild?.id
                if (guildId != null) fetchConfig(guildId)
            },
        )
        patcher.patch(
            WidgetGuildProfileSheet::class.java,
            "configureUI",
            arrayOf(WidgetGuildProfileSheetViewModel.ViewState.Loaded::class.java),
            Hook { frame ->
                syncAccount()
                val sheet = frame.thisObject as WidgetGuildProfileSheet
                val state = frame.args[0] as WidgetGuildProfileSheetViewModel.ViewState.Loaded
                ensureSheetButtons(sheet, state.guildId)
            },
        )
        patcher.patch(
            MGRecyclerAdapterSimple::class.java,
            "setData",
            arrayOf(List::class.java),
            PreHook { frame ->
                syncAccount()
                val adapter = frame.thisObject as? WidgetChannelsListAdapter ?: return@PreHook
                @Suppress("UNCHECKED_CAST")
                val items = frame.args[0] as? List<ChannelListItem> ?: return@PreHook
                originalLists[adapter] = items
                val guildId = adapter.selectedGuildId
                if (guildId != 0L && isFiltered(guildId)) {
                    frame.args[0] = filterItems(guildId, items)
                }
            },
        )
        patchJoinFlow()
    }

    private fun patchJoinFlow() {
        patcher.patch(
            GuildJoinHelperKt::class.java,
            "joinGuild",
            arrayOf(
                Context::class.java,
                Long::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                String::class.java,
                Long::class.javaObjectType,
                String::class.java,
                Class::class.java,
                kotlin.Function1::class.java,
                kotlin.Function1::class.java,
                CaptchaHelper.CaptchaPayload::class.java,
                kotlin.Function1::class.java,
            ),
            PreHook { frame ->
                @Suppress("UNCHECKED_CAST")
                val original = frame.args[10] as? (ApiGuild) -> Unit ?: return@PreHook
                val isLurker = frame.args[2] as Boolean
                val context = WeakReference(frame.args[0] as Context)
                frame.args[10] = { guild: ApiGuild ->
                    original.invoke(guild)
                    if (!isLurker && running) {
                        main.postDelayed({
                            val activity = context.get()?.let(::activityFrom)
                            if (activity != null && !activity.isFinishing && !activity.isDestroyed && running) {
                                fetchConfig(guild.r()) { config ->
                                    if (config != null && config.prompts.any { it.inOnboarding }) {
                                        openScreen(activity, guild.r(), true)
                                    }
                                }
                            }
                        }, 400)
                    }
                }
            },
        )
    }

    private fun ensureSheetButtons(sheet: WidgetGuildProfileSheet, guildId: Long) {
        val root = sheet.view ?: return
        val cardId = Utils.getResId("guild_profile_sheet_secondary_actions", "id")
        val card = if (cardId != 0) root.findViewById<CardView>(cardId) else null
        val container = card?.getChildAt(0) as? LinearLayout ?: return
        val fontSourceId = Utils.getResId("change_identity_header", "id")
        val fontSource = if (fontSourceId != 0) root.findViewById<TextView>(fontSourceId) else null
        ensureSheetAction(container, guildId, fontSource, CHANNELS_TAG, "Channels & Roles", 0) { _, id ->
            val activity = sheet.activity ?: return@ensureSheetAction
            sheet.dismiss()
            main.post { openScreen(activity, id, false) }
        }
        val toggle = container.findViewWithTag<CheckedSetting>(SHOW_ALL_TAG) ?: run {
            val actions = LayoutInflater.from(container.context).inflate(
                Utils.getResId("widget_guild_profile_actions", "layout"), null, false,
            )
            val row = actions.findViewById<CheckedSetting>(
                Utils.getResId("guild_profile_sheet_hide_muted_channels", "id"),
            )
            (row.parent as ViewGroup).removeView(row)
            row.id = View.NO_ID
            row.tag = SHOW_ALL_TAG
            row.setText("Show All Channels")
            val identity = container.findViewById<View>(Utils.getResId("change_identity", "id"))
            container.addView(row, if (identity != null) container.indexOfChild(identity) + 1 else 1)
            buttons += WeakReference(row)
            row
        }
        buttonGuilds[toggle] = guildId
        toggle.visibility = View.VISIBLE
        toggle.setOnCheckedListener(null)
        toggle.isChecked = !isFiltered(guildId)
        toggle.setOnCheckedListener { showAll ->
            buttonGuilds[toggle]?.let { setFiltered(it, !showAll) }
        }
    }

    private fun ensureSheetAction(
        container: LinearLayout,
        guildId: Long,
        fontSource: TextView?,
        tagName: String,
        label: String,
        index: Int,
        action: (TextView, Long) -> Unit,
    ) {
        container.findViewWithTag<TextView>(tagName)?.let {
            buttonGuilds[it] = guildId
            it.visibility = View.VISIBLE
            return
        }
        val context = container.context
        val styleId = Utils.getResId("GuildProfileSheet_Actions_Title", "style")
        val row = TextView(context, null, 0, styleId).apply {
            tag = tagName
            text = label
            if (styleId == 0) {
                textSize = 16f
                setTextColor(themeColor(context, "colorHeaderPrimary", 0xfff2f3f5.toInt()))
            }
            fontSource?.let { source ->
                typeface = source.typeface
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, source.textSize)
                setTextColor(source.currentTextColor)
                letterSpacing = source.letterSpacing
            }
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
            isClickable = true
            isFocusable = true
            contentDescription = label
            val backgroundId = Utils.getResId("bg_pressed_highlight", "attr")
            if (styleId == 0 && backgroundId != 0) {
                val attribute = android.util.TypedValue()
                if (context.theme.resolveAttribute(backgroundId, attribute, true) && attribute.resourceId != 0) {
                    setBackgroundResource(attribute.resourceId)
                }
            }
            setOnClickListener {
                val id = buttonGuilds[this] ?: return@setOnClickListener
                action(this, id)
            }
        }
        container.addView(row, if (index <= container.childCount) index else container.childCount,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        buttons += WeakReference(row)
        buttonGuilds[row] = guildId
    }

    private fun fetchConfig(guildId: Long, onReady: ((OnboardingConfig?) -> Unit)? = null) {
        if (!running) return
        if (cached.containsKey(guildId) &&
            SystemClock.elapsedRealtime() - (cacheTime[guildId] ?: 0L) < CACHE_DURATION_MS
        ) {
            onReady?.invoke(cached[guildId])
            return
        }
        if (onReady != null) waiters.getOrPut(guildId) { mutableListOf() } += onReady
        if (guildId in pending) return
        if (SystemClock.elapsedRealtime() - (failures[guildId] ?: 0L) < RETRY_DELAY_MS) {
            waiters.remove(guildId)?.forEach { it(null) }
            return
        }
        pending += guildId
        val currentGeneration = generation
        worker.execute {
            val result = runCatching { OnboardingApi(OnboardingApi.currentToken()).getConfig(guildId) }
            main.post {
                if (!running || generation != currentGeneration) return@post
                pending -= guildId
                result.onSuccess { config ->
                    cached[guildId] = config
                    cacheTime[guildId] = SystemClock.elapsedRealtime()
                    failures.remove(guildId)
                    refreshChannelLists()
                }.onFailure { error ->
                    failures[guildId] = SystemClock.elapsedRealtime()
                    logger.error("Could not load onboarding for guild $guildId", error)
                }
                val config = result.getOrNull()
                waiters.remove(guildId)?.forEach { it(config) }
            }
        }
    }

    private fun syncAccount() {
        val current = runCatching { StoreStream.getUsers().me.id }.getOrNull()
        if (current == accountId) return
        accountId = current
        generation++
        screen?.dismiss()
        screen = null
        cached.clear()
        cacheTime.clear()
        pending.clear()
        waiters.clear()
        failures.clear()
        manualOptIns.clear()
        originalLists.clear()
        buttonGuilds.clear()
        updateButtons()
    }

    private fun updateButtons() {
        buttons.removeAll { it.get() == null }
        buttons.forEach { reference ->
            reference.get()?.let { button ->
                button.visibility = if (buttonGuilds[button] != null) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openScreen(context: Context, guildId: Long, initial: Boolean) {
        if (!running || screen != null) return
        val activity = activityFrom(context) ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        screen = OnboardingScreen(activity, guildId, initial,
            personalized = isFiltered(guildId),
            onConfigChanged = { config ->
                cached[guildId] = config
                cacheTime[guildId] = SystemClock.elapsedRealtime()
                refreshChannelLists()
            },
            onInitialComplete = {
                setFiltered(guildId, true)
            },
            onPersonalizedChanged = { enabled -> setFiltered(guildId, enabled) },
            isChannelHidden = { channelId -> isChannelHidden(guildId, channelId) },
            onChannelsChanged = { changes ->
                manualOptIns.getOrPut(guildId) { mutableMapOf() }.putAll(changes)
                changes.forEach { (channelId, enabled) -> setChannelHidden(guildId, channelId, !enabled) }
                refreshChannelLists()
            },
            onClosed = { screen = null },
        ).also { it.show() }
    }

    private fun filterItems(guildId: Long, items: List<ChannelListItem>): List<ChannelListItem> {
        val config = cached[guildId] ?: OnboardingConfig(guildId, emptyList(), emptySet(), emptySet())
        val visible = config.defaultChannelIds.toMutableSet()
        config.prompts.forEach { prompt ->
            prompt.options.filter { it.id in config.responses }.forEach { visible.addAll(it.channelIds) }
        }
        visible.removeAll { isChannelHidden(guildId, it) }
        val settingsForGuild = StoreStream.getUserGuildSettings().guildSettings[guildId]
        StoreStream.getChannels().getChannelsForGuild(guildId)?.keys?.forEach { id ->
            val opted = manualOptIns[guildId]?.get(id)
                ?: (settingsForGuild?.getChannelOverride(id)?.flags?.and(OnboardingApi.OPTED_IN_FLAG) != 0)
            if (opted && !isChannelHidden(guildId, id)) visible += id
        }
        visible += StoreStream.getChannelsSelected().id
        val parentIds = visible.mapNotNull { id ->
            StoreStream.getChannels().getChannel(id)?.u()?.takeIf { it != 0L }
        }.toSet()
        return items.filter { item ->
            when (item) {
                is ChannelListItemTextChannel -> item.channel.k() in visible
                is ChannelListItemVoiceChannel -> item.channel.k() in visible
                is ChannelListItemStageVoiceChannel -> item.channel.k() in visible
                is ChannelListItemVoiceUser -> item.channel.k() in visible
                is ChannelListItemThread -> item.channel.u() in visible
                is ChannelListItemCategory -> item.channel.k() in parentIds || item.channel.k() in visible
                else -> true
            }
        }
    }

    private fun refreshChannelLists() {
        originalLists.toMap().forEach { (adapter, items) -> adapter.setData(items) }
    }

    private fun isFiltered(guildId: Long): Boolean {
        val userId = runCatching { StoreStream.getUsers().me.id }.getOrNull() ?: return false
        return settings.getBool("personalized_${userId}_$guildId", false)
    }

    private fun isChannelHidden(guildId: Long, channelId: Long): Boolean {
        val userId = runCatching { StoreStream.getUsers().me.id }.getOrNull() ?: return false
        return settings.getBool("hidden_${userId}_${guildId}_$channelId", false)
    }

    private fun setChannelHidden(guildId: Long, channelId: Long, hidden: Boolean) {
        val userId = runCatching { StoreStream.getUsers().me.id }.getOrNull() ?: return
        settings.setBool("hidden_${userId}_${guildId}_$channelId", hidden)
    }

    private fun setFiltered(guildId: Long, enabled: Boolean) {
        val userId = runCatching { StoreStream.getUsers().me.id }.getOrNull() ?: return
        settings.setBool("personalized_${userId}_$guildId", enabled)
        buttons.forEach { reference ->
            val toggle = reference.get() as? CheckedSetting ?: return@forEach
            if (buttonGuilds[toggle] == guildId && toggle.isChecked == enabled) toggle.isChecked = !enabled
        }
        refreshChannelLists()
    }

    override fun stop(context: Context) {
        running = false
        generation++
        patcher.unpatchAll()
        screen?.dismiss()
        screen = null
        buttons.forEach { reference ->
            reference.get()?.let { button ->
                button.setOnClickListener(null)
                (button as? CheckedSetting)?.setOnCheckedListener(null)
                (button.parent as? ViewGroup)?.removeView(button)
            }
        }
        buttons.clear()
        buttonGuilds.clear()
        cached.clear()
        cacheTime.clear()
        pending.clear()
        waiters.clear()
        failures.clear()
        originalLists.clear()
        manualOptIns.clear()
        accountId = null
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }

    private fun themeColor(context: Context, name: String, fallback: Int): Int {
        val id = Utils.getResId(name, "attr")
        return if (id == 0) fallback else ColorCompat.getThemedColor(context, id)
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun activityFrom(context: Context): Activity? {
        var current: Context? = context
        repeat(8) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }

    private companion object {
        const val CHANNELS_TAG = "onboarding_channels_and_roles"
        const val SHOW_ALL_TAG = "onboarding_show_all_channels"
        const val RETRY_DELAY_MS = 30_000L
        const val CACHE_DURATION_MS = 300_000L
    }
}
