package com.github.yutaplug.irc

import android.content.Context
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
import androidx.constraintlayout.widget.Guideline
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.spans.ClickableSpan
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.ReactionView
import com.discord.widgets.chat.list.ChatListItemMessageAccessibilityDelegate
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemReactions
import com.discord.widgets.chat.list.adapter.WidgetChatListItem
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.lytefast.flexinput.R
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Compact chat with inline authors and native rich text flowing underneath them. */
@AliucordPlugin(requiresRestart = true)
class IRC : Plugin() {
    private val rows = WeakHashMap<WidgetChatListAdapterItemMessage, RowState>()
    private val messageTexts = WeakHashMap<SimpleDraweeSpanTextView, TextBinding>()
    private val chatGuidelines = WeakHashMap<Guideline, Boolean>()
    private val compactReactionViews = WeakHashMap<ReactionView, Boolean>()
    private val adapterField by lazy {
        MGRecyclerViewHolder::class.java.getDeclaredField("adapter").apply { isAccessible = true }
    }

    private var itemTextId = 0
    private var itemAvatarId = 0
    private var itemNameId = 0
    private var itemTimestampId = 0
    private var headerId = 0
    private var loadingTextId = 0
    private var sendErrorId = 0
    private var replyHolderId = 0
    private var replyLinkId = 0
    private var replyNameId = 0
    private var threadHeaderId = 0
    private var threadSpineId = 0
    private var guidelineId = 0
    private var reactionContainerId = 0
    private var quickAddReactionId = 0
    private var avatarDecorationId: Int? = null
    private var timestampWidthPx = 0

    init {
        settingsTab = SettingsTab(IRCSettings::class.java, SettingsTab.Type.BOTTOM_SHEET).withArgs(settings, this)
    }

    override fun start(context: Context) {
        itemTextId = Utils.getResId("chat_list_adapter_item_text", "id")
        itemAvatarId = Utils.getResId("chat_list_adapter_item_text_avatar", "id")
        itemNameId = Utils.getResId("chat_list_adapter_item_text_name", "id")
        itemTimestampId = Utils.getResId("chat_list_adapter_item_text_timestamp", "id")
        headerId = Utils.getResId("chat_list_adapter_item_text_header", "id")
        loadingTextId = Utils.getResId("chat_list_adapter_item_text_loading", "id")
        sendErrorId = Utils.getResId("chat_list_adapter_item_text_error", "id")
        replyHolderId = Utils.getResId("chat_list_adapter_item_text_decorator", "id")
        replyLinkId = Utils.getResId("chat_list_adapter_item_text_decorator_reply_link_icon", "id")
        replyNameId = Utils.getResId("chat_list_adapter_item_text_decorator_reply_name", "id")
        threadHeaderId = Utils.getResId("thread_starter_message_header", "id")
        threadSpineId = Utils.getResId("chat_list_adapter_item_thread_embed_spine", "id")
        guidelineId = Utils.getResId("uikit_chat_guideline", "id")
        reactionContainerId = Utils.getResId("chat_list_item_reactions", "id")
        quickAddReactionId = Utils.getResId("reaction_quick_add", "id")
        avatarDecorationId = findAvatarDecorationId()
        patchInlineAuthors()

        val configureArgs = arrayOf(Int::class.javaPrimitiveType!!, ChatListEntry::class.java)
        // Attachments and embeds use the same leading column as message text.
        patcher.patch(WidgetChatListItem::class.java, "onConfigure", configureArgs, Hook { frame ->
            val item = frame.thisObject as? WidgetChatListItem ?: return@Hook
            if (item !is WidgetChatListAdapterItemMessage) updateGuideline(item.itemView)
        })
        patcher.patch(WidgetChatListAdapterItemMessage::class.java, "onConfigure", configureArgs, Hook { frame ->
            val item = frame.thisObject as? WidgetChatListAdapterItemMessage ?: return@Hook
            val entry = frame.args[1] as? MessageEntry ?: return@Hook
            configureMessage(item, entry)
        })
        patcher.patch(WidgetChatListAdapterItemReactions::class.java, "onConfigure", configureArgs, Hook { frame ->
            val item = frame.thisObject as? WidgetChatListAdapterItemReactions ?: return@Hook
            val container = item.itemView.findViewById<View>(reactionContainerId) as? ViewGroup ?: return@Hook
            compactReactions(container)
            container.post { if (container.parent != null) compactReactions(container) }
        })
    }

