package com.github.yutaplug.instantmessages

import android.content.Context
import android.os.Build
import android.view.WindowInsetsAnimation
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.ReflectUtils
import com.discord.models.message.Message
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.chat.input.SmoothKeyboardReactionHelper
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Makes outgoing messages appear immediately without chat-list animations. */
@AliucordPlugin
class InstantMessages : Plugin() {
    private class DeferredData(var data: WidgetChatListAdapter.Data, val callback: Runnable)

    private val deferredData = WeakHashMap<WidgetChatListAdapter, DeferredData>()
    private val itemAnimators = WeakHashMap<RecyclerView, RecyclerView.ItemAnimator?>()
    private val textAlphas = WeakHashMap<SimpleDraweeSpanTextView, Float>()
    private var applyingDeferredAdapter: WidgetChatListAdapter? = null

    override fun start(context: Context) {
        // These classes do not exist before Android 11.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) patchKeyboardAnimation()

        val disableAnimations = Hook { frame ->
            val chatList = frame.thisObject as WidgetChatList
            val adapter = WidgetChatList.`access$getAdapter$p`(chatList)
            if (adapter != null) {
                disableItemAnimations(adapter.recycler)
                // onResume saves the current animator, which we may already have disabled.
                // Preserve Discord's default so subsequent enable calls still work after stop.
                ReflectUtils.setField(chatList, "defaultItemAnimator", itemAnimators[adapter.recycler])
            }
        }
        patcher.patch(WidgetChatList::class.java, "onViewBoundOrOnResume", hook = disableAnimations)
        patcher.patch(WidgetChatList::class.java, "enableItemAnimations", hook = disableAnimations)
        patcher.patch(
            WidgetChatListAdapter::class.java,
            "setData",
            arrayOf(WidgetChatListAdapter.Data::class.java),
            PreHook { frame ->
                val adapter = frame.thisObject as WidgetChatListAdapter
                val data = frame.args[0] as WidgetChatListAdapter.Data
                disableItemAnimations(adapter.recycler)
                if (applyingDeferredAdapter !== adapter && deferTransientUpdate(adapter, data)) {
                    frame.result = null
                } else {
                    keepNewOutgoingMessageAtBottom(adapter, data)
                }
            },
        )
        patcher.patch(
            WidgetChatListAdapterItemMessage::class.java,
            "processMessageText",
            arrayOf(SimpleDraweeSpanTextView::class.java, MessageEntry::class.java),
            Hook { frame ->
                val view = frame.args[0] as SimpleDraweeSpanTextView
                val message = (frame.args[1] as MessageEntry).message
                // Discord resets alpha during binding, including when a row is recycled.
                textAlphas.remove(view)
                if (isPending(message)) {
                    textAlphas[view] = view.alpha
                    view.alpha = 1f
                }
            },
        )
    }

    private fun patchKeyboardAnimation() {
        patcher.patch(
            SmoothKeyboardReactionHelper.Callback::class.java,
            "onStart",
            arrayOf(WindowInsetsAnimation::class.java, WindowInsetsAnimation.Bounds::class.java),
            PreHook { frame -> frame.result = frame.args[1] },
        )
    }

    private fun disableItemAnimations(recycler: RecyclerView) {
        if (!itemAnimators.containsKey(recycler) || recycler.itemAnimator != null) {
            itemAnimators[recycler] = recycler.itemAnimator
        }
        recycler.itemAnimator = null
    }

    private fun keepNewOutgoingMessageAtBottom(
        adapter: WidgetChatListAdapter,
        data: WidgetChatListAdapter.Data,
    ) {
        val current = adapter.data
        // Initial binding and channel switches should preserve Discord's chosen position.
        if (current.channelId != data.channelId) return
        val messages = messages(data.list)
        val previous = messages(current.list)
        val previousIds = previous.mapTo(HashSet()) { it.id }
        val previousNonces = previous.mapNotNullTo(HashSet()) { it.nonce }
        val newest = messages.firstOrNull() ?: return
        val newPending = messages.any {
            isPending(it) && it.author?.id == data.userId &&
                it.id !in previousIds && (it.nonce == null || it.nonce !in previousNonces)
        }
        val newAcknowledged = !newest.isLocal && newest.author?.id == data.userId &&
            newest.id !in previousIds && (newest.nonce == null || newest.nonce !in previousNonces) &&
            previous.firstOrNull()?.let { newest.id > it.id } == true
        if (!newPending && !newAcknowledged) return
        val layoutManager = adapter.layoutManager ?: return
        adapter.recycler.stopScroll()
        layoutManager.scrollToPositionWithOffset(0, 0)
    }

