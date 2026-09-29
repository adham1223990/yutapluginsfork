package com.github.yutaplug.playembeds

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.api.message.embed.MessageEmbed
import com.discord.player.MediaSource
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.media.WidgetMedia
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

@AliucordPlugin(requiresRestart = true)
@Suppress("unused")
class PlayEmbeds : Plugin() {
    private val embedLinks = WeakHashMap<ViewGroup, EmbedLink>()
    private val hostedEmbeds = WeakHashMap<ViewGroup, MessageEmbed>()
    private val hostedPlayers = WeakValueMap<ViewGroup, HostedPlayerState>()
    private val embedRowKeys = WeakHashMap<ViewGroup, Pair<Long, Int>>()
    private val searchEmbedClicks = WeakHashMap<ViewGroup, SearchEmbedClick>()
    private val inlineVideoPlayers = WeakValueMap<ViewGroup, InlineVideoState>()
    private val spotifyPlayButtons = WeakValueMap<ViewGroup, ImageView>()
    private val fullscreenPlayers = WeakValueMap<WebView, FullscreenState>()
    // Kept separate from row state because audio-focus callbacks can arrive on
    // Chromium threads while chat rows are being rebound on the main thread.
    private val activeEmbedWebViews = ConcurrentHashMap.newKeySet<WebView>()

    override fun start(context: Context) {
        patchEmbedRows()
        patchNativeHostedLaunches()
        patchWebViewAudioFocus()
    }