    private fun patchInlineAuthors() {
        val processArgs = arrayOf(SimpleDraweeSpanTextView::class.java, MessageEntry::class.java)
        patcher.patch(WidgetChatListAdapterItemMessage::class.java, "processMessageText", processArgs, PreHook { frame ->
            val text = frame.args[0] as SimpleDraweeSpanTextView
            messageTexts[text] = TextBinding(
                WeakReference(frame.thisObject as WidgetChatListAdapterItemMessage),
                frame.args[1] as MessageEntry,
            )
        })
        // Prefix the original builder before binding, preserving emoji holders and link state.
        patcher.patch(
            SimpleDraweeSpanTextView::class.java,
            "setDraweeSpanStringBuilder",
            arrayOf(DraweeSpanStringBuilder::class.java),
            PreHook { frame ->
                val text = frame.thisObject as SimpleDraweeSpanTextView
                val binding = messageTexts[text] ?: return@PreHook
                val builder = frame.args[0] as? DraweeSpanStringBuilder ?: return@PreHook
                prependAuthor(builder, text, binding)
                text.visibility = View.VISIBLE
            },
        )
        // Keep native spoiler accessibility without announcing the author twice.
        patcher.patch(
            ChatListItemMessageAccessibilityDelegate::class.java.getDeclaredConstructor(
                TextView::class.java, TextView::class.java, TextView::class.java, TextView::class.java,
            ),
            PreHook { frame ->
                if (InlineAuthorText.find((frame.args[0] as TextView).text) != null) frame.args[1] = null
            },
        )
    }

    private fun prependAuthor(text: SpannableStringBuilder, body: TextView, binding: TextBinding) {
        val item = binding.item.get() ?: return
        val root = item.itemView
        val fontTemplate = root.findViewById<View>(replyNameId) as? TextView ?: body
        val color = authorColor(binding.entry, root)
        val link = ClickableSpan(
            color,
            false,
            {
                val holder = binding.item.get()
                val message = binding.entry.message
                if (holder != null && message != null) {
                    val adapter = adapterField.get(holder) as WidgetChatListAdapter
                    adapter.eventHandler.onMessageAuthorLongClicked(message, adapter.data.guildId)
                }
            },
            {
                val holder = binding.item.get()
                val message = binding.entry.message
                if (holder != null && message != null) {
                    val adapter = adapterField.get(holder) as WidgetChatListAdapter
                    adapter.eventHandler.onMessageAuthorNameClicked(message, adapter.data.guildId)
                }
            },
        )
        InlineAuthorText.prepend(text, displayName(binding.entry), fontTemplate.typeface, body.textSize, color, link)
    }

    private fun configureMessage(item: WidgetChatListAdapterItemMessage, entry: MessageEntry) {
        val root = item.itemView as? ConstraintLayout ?: return
        val messageText = root.findViewById<View>(itemTextId) as? TextView ?: return
        val header = root.findViewById<View>(headerId)
        val avatar = root.findViewById<View>(itemAvatarId) as? ImageView
        val name = root.findViewById<View>(itemNameId) as? TextView
        val timestamp = root.findViewById<View>(itemTimestampId) as? TextView
        val decoration = findAvatarDecoration(root)
        val oldTimestampWidth = timestampWidthPx
        timestamp?.let {
            setShortTimestamp(it, entry)
            updateTimestampWidth(root.context, it)
        }

        root.minimumHeight = 0
        rows[item]?.row?.minimumHeight = 0
        val regular = header != null && avatar != null && name != null && timestamp != null
        val state = rows[item]?.takeIf { it.regular == regular } ?: run {
            val created = if (regular) {
                createRegularRow(root, avatar!!, timestamp!!, messageText, decoration)
            } else {
                createMinimalRow(root, messageText)
            }
            rows[item] = created
            created
        }
        // Themes may restore stock padding when a holder is rebound.
        root.setPadding(0, 0, 0, 0)
        header?.visibility = View.GONE
        if (state.regular && avatar != null) updateAvatarCell(state.leadingCell as FrameLayout, avatar, decoration)
        val loading = root.findViewById<View>(loadingTextId) as? TextView
        if (loading != null && isVisible(loading)) {
            val text = SpannableStringBuilder(loading.text)
            prependAuthor(text, loading, TextBinding(WeakReference(item), entry))
            loading.setText(text, TextView.BufferType.SPANNABLE)
        }
        updateLeadingCell(state)
        applyRootConstraints(root, state, root.findViewById(replyHolderId), root.findViewById(threadHeaderId))
        root.findViewById<View>(threadSpineId)?.visibility = View.GONE
        root.findViewById<View>(replyLinkId)?.visibility = View.GONE
        updateGuideline(root)
        if (timestampWidthPx != oldTimestampWidth) refreshAvatarLayout()
    }

