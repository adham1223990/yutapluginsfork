package com.github.yutaplug.superreactions

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.GsonUtils
import com.aliucord.utils.GsonUtils.fromJson
import com.aliucord.utils.GsonUtils.toJson
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.api.message.reaction.MessageReactionUpdate
import com.discord.api.premium.PremiumTier
import com.discord.models.deserialization.gson.InboundGatewayGsonParser
import com.discord.models.domain.emoji.Emoji
import com.discord.models.user.CoreUser
import com.discord.models.user.User
import com.discord.stores.StoreMessageReactions
import com.discord.stores.StoreMessages
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.user.UserUtils
import com.discord.views.ReactionView
import com.discord.widgets.chat.input.emoji.EmojiPickerContextType
import com.discord.widgets.chat.input.emoji.EmojiPickerListener
import com.discord.widgets.chat.input.emoji.EmojiPickerNavigator
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterEventsHandler
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemReactions
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.ReactionsEntry
import com.discord.widgets.chat.list.model.WidgetChatListModel
import com.discord.widgets.chat.managereactions.ManageReactionsEmojisAdapter
import com.discord.widgets.chat.managereactions.ManageReactionsModel
import com.discord.widgets.chat.managereactions.ManageReactionsResultsAdapter
import com.discord.widgets.chat.managereactions.WidgetManageReactions
import com.google.gson.stream.JsonReader
import java.io.StringReader
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

@AliucordPlugin
@Suppress("unused")
class SuperReactions : Plugin() {
    private val superReactionButtons = WeakHashMap<WidgetChatListActions, TextView>()
    private val visibleReactionItems = WeakHashMap<WidgetChatListAdapterItemReactions, Long>()
    private val visibleReactionEntries = WeakHashMap<WidgetChatListAdapterItemReactions, ReactionsEntry>()
    private val visibleReactionPositions = WeakHashMap<WidgetChatListAdapterItemReactions, Int>()
    private val reactionStyles = WeakHashMap<ReactionView, ReactionStyle>()
    private val reactionBindings = WeakHashMap<ReactionView, ReactionBinding>()
    private val activeManageReactions = WeakHashMap<WidgetManageReactions, ManageReactionTarget>()
    private val activeManageEmojiAdapters =
        WeakHashMap<ManageReactionsEmojisAdapter, WeakReference<WidgetManageReactions>>()
    private val manageReactionWidgetTypes = WeakHashMap<WidgetManageReactions, Boolean>()
    private val reactionChannels = ConcurrentHashMap<Long, Long>()
    private val superReactionCounts = ConcurrentHashMap<Long, MutableMap<String, Int>>()
    private val normalReactionCounts = ConcurrentHashMap<Long, MutableMap<String, Int>>()
    private val superReactionColors = ConcurrentHashMap<Long, MutableMap<String, Int>>()
    private val expandedReactionTypes = ConcurrentHashMap<Long, IdentityHashMap<MessageReaction, Boolean>>()
    private val manageReactionTypes = ConcurrentHashMap<String, Boolean>()
    private val manageReactionItemTypes = WeakIdentityMap<ManageReactionsEmojisAdapter.ReactionEmojiItem, Boolean>()
    private val gatewayReactionTypes = WeakIdentityMap<MessageReactionUpdate, Boolean>()
    private val superReactionFetchTimes = ConcurrentHashMap<Long, Long>()
    private val superReactionFetches = ConcurrentHashMap.newKeySet<Long>()
    private val channelBatchFetches = ConcurrentHashMap.newKeySet<Long>()
    private val batchPendingMessages = ConcurrentHashMap.newKeySet<Long>()
    private val superReactionInvalidationVersions = ConcurrentHashMap<Long, Long>()
    private val localMutationVersions = ConcurrentHashMap<Long, Long>()
    private val metadataAttempts = ConcurrentHashMap<Long, Long>()
    private val metadataRefreshes = mutableMapOf<Long, Runnable>()
    private val metadataWaiters = mutableMapOf<Long, MutableList<(Boolean) -> Unit>>()
    private val pendingReactionActions = ConcurrentHashMap.newKeySet<String>()
    private val locallySentSuperReactions = ConcurrentHashMap.newKeySet<String>()
    private val ownedSuperReactions = ConcurrentHashMap.newKeySet<String>()
    private val pendingSuperReactionRemovals = ConcurrentHashMap.newKeySet<String>()
    private val completedSuperReactionRemovals = ConcurrentHashMap.newKeySet<String>()
    private val completedRemovalTokens = ConcurrentHashMap<String, Any>()
    private val burstReactionUsers = ConcurrentHashMap<String, List<User>>()
    private val normalReactionItems = ConcurrentHashMap<String, List<MGRecyclerDataPayload>>()
    private val burstUserFetches = ConcurrentHashMap.newKeySet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nativeReactionUpdateMethod by lazy {
        WidgetChatListAdapterEventsHandler.UserReactionHandler::class.java.getDeclaredMethod(
            "requestReactionUpdate",
            WidgetChatListAdapterEventsHandler.UserReactionHandler.UpdateRequest::class.java,
        ).apply { isAccessible = true }
    }
    private var requests: DiscordRequestQueue? = null
    private var ownedReactionPreferences: SharedPreferences? = null
    private var ownedReactionPreferencesKey: String? = null

    @Volatile private var running = false

    override fun start(context: Context) {
        requests = DiscordRequestQueue()
        running = true
        ownedSuperReactions.clear()
        val userId = getCurrentUserId()
        val preferences = context.getSharedPreferences("SuperReactions", Context.MODE_PRIVATE)
        val preferencesKey = OWNED_REACTIONS_PREFERENCES + if (userId == 0L) "unknown" else userId.toString()
        ownedReactionPreferences = preferences
        ownedReactionPreferencesKey = preferencesKey
        preferences.getStringSet(preferencesKey, null)?.let { ownedSuperReactions.addAll(it) }
        patchGateway()
        patchMessageActions()
        patchChatList()
        patchReactionViews()
        patchReactionToggles()
        patchManageReactions()
    }

    private fun patchGateway() {
        // Preserve the burst flag before the legacy model discards it, using Discord's adapter for its other fields.
        patcher.patch(
            InboundGatewayGsonParser::class.java,
            "fromJson",
            arrayOf(JsonReader::class.java, Class::class.java),
            PreHook { frame ->
                if (frame.args[1] != MessageReactionUpdate::class.java) return@PreHook
                val raw = GsonUtils.gson.d<Map<*, *>>(frame.args[0] as JsonReader, Map::class.java)
                val update = InboundGatewayGsonParser.INSTANCE.getGatewayGsonInstance().d<MessageReactionUpdate>(
                    JsonReader(StringReader(GsonUtils.gson.toJson(raw))),
                    MessageReactionUpdate::class.java,
                )
                if (raw != null &&
                    update != null
                ) {
                    ReactionMetadata.eventType(raw)?.let { gatewayReactionTypes.put(update, it) }
                }
                frame.result = update
            },
        )
        for (method in listOf("handleReactionAdd", "handleReactionRemove")) {
            patcher.patch(
                StoreMessageReactions::class.java,
                method,
                arrayOf(MessageReactionUpdate::class.java),
                PreHook { frame ->
                    if (gatewayReactionTypes[frame.args[0] as MessageReactionUpdate] == true) frame.result = null
                },
            )
        }
        patcher.patch(
            StoreMessages::class.java,
            "handleReactionUpdate",
            arrayOf(List::class.java, BOOLEAN),
            PreHook { frame ->
                val updates = frame.args[0] as? List<*> ?: return@PreHook
                val userId = getCurrentUserId()
                if (userId == 0L) return@PreHook
                val isAdd = frame.args[1] == true
                var changed = false
                val rewritten = mutableListOf<Any?>()
                for (raw in updates) {
                    val update = raw as? MessageReactionUpdate
                    val key = update?.b()?.c()
                    if (update == null || key == null || update.d() != userId) {
                        rewritten.add(raw)
                        continue
                    }
                    val burst = gatewayReactionTypes[update]
                    if (burst == false || (isAdd && burst != true)) {
                        rewritten.add(raw)
                        continue
                    }
                    if (isAdd) {
                        markOwnedSuperReaction(update.c(), key)
                    } else {
                        if (isCompletedSuperReactionRemoval(update.c(), key)) {
                            changed = true
                            continue
                        }
                        if (burst != true && !isPendingSuperReactionRemoval(update.c(), key)) {
                            rewritten.add(raw)
                            continue
                        }
                        completeSuperReactionRemoval(update.c(), key)
                        removeLocalSuperReaction(update.c(), key)
                    }
                    localMutationVersions.merge(update.c(), 1L, Long::plus)
                    // A non-self update changes the aggregate without changing the legacy normal `me` bit.
                    rewritten.add(
                        MessageReactionUpdate(
                            if (userId ==
                                1L
                            ) {
                                2L
                            } else {
                                1L
                            },
                            update.a(),
                            update.c(),
                            update.b(),
                        ),
                    )
                    changed = true
                }
                if (changed) frame.args[0] = rewritten
            },
        )
        patcher.patch(
            StoreMessages::class.java,
            "handleReactionUpdate",
            arrayOf(List::class.java, BOOLEAN),
            Hook { frame ->
                (frame.args[0] as? List<*>)?.filterIsInstance<MessageReactionUpdate>()?.forEach {
                    val key = it.b()?.c()
                    if (gatewayReactionTypes[it] == true || isSuperReaction(it.c(), key) ||
                        isOwnSuperReaction(it.c(), key)
                    ) invalidateSuperReactionCache(it.c(), it.a())
                }
            },
        )
        for (method in listOf("handleReactionsRemoveAll", "handleReactionsRemoveEmoji")) {
            patcher.patch(
                StoreMessages::class.java,
                method,
                arrayOf(MessageReactionUpdate::class.java),
                Hook { frame ->
                    val update = frame.args[0] as MessageReactionUpdate
                    invalidateSuperReactionCache(update.c(), update.a())
                },
            )
        }
    }