    private fun deferTransientUpdate(
        adapter: WidgetChatListAdapter,
        incoming: WidgetChatListAdapter.Data,
    ): Boolean {
        val current = adapter.data
        if (current.channelId != incoming.channelId || current.userId != incoming.userId) {
            cancelDeferredData(adapter)
            return false
        }
        val incomingMessages = messages(incoming.list)
        val currentPending = messages(current.list).filter { isPending(it) }
        val transient = incomingMessages.any { pending ->
            isPending(pending) && incomingMessages.any { !it.isLocal && sameMessage(it, pending) }
        } || currentPending.any { pending -> incomingMessages.none { sameMessage(it, pending) } }
        // Never hide a newly sent message behind an older acknowledgement transition.
        val newPending = incomingMessages.any { pending ->
            isPending(pending) && currentPending.none { sameMessage(it, pending) }
        }
        if (!transient || newPending) {
            cancelDeferredData(adapter)
            return false
        }

        val existing = deferredData[adapter]
        if (existing != null) {
            // Update the payload without extending the original deadline.
            existing.data = incoming
            return true
        }
        val reference = WeakReference(adapter)
        val callback = Runnable { reference.get()?.let(::applyDeferredData) }
        deferredData[adapter] = DeferredData(incoming, callback)
        if (!adapter.recycler.postDelayed(callback, TRANSIENT_DATA_DELAY_MS)) {
            deferredData.remove(adapter)
            return false
        }
        return true
    }

    private fun applyDeferredData(adapter: WidgetChatListAdapter) {
        val deferred = deferredData.remove(adapter) ?: return
        if (adapter.data.channelId != deferred.data.channelId || adapter.data.userId != deferred.data.userId) return
        applyingDeferredAdapter = adapter
        try {
            adapter.setData(deferred.data)
        } finally {
            applyingDeferredAdapter = null
        }
    }

    private fun cancelDeferredData(adapter: WidgetChatListAdapter) {
        val deferred = deferredData.remove(adapter) ?: return
        adapter.recycler.removeCallbacks(deferred.callback)
    }

    private fun messages(entries: List<ChatListEntry>): List<Message> =
        entries.mapNotNull { (it as? MessageEntry)?.message }

    private fun isPending(message: Message): Boolean = message.type == PENDING_MESSAGE_TYPE

    // Nonces are Discord's acknowledgement identity. Text and timestamps are ambiguous,
    // especially for repeated messages, attachments, and stickers.
    private fun sameMessage(candidate: Message, target: Message): Boolean =
        candidate.channelId == target.channelId &&
            (candidate.id == target.id || (target.nonce != null && target.nonce == candidate.nonce))

    override fun stop(context: Context) {
        for ((adapter, deferred) in deferredData.entries.map { it.key to it.value }) {
            adapter.recycler.removeCallbacks(deferred.callback)
            applyDeferredData(adapter)
        }
        deferredData.clear()
        applyingDeferredAdapter = null
        patcher.unpatchAll()
        for ((recycler, animator) in itemAnimators) {
            if (recycler.itemAnimator == null) recycler.itemAnimator = animator
        }
        itemAnimators.clear()
        for ((view, alpha) in textAlphas) {
            if (view.alpha == 1f) view.alpha = alpha
        }
        textAlphas.clear()
    }

    private companion object {
        const val PENDING_MESSAGE_TYPE = -1
        const val TRANSIENT_DATA_DELAY_MS = 500L
    }
}