    private fun createRegularRow(
        root: ConstraintLayout,
        avatar: ImageView,
        timestamp: TextView,
        messageText: TextView,
        decoration: View?,
    ): RowState {
        val context = root.context
        updateTimestampWidth(context, timestamp)
        val row = createRow(context)
        val spine = MessageSpine(context)
        root.findViewById<View>(headerId)?.visibility = View.GONE
        root.setPadding(0, 0, 0, 0)
        root.clipChildren = false
        detach(timestamp)
        detach(messageText)
        prepareMessageText(messageText)
        timestamp.gravity = Gravity.TOP or Gravity.END
        timestamp.setPadding(0, 0, dp(context, TIMESTAMP_END_PADDING_DP), 0)
        row.addView(timestamp, LinearLayout.LayoutParams(timestampColumnWidth(context), WRAP_CONTENT))
        val cell = FrameLayout(context).apply {
            id = View.generateViewId()
            clipChildren = false
            clipToPadding = false
        }
        updateAvatarCell(cell, avatar, decoration)
        row.addView(cell, LinearLayout.LayoutParams(dp(context, AVATAR_SIZE_DP), dp(context, AVATAR_SIZE_DP)).apply {
            leftMargin = dp(context, AVATAR_GAP_DP)
        })
        addBodyViews(root, row, messageText, dp(context, NAME_GAP_DP))
        root.findViewById<View>(sendErrorId)?.let { error ->
            detach(error)
            row.addView(error, LinearLayout.LayoutParams(dp(context, 16), dp(context, 16)).apply {
                rightMargin = dp(context, BODY_GAP_DP)
            })
        }
        return attachRow(root, row, spine, cell, timestamp)
    }

    private fun createMinimalRow(root: ConstraintLayout, messageText: TextView): RowState {
        val row = createRow(root.context)
        val spine = MessageSpine(root.context)
        root.setPadding(0, 0, 0, 0)
        root.clipChildren = false
        detach(messageText)
        prepareMessageText(messageText)
        val space = Space(root.context)
        row.addView(space, LinearLayout.LayoutParams(bodyStartDp(root.context), WRAP_CONTENT))
        addBodyViews(root, row, messageText, 0)
        return attachRow(root, row, spine, space, null)
    }

    private fun addBodyViews(root: ConstraintLayout, row: LinearLayout, messageText: TextView, margin: Int) {
        row.addView(messageText, bodyParams(root.context, margin))
        root.findViewById<View>(loadingTextId)?.let { loading ->
            detach(loading)
            prepareMessageText(loading)
            row.addView(loading, bodyParams(root.context, margin))
        }
    }