    /**
     * Chromium asks Android for exclusive audio focus whenever a WebView starts
     * media. Report focus as granted for an attached PlayEmbeds player without
     * forwarding that request to the system, so other apps keep their audio.
     *
     * Both overloads are patched because the Android System WebView decides
     * which one to use. The Chromium stack check keeps Discord's native media,
     * calls, and non-WebView audio on Android's normal focus path.
     */
    private fun patchWebViewAudioFocus() {
        val audioManager = AudioManager::class.java
        val shouldSuppress = {
            activeEmbedWebViews.isNotEmpty() && Thread.currentThread().stackTrace.any { element ->
                element.className.startsWith("org.chromium.") ||
                    element.className.startsWith("com.android.webview.chromium.")
            }
        }
        val suppressFocus = PreHook { frame ->
            if (shouldSuppress()) frame.setResult(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        }
        // Present since Android 8. Resolve by name so devices on older Android
        // versions can still load the plugin and use the legacy overload below.
        runCatching { Class.forName("android.media.AudioFocusRequest") }.getOrNull()?.let { requestClass ->
            audioManager.methods.firstOrNull { method ->
                method.name == "requestAudioFocus" && method.parameterTypes.contentEquals(arrayOf(requestClass))
            }?.let { patcher.patch(it, suppressFocus) }
        }
        audioManager.getMethod(
            "requestAudioFocus",
            AudioManager.OnAudioFocusChangeListener::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).let { patcher.patch(it, suppressFocus) }
    }

    private fun patchEmbedRows() {
        val getAdapter = WidgetChatListAdapterItemEmbed::class.java.getDeclaredMethod("access\$getAdapter\$p", WidgetChatListAdapterItemEmbed::class.java)
        val onConfigure = WidgetChatListAdapterItemEmbed::class.java.getDeclaredMethod(
            "onConfigure",
            Int::class.javaPrimitiveType,
            ChatListEntry::class.java,
        )

        // Restore the old row before Discord configures its next embed. Restoring
        // afterward would overwrite the new embed's image/inline-media visibility.
        patcher.patch(
            onConfigure,
            PreHook { frame ->
                val item = frame.thisObject as? WidgetChatListAdapterItemEmbed ?: return@PreHook
                val entry = frame.args.getOrNull(1) as? EmbedEntry
                embedContainers(item.itemView).forEach { container ->
                    val currentEmbed = entry?.embed
                    val state = hostedPlayers[container]
                    val inlineState = inlineVideoPlayers[container]
                    val sameRow = entry != null &&
                        embedRowKeys[container] == (entry.message.id to entry.embedIndex)
                    val samePlayer = sameRow && currentEmbed != null && ((state != null &&
                        EmbedUrls.hostedEmbed(currentEmbed)?.url == state.url &&
                        state.webView.parent === state.parent) ||
                        (inlineState != null &&
                            EmbedUrls.genericVideoUrl(currentEmbed) == inlineState.url &&
                            inlineState.webView.parent === inlineState.parent))
                    if (!samePlayer) resetEmbedContainer(container)
                }
            },
        )
        patcher.patch(
            onConfigure,
            Hook { frame ->
                val item = frame.thisObject as? WidgetChatListAdapterItemEmbed ?: return@Hook
                val entry = frame.args.getOrNull(1) as? EmbedEntry ?: return@Hook
                embedContainers(item.itemView).forEach { container ->
                    embedRowKeys[container] = entry.message.id to entry.embedIndex
                    hostedPlayers[container]?.let { state ->
                        state.previousVisibility.forEach { (view, visibility) ->
                            if (visibility == View.VISIBLE) view.visibility = View.INVISIBLE
                        }
                        state.webView.bringToFront()
                    }
                    inlineVideoPlayers[container]?.let { state ->
                        state.previousVisibility.forEach { (view, _) -> view.visibility = View.GONE }
                        state.webView.bringToFront()
                    }
                }
                val adapter = getAdapter.invoke(null, item) as WidgetChatListAdapter
                val handler = adapter.eventHandler
                if (handler.javaClass.name == "com.discord.widgets.search.results.WidgetSearchResults\$SearchResultAdapterEventHandler") {
                    embedContainers(item.itemView).forEach { container ->
                        searchEmbedClicks[container] = SearchEmbedClick(handler, entry)
                    }
                    return@Hook
                }
                val link = EmbedUrls.embedLink(entry.embed) ?: return@Hook
                attachMediaClickHandlers(item.itemView, link)
            },
        )
    }

    private fun patchNativeHostedLaunches() {
        val widgetMediaLaunch = WidgetMedia.Companion::class.java.getDeclaredMethod(
            "launch",
            Context::class.java,
            MessageEmbed::class.java,
        )
        patcher.patch(
            widgetMediaLaunch,
            PreHook { frame ->
                val embed = frame.args.getOrNull(1) as? MessageEmbed ?: return@PreHook
                if (EmbedUrls.genericVideoUrl(embed) != null) {
                    val container = findHostedContainer(embed)
                    if (container != null && playInlineVideoEmbed(container, embed)) {
                        frame.setResult(null)
                        return@PreHook
                    }
                }
                val hosted = EmbedUrls.hostedEmbed(embed) ?: return@PreHook
                val container = findHostedContainer(embed) ?: return@PreHook
                if (openHostedEmbed(container, hosted)) frame.setResult(null)
            },
        )

        val playButtonListener = Class.forName(
            "com.discord.widgets.chat.list.InlineMediaView\$updateUI\$5",
        )
        val onClick = playButtonListener.getDeclaredMethod("onClick", View::class.java)
        patcher.patch(
            onClick,
            PreHook { frame ->
                val view = frame.args.getOrNull(0) as? View ?: return@PreHook
                val container = findHostedContainer(view)
                if (handleSearchEmbedClick(container)) {
                    frame.setResult(null)
                    return@PreHook
                }
                val embed = container?.let { hostedEmbeds[it] }
                if (container != null && embed != null && EmbedUrls.genericVideoUrl(embed) != null) {
                    if (playInlineVideoEmbed(container, embed)) {
                        frame.setResult(null)
                        return@PreHook
                    }
                }
                val url = mediaSourceUrl(frame.thisObject) ?: return@PreHook
                if (EmbedUrls.isMediaLink(url)) return@PreHook
                val hosted = EmbedUrls.hostedEmbed(url) ?: return@PreHook
                if (!openHostedEmbed(view, hosted)) return@PreHook
                frame.setResult(null)
            },
        )

        // Rich VIDEO previews (including FxTwitter) open EmbedVideo.url via
        // UriHandler directly, so intercept them before that external launch.
        patchEmbedPreviewClick(
            "com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed\$configureEmbedImage\$\$inlined\$apply\$lambda\$1",
        )
        patchEmbedPreviewClick(
            "com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed\$configureInlineEmbed\$\$inlined\$apply\$lambda\$1",
        )
    }

    private fun patchEmbedPreviewClick(className: String) {
        val listener = Class.forName(className)
        val onClick = listener.getDeclaredMethod("onClick", View::class.java)
        patcher.patch(
            onClick,
            PreHook { frame ->
                val embed = messageEmbedField(frame.thisObject) ?: return@PreHook
                val source = frame.args.getOrNull(0) as? View
                    ?: viewGroupField(frame.thisObject)
                    ?: return@PreHook
                val container = findHostedContainer(source)
                if (handleSearchEmbedClick(container)) {
                    frame.setResult(null)
                    return@PreHook
                }
                if (EmbedUrls.genericVideoUrl(embed) != null) {
                    val target = container ?: return@PreHook
                    if (playInlineVideoEmbed(target, embed)) {
                        frame.setResult(null)
                        return@PreHook
                    }
                }
                val hosted = EmbedUrls.hostedEmbed(embed) ?: return@PreHook
                if (!openHostedEmbed(source, hosted)) return@PreHook
                frame.setResult(null)
            },
        )
    }

    private fun handleSearchEmbedClick(container: ViewGroup?): Boolean {
        val click = container?.let { searchEmbedClicks[it] } ?: return false
        val handler = click.handler ?: return false
        handler.onMessageClicked(click.entry.message, click.entry.isThreadStarterMessage)
        return true
    }

    private fun mediaSourceUrl(listener: Any): String? {
        for (field in listener.javaClass.declaredFields) {
            if (!MediaSource::class.java.isAssignableFrom(field.type)) continue
            try {
                field.isAccessible = true
                val source = field.get(listener) as? MediaSource ?: return null
                return source.j.toString()
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun messageEmbedField(listener: Any): MessageEmbed? {
        for (field in listener.javaClass.declaredFields) {
            if (!MessageEmbed::class.java.isAssignableFrom(field.type)) continue
            try {
                field.isAccessible = true
                return field.get(listener) as? MessageEmbed
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun viewGroupField(listener: Any): ViewGroup? {
        for (field in listener.javaClass.declaredFields) {
            if (!ViewGroup::class.java.isAssignableFrom(field.type)) continue
            try {
                field.isAccessible = true
                return field.get(listener) as? ViewGroup
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun attachMediaClickHandlers(root: View, link: EmbedLink) {
        val listener = View.OnClickListener { view ->
            val currentLink = embedLinkForView(view) ?: return@OnClickListener
            openEmbed(view, currentLink)
        }

        val containers = embedContainers(root)
        containers.forEach { container ->
            embedLinks[container] = link
            if (link is EmbedLink.InlineVideo) {
                hostedEmbeds[container] = link.embed
            }
            if (link is EmbedLink.Hosted) {
                link.embed.sourceEmbed?.let { hostedEmbeds[container] = it }
            }
            if (link is EmbedLink.Hosted && link.embed.provider == HostedProvider.SPOTIFY) {
                addSpotifyPlayButton(container, listener)
            }
        }
    }

    private fun resetEmbedContainer(container: ViewGroup) {
        val replacedCard = hostedPlayers[container]?.replacement?.originalCard
        removeHostedPlayer(container)
        removeInlineVideoPlayer(container)
        listOfNotNull(container, replacedCard).forEach { view ->
            removeSpotifyPlayButton(view)
            embedLinks.remove(view)
            hostedEmbeds.remove(view)
            embedRowKeys.remove(view)
            searchEmbedClicks.remove(view)
        }
    }

    private fun embedLinkForView(view: View): EmbedLink? {
        var current: View? = view
        while (current != null) {
            if (current is ViewGroup) {
                embedLinks[current]?.let { return it }
            }
            current = current.parent as? View
        }
        if (view is ViewGroup) {
            embedContainers(view).forEach { container ->
                embedLinks[container]?.let { return it }
            }
        }
        return null
    }

    private fun openEmbed(view: View, link: EmbedLink) {
        when (link) {
            is EmbedLink.InlineVideo -> {
                val container = findHostedContainer(view) ?: return
                playInlineVideoEmbed(container, link.embed)
            }
            is EmbedLink.Hosted -> {
                if (!openHostedEmbed(view, link.embed)) {
                    try {
                        view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.embed.url)))
                    } catch (_: ActivityNotFoundException) {
                        Utils.showToast("Unable to open the embed", true)
                    }
                }
            }
        }
    }

    private fun embedContainers(root: View): List<ViewGroup> {
        val containers = ArrayList<ViewGroup>()
        val cardId = Utils.getResId("chat_list_item_embed_container_card", "id")
        val card = root.findViewById<View>(cardId) as? ViewGroup
        if (card != null) {
            containers.add(card)
        } else if (root is ViewGroup) {
            containers.add(root)
        }
        return containers
    }

    private fun findHostedContainer(view: View): ViewGroup? {
        val inlineMediaId = Utils.getResId("embed_inline_media", "id")
        val imageContainerId = Utils.getResId("embed_image_container", "id")
        val cardId = Utils.getResId("chat_list_item_embed_container_card", "id")
        var current: View? = view
        var inlineMedia: ViewGroup? = null
        var fallback: ViewGroup? = null
        while (current != null) {
            if (current is ViewGroup) {
                if (current.id == cardId) return current
                if (current.id == inlineMediaId || current.id == imageContainerId) inlineMedia = current
                fallback = current
            }
            current = current.parent as? View
        }
        if (view is ViewGroup) {
            val card = view.findViewById<View>(cardId) as? ViewGroup
            if (card != null) return card
            val inline = view.findViewById<View>(inlineMediaId)
            if (inline is ViewGroup && inline.visibility == View.VISIBLE) return inline
        }
        return inlineMedia ?: fallback
    }

    private fun findHostedContainer(embed: MessageEmbed): ViewGroup? {
        return hostedEmbeds.entries
            .firstOrNull { entry -> entry.value === embed }
            ?.key
    }

    private fun openHostedEmbed(view: View, embed: HostedEmbed): Boolean {
        val container = findHostedContainer(view)
            ?: embed.sourceEmbed?.let(::findHostedContainer)
            ?: return false
        return openHostedEmbed(container, embed)
    }

    private fun requestAncestorsDisallowIntercept(view: View, disallow: Boolean) {
        var ancestor: ViewParent? = view.parent
        while (ancestor != null) {
            ancestor.requestDisallowInterceptTouchEvent(disallow)
            ancestor = (ancestor as? View)?.parent
        }
    }

    private fun wrapEmbedForHostedPlayer(container: ViewGroup): HostedEmbedReplacement? {
        val originalParent = container.parent as? ViewGroup ?: return null
        val originalIndex = originalParent.indexOfChild(container)
        val originalLayoutParams = container.layoutParams ?: return null
        val originalId = container.id
        val width = container.width
        val height = container.height
        if (originalIndex < 0 || originalId == View.NO_ID || width <= 0 || height <= 0) return null

        val wrapper = FrameLayout(container.context).apply {
            id = originalId
            minimumWidth = maxOf(container.minimumWidth, width)
            minimumHeight = maxOf(container.minimumHeight, height)
            elevation = container.elevation
            clipChildren = container.clipChildren
            clipToPadding = container.clipToPadding
            clipToOutline = container.clipToOutline
            container.background?.constantState
                ?.newDrawable(container.resources)
                ?.mutate()
                ?.let { background = it }
        }

        originalParent.removeViewAt(originalIndex)
        container.id = View.generateViewId()
        originalParent.addView(wrapper, originalIndex, originalLayoutParams)
        wrapper.addView(
            container,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        moveEmbedViewState(container, wrapper)
        return HostedEmbedReplacement(
            wrapper,
            originalParent,
            originalIndex,
            originalLayoutParams,
            container,
            originalId,
            width,
            height,
        )
    }

    private fun moveEmbedViewState(from: ViewGroup, to: ViewGroup) {
        embedLinks.remove(from)?.let { embedLinks[to] = it }
        hostedEmbeds.remove(from)?.let { hostedEmbeds[to] = it }
        embedRowKeys.remove(from)?.let { embedRowKeys[to] = it }
        searchEmbedClicks.remove(from)?.let { searchEmbedClicks[to] = it }
        spotifyPlayButtons.remove(from)?.let { spotifyPlayButtons[to] = it }
    }

    private fun copyLayoutParams(params: ViewGroup.LayoutParams): ViewGroup.LayoutParams {
        return when (params) {
            is ConstraintLayout.LayoutParams -> ConstraintLayout.LayoutParams(params)
            is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(params)
            is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(params)
            else -> ViewGroup.LayoutParams(params)
        }
    }

    /**
     * YouTube and SoundCloud URLs are webpages, not progressive media files.
     * Add their provider player to the embed itself instead of opening a
     * dialog, external app, or browser.
     */
    private fun openHostedEmbed(container: ViewGroup, embed: HostedEmbed): Boolean {
        val state = hostedPlayers[container]
        val existing = state?.webView
        if (existing != null) {
            if (existing.parent == state.parent) {
                removeSpotifyPlayButton(container)
                existing.visibility = View.VISIBLE
                existing.bringToFront()
                loadHostedPlayer(existing, embed)
                return true
            }
            val replacedCard = state.replacement?.originalCard
            removeHostedPlayer(container)
            if (replacedCard != null) {
                return openHostedEmbed(replacedCard, embed)
            }
        }

        val replaceWholeEmbed = embed.provider == HostedProvider.YOUTUBE
        val imageContainer = container.findViewById<View>(Utils.getResId("embed_image_container", "id"))
        val inlineMedia = container.findViewById<View>(Utils.getResId("embed_inline_media", "id"))
        val content = container.findViewById<View>(Utils.getResId("chat_list_item_embed_content", "id"))
        val preview = when {
            content?.visibility == View.VISIBLE && imageContainer?.visibility == View.VISIBLE ->
                imageContainer.findViewById<View>(Utils.getResId("chat_list_item_embed_image", "id"))
            inlineMedia?.visibility == View.VISIBLE ->
                inlineMedia.findViewById<View>(Utils.getResId("inline_media_image_preview", "id"))
            else -> container.findViewById<View>(Utils.getResId("chat_list_item_embed_image_thumbnail", "id"))
                ?.takeIf { it.visibility == View.VISIBLE }
        } ?: return false
        val replacement = if (replaceWholeEmbed) wrapEmbedForHostedPlayer(container) else null
        val playerParent = replacement?.wrapper ?: (preview.parent as? ViewGroup ?: return false)
        val playerContainer = replacement?.wrapper ?: container
        val compactSpotify = embed.provider == HostedProvider.SPOTIFY &&
            preview.id == Utils.getResId("chat_list_item_embed_image_thumbnail", "id") &&
            playerParent is ConstraintLayout && content?.visibility == View.VISIBLE
        val previousMinimumWidth = playerParent.minimumWidth
        val width = if (replacement != null) {
            ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            preview.width.takeIf { it > 0 }
                ?: preview.layoutParams.width.takeIf { it > 0 }
                ?: return false
        }
        val height = if (replacement != null) {
            ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            preview.height.takeIf { it > 0 }
                ?: preview.layoutParams.height.takeIf { it > 0 }
                ?: return false
        }
        removeSpotifyPlayButton(container)
        if (compactSpotify) {
            playerParent.minimumWidth = maxOf(
                previousMinimumWidth,
                EmbedResourceUtils.INSTANCE.computeMaximumImageWidthPx(container.context),
            )
        }
        val dividerId = Utils.getResId("chat_list_item_embed_divider", "id")
        val bottomBarrier = if (compactSpotify) {
            Barrier(container.context).apply {
                id = View.generateViewId()
                type = Barrier.BOTTOM
                referencedIds = intArrayOf(preview.id, content.id)
            }.also { playerParent.addView(it, ConstraintLayout.LayoutParams(0, 0)) }
        } else null
        val playerParams = if (replacement != null) {
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        } else if (bottomBarrier != null) {
            val maxPlayerWidth = maxOf(
                dp(container.context, 1),
                EmbedResourceUtils.INSTANCE.computeMaximumImageWidthPx(container.context) -
                    (container.findViewById<View>(dividerId)?.width ?: 0),
            )
            ConstraintLayout.LayoutParams(maxPlayerWidth, dp(container.context, SPOTIFY_PLAYER_HEIGHT_DP)).apply {
                startToEnd = dividerId
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                horizontalBias = 0f
                topToBottom = bottomBarrier.id
                topMargin = dp(container.context, 8)
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                bottomMargin = dp(container.context, 8)
            }
        } else {
            when (playerParent) {
                is ConstraintLayout -> ConstraintLayout.LayoutParams(preview.layoutParams as ConstraintLayout.LayoutParams)
                is FrameLayout -> FrameLayout.LayoutParams(preview.layoutParams)
                else -> ViewGroup.LayoutParams(preview.layoutParams)
            }.apply {
                this.width = width
                this.height = height
            }
        }

        val webView = object : WebView(container.context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                // Some Discord/device-specific parent containers don't reliably
                // propagate this request through the full hierarchy. Request it
                // directly from every ancestor so seekbar drags stay with YouTube
                // instead of becoming panel/chat swipes.
                val finished = event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                requestAncestorsDisallowIntercept(this, !finished)
                return try {
                    super.dispatchTouchEvent(event)
                } finally {
                    // Reassert after WebView's own dispatch; release all parents
                    // only once the gesture ends.
                    requestAncestorsDisallowIntercept(this, !finished)
                }
            }
        }
        webView.setBackgroundColor(Color.BLACK)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
            allowContentAccess = true
            allowFileAccess = false
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return redirectYoutubeVideo(view, embed, request.url.toString()) ||
                    redirectSpotifyLink(view, embed, request.url.toString()) ||
                    !EmbedUrls.isHttpUrl(request.url.toString())
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (embed.provider == HostedProvider.YOUTUBE && isYoutubePlayerRequest(request)) {
                    Utils.showToast("YouTube player failed to load: ${error.description}", true)
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                response: WebResourceResponse,
            ) {
                if (embed.provider == HostedProvider.YOUTUBE && isYoutubePlayerRequest(request)) {
                    Utils.showToast("YouTube player failed to load (HTTP ${response.statusCode})", true)
                }
            }

            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return redirectYoutubeVideo(view, embed, url) ||
                    redirectSpotifyLink(view, embed, url) || !EmbedUrls.isHttpUrl(url)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in Java")
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                showFullscreen(webView, view, callback)
            }

            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in Java")
            override fun onShowCustomView(view: View, requestedOrientation: Int, callback: CustomViewCallback) {
                showFullscreen(webView, view, callback)
            }

            @Suppress("DEPRECATION")
            override fun onHideCustomView() {
                hideFullscreen(webView)
            }
        }
        // YouTube replaces the full embed card; other hosted players stay within
        // Discord's original preview area and preserve the surrounding metadata.
        val previousVisibility = ArrayList<Pair<View, Int>>()
        if (replacement != null || compactSpotify) {
            // Keep the artwork and metadata visible above the playable panel.
        } else if (playerParent is ConstraintLayout) {
            previousVisibility.add(preview to preview.visibility)
            preview.visibility = View.INVISIBLE
        } else {
            for (index in 0 until playerParent.childCount) {
                val child = playerParent.getChildAt(index)
                previousVisibility.add(child to child.visibility)
                if (child.visibility == View.VISIBLE) child.visibility = View.INVISIBLE
            }
        }
        val playerState = HostedPlayerState(
            webView,
            playerParent,
            previousVisibility,
            embed.url,
            bottomBarrier,
            previousMinimumWidth,
            replacement,
        )
        webView.tag = playerState
        hostedPlayers[playerContainer] = playerState
        playerParent.addView(webView, playerParams)
        activeEmbedWebViews.add(webView)
        webView.bringToFront()
        playerContainer.requestLayout()
        loadHostedPlayer(webView, embed)
        return true
    }

    private fun loadHostedPlayer(webView: WebView, embed: HostedEmbed) {
        val appOrigin = "https://${webView.context.packageName.lowercase(Locale.ROOT)}"
        val url = EmbedUrls.hostedPlayerUrl(embed)
        if (embed.provider == HostedProvider.YOUTUBE) {
            // Load the embed endpoint as the WebView's page rather than nesting
            // it in a synthetic iframe document. Keep the app origin as Referer
            // for YouTube's player identity checks.
            webView.loadUrl(url, mapOf("Referer" to "$appOrigin/"))
        } else {
            if (embed.provider == HostedProvider.SPOTIFY) {
                val html = """
                    <!doctype html>
                    <html>
                      <head>
                        <meta charset="utf-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <style>
                          html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#121212}
                          iframe{position:absolute;inset:0;width:100%;height:100%;border:0}
                        </style>
                      </head>
                      <body>
                        <iframe src="${escapeHtmlAttribute(url)}" title="Spotify player"
                          allow="autoplay; clipboard-write; encrypted-media; fullscreen; picture-in-picture"
                          allowfullscreen></iframe>
                      </body>
                    </html>
                """
                webView.loadDataWithBaseURL("https://open.spotify.com/", html, "text/html", "UTF-8", null)
            } else {
                webView.loadUrl(url)
            }
        }
    }

    private fun redirectYoutubeVideo(webView: WebView, embed: HostedEmbed, url: String): Boolean {
        if (embed.provider != HostedProvider.YOUTUBE) return false
        val uri = Uri.parse(url)
        val host = uri.host
        if (!EmbedUrls.isHost(host, "youtube.com") && !EmbedUrls.isHost(host, "youtu.be")) return false
        val path = uri.path.orEmpty().trim('/')
        // An /embed request is the player itself. A watch/shorts/live link
        // selected inside it must not replace the iframe with YouTube's site.
        if (path == "embed" || path.startsWith("embed/")) return false
        if (EmbedUrls.youtubeVideoId(uri) == null && uri.getQueryParameter("list").isNullOrEmpty()) return false
        hideFullscreen(webView)
        webView.post {
            if (webView.parent != null) {
                loadHostedPlayer(webView, embed.copy(url = url))
            }
        }
        return true
    }

    private fun redirectSpotifyLink(webView: WebView, embed: HostedEmbed, url: String): Boolean {
        if (embed.provider != HostedProvider.SPOTIFY || !EmbedUrls.isHttpUrl(url)) return false
        val uri = Uri.parse(url)
        if (!EmbedUrls.isHost(uri.host, "open.spotify.com")) return false
        val playerUrl = EmbedUrls.spotifyPlayerUrl(url)
        if (playerUrl == url || uri.pathSegments.any { it.equals("embed", ignoreCase = true) }) return false
        // spotify.link redirects to an ordinary content page. Replace that
        // navigation with Spotify's playable embed endpoint.
        webView.post {
            if (webView.parent != null) loadHostedPlayer(webView, embed.copy(url = url))
        }
        return true
    }

    private fun isYoutubePlayerRequest(request: WebResourceRequest): Boolean {
        return request.isForMainFrame ||
            (EmbedUrls.isHost(request.url.host, "youtube.com") && request.url.path?.startsWith("/embed/") == true)
    }

    private fun playInlineVideoEmbed(container: ViewGroup, embed: MessageEmbed): Boolean =
        openInlineVideoEmbed(container, embed)

    /** Replace the preview on tap with an HTML5 player using Discord's video URL. */
    private fun openInlineVideoEmbed(container: ViewGroup, embed: MessageEmbed): Boolean {
        val videoUrl = EmbedUrls.genericVideoUrl(embed) ?: return false
        inlineVideoPlayers[container]?.let { state ->
            if (state.url == videoUrl && state.webView.parent === state.parent) {
                if (!fullscreenPlayers.containsKey(state.webView)) state.webView.visibility = View.VISIBLE
                state.webView.bringToFront()
                return true
            }
        }
        removeInlineVideoPlayer(container)

        val imageContainerId = Utils.getResId("embed_image_container", "id")
        val inlineMediaId = Utils.getResId("embed_inline_media", "id")
        val contentId = Utils.getResId("chat_list_item_embed_content", "id")
        val imageContainer = container.findViewById<View>(imageContainerId) as? ViewGroup
        val inlineMedia = container.findViewById<View>(inlineMediaId) as? ViewGroup
        val content = container.findViewById<View>(contentId)
        val parent = when {
            content?.visibility == View.VISIBLE && imageContainer?.visibility == View.VISIBLE -> imageContainer
            inlineMedia?.visibility == View.VISIBLE -> inlineMedia
            else -> return false
        }
        val previousVisibility = ArrayList<Pair<View, Int>>()

        fun remember(view: View?) {
            if (view == null) return
            previousVisibility.add(view to view.visibility)
        }

        if (parent === imageContainer) {
            remember(parent.findViewById(Utils.getResId("chat_list_item_embed_image", "id")))
            remember(parent.findViewById(Utils.getResId("chat_list_item_embed_image_icons", "id")))
        } else {
            for (index in 0 until parent.childCount) remember(parent.getChildAt(index))
        }

        // The image card wraps its children. A MATCH_PARENT WebView cannot
        // provide its own size once the native preview is removed.
        val previewId = Utils.getResId(
            if (parent === imageContainer) "chat_list_item_embed_image" else "inline_media_image_preview",
            "id",
        )
        val preview = parent.findViewById<View>(previewId)
        val video = embed.m()
        val thumbnail = embed.h()
        val sourceWidth = video?.d()?.takeIf { it > 0 }
            ?: thumbnail?.d()?.takeIf { it > 0 }
            ?: 16
        val sourceHeight = video?.a()?.takeIf { it > 0 }
            ?: thumbnail?.a()?.takeIf { it > 0 }
            ?: 9
        val embedResources = EmbedResourceUtils.INSTANCE
        val fallbackSize = embedResources.calculateScaledSize(
            sourceWidth,
            sourceHeight,
            embedResources.computeMaximumImageWidthPx(parent.context),
            embedResources.getMAX_IMAGE_VIEW_HEIGHT_PX(),
            parent.resources,
            0,
        )
        val playerWidth = (parent.width - parent.paddingLeft - parent.paddingRight).takeIf { it > 0 }
            ?: preview?.layoutParams?.width?.takeIf { it > 0 }
            ?: fallbackSize.first
        val playerHeight = (parent.height - parent.paddingTop - parent.paddingBottom).takeIf { it > 0 }
            ?: preview?.layoutParams?.height?.takeIf { it > 0 }
            ?: fallbackSize.second

        val webView = WebView(parent.context)
        // The player must be visible while streaming. Waiting for page-finished
        // can leave an already-playing video hidden behind its grey preview.
        webView.visibility = View.VISIBLE
        webView.setBackgroundColor(Color.BLACK)
        webView.isHorizontalScrollBarEnabled = false
        webView.isVerticalScrollBarEnabled = false
        webView.settings.apply {
            javaScriptEnabled = false
            domStorageEnabled = false
            // This WebView is created by an embed/play-button tap.
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
            allowContentAccess = false
            allowFileAccess = false
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
        }
        webView.webViewClient = object : WebViewClient() {
            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return !EmbedUrls.isHttpUrl(url)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in Java")
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                showFullscreen(webView, view, callback)
            }

            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in Java")
            override fun onShowCustomView(
                view: View,
                requestedOrientation: Int,
                callback: CustomViewCallback,
            ) {
                showFullscreen(webView, view, callback)
            }

            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in Java")
            override fun onHideCustomView() {
                hideFullscreen(webView)
            }
        }

        val posterUrl = thumbnail?.b()?.takeIf(EmbedUrls::isHttpUrl)
            ?: thumbnail?.c()?.takeIf(EmbedUrls::isHttpUrl)
        val posterAttribute = posterUrl?.let { " poster=\"${escapeHtmlAttribute(it)}\"" }.orEmpty()
        // Preserve the whitespace: trimIndent calls isBlank, which crashes with
        // Discord's obfuscated Kotlin runtime. HTML ignores this indentation.
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>html,body{width:100%;height:100%;margin:0;padding:0;overflow:hidden}</style>
              </head>
              <body style="margin:0;padding:0;background:#000">
                <video controls autoplay playsinline preload="metadata"$posterAttribute
                  src="${escapeHtmlAttribute(videoUrl)}"
                  style="display:block;width:100%;height:100%;object-fit:contain"></video>
              </body>
            </html>
        """

        val layoutParams = if (parent is FrameLayout) {
            FrameLayout.LayoutParams(
                playerWidth,
                playerHeight,
            )
        } else {
            ViewGroup.LayoutParams(
                playerWidth,
                playerHeight,
            )
        }
        // Match PlayableEmbeds' image-card replacement: remove the original
        // preview from layout and give the visible player its own dimensions.
        previousVisibility.forEach { (child, _) -> child.visibility = View.GONE }
        parent.addView(webView, layoutParams)
        webView.bringToFront()
        val playerState = InlineVideoState(webView, parent, previousVisibility, videoUrl)
        webView.tag = playerState
        inlineVideoPlayers[container] = playerState
        hostedEmbeds[container] = embed
        activeEmbedWebViews.add(webView)
        // loadData uses a data URL: '#' truncates unencoded HTML and '%' can
        // decode signed/redirect URLs. An HTTP(S) base loads the HTML verbatim.
        val baseUrl = embed.l()?.takeIf(EmbedUrls::isHttpUrl) ?: videoUrl
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
        container.requestLayout()
        return true
    }

    private fun escapeHtmlAttribute(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun removeInlineVideoPlayer(container: ViewGroup) {
        val state = inlineVideoPlayers.remove(container) ?: return
        activeEmbedWebViews.remove(state.webView)
        hideFullscreen(state.webView)
        state.webView.tag = null
        state.webView.stopLoading()
        state.webView.loadUrl("about:blank")
        (state.webView.parent as? ViewGroup)?.removeView(state.webView)
        state.webView.destroy()
        state.previousVisibility.forEach { (view, visibility) ->
            view.visibility = visibility
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private fun removeHostedPlayer(container: ViewGroup) {
        val state = hostedPlayers.remove(container) ?: return
        val replacement = state.replacement
        val webView = state.webView
        activeEmbedWebViews.remove(webView)
        hideFullscreen(webView)
        webView.tag = null
        webView.stopLoading()
        webView.loadUrl("about:blank")
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        state.extraView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        state.parent.minimumWidth = state.previousMinimumWidth
        state.previousVisibility.forEach { (view, visibility) ->
            view.visibility = visibility
        }
        if (replacement != null) {
            (replacement.originalCard.parent as? ViewGroup)?.removeView(replacement.originalCard)
            replacement.wrapper.id = View.generateViewId()
            replacement.originalCard.id = replacement.originalId
            (replacement.wrapper.parent as? ViewGroup)?.removeView(replacement.wrapper)
            val insertionIndex = replacement.originalIndex.coerceIn(0, replacement.originalParent.childCount)
            replacement.originalParent.addView(
                replacement.originalCard,
                insertionIndex,
                replacement.originalLayoutParams,
            )
            moveEmbedViewState(replacement.wrapper, replacement.originalCard)
            replacement.originalParent.requestLayout()
        } else {
            container.requestLayout()
        }
    }

    @Suppress("DEPRECATION")
    private fun showFullscreen(webView: WebView, customView: View, callback: WebChromeClient.CustomViewCallback) {
        val activity = activityFrom(webView.context) ?: run {
            callback.onCustomViewHidden()
            return
        }
        hideFullscreen(webView)

        val decor = activity.window.decorView as? ViewGroup ?: run {
            callback.onCustomViewHidden()
            return
        }
        val playerParent = webView.parent as? ViewGroup
        val hostedReplacement = hostedPlayers.values
            .firstOrNull { it.webView === webView }
            ?.replacement
        (customView.parent as? ViewGroup)?.removeView(customView)
        val previousSystemUiVisibility = decor.systemUiVisibility
        val wasFullscreen = activity.window.attributes.flags and
            WindowManager.LayoutParams.FLAG_FULLSCREEN != 0
        val overlay = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    hideFullscreen(webView)
                    true
                } else {
                    false
                }
            }
            addView(
                customView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        decor.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        webView.visibility = View.INVISIBLE
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        decor.systemUiVisibility = previousSystemUiVisibility or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        overlay.requestFocus()
        val fullscreenState = FullscreenState(
            activity,
            overlay,
            callback,
            previousSystemUiVisibility,
            wasFullscreen,
            playerParent,
            webView.layoutParams?.let(::copyLayoutParams),
            hostedReplacement,
        )
        overlay.tag = fullscreenState
        fullscreenPlayers[webView] = fullscreenState
    }

    @Suppress("DEPRECATION")
    private fun hideFullscreen(webView: WebView) {
        val state = fullscreenPlayers.remove(webView) ?: return
        state.overlay.tag = null
        state.overlay.removeAllViews()
        (state.overlay.parent as? ViewGroup)?.removeView(state.overlay)
        webView.visibility = View.VISIBLE
        state.activity.window.decorView.systemUiVisibility = state.previousSystemUiVisibility
        if (!state.wasFullscreen) {
            state.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        state.callback.onCustomViewHidden()
        webView.post {
            if (webView.parent === state.playerParent) {
                state.playerLayoutParams?.let { webView.layoutParams = copyLayoutParams(it) }
            }
            state.embedReplacement?.let { replacement ->
                val wrapper = replacement.wrapper
                if (wrapper.parent != null) {
                    wrapper.layoutParams = copyLayoutParams(replacement.originalLayoutParams).apply {
                        width = replacement.originalWidth
                        height = replacement.originalHeight
                    }
                    wrapper.minimumWidth = replacement.originalWidth
                    wrapper.minimumHeight = replacement.originalHeight
                    replacement.originalCard.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    wrapper.requestLayout()
                }
            }
            state.playerParent?.requestLayout()
            state.playerParent?.invalidate()
        }
    }

    private fun activityFrom(context: Context): Activity? {
        var current: Context? = context
        while (current != null) {
            if (current is Activity) return current
            current = (current as? android.content.ContextWrapper)?.baseContext
        }
        return null
    }

    private fun addSpotifyPlayButton(container: ViewGroup, listener: View.OnClickListener) {
        val existing = spotifyPlayButtons[container]
        if (existing != null && existing.parent != null) {
            existing.setOnClickListener(listener)
            return
        }
        removeSpotifyPlayButton(container)

        val size = dp(container.context, 32)
        val button = ImageView(container.context)
        val iconId = Utils.getResId("ic_play_arrow_24dp", "drawable")
        if (iconId > 0) button.setImageResource(iconId)
        val backgroundId = Utils.getResId("drawable_circle_primary_900", "drawable")
        if (backgroundId > 0) button.setBackgroundResource(backgroundId)
        button.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        val padding = dp(container.context, 4)
        button.setPadding(padding, padding, padding, padding)
        button.contentDescription = "Play Spotify embed"
        button.isClickable = true
        button.setOnClickListener(listener)

        val thumbnailId = Utils.getResId("chat_list_item_embed_image_thumbnail", "id")
        val imageContainerId = Utils.getResId("embed_image_container", "id")
        val image = listOfNotNull(
            container.findViewById<View>(thumbnailId),
            container.findViewById<View>(imageContainerId),
        ).firstOrNull { it.visibility == View.VISIBLE }
        val imageParent = image?.parent as? ConstraintLayout
        val imageId = image?.id ?: View.NO_ID
        val layoutParams = if (imageParent != null && imageId != View.NO_ID) {
            ConstraintLayout.LayoutParams(size, size).apply {
                startToStart = imageId
                endToEnd = imageId
                topToTop = imageId
                bottomToBottom = imageId
            }
        } else if (container is FrameLayout) {
            FrameLayout.LayoutParams(size, size, Gravity.START or Gravity.CENTER_VERTICAL).apply {
                marginStart = dp(container.context, 4)
            }
        } else {
            ViewGroup.LayoutParams(size, size)
        }
        (imageParent ?: container).addView(button, layoutParams)
        button.elevation = dp(container.context, 2).toFloat()
        button.bringToFront()
        spotifyPlayButtons[container] = button
    }

    private fun removeSpotifyPlayButton(container: ViewGroup) {
        val button = spotifyPlayButtons.remove(container) ?: return
        (button.parent as? ViewGroup)?.removeView(button)
    }

    override fun stop(context: Context) {
        ArrayList(fullscreenPlayers.keys).forEach(::hideFullscreen)
        ArrayList(hostedPlayers.keys).forEach(::removeHostedPlayer)
        ArrayList(inlineVideoPlayers.keys).forEach(::removeInlineVideoPlayer)
        ArrayList(spotifyPlayButtons.keys).forEach(::removeSpotifyPlayButton)
        embedLinks.clear()
        hostedEmbeds.clear()
        hostedPlayers.clear()
        inlineVideoPlayers.clear()
        fullscreenPlayers.clear()
        spotifyPlayButtons.clear()
        activeEmbedWebViews.clear()
        embedRowKeys.clear()
        searchEmbedClicks.clear()
        patcher.unpatchAll()
    }

    private companion object {
        const val SPOTIFY_PLAYER_HEIGHT_DP = 152
    }
}