    private fun patchMessageActions() {
        patcher.patch(
            WidgetChatListActions::class.java,
            "onViewCreated",
            arrayOf(View::class.java, Bundle::class.java),
            Hook { frame ->
                addSuperReactionButton(frame.thisObject as WidgetChatListActions, frame.args[0] as View)
            },
        )
        patcher.patch(
            WidgetChatListActions::class.java,
            "configureUI",
            arrayOf(WidgetChatListActions.Model::class.java),
            Hook { frame ->
                val button = superReactionButtons[frame.thisObject as WidgetChatListActions] ?: return@Hook
                val model = frame.args[0] as? WidgetChatListActions.Model ?: return@Hook
                button.visibility =
                    if (model.manageMessageContext.canAddReactions &&
                        !model.message.isLocal &&
                        hasNitro()
                    ) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
            },
        )
    }

    private fun patchChatList() {
        // A channel model contains its initially loaded messages before every individual
        // reaction row is bound. Warm those snapshots here so normal reaction toggles do
        // not need to stop and classify the pill after it becomes visible.
        patcher.patch(
            WidgetChatList::class.java,
            "configureUI",
            arrayOf(WidgetChatListModel::class.java),
            PreHook { frame ->
                val model = frame.args[0] as? WidgetChatListModel ?: return@PreHook
                val channelId = model.channelId
                if (channelId == 0L) return@PreHook
                prefetchChannelSuperReactionMetadata(channelId, model.list.filterIsInstance<ReactionsEntry>())
            },
        )
    }

    private fun patchReactionViews() {
        patcher.patch(
            WidgetChatListAdapterItemReactions::class.java,
            "displayReactions",
            arrayOf(Collection::class.java, LONG, BOOLEAN, BOOLEAN, BOOLEAN),
            PreHook { frame ->
                frame.args[0] = expandReactions(frame.args[0] as? Collection<*>, frame.args[1] as Long)
            },
        )
        patcher.patch(
            WidgetChatListAdapterItemReactions::class.java,
            "onConfigure",
            arrayOf(INT, ChatListEntry::class.java),
            Hook { frame ->
                val item = frame.thisObject as WidgetChatListAdapterItemReactions
                val entry = frame.args[1] as? ReactionsEntry ?: return@Hook
                val message = entry.message ?: return@Hook
                synchronized(visibleReactionItems) {
                    visibleReactionItems[item] = message.id
                    visibleReactionEntries[item] = entry
                    visibleReactionPositions[item] = frame.args[0] as Int
                }
                reactionChannels[message.id] = message.channelId
                applySuperReactionStyles(item, message.id)
                if (message.id !in batchPendingMessages) {
                    fetchSuperReactionMetadata(message.channelId, message.id)
                }
            },
        )
        patcher.patch(
            ReactionView::class.java,
            "a",
            arrayOf(MessageReaction::class.java, LONG, BOOLEAN),
            Hook { frame ->
                val reaction = frame.args[0] as? MessageReaction ?: return@Hook
                val key = reaction.b()?.c() ?: return@Hook
                val messageId = frame.args[1] as Long
                val count = getSuperReactionCount(messageId, key)
                val type = getExpandedReactionType(messageId, reaction)
                val isSuper = type ?: ((count ?: 0) > 0)
                val view = frame.thisObject as ReactionView
                val style = reactionStyles.getOrPut(view) { ReactionStyle(view) }
                reactionBindings[view] = ReactionBinding(messageId, reaction, type)
                setReactionMeState(view, messageId, reaction, isSuper)
                styleReactionView(view, messageId, key, isSuper, count)
            },
        )
    }

    private fun patchReactionToggles() {
        patcher.patch(
            WidgetChatListAdapterEventsHandler.UserReactionHandler::class.java,
            "toggleReaction",
            arrayOf(LONG, LONG, LONG, MessageReaction::class.java),
            PreHook { frame ->
                val reaction = frame.args[3] as? MessageReaction ?: return@PreHook
                val emoji = reaction.b() ?: return@PreHook
                val key = emoji.c()
                val channelId = frame.args[1] as Long
                val messageId = frame.args[2] as Long
                if (actionKey(messageId, key) in pendingReactionActions ||
                    isPendingSuperReactionRemoval(messageId, key)
                ) {
                    frame.result = null
                    return@PreHook
                }
                val type = getExpandedReactionType(messageId, reaction)
                val isSuper = type ?: isSuperReaction(messageId, key)
                if (type == false || (type == null && !isSuper)) {
                    // Discord's toggleReaction queues taps behind a 250 ms throttle.
                    // Its own update method still performs the optimistic store update,
                    // REST request, and rollback, but starts immediately.
                    clearCompletedSuperReactionRemoval(messageId, key)
                    if (requestNormalReactionImmediately(
                            frame.thisObject as WidgetChatListAdapterEventsHandler.UserReactionHandler,
                            frame.args[0] as Long,
                            channelId,
                            messageId,
                            reaction,
                        )
                    ) frame.result = null
                } else if (!isMetadataCurrent(messageId)) {
                    frame.result = null
                    resolveReactionAndToggle(channelId, messageId, reaction, type)
                } else if (isSuper) {
                    frame.result = null
                    if (isOwnSuperReaction(messageId, key)) {
                        removeSuperReaction(channelId, messageId, emoji)
                    } else {
                        sendSuperReaction(channelId, messageId, emoji)
                    }
                } else {
                    clearCompletedSuperReactionRemoval(messageId, key)
                }
            },
        )
        patcher.patch(
            StoreMessageReactions::class.java,
            "deleteEmoji",
            arrayOf(LONG, LONG, MessageReactionEmoji::class.java, LONG),
            PreHook { frame ->
                val channelId = frame.args[0] as Long
                val messageId = frame.args[1] as Long
                val emoji = frame.args[2] as? MessageReactionEmoji ?: return@PreHook
                val key = emoji.c()
                val type =
                    manageReactionTypes[manageReactionKey(channelId, messageId, key)] ?: isSuperReaction(messageId, key)
                if (type && frame.args[3] == getCurrentUserId() && isOwnSuperReaction(messageId, key)) {
                    frame.result = null
                    removeSuperReaction(channelId, messageId, emoji)
                }
            },
        )
    }

    private fun requestNormalReactionImmediately(
        handler: WidgetChatListAdapterEventsHandler.UserReactionHandler,
        userId: Long,
        channelId: Long,
        messageId: Long,
        reaction: MessageReaction,
    ): Boolean = try {
        nativeReactionUpdateMethod.invoke(
            handler,
            WidgetChatListAdapterEventsHandler.UserReactionHandler.UpdateRequest(
                userId, channelId, messageId, reaction,
            ),
        )
        true
    } catch (error: Throwable) {
        logger.error("Could not start normal reaction immediately", error)
        false
    }

