package com.github.yutaplug.playembeds

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.entries.EmbedEntry
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Neither the key nor the value keeps a recycled embed row alive. */
internal class WeakValueMap<K : Any, V : Any> {
    private val entries = WeakHashMap<K, WeakReference<V>>()

    operator fun get(key: K): V? = entries[key]?.get()
    operator fun set(key: K, value: V) { entries[key] = WeakReference(value) }
    fun remove(key: K): V? = entries.remove(key)?.get()
    fun containsKey(key: K): Boolean = get(key) != null
    val keys: Set<K> get() = entries.keys
    val values: List<V> get() = entries.values.mapNotNull { it.get() }
    fun clear() = entries.clear()
}

internal data class FullscreenState(
    val activity: Activity,
    val overlay: FrameLayout,
    val callback: WebChromeClient.CustomViewCallback,
    val previousSystemUiVisibility: Int,
    val wasFullscreen: Boolean,
    val playerParent: ViewGroup?,
    val playerLayoutParams: ViewGroup.LayoutParams?,
    val embedReplacement: HostedEmbedReplacement?,
)

internal data class HostedPlayerState(
    val webView: WebView,
    val parent: ViewGroup,
    val previousVisibility: List<Pair<View, Int>>,
    val url: String,
    val extraView: View?,
    val previousMinimumWidth: Int,
    val replacement: HostedEmbedReplacement? = null,
)

internal data class HostedEmbedReplacement(
    val wrapper: FrameLayout,
    val originalParent: ViewGroup,
    val originalIndex: Int,
    val originalLayoutParams: ViewGroup.LayoutParams,
    val originalCard: ViewGroup,
    val originalId: Int,
    val originalWidth: Int,
    val originalHeight: Int,
)

internal class SearchEmbedClick(handler: WidgetChatListAdapter.EventHandler, val entry: EmbedEntry) {
    private val handlerRef = WeakReference(handler)
    val handler: WidgetChatListAdapter.EventHandler? get() = handlerRef.get()
}

internal data class InlineVideoState(
    val webView: WebView,
    val parent: ViewGroup,
    val previousVisibility: List<Pair<View, Int>>,
    val url: String,
)