    private fun attachRow(
        root: ConstraintLayout,
        row: LinearLayout,
        spine: MessageSpine,
        leadingCell: View,
        timestamp: TextView?,
    ): RowState {
        root.addView(row, ConstraintLayout.LayoutParams(0, WRAP_CONTENT))
        root.addView(spine, 0, ConstraintLayout.LayoutParams(0, 0))
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> spine.invalidate() }
        return RowState(timestamp != null, row, spine, leadingCell, timestamp)
    }

    private fun updateAvatarCell(cell: FrameLayout, avatar: ImageView, decoration: View?) {
        val context = cell.context
        val size = dp(context, AVATAR_SIZE_DP)
        val avatarParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        val padding = dp(context, 1)
        avatar.setPadding(padding, padding, padding, padding)
        if (avatar.parent !== cell) {
            detach(avatar)
            cell.addView(avatar, avatarParams)
        } else {
            avatar.layoutParams = avatarParams
        }
        decoration?.let {
            val decorationSize = (size * 12f / 11f).roundToInt().minus(dp(context, 4)).coerceAtLeast(dp(context, 1))
            val params = FrameLayout.LayoutParams(decorationSize, decorationSize, Gravity.CENTER)
            if (it.parent !== cell) {
                detach(it)
                cell.addView(it, params)
            } else {
                it.layoutParams = params
            }
        }
    }

    private fun updateLeadingCell(state: RowState) {
        val context = state.row.context
        if (state.regular) {
            state.leadingCell.visibility = if (showAvatars()) View.VISIBLE else View.GONE
            state.timestamp?.let { timestamp ->
                val params = timestamp.layoutParams
                val width = timestampColumnWidth(context)
                if (params.width != width) {
                    params.width = width
                    timestamp.layoutParams = params
                }
            }
        } else {
            val params = state.leadingCell.layoutParams
            val width = bodyStartDp(context)
            val height = if (showAvatars()) dp(context, AVATAR_SIZE_DP) else 0
            if (params.width != width || params.height != height) {
                params.width = width
                params.height = height
                state.leadingCell.layoutParams = params
            }
        }
    }

    private fun updateGuideline(root: View) {
        val guideline = root.findViewById<View>(guidelineId) as? Guideline ?: return
        chatGuidelines[guideline] = true
        guideline.setGuidelineBegin(bodyStartDp(root.context))
    }

    internal fun refreshAvatarLayout() {
        for (guideline in chatGuidelines.keys) guideline.setGuidelineBegin(bodyStartDp(guideline.context))
        for ((item, state) in rows) {
            val root = item.itemView as? ConstraintLayout ?: continue
            root.minimumHeight = 0
            state.row.minimumHeight = 0
            updateLeadingCell(state)
            applyRootConstraints(root, state, root.findViewById(replyHolderId), root.findViewById(threadHeaderId))
            root.requestLayout()
        }
    }

    private fun applyRootConstraints(root: ConstraintLayout, state: RowState, reply: View?, threadHeader: View?) {
        val replyVisible = isVisible(reply)
        val threadVisible = isVisible(threadHeader)
        val rowParams = ConstraintLayout.LayoutParams(0, WRAP_CONTENT).apply {
            startToStart = PARENT_ID
            endToEnd = PARENT_ID
            bottomToBottom = PARENT_ID
            verticalBias = 0f
            when {
                replyVisible -> topToBottom = reply!!.id
                threadVisible -> topToBottom = threadHeader!!.id
                else -> topToTop = PARENT_ID
            }
        }
        root.updateViewLayout(state.row, rowParams)
        (reply?.layoutParams as? ConstraintLayout.LayoutParams)?.let { params ->
            params.width = 0
            params.height = WRAP_CONTENT
            params.leftToLeft = NO_CONSTRAINT
            params.leftToRight = NO_CONSTRAINT
            params.rightToLeft = NO_CONSTRAINT
            params.rightToRight = NO_CONSTRAINT
            params.startToEnd = NO_CONSTRAINT
            params.endToStart = NO_CONSTRAINT
            params.startToStart = PARENT_ID
            params.endToEnd = PARENT_ID
            params.topToTop = if (threadVisible) NO_CONSTRAINT else PARENT_ID
            params.topToBottom = if (threadVisible) threadHeader!!.id else NO_CONSTRAINT
            // Avoid a circular vertical chain: only the message depends on the reply.
            params.bottomToBottom = NO_CONSTRAINT
            params.bottomToTop = NO_CONSTRAINT
            params.marginStart = bodyStartDp(root.context) + dp(root.context, 8)
            params.marginEnd = dp(root.context, 8)
            root.updateViewLayout(reply, params)
        }
        val spineParams = ConstraintLayout.LayoutParams(
            bodyStartDp(root.context) + dp(root.context, 8) - spineStartDp(root.context), 0,
        ).apply {
            startToStart = PARENT_ID
            topToTop = if (replyVisible) reply!!.id else state.row.id
            bottomToBottom = state.row.id
            marginStart = spineStartDp(root.context)
        }
        root.updateViewLayout(state.spine, spineParams)
        val loading = root.findViewById<View>(loadingTextId) as? TextView
        val active = loading?.takeIf { isVisible(it) } ?: root.findViewById<View>(itemTextId) as? TextView
        state.spine.bind(active, reply?.takeIf { replyVisible })
    }

    private fun compactReactions(container: ViewGroup) {
        val context = container.context
        container.minimumHeight = dp(context, REACTION_HEIGHT_DP)
        (container.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = dp(context, REACTION_VERTICAL_MARGIN_DP)
            it.bottomMargin = dp(context, REACTION_VERTICAL_MARGIN_DP)
            container.layoutParams = it
        }
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            when {
                child is ReactionView -> compactReactionView(child)
                child.id == quickAddReactionId && child is ImageView -> compactQuickAdd(child)
            }
        }
    }

    private fun compactQuickAdd(view: ImageView) {
        val height = dp(view.context, REACTION_HEIGHT_DP)
        view.layoutParams?.let {
            if (it.height != height) {
                it.height = height
                view.layoutParams = it
            }
        }
        val padding = dp(view.context, 2)
        view.setPadding(padding, padding, padding, padding)
        view.minimumHeight = 0
    }

    private fun compactReactionView(reaction: ReactionView) {
        val context = reaction.context
        val height = dp(context, REACTION_HEIGHT_DP)
        var changed = false
        reaction.layoutParams?.let {
            if (it.height != height) {
                it.height = height
                reaction.layoutParams = it
                changed = true
            }
        }
        val padding = dp(context, REACTION_HORIZONTAL_PADDING_DP)
        if (reaction.paddingLeft != padding || reaction.paddingRight != padding ||
            reaction.paddingTop != 0 || reaction.paddingBottom != 0
        ) {
            reaction.setPadding(padding, 0, padding, 0)
            changed = true
        }
        reaction.gravity = Gravity.CENTER_VERTICAL
        reaction.clipChildren = false
        reaction.getChildAt(0)?.let { emoji ->
            if (emoji.scaleX != REACTION_EMOJI_SCALE || emoji.scaleY != REACTION_EMOJI_SCALE) {
                emoji.scaleX = REACTION_EMOJI_SCALE
                emoji.scaleY = REACTION_EMOJI_SCALE
                changed = true
            }
            setEndMargin(emoji, dp(context, 2))
        }
        reaction.getChildAt(1)?.let { counter ->
            setStartMargin(counter, dp(context, 2))
            if (counter is ViewGroup) {
                for (i in 0 until counter.childCount) {
                    val text = counter.getChildAt(i) as? TextView ?: continue
                    if (text.textSize != sp(context, REACTION_COUNTER_TEXT_SP)) {
                        text.setTextSize(REACTION_COUNTER_TEXT_SP)
                        changed = true
                    }
                }
            }
        }
        // WhoReacted adds avatars/chips after the stock emoji and counter.
        val avatarSize = dp(context, REACTION_AVATAR_SIZE_DP)
        for (i in 2 until reaction.childCount) {
            val reactor = reaction.getChildAt(i)
            val params = reactor.layoutParams as? LinearLayout.LayoutParams ?: continue
            val width = if (reactor is TextView) WRAP_CONTENT else avatarSize
            val margin = if (i == 2) dp(context, 6) else -dp(context, REACTION_AVATAR_SIZE_DP - 6)
            if (params.width != width || params.height != avatarSize || params.leftMargin != margin ||
                params.topMargin != 0 || params.bottomMargin != 0
            ) {
                params.width = width
                params.height = avatarSize
                params.leftMargin = margin
                params.topMargin = 0
                params.bottomMargin = 0
                reactor.layoutParams = params
                changed = true
            }
            reactor.minimumWidth = avatarSize
            reactor.minimumHeight = avatarSize
            if (reactor is TextView) {
                reactor.setTextSize(sp(context, 10f))
                reactor.setPadding(0, 0, 0, 0)
                reactor.gravity = Gravity.CENTER
            }
        }
        if (compactReactionViews.put(reaction, true) == null) {
            reaction.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                compactReactionView(view as ReactionView)
            }
        }
        if (changed) reaction.requestLayout()
    }

    private fun setStartMargin(view: View, margin: Int) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.leftMargin != margin) {
            params.leftMargin = margin
            view.layoutParams = params
        }
    }

    private fun setEndMargin(view: View, margin: Int) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.rightMargin != margin) {
            params.rightMargin = margin
            view.layoutParams = params
        }
    }

    private fun setShortTimestamp(timestamp: TextView, entry: MessageEntry) {
        val time = entry.message?.timestamp ?: return
        timestamp.text = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(time.g()))
    }

    private fun displayName(entry: MessageEntry): String {
        entry.message?.author?.let { author ->
            entry.nickOrUsernames?.get(author.id)?.takeIf { it.isNotEmpty() }?.let { return it }
            author.username?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return entry.author?.nick?.takeIf { it.isNotEmpty() } ?: "Unknown user"
    }

    private fun authorColor(entry: MessageEntry, root: View): Int =
        entry.author?.color?.takeUnless { it == -0x1000000 }
            ?: ColorCompat.getThemedColor(root.context, R.b.colorHeaderPrimary)

    private fun createRow(context: Context) = LinearLayout(context).apply {
        id = View.generateViewId()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        isBaselineAligned = true
        clipChildren = false
        clipToPadding = false
        val padding = dp(context, ROW_VERTICAL_PADDING_DP)
        setPadding(0, padding, 0, padding)
    }

    private fun bodyParams(context: Context, margin: Int) = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
        leftMargin = margin
        rightMargin = dp(context, BODY_GAP_DP)
        bottomMargin = dp(context, BODY_BOTTOM_PADDING_DP)
    }

    private fun prepareMessageText(view: View) {
        val text = view as? TextView ?: return
        text.setSingleLine(false)
        text.maxLines = Int.MAX_VALUE
        text.ellipsize = null
        text.setHorizontallyScrolling(false)
        text.gravity = Gravity.TOP or Gravity.START
    }

    private fun updateTimestampWidth(context: Context, timestamp: TextView) {
        val measured = ceil(timestamp.paint.measureText("00:00").toDouble()).toInt()
        timestampWidthPx = maxOf(timestampWidthPx, dp(context, DEFAULT_TIMESTAMP_WIDTH_DP),
            measured + dp(context, TIMESTAMP_END_PADDING_DP))
    }

    private fun timestampColumnWidth(context: Context): Int {
        if (timestampWidthPx == 0) timestampWidthPx = dp(context, DEFAULT_TIMESTAMP_WIDTH_DP)
        return timestampWidthPx
    }

    private fun bodyStartDp(context: Context) = timestampColumnWidth(context) + dp(
        context, NAME_GAP_DP + if (showAvatars()) AVATAR_GAP_DP + AVATAR_SIZE_DP else 0,
    )

    private fun spineStartDp(context: Context) = bodyStartDp(context) - dp(context, NAME_GAP_DP / 2)

    private fun showAvatars() = settings.getBool(SHOW_AVATARS, false)

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    private fun sp(context: Context, value: Float) = value * context.resources.displayMetrics.scaledDensity

    private fun detach(view: View) { (view.parent as? ViewGroup)?.removeView(view) }

    private fun isVisible(view: View?) = view != null && view.visibility != View.GONE

    private fun findAvatarDecorationId(): Int? {
        val decorator = runCatching {
            Class.forName("com.aliucord.coreplugins.decorations.avatar.AvatarDecoratorKt")
        }.getOrNull() ?: return null
        val accessorId = runCatching {
            decorator.getDeclaredMethod("access\$getDecoId\$p").apply { isAccessible = true }.invoke(null) as? Int
        }.getOrNull()
        if (accessorId != null) return accessorId
        // Older Aliucord builds expose a field instead of an accessor.
        return runCatching {
            decorator.getDeclaredField("decoId").apply { isAccessible = true }.get(null) as? Int
        }.getOrNull()
    }

    private fun findAvatarDecoration(root: ConstraintLayout): View? {
        val id = avatarDecorationId?.takeIf { it > 0 } ?: findAvatarDecorationId().also { avatarDecorationId = it }
        return id?.takeIf { it > 0 }?.let { root.findViewById(it) }
    }

    override fun stop(context: Context) {
        rows.clear()
        messageTexts.clear()
        chatGuidelines.clear()
        patcher.unpatchAll()
    }

    private data class RowState(
        val regular: Boolean,
        val row: LinearLayout,
        val spine: MessageSpine,
        val leadingCell: View,
        val timestamp: TextView?,
    )

    private data class TextBinding(val item: WeakReference<WidgetChatListAdapterItemMessage>, val entry: MessageEntry)

    companion object {
        internal const val SHOW_AVATARS = "showAvatars"
        private const val NO_CONSTRAINT = -1
        private const val DEFAULT_TIMESTAMP_WIDTH_DP = 32
        private const val TIMESTAMP_END_PADDING_DP = 4
        private const val AVATAR_GAP_DP = 6
        private const val AVATAR_SIZE_DP = 24
        private const val NAME_GAP_DP = 6
        private const val BODY_GAP_DP = 4
        private const val BODY_BOTTOM_PADDING_DP = 2
        private const val ROW_VERTICAL_PADDING_DP = 1
        private const val REACTION_HEIGHT_DP = 20
        private const val REACTION_HORIZONTAL_PADDING_DP = 4
        private const val REACTION_VERTICAL_MARGIN_DP = 2
        private const val REACTION_AVATAR_SIZE_DP = 16
        private const val REACTION_EMOJI_SCALE = 0.8f
        private const val REACTION_COUNTER_TEXT_SP = 12f
    }
}