    private fun patchManageReactions() {
        patcher.patch(
            WidgetManageReactions.Companion::class.java,
            "create",
            arrayOf(LONG, LONG, Context::class.java, MessageReaction::class.java),
            Hook { frame ->
                val reaction = frame.args[3] as? MessageReaction ?: return@Hook
                val key = reaction.b()?.c() ?: return@Hook
                val messageId = frame.args[1] as Long
                getExpandedReactionType(messageId, reaction)?.let {
                    manageReactionTypes[manageReactionKey(frame.args[0] as Long, messageId, key)] = it
                }
            },
        )
        patcher.patch(
            ManageReactionsEmojisAdapter.ReactionEmojiItem::class.java,
            "getKey",
            emptyArray(),
            Hook { frame ->
                val item = frame.thisObject as ManageReactionsEmojisAdapter.ReactionEmojiItem
                val type = getManageReactionItemType(item) ?: return@Hook
                val key = item.reaction.b()?.c() ?: return@Hook
                frame.result = key + if (type) ":super" else ":normal"
            },
        )
        patcher.patch(
            ManageReactionsEmojisAdapter.ReactionEmojiViewHolder::class.java,
            "onConfigure",
            arrayOf(INT, ManageReactionsEmojisAdapter.ReactionEmojiItem::class.java),
            Hook { frame ->
                val holder = frame.thisObject as ManageReactionsEmojisAdapter.ReactionEmojiViewHolder
                val item = frame.args[1] as? ManageReactionsEmojisAdapter.ReactionEmojiItem ?: return@Hook
                val type = getManageReactionItemType(item) ?: return@Hook
                val emoji = item.reaction.b() ?: return@Hook
                val adapter = holder.adapter
                val widget = getManageReactionWidget(adapter) ?: return@Hook
                val listener = adapter.onEmojiSelectedListener ?: return@Hook
                val intent = widget.mostRecentIntent
                val channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, 0L)
                val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0L)
                val key = emoji.c()
                holder.itemView.setOnClickListener {
                    synchronized(manageReactionWidgetTypes) { manageReactionWidgetTypes[widget] = type }
                    manageReactionTypes[manageReactionKey(channelId, messageId, key)] = type
                    selectManageReaction(widget, channelId, messageId, emoji, type)
                    listener(key)
                }
            },
        )
        patcher.patch(
            WidgetManageReactions::class.java,
            "onViewBound",
            arrayOf(View::class.java),
            Hook { frame ->
                registerManageReactionAdapter(frame.thisObject as WidgetManageReactions)
            },
        )
        patcher.patch(
            WidgetManageReactions::class.java,
            "configureUI",
            arrayOf(ManageReactionsModel::class.java),
            PreHook { frame ->
                val widget = frame.thisObject as WidgetManageReactions
                val model = frame.args[0] as? ManageReactionsModel
                registerManageReactions(widget, model)
                if (model == null) return@PreHook
                val target = getManageReactionTarget(widget) ?: return@PreHook
                val reactions = expandManageReactionItems(model.reactionItems, target)
                normalReactionItems[normalCacheKey(target)] = model.userItems.toList()
                val users = if (target.superReaction) {
                    burstReactionUsers[target.cacheKey()]?.let { createManageReactionItems(target, it) }
                        ?: emptyList()
                } else {
                    model.userItems
                }
                frame.args[0] = ManageReactionsModel(reactions, users)
            },
        )
    }

    private fun addSuperReactionButton(actions: WidgetChatListActions, root: View) {
        if (actions in superReactionButtons) return
        val container = root.findViewById<View>(Utils.getResId(ACTIONS_CONTAINER_ID, "id")) as? LinearLayout ?: return
        root.findViewById<View>(Utils.getResId(ADD_REACTION_LIST_ID, "id")) ?: return
        val template = root.findViewById<View>(Utils.getResId(MANAGE_REACTIONS_ID, "id")) as? TextView ?: return
        val button = TextView(root.context).apply {
            text = "Super React"
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(template.textColors)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, template.textSize)
            typeface = template.typeface
            includeFontPadding = template.includeFontPadding
            compoundDrawablePadding = template.compoundDrawablePadding
            setPadding(template.paddingLeft, template.paddingTop, template.paddingRight, template.paddingBottom)
            cloneDrawable(template.compoundDrawablesRelative[0])?.let {
                setCompoundDrawablesRelativeWithIntrinsicBounds(it, null, null, null)
                TextViewCompat.setCompoundDrawableTintList(this, TextViewCompat.getCompoundDrawableTintList(template))
            }
            cloneDrawable(template.background)?.let { background = it }
            contentDescription = "Super React"
            visibility = View.GONE
        }
        val reference = WeakReference(actions)
        button.setOnClickListener { reference.get()?.let(::openSuperReactionPicker) }
        val params = template.layoutParams?.let { LinearLayout.LayoutParams(it) }
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        container.addView(button, params)
        superReactionButtons[actions] = button
    }

    private fun cloneDrawable(drawable: Drawable?): Drawable? =
        drawable?.let { (it.constantState?.newDrawable() ?: it).mutate() }

    private fun request(
        route: String,
        method: String,
        priority: Int,
        success: (DiscordRequestQueue.Response) -> Unit,
        failure: (Throwable) -> Unit,
    ) {
        val queue = requests ?: return
        if (!running) return
        queue.enqueue(route, method, priority, { response ->
            mainHandler.post {
                if (running && requests === queue) success(response)
            }
        }, { error ->
            mainHandler.post {
                if (running && requests === queue) failure(error)
            }
        })
    }

    private fun httpError(response: DiscordRequestQueue.Response) = IllegalStateException("HTTP ${response.status}")

    private fun showRequestError(action: String, error: Throwable) {
        logger.error("Could not $action", error)
        Utils.showToast(
            if (error.message?.contains("rate limited") == true) {
                "Discord is rate limited. Please try again shortly."
            } else {
                "Could not $action"
            },
        )
    }

    private fun actionKey(messageId: Long, key: String?) =
        localReactionKey(messageId, normalizeReactionKey(displayReactionKey(key)))

    private fun beginReactionAction(messageId: Long, key: String?): Boolean {
        if (!running || key == null || isPendingSuperReactionRemoval(messageId, key)) return false
        if (!pendingReactionActions.add(actionKey(messageId, key))) return false
        superReactionInvalidationVersions.merge(messageId, 1L, Long::plus)
        applyVisibleReactionStyles(messageId)
        return true
    }

    private fun finishReactionAction(messageId: Long, key: String?) {
        pendingReactionActions.remove(actionKey(messageId, key))
        applyVisibleReactionStyles(messageId)
    }

    private fun hasNitro(): Boolean = try {
        UserUtils.INSTANCE.isPremium(StoreStream.getUsers().me)
    } catch (_: Throwable) {
        false
    }

    private fun registerManageReactions(widget: WidgetManageReactions, model: ManageReactionsModel?) {
        if (model == null) {
            synchronized(activeManageReactions) { activeManageReactions.remove(widget) }
            return
        }
        val intent = widget.mostRecentIntent
        val channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, 0L)
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0L)
        if (channelId == 0L || messageId == 0L) return
        registerManageReactionAdapter(widget)
        val selected = model.reactionItems.firstOrNull { it.isSelected && it.reaction.b() != null }
        var emoji = selected?.reaction?.b()
        var key = emoji?.c()
        var type = selected?.let(::getManageReactionItemType)
        if (key == null) {
            key = intent.getStringExtra(MANAGE_REACTIONS_EMOJI_ARGUMENT)
            emoji = model.reactionItems.firstOrNull { key != null && it.reaction.b()?.c() == key }?.reaction?.b()
        }
        if (emoji == null || key == null) return
        if (type == null) type = synchronized(manageReactionWidgetTypes) { manageReactionWidgetTypes[widget] }
        if (type == null) type = manageReactionTypes[manageReactionKey(channelId, messageId, key)]
        val target = ManageReactionTarget(channelId, messageId, key, emoji, type ?: isSuperReaction(messageId, key))
        synchronized(activeManageReactions) { activeManageReactions[widget] = target }
        if (target.superReaction) fetchBurstReactionUsers(target)
        fetchSuperReactionMetadata(channelId, messageId)
    }

    private fun normalCacheKey(target: ManageReactionTarget) = "${target.messageId}:${target.reactionKey}:normal"

    private fun manageReactionKey(channelId: Long, messageId: Long, key: String?) = "$channelId:$messageId:$key"

    private fun selectManageReaction(
        widget: WidgetManageReactions,
        channelId: Long,
        messageId: Long,
        emoji: MessageReactionEmoji,
        isSuper: Boolean,
    ) {
        val key = emoji.c()
        manageReactionTypes[manageReactionKey(channelId, messageId, key)] = isSuper
        synchronized(manageReactionWidgetTypes) { manageReactionWidgetTypes[widget] = isSuper }
        val target = ManageReactionTarget(channelId, messageId, key, emoji, isSuper)
        synchronized(activeManageReactions) { activeManageReactions[widget] = target }
        refreshManageReactionTabs(widget, target)
        if (isSuper) {
            setManageReactionResults(widget, emptyList())
            fetchBurstReactionUsers(target)
        } else {
            setManageReactionResults(widget, normalReactionItems[normalCacheKey(target)]?.toList() ?: emptyList())
        }
    }

    private fun getWidgetAdapter(widget: WidgetManageReactions, field: String): Any? =
        WidgetManageReactions::class.java.getDeclaredField(field).apply { isAccessible = true }.get(widget)

    private fun registerManageReactionAdapter(widget: WidgetManageReactions) {
        try {
            val adapter = getWidgetAdapter(widget, "emojisAdapter") as? ManageReactionsEmojisAdapter ?: return
            synchronized(activeManageEmojiAdapters) { activeManageEmojiAdapters[adapter] = WeakReference(widget) }
        } catch (_: Throwable) {
            // Keep this hook optional for older client variants.
        }
    }

    private fun getManageReactionWidget(adapter: ManageReactionsEmojisAdapter): WidgetManageReactions? {
        synchronized(activeManageEmojiAdapters) { activeManageEmojiAdapters[adapter]?.get()?.let { return it } }
        synchronized(activeManageReactions) {
            for (widget in activeManageReactions.keys) {
                try {
                    if (getWidgetAdapter(widget, "emojisAdapter") === adapter) return widget
                } catch (
                    _: Throwable,
                ) {
                    return null
                }
            }
        }
        return null
    }

    private fun getManageReactionItemType(item: ManageReactionsEmojisAdapter.ReactionEmojiItem) =
        manageReactionItemTypes[item]

    private fun expandManageReactionItems(
        source: List<ManageReactionsEmojisAdapter.ReactionEmojiItem>,
        target: ManageReactionTarget,
    ): List<ManageReactionsEmojisAdapter.ReactionEmojiItem> {
        val selectedTypes = mutableMapOf<String, Boolean>()
        for (item in source) {
            if (!item.isSelected) continue
            val key = item.reaction.b()?.c() ?: continue
            selectedTypes[key] = if (target.reactionKey == key) {
                target.superReaction
            } else {
                getManageReactionItemType(item)
                    ?: manageReactionTypes[manageReactionKey(target.channelId, target.messageId, key)]
                    ?: false
            }
        }
        selectedTypes.putIfAbsent(target.reactionKey, target.superReaction)
        val result = mutableListOf<ManageReactionsEmojisAdapter.ReactionEmojiItem>()
        val seen = mutableSetOf<String>()

        fun add(count: Int, emoji: MessageReactionEmoji, me: Boolean, selected: Boolean, superReaction: Boolean) {
            val item = ManageReactionsEmojisAdapter.ReactionEmojiItem(MessageReaction(count, emoji, me), selected)
            manageReactionItemTypes.put(item, superReaction)
            result.add(item)
        }
        for (item in source) {
            val reaction = item.reaction
            val emoji = reaction.b() ?: continue
            val key = emoji.c()
            if (!seen.add(key)) continue
            var bursts = getSuperReactionCount(target.messageId, key)
            var normal = getNormalReactionCount(target.messageId, key)
            if (normal == null && bursts != null && bursts > 0 && reaction.a() > bursts) normal = reaction.a() - bursts
            // Keep a selected burst tab while the first metadata lookup is pending.
            if (!isMetadataCurrent(target.messageId) &&
                target.superReaction &&
                target.reactionKey == key &&
                (bursts ?: 0) <= 0
            ) {
                bursts = 1
            }
            if ((bursts ?: 0) <= 0 && (normal == 0 || (normal == null && isMetadataCurrent(target.messageId)))) continue
            val selected = key in selectedTypes
            val selectedSuper = selectedTypes[key] == true
            if (bursts != null && bursts > 0) {
                add(bursts, emoji, false, selectedSuper, true)
                if (normal != null && normal > 0) add(normal, emoji, reaction.c(), selected && !selectedSuper, false)
            } else {
                add(normal ?: reaction.a(), emoji, reaction.c(), selected && !selectedSuper, false)
            }
        }
        return result
    }

    private fun isSuperReaction(messageId: Long, key: String?) = (getSuperReactionCount(messageId, key) ?: 0) > 0

    private fun resolveReactionAndToggle(
        channelId: Long,
        messageId: Long,
        reaction: MessageReaction,
        preferredType: Boolean?,
    ) {
        val emoji = reaction.b() ?: return
        if (channelId == 0L || messageId == 0L) return
        val key = emoji.c()
        if (!beginReactionAction(messageId, key)) return
        Utils.showToast("Loading reaction…")
        // One lookup resolves every emoji, including ownership beyond the first 100 users.
        fetchSuperReactionMetadata(channelId, messageId) { success ->
            if (!success) {
                finishReactionAction(messageId, key)
                Utils.showToast("Could not load this reaction. Please try again.")
                return@fetchSuperReactionMetadata
            }
            if (preferredType ?: isSuperReaction(messageId, key)) {
                when {
                    isOwnSuperReaction(messageId, key) -> {
                        performRemoveSuperReaction(channelId, messageId, emoji)
                    }

                    hasNitro() -> {
                        performSendSuperReaction(channelId, messageId, getReactionApiKey(emoji), 0)
                    }

                    else -> {
                        finishReactionAction(messageId, key)
                        Utils.showToast("Nitro is required to add a Super Reaction")
                    }
                }
            } else {
                sendNormalReaction(channelId, messageId, emoji, reaction.c())
            }
        }
    }

    private fun sendNormalReaction(channelId: Long, messageId: Long, emoji: MessageReactionEmoji, remove: Boolean) {
        val apiKey = getReactionApiKey(emoji)
        if (apiKey.isEmpty()) {
            finishReactionAction(messageId, emoji.c())
            return
        }
        clearCompletedSuperReactionRemoval(messageId, emoji.c())
        request(
            reactionRoute(
                channelId,
                messageId,
                apiKey,
            ) +
                "/@me",
            if (remove) "DELETE" else "PUT",
            DiscordRequestQueue.ACTION,
            { response ->
                finishReactionAction(messageId, emoji.c())
                if (!response.ok()) {
                    showRequestError("update reaction", httpError(response))
                } else {
                    localMutationVersions.merge(messageId, 1L, Long::plus)
                    invalidateSuperReactionCache(messageId, channelId)
                }
            },
            { error ->
                finishReactionAction(messageId, emoji.c())
                showRequestError("update reaction", error)
            },
        )
    }

    private fun fetchBurstReactionUsers(target: ManageReactionTarget) {
        val cacheKey = target.cacheKey()
        burstReactionUsers[cacheKey]?.let {
            updateManageReactionResults(target, it)
            return
        }
        if (!running || !burstUserFetches.add(cacheKey)) return
        val version = superReactionInvalidationVersions[target.messageId] ?: 0L
        request(
            reactionRoute(target.channelId, target.messageId, getReactionApiKey(target.emoji)) + "?limit=100&type=1",
            "GET",
            DiscordRequestQueue.USERS,
            { response ->
                burstUserFetches.remove(cacheKey)
                if (!response.ok()) {
                    logger.error("Could not load Super Reaction users", httpError(response))
                    return@request
                }
                try {
                    val users = parseBurstReactionUsers(response.body)
                    val dirty = version != (superReactionInvalidationVersions[target.messageId] ?: 0L)
                    if (!dirty) burstReactionUsers[cacheKey] = users
                    updateManageReactionResults(target, users)
                    if (dirty) {
                        mainHandler.postDelayed({
                            if (running &&
                                synchronized(activeManageReactions) { activeManageReactions.containsValue(target) }
                            ) {
                                fetchBurstReactionUsers(target)
                            }
                        }, METADATA_MIN_INTERVAL_MS)
                    }
                } catch (error: Throwable) {
                    logger.error("Could not read Super Reaction users", error)
                }
            },
            { error ->
                burstUserFetches.remove(cacheKey)
                logger.error("Could not load Super Reaction users", error)
            },
        )
    }

    private fun reactionRoute(channelId: Long, messageId: Long, apiKey: String) =
        "/channels/$channelId/messages/$messageId/reactions/${Uri.encode(apiKey)}"

    private fun getReactionApiKey(emoji: MessageReactionEmoji): String =
        if (emoji.e() && emoji.b() != null) "${emoji.d() ?: ""}:${emoji.b()}" else emoji.d() ?: emoji.c()

    private fun parseBurstReactionUsers(body: String): List<User> {
        val raw: List<*>? = GsonUtils.gson.fromJson(body, List::class.java)
        return raw?.mapNotNull { (it as? Map<*, *>)?.let(::createCoreUser) } ?: emptyList()
    }

    private fun createCoreUser(raw: Map<*, *>): User? {
        val id = longValue(raw["id"]) ?: return null
        return CoreUser(
            id,
            raw["username"]?.toString()?.takeIf { it.isNotEmpty() } ?: "Unknown user",
            raw["avatar"]?.toString(),
            raw["banner"]?.toString(),
            raw["bot"] == true,
            raw["system"] == true,
            intValue(raw["discriminator"]),
            PremiumTier.NONE,
            intValue(raw["flags"]),
            intValue(raw["public_flags"]),
            raw["bio"]?.toString(),
            raw["banner_color"]?.toString(),
        )
    }

    private fun longValue(value: Any?): Long? = (value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull()

    private fun intValue(value: Any?): Int = (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull() ?: 0

    private fun updateManageReactionResults(target: ManageReactionTarget, users: List<User>) {
        synchronized(activeManageReactions) {
            activeManageReactions
                .filterValues {
                    it == target
                }.keys
                .forEach { setManageReactionResults(it, createManageReactionItems(target, users)) }
        }
    }

    private fun createManageReactionItems(
        target: ManageReactionTarget,
        users: List<User>,
    ): List<MGRecyclerDataPayload> = users.map {
        ManageReactionsResultsAdapter.ReactionUserItem(
            it,
            target.channelId,
            target.messageId,
            target.emoji,
            StoreStream.getUsers().me.id == it.id,
            null,
        )
    }

    private fun getManageReactionTarget(widget: WidgetManageReactions) = synchronized(activeManageReactions) {
        activeManageReactions[widget]
    }

    private fun setManageReactionResults(widget: WidgetManageReactions, items: List<MGRecyclerDataPayload>) {
        try {
            (getWidgetAdapter(widget, "resultsAdapter") as? ManageReactionsResultsAdapter)?.setData(items)
        } catch (
            error: Throwable,
        ) {
            logger.error("Could not update Super Reaction users", error)
        }
    }

    private fun isMetadataCurrent(messageId: Long) = superReactionFetchTimes[messageId]?.let {
        SystemClock.elapsedRealtime() - it < SUPER_REACTION_CACHE_TTL
    } ?: false

    /** Load the channel's current message window, including burst reaction details. */
    private fun prefetchChannelSuperReactionMetadata(channelId: Long, entries: List<ReactionsEntry>) {
        if (!running || channelId == 0L || entries.isEmpty()) return
        val messageIds = entries.mapNotNull { it.message?.id }.filter { it != 0L }.distinct().sorted()
        messageIds.forEach { reactionChannels[it] = channelId }
        val missing = messageIds.filterNot(::isMetadataCurrent)
        if (missing.isEmpty() || !channelBatchFetches.add(channelId)) return
        batchPendingMessages.addAll(missing)
        val versions = missing.associateWith {
            Pair(localMutationVersions[it] ?: 0L, superReactionInvalidationVersions[it] ?: 0L)
        }
        val anchor = messageIds[messageIds.size / 2]
        request(
            "/channels/$channelId/messages?around=$anchor&limit=50",
            "GET",
            DiscordRequestQueue.METADATA,
            { response ->
                channelBatchFetches.remove(channelId)
                if (!response.ok()) {
                    completeChannelBatch(channelId, missing, versions, emptyMap())
                    return@request
                }
                try {
                    val messages = GsonUtils.gson.fromJson(response.body, List::class.java)
                        .filterIsInstance<Map<*, *>>()
                        .associateBy { it["id"]?.toString() }
                    completeChannelBatch(channelId, missing, versions, messages)
                } catch (error: Throwable) {
                    logger.error("Could not read channel Super Reaction metadata", error)
                    completeChannelBatch(channelId, missing, versions, emptyMap())
                }
            },
            { error ->
                channelBatchFetches.remove(channelId)
                logger.error("Could not load channel Super Reaction metadata", error)
                completeChannelBatch(channelId, missing, versions, emptyMap())
            },
        )
    }

    private fun completeChannelBatch(
        channelId: Long,
        messageIds: List<Long>,
        versions: Map<Long, Pair<Long, Long>>,
        messages: Map<String?, Map<*, *>>,
    ) {
        messageIds.forEach { messageId ->
            batchPendingMessages.remove(messageId)
            val message = messages[messageId.toString()]
            val reactions = message?.get("reactions") as? List<*>
            val hasDetails = reactions != null && reactions.all {
                val reaction = it as? Map<*, *>
                reaction?.get("count_details") is Map<*, *> || reaction?.containsKey("burst_count") == true
            }
            val unchanged = versions[messageId] == Pair(
                localMutationVersions[messageId] ?: 0L,
                superReactionInvalidationVersions[messageId] ?: 0L,
            )
            if (message != null && hasDetails && unchanged) {
                try {
                    val hadBurst = superReactionCounts[messageId]?.values?.any { it > 0 } == true
                    val bursts = parseSuperReactionCounts(messageId, GsonUtils.gson.toJson(message))
                    superReactionCounts[messageId] = ConcurrentHashMap(bursts)
                    superReactionFetchTimes[messageId] = SystemClock.elapsedRealtime()
                    if (hadBurst || bursts.values.any { it > 0 }) refreshSuperReactionStyles(messageId)
                    refreshManageReactionUsers(messageId)
                    completeMetadataWaiters(messageId, true)
                    return@forEach
                } catch (error: Throwable) {
                    logger.error("Could not read Super Reaction details", error)
                }
            }
            // A message outside this window, or lacking burst fields, needs its own lookup.
            fetchSuperReactionMetadata(channelId, messageId)
        }
    }

    private fun fetchSuperReactionMetadata(channelId: Long, messageId: Long, callback: ((Boolean) -> Unit)? = null) {
        if (!running || channelId == 0L || messageId == 0L) {
            callback?.invoke(false)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (isMetadataCurrent(messageId) && superReactionCounts.containsKey(messageId)) {
            callback?.invoke(true)
            return
        }
        callback?.let { metadataWaiters.getOrPut(messageId) { mutableListOf() }.add(it) }
        val route = "/channels/$channelId/messages?around=$messageId&limit=1"
        if (messageId in superReactionFetches) {
            if (callback != null) requests?.prioritize(route, "GET")
            return
        }
        val nextAttempt = metadataAttempts[messageId] ?: 0L
        if (now < nextAttempt) {
            scheduleMetadataRefresh(channelId, messageId, nextAttempt - now)
            return
        }
        superReactionFetches.add(messageId)
        metadataAttempts[messageId] = now + METADATA_MIN_INTERVAL_MS
        val version = superReactionInvalidationVersions[messageId] ?: 0L
        val mutationVersion = localMutationVersions[messageId] ?: 0L
        request(
            route,
            "GET",
            if (messageId in
                metadataWaiters
            ) {
                DiscordRequestQueue.ACTION
            } else {
                DiscordRequestQueue.METADATA
            },
            { response ->
                superReactionFetches.remove(messageId)
                if (!response.ok()) {
                    metadataFailed(channelId, messageId, httpError(response))
                    return@request
                }
                if (mutationVersion != (localMutationVersions[messageId] ?: 0L)) {
                    scheduleMetadataRefresh(channelId, messageId, METADATA_REFRESH_DELAY_MS)
                    return@request
                }
                val dirty = version != (superReactionInvalidationVersions[messageId] ?: 0L)
                try {
                    val body = extractMessageFromList(response.body, messageId) ?: error("Message was not returned")
                    val hadBurst = superReactionCounts[messageId]?.values?.any { it > 0 } == true
                    val bursts = parseSuperReactionCounts(messageId, body)
                    superReactionCounts[messageId] = ConcurrentHashMap(bursts)
                    superReactionFetchTimes[messageId] = SystemClock.elapsedRealtime()
                    if (hadBurst || bursts.values.any { it > 0 }) refreshSuperReactionStyles(messageId)
                    refreshManageReactionUsers(messageId)
                    completeMetadataWaiters(messageId, true)
                    // Publish useful snapshots during continuous activity, then follow up.
                    if (dirty) {
                        superReactionFetchTimes.remove(messageId)
                        scheduleMetadataRefresh(channelId, messageId, METADATA_REFRESH_DELAY_MS)
                    }
                } catch (error: Throwable) {
                    metadataFailed(channelId, messageId, error)
                }
            },
            { error ->
                superReactionFetches.remove(messageId)
                metadataFailed(channelId, messageId, error)
            },
        )
    }

    private fun metadataFailed(channelId: Long, messageId: Long, error: Throwable) {
        metadataAttempts[messageId] = SystemClock.elapsedRealtime() + METADATA_FAILURE_DELAY_MS
        logger.error("Could not load Super Reaction metadata", error)
        completeMetadataWaiters(messageId, false)
        scheduleMetadataRefresh(channelId, messageId, METADATA_FAILURE_DELAY_MS)
    }

    private fun completeMetadataWaiters(messageId: Long, success: Boolean) {
        metadataWaiters.remove(messageId)?.forEach { it(success) }
    }

    private fun scheduleMetadataRefresh(channelId: Long, messageId: Long, delay: Long) {
        metadataRefreshes.remove(messageId)?.let(mainHandler::removeCallbacks)
        val refresh = Runnable {
            metadataRefreshes.remove(messageId)
            if (running &&
                (messageId in metadataWaiters || isMessageVisible(messageId))
            ) {
                fetchSuperReactionMetadata(channelId, messageId)
            }
        }
        metadataRefreshes[messageId] = refresh
        mainHandler.postDelayed(refresh, delay)
    }

    private fun isMessageVisible(messageId: Long): Boolean {
        synchronized(visibleReactionItems) {
            if (visibleReactionItems.any { (item, id) ->
                    id == messageId && item.itemView.isAttachedToWindow
                }
            ) {
                return true
            }
        }
        return synchronized(activeManageReactions) { activeManageReactions.values.any { it.messageId == messageId } }
    }

    private fun extractMessageFromList(body: String, messageId: Long): String? {
        val decoded = GsonUtils.gson.fromJson(body, Any::class.java)
        if (decoded is Map<*, *>) return body
        val messages = decoded as? List<*> ?: return null
        return messages
            .filterIsInstance<Map<*, *>>()
            .firstOrNull {
                it["id"]?.toString() == messageId.toString()
            }?.let { GsonUtils.gson.toJson(it) }
    }

    private fun parseSuperReactionCounts(messageId: Long, body: String): Map<String, Int> {
        val metadata = ReactionMetadata.parse(GsonUtils.gson.fromJson(body, Map::class.java))
        val colors = ConcurrentHashMap<String, Int>()
        metadata.colors.forEach { (key, raw) ->
            parseBurstColor(raw)?.let {
                colors[key] = it
                colors[normalizeReactionKey(key)!!] = it
            }
        }
        val ownKeys = metadata.owned.flatMap { reactionStateKeys(messageId, it) }.toSet()
        normalReactionCounts[messageId] = ConcurrentHashMap(metadata.normal)
        superReactionColors[messageId] = colors
        val prefix = "$messageId:"
        var changed = false
        ownedSuperReactions.filter { it.startsWith(prefix) && it !in ownKeys }.forEach {
            if (ownedSuperReactions.remove(it)) {
                changed =
                    true
            }
        }
        if (ownedSuperReactions.addAll(ownKeys)) changed = true
        locallySentSuperReactions.removeIf { it.startsWith(prefix) }
        if (changed) persistOwnedSuperReactions()
        return metadata.bursts
    }

    private fun expandReactions(reactions: Collection<*>?, messageId: Long): List<MessageReaction> {
        val expanded = mutableListOf<MessageReaction>()
        val types = IdentityHashMap<MessageReaction, Boolean>()
        if (reactions == null) return expanded
        for (reaction in reactions.filterIsInstance<MessageReaction>()) {
            val emoji = reaction.b()
            val key = emoji?.c()
            val burstCount = getSuperReactionCount(messageId, key)
            val normalCount = getNormalReactionCount(messageId, key)
            if (burstCount != null && burstCount > 0) {
                val burst = MessageReaction(burstCount, emoji, false)
                expanded.add(burst)
                types[burst] = true
                val normals = if (reaction.a() >= burstCount) {
                    reaction.a() - burstCount
                } else {
                    normalCount ?: 0
                }
                if (normals > 0) {
                    val normal = MessageReaction(normals, emoji, reaction.c())
                    expanded.add(normal)
                    types[normal] = false
                }
            } else {
                // Discord updates this reaction and its `me` bit optimistically. The
                // snapshot's normal count can already be stale after a tap.
                expanded.add(reaction)
                if (isMetadataCurrent(messageId)) types[reaction] = false
            }
        }
        expandedReactionTypes[messageId] = types
        return expanded
    }

    private fun parseBurstColor(raw: Any?): Int? {
        val colors = raw as? List<*> ?: return null
        var best: Int? = null
        var bestScore = -1f
        // Match Discord's choice of the most vivid of the first three palette entries.
        for (value in colors.take(3)) {
            if (value == null) continue
            try {
                val color = Color.parseColor(value.toString())
                val hsv = FloatArray(3)
                Color.colorToHSV(color, hsv)
                val score = hsv[1] + hsv[2]
                if (score > bestScore) {
                    bestScore = score
                    best = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
                }
            } catch (_: Throwable) {
                // Use the next valid palette entry.
            }
        }
        return best
    }

    private fun cachedCount(counts: Map<String, Int>?, key: String?): Int? =
        if (key == null) null else counts?.get(key) ?: counts?.get(normalizeReactionKey(key))

    private fun getSuperReactionCount(messageId: Long, key: String?): Int? {
        if (key == null) return null
        // Prefer the server total to the local send fallback so a count never resets to one.
        return cachedCount(superReactionCounts[messageId], key)
            ?: if (reactionStateKeys(messageId, key).any { it in locallySentSuperReactions }) 1 else null
    }

    private fun getNormalReactionCount(messageId: Long, key: String?) =
        cachedCount(normalReactionCounts[messageId], key)

    private fun getExpandedReactionType(messageId: Long, reaction: MessageReaction): Boolean? {
        expandedReactionTypes[messageId]?.get(reaction)?.let { return it }
        // Each visible view retains its type when another holder rebinds the same message.
        return reactionBindings.entries
            .firstOrNull { (view, binding) ->
                binding.messageId == messageId && binding.reaction === reaction && view.visibility == View.VISIBLE
            }?.value
            ?.type
    }

    private fun getSuperReactionColor(messageId: Long, key: String?) =
        cachedCount(superReactionColors[messageId], key) ?: SUPER_REACTION_COLOR

    private fun markOwnedSuperReaction(messageId: Long, key: String?) {
        if (key == null) return
        ownedSuperReactions.add(localReactionKey(messageId, key))
        ownedSuperReactions.add(localReactionKey(messageId, normalizeReactionKey(key)))
        persistOwnedSuperReactions()
    }

    private fun persistOwnedSuperReactions() {
        val preferences = ownedReactionPreferences ?: return
        val key = ownedReactionPreferencesKey ?: return
        preferences.edit().putStringSet(key, HashSet(ownedSuperReactions)).apply()
    }

    private fun isOwnSuperReaction(messageId: Long, key: String?) = key != null &&
        listOf(localReactionKey(messageId, key), localReactionKey(messageId, normalizeReactionKey(key))).any {
            it in ownedSuperReactions || it in locallySentSuperReactions
        }

    private fun getCurrentUserId(): Long = try {
        StoreStream.getUsers().me.id
    } catch (_: Throwable) {
        0L
    }

    private fun localReactionKey(messageId: Long, key: String?) = "$messageId:$key"

    private fun displayReactionKey(key: String?): String? {
        if (key == null) return null
        val separator = key.lastIndexOf(':')
        if (separator > 0 && separator < key.length - 1) {
            val id = key.substring(separator + 1)
            if (id.toLongOrNull() != null) return id
        }
        return key
    }

    private fun normalizeReactionKey(key: String?) = key?.replace("\uFE0F", "")

    private fun invalidateSuperReactionCache(messageId: Long, channelId: Long) {
        if (messageId == 0L) return
        val queue = requests
        mainHandler.post {
            if (!running || requests !== queue) return@post
            if (channelId != 0L) reactionChannels[messageId] = channelId
            superReactionInvalidationVersions.merge(messageId, 1L, Long::plus)
            superReactionFetchTimes.remove(messageId)
            val prefix = "$messageId:"
            burstReactionUsers.keys.removeIf { it.startsWith(prefix) }
            normalReactionItems.keys.removeIf { it.startsWith(prefix) }
            // Keep counts, ownership and bound types until their replacement snapshot arrives.
            reactionChannels[messageId]?.let { scheduleMetadataRefresh(it, messageId, METADATA_REFRESH_DELAY_MS) }
        }
    }

    private fun refreshSuperReactionStyles(messageId: Long) {
        synchronized(visibleReactionItems) {
            // Snapshot entries because onConfigure updates this weak map again.
            for ((item, id) in visibleReactionItems.toMap()) {
                if (id != messageId) continue
                val entry = visibleReactionEntries[item]
                val position = visibleReactionPositions[item]
                if (entry != null && position != null) {
                    item.onConfigure(position, entry)
                } else {
                    applySuperReactionStyles(item, messageId)
                }
            }
        }
    }

    private fun applyVisibleReactionStyles(messageId: Long) {
        synchronized(visibleReactionItems) {
            visibleReactionItems.filterValues { it == messageId }.keys.forEach {
                applySuperReactionStyles(
                    it,
                    messageId,
                )
            }
        }
    }

    private fun refreshManageReactionUsers(messageId: Long) {
        synchronized(activeManageReactions) {
            activeManageReactions.filterValues { it.messageId == messageId }.forEach { (widget, target) ->
                refreshManageReactionTabs(widget, target)
                if (target.superReaction) fetchBurstReactionUsers(target)
            }
        }
    }

    private fun refreshManageReactionTabs(widget: WidgetManageReactions, target: ManageReactionTarget) {
        try {
            val adapter = getWidgetAdapter(widget, "emojisAdapter") as? ManageReactionsEmojisAdapter ?: return
            val current = adapter.internalData
            if (current.isNullOrEmpty()) return
            adapter.setData(expandManageReactionItems(current, target))
        } catch (error: Throwable) {
            logger.error("Could not update Super Reaction tabs", error)
        }
    }

    private fun applySuperReactionStyles(item: WidgetChatListAdapterItemReactions, messageId: Long) {
        val views = mutableListOf<ReactionView>()
        collectReactionViews(item.itemView, views)
        for (view in views) {
            val reaction = view.reaction
            val key = reaction?.b()?.c()
            val binding = reactionBindings[view] ?: continue
            if (binding.messageId != messageId || view.visibility != View.VISIBLE) continue
            val count = getSuperReactionCount(messageId, key)
            val isSuper = binding.type ?: ((count ?: 0) > 0)
            setReactionMeState(view, messageId, reaction, isSuper)
            styleReactionView(view, messageId, key, isSuper, count)
        }
    }

    private fun collectReactionViews(view: View, output: MutableList<ReactionView>) {
        when (view) {
            is ReactionView -> {
                output.add(view)
            }

            is ViewGroup -> {
                // Avoid constructing a range backed by Discord's obfuscated primitive iterator.
                var index = 0
                while (index < view.childCount) collectReactionViews(view.getChildAt(index++), output)
            }
        }
    }

    private fun styleReactionView(
        view: ReactionView,
        messageId: Long,
        key: String?,
        isSuper: Boolean,
        burstCount: Int?,
    ) {
        val style = reactionStyles[view] ?: return
        if (!isSuper) {
            if (view.background === style.shine) setReactionBackground(view, cloneDrawable(style.background))
            style.restoreTextColors(view)
            view.contentDescription = style.description
            return
        }
        val color = getSuperReactionColor(messageId, key)
        val owned = isOwnSuperReaction(messageId, key)
        val pending =
            actionKey(messageId, key) in pendingReactionActions || isPendingSuperReactionRemoval(messageId, key)
        style.shine.update(color, owned, pending)
        if (view.background !== style.shine) setReactionBackground(view, style.shine)
        style.tintText(view, color, owned)
        view.contentDescription = "Super reaction, ${burstCount ?: 1} total" +
            (if (owned) ", reacted by you" else "") +
            (if (pending) ", updating" else "")
    }

    private fun blendColors(color: Int, target: Int, amount: Float): Int {
        val fraction = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(color) + (Color.red(target) - Color.red(color)) * fraction).roundToInt(),
            (Color.green(color) + (Color.green(target) - Color.green(color)) * fraction).roundToInt(),
            (Color.blue(color) + (Color.blue(target) - Color.blue(color)) * fraction).roundToInt(),
        )
    }

    private fun setReactionBackground(view: ReactionView, background: Drawable?) {
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        view.background = background
        view.setPadding(left, top, right, bottom)
    }

    private fun setReactionMeState(view: ReactionView, messageId: Long, reaction: MessageReaction?, isSuper: Boolean) {
        val mine = reaction != null && (reaction.c() || (isSuper && isOwnSuperReaction(messageId, reaction.b()?.c())))
        val nativeSelection = mine && !isSuper
        view.isActivated = nativeSelection
        view.isSelected = nativeSelection
        counter(view, "counter_text_1")?.isActivated = nativeSelection
        counter(view, "counter_text_2")?.isActivated = nativeSelection
    }

    private fun counter(view: ReactionView, id: String): TextView? = view.findViewById(Utils.getResId(id, "id"))

    private class ManageReactionTarget(
        val channelId: Long,
        val messageId: Long,
        val reactionKey: String,
        val emoji: MessageReactionEmoji,
        val superReaction: Boolean,
    ) {
        fun cacheKey() = "$messageId:$reactionKey:" + if (superReaction) "super" else "normal"

        override fun equals(other: Any?) = this === other ||
            (
                other is ManageReactionTarget &&
                    channelId == other.channelId &&
                    messageId == other.messageId &&
                    reactionKey == other.reactionKey &&
                    superReaction == other.superReaction
            )

        override fun hashCode(): Int = 31 *
            (31 * (31 * channelId.hashCode() + messageId.hashCode()) + reactionKey.hashCode()) +
            superReaction.hashCode()
    }

    private data class ReactionBinding(
        val messageId: Long,
        val reaction: MessageReaction,
        val type: Boolean?,
    )

    private inner class ReactionStyle(view: ReactionView) {
        val background = cloneDrawable(view.background)
        val description = view.contentDescription
        val shine = SuperReactionDrawable(cloneDrawable(background), view.resources.displayMetrics.density)
        // ReactionView.a() may leave previously tinted TextViews unchanged when this
        // holder is recycled. Capture the native state only on its first binding.
        private val firstColor: ColorStateList? = counter(view, "counter_text_1")?.textColors
        private val secondColor: ColorStateList? = counter(view, "counter_text_2")?.textColors

        fun restoreTextColors(view: ReactionView) {
            firstColor?.let { counter(view, "counter_text_1")?.setTextColor(it) }
            secondColor?.let { counter(view, "counter_text_2")?.setTextColor(it) }
        }

        fun tintText(view: ReactionView, color: Int, owned: Boolean) {
            val amount = if (owned) 0.35f else 0.2f
            firstColor?.let {
                counter(
                    view,
                    "counter_text_1",
                )?.setTextColor(blendColors(it.defaultColor, color, amount))
            }
            secondColor?.let {
                counter(
                    view,
                    "counter_text_2",
                )?.setTextColor(blendColors(it.defaultColor, color, amount))
            }
        }
    }

    private fun openSuperReactionPicker(actions: WidgetChatListActions) {
        val manager = try {
            actions.parentFragmentManager
        } catch (error: Throwable) {
            logger.error("Could not open the Super Reaction picker", error)
            return
        }
        EmojiPickerNavigator.launchBottomSheet(
            manager,
            object : EmojiPickerListener {
                override fun onEmojiPicked(emoji: Emoji) {
                    sendSuperReaction(actions, emoji)
                }
            },
            EmojiPickerContextType.Chat.INSTANCE,
            null,
        )
    }

    private fun sendSuperReaction(actions: WidgetChatListActions, emoji: Emoji) {
        if (!hasNitro()) return
        val arguments = actions.arguments ?: return
        val channelId = arguments.getLong(CHANNEL_ID_ARGUMENT, 0L)
        val messageId = arguments.getLong(MESSAGE_ID_ARGUMENT, 0L)
        val key = emoji.reactionKey
        if (channelId == 0L || messageId == 0L || key.isNullOrEmpty()) return
        actions.dismiss()
        sendSuperReaction(channelId, messageId, key)
    }

    private fun sendSuperReaction(channelId: Long, messageId: Long, emoji: MessageReactionEmoji) =
        sendSuperReaction(channelId, messageId, getReactionApiKey(emoji))

    private fun sendSuperReaction(channelId: Long, messageId: Long, key: String) {
        if (channelId == 0L || messageId == 0L || key.isEmpty()) return
        if (!hasNitro()) {
            Utils.showToast("Nitro is required to add a Super Reaction")
            return
        }
        if (!beginReactionAction(messageId, key)) return
        if (isOwnSuperReaction(messageId, displayReactionKey(key))) {
            finishReactionAction(messageId, key)
            Utils.showToast("You already super reacted with this emoji")
            return
        }
        performSendSuperReaction(channelId, messageId, key, 0)
    }

    private fun performSendSuperReaction(channelId: Long, messageId: Long, key: String, variant: Int) {
        val targets = arrayOf(
            "/@me?location=Message%20Inline%20Button&type=1",
            "/1/@me?location=Message%20Inline%20Button&burst=true",
            "/@me?burst=true",
        )
        request(
            reactionRoute(
                channelId,
                messageId,
                key,
            ) +
                targets[variant],
            "PUT",
            DiscordRequestQueue.ACTION,
            { response ->
                if (!response.ok()) {
                    // Try compatibility routes only when the endpoint itself is unsupported.
                    if (response.status in listOf(404, 405) && variant + 1 < targets.size) {
                        performSendSuperReaction(channelId, messageId, key, variant + 1)
                        return@request
                    }
                    finishReactionAction(messageId, key)
                    showRequestError("send Super Reaction", httpError(response))
                    return@request
                }
                clearCompletedSuperReactionRemoval(messageId, key)
                locallySentSuperReactions.addAll(reactionStateKeys(messageId, key))
                val displayKey = displayReactionKey(key)
                markOwnedSuperReaction(messageId, displayKey)
                localMutationVersions.merge(messageId, 1L, Long::plus)
                finishReactionAction(messageId, key)
                invalidateSuperReactionCache(messageId, channelId)
                Utils.showToast("Super reaction sent")
            },
            { error ->
                finishReactionAction(messageId, key)
                showRequestError("send Super Reaction", error)
            },
        )
    }

    private fun removeSuperReaction(channelId: Long, messageId: Long, emoji: MessageReactionEmoji) {
        if (channelId == 0L || messageId == 0L || !beginReactionAction(messageId, emoji.c())) return
        performRemoveSuperReaction(channelId, messageId, emoji)
    }

    private fun performRemoveSuperReaction(channelId: Long, messageId: Long, emoji: MessageReactionEmoji) {
        val apiKey = getReactionApiKey(emoji)
        val key = emoji.c()
        if (apiKey.isEmpty()) {
            finishReactionAction(messageId, key)
            return
        }
        pendingSuperReactionRemovals.addAll(reactionStateKeys(messageId, key))
        applyVisibleReactionStyles(messageId)
        request(
            reactionRoute(channelId, messageId, apiKey) + "/1/@me?location=Message%20Inline%20Button&burst=true",
            "DELETE",
            DiscordRequestQueue.ACTION,
            { response ->
                if (!response.ok()) {
                    clearPendingSuperReactionRemoval(messageId, key)
                    finishReactionAction(messageId, key)
                    showRequestError("remove Super Reaction", httpError(response))
                    return@request
                }
                removeLocalSuperReaction(messageId, key)
                localMutationVersions.merge(messageId, 1L, Long::plus)
                finishReactionAction(messageId, key)
                invalidateSuperReactionCache(messageId, channelId)
                scheduleLocalSuperReactionRemovalFallback(channelId, messageId, emoji)
                Utils.showToast("Super reaction removed")
            },
            { error ->
                clearPendingSuperReactionRemoval(messageId, key)
                finishReactionAction(messageId, key)
                showRequestError("remove Super Reaction", error)
            },
        )
    }

    private fun reactionStateKeys(messageId: Long, key: String): List<String> = listOf(
        localReactionKey(messageId, key),
        localReactionKey(messageId, normalizeReactionKey(key)),
        localReactionKey(messageId, displayReactionKey(key)),
        localReactionKey(messageId, normalizeReactionKey(displayReactionKey(key))),
    )

    private fun isPendingSuperReactionRemoval(messageId: Long, key: String?) = key != null &&
        reactionStateKeys(messageId, key).any { it in pendingSuperReactionRemovals }

    private fun isCompletedSuperReactionRemoval(messageId: Long, key: String?) = key != null &&
        reactionStateKeys(messageId, key).any { it in completedSuperReactionRemovals }

    private fun clearPendingSuperReactionRemoval(messageId: Long, key: String): Boolean {
        var removed = false
        reactionStateKeys(messageId, key).forEach { if (pendingSuperReactionRemovals.remove(it)) removed = true }
        return removed
    }

    private fun completeSuperReactionRemoval(messageId: Long, key: String) {
        clearPendingSuperReactionRemoval(messageId, key)
        completedSuperReactionRemovals.addAll(reactionStateKeys(messageId, key))
        val action = actionKey(messageId, key)
        val token = Any()
        completedRemovalTokens[action] = token
        mainHandler.postDelayed({
            if (completedRemovalTokens[action] === token) clearCompletedSuperReactionRemoval(messageId, key)
        }, LOCAL_REMOVAL_MARKER_TTL_MS)
    }

    private fun clearCompletedSuperReactionRemoval(messageId: Long, key: String?) {
        if (key == null) return
        completedRemovalTokens.remove(actionKey(messageId, key))
        reactionStateKeys(messageId, key).forEach { completedSuperReactionRemovals.remove(it) }
    }

    private fun scheduleLocalSuperReactionRemovalFallback(
        channelId: Long,
        messageId: Long,
        emoji: MessageReactionEmoji,
    ) {
        mainHandler.postDelayed({
            val key = emoji.c()
            if (!clearPendingSuperReactionRemoval(messageId, key)) return@postDelayed
            completeSuperReactionRemoval(messageId, key)
            try {
                // Some old clients never receive the successful DELETE's gateway event.
                val update = MessageReactionUpdate(
                    if (getCurrentUserId() ==
                        1L
                    ) {
                        2L
                    } else {
                        1L
                    },
                    channelId,
                    messageId,
                    emoji,
                )
                StoreStream.getMessages().handleReactionUpdate(listOf(update), false)
            } catch (error: Throwable) {
                logger.error("Could not update removed Super Reaction locally", error)
            }
        }, LOCAL_REMOVAL_FALLBACK_DELAY_MS)
    }

    private fun removeLocalSuperReaction(messageId: Long, key: String) {
        var changed = false
        reactionStateKeys(messageId, key).forEach {
            if (locallySentSuperReactions.remove(it)) changed = true
            if (ownedSuperReactions.remove(it)) changed = true
        }
        if (changed) persistOwnedSuperReactions()
    }

    override fun stop(context: Context) {
        running = false
        requests?.close()
        requests = null
        patcher.unpatchAll()
        mainHandler.removeCallbacksAndMessages(null)
        reactionStyles.forEach { (view, style) ->
            setReactionBackground(view, cloneDrawable(style.background))
            style.restoreTextColors(view)
            view.contentDescription = style.description
            setReactionMeState(view, 0L, view.reaction, false)
        }
        reactionStyles.clear()
        reactionBindings.clear()
        synchronized(superReactionButtons) {
            superReactionButtons.values.forEach { (it.parent as? ViewGroup)?.removeView(it) }
            superReactionButtons.clear()
        }
        synchronized(visibleReactionItems) {
            visibleReactionItems.keys.toList().forEach { item ->
                val entry = visibleReactionEntries[item]
                val position = visibleReactionPositions[item]
                if (entry != null && position != null) item.onConfigure(position, entry)
            }
            visibleReactionItems.clear()
            visibleReactionEntries.clear()
            visibleReactionPositions.clear()
        }
        synchronized(activeManageReactions) { activeManageReactions.clear() }
        synchronized(activeManageEmojiAdapters) { activeManageEmojiAdapters.clear() }
        synchronized(manageReactionWidgetTypes) { manageReactionWidgetTypes.clear() }
        manageReactionItemTypes.clear()
        gatewayReactionTypes.clear()
        listOf(
            manageReactionTypes,
            reactionChannels,
            superReactionCounts,
            normalReactionCounts,
            superReactionColors,
            expandedReactionTypes,
            superReactionFetchTimes,
            superReactionInvalidationVersions,
            localMutationVersions,
            completedRemovalTokens,
            metadataAttempts,
            metadataRefreshes,
            metadataWaiters,
            burstReactionUsers,
            normalReactionItems,
        ).forEach { it.clear() }
        listOf(
            superReactionFetches,
            channelBatchFetches,
            batchPendingMessages,
            locallySentSuperReactions,
            ownedSuperReactions,
            pendingSuperReactionRemovals,
            completedSuperReactionRemovals,
            pendingReactionActions,
            burstUserFetches,
        ).forEach { it.clear() }
        ownedReactionPreferences = null
        ownedReactionPreferencesKey = null
    }

    private companion object {
        val LONG = Long::class.javaPrimitiveType!!
        val INT = Int::class.javaPrimitiveType!!
        val BOOLEAN = Boolean::class.javaPrimitiveType!!
        const val CHANNEL_ID_ARGUMENT = "INTENT_EXTRA_MESSAGE_CHANNEL_ID"
        const val MESSAGE_ID_ARGUMENT = "INTENT_EXTRA_MESSAGE_ID"
        const val ADD_REACTION_LIST_ID = "dialog_chat_actions_add_reaction_emojis_list"
        const val ACTIONS_CONTAINER_ID = "dialog_chat_actions_container"
        const val MANAGE_REACTIONS_ID = "dialog_chat_actions_manage_reactions"
        const val MANAGE_REACTIONS_EMOJI_ARGUMENT = "com.discord.intent.extra.EXTRA_EMOJI_KEY"
        const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        const val EXTRA_MESSAGE_ID = "com.discord.intent.extra.EXTRA_MESSAGE_ID"
        const val SUPER_REACTION_CACHE_TTL = 5 * 60 * 1000L
        const val METADATA_REFRESH_DELAY_MS = 400L
        const val METADATA_MIN_INTERVAL_MS = 1_500L
        const val METADATA_FAILURE_DELAY_MS = 15_000L
        const val LOCAL_REMOVAL_FALLBACK_DELAY_MS = 2_000L
        const val LOCAL_REMOVAL_MARKER_TTL_MS = 10_000L
        const val OWNED_REACTIONS_PREFERENCES = "owned_super_reactions_"
        val SUPER_REACTION_COLOR = Color.rgb(255, 196, 61)
    }
}
