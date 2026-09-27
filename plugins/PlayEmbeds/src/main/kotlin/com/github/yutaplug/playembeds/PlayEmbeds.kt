package com.github.yutaplug.playembeds

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import androidx.constraintlayout.widget.ConstraintLayout
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.MessageEmbed
import com.discord.player.MediaSource
import com.discord.player.MediaType
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.media.WidgetMedia
import java.util.Locale
import java.util.WeakHashMap
import b.a.d.j as AppScreen

@AliucordPlugin(requiresRestart = true)
@Suppress("unused")
class PlayEmbeds : Plugin() {
    private val embedLinks = WeakHashMap<ViewGroup, EmbedLink>()
    private val hostedEmbeds = WeakHashMap<ViewGroup, MessageEmbed>()
    private val hostedPlayers = WeakHashMap<ViewGroup, HostedPlayerState>()
    private val inlineVideoPlayers = WeakHashMap<ViewGroup, InlineVideoState>()
    private val spotifyPlayButtons = WeakHashMap<ViewGroup, ImageView>()
    private val fullscreenPlayers = WeakHashMap<WebView, FullscreenState>()

    override fun start(context: Context) {
        patchEmbedRows()
        patchMediaLinks()
        patchNativeHostedLaunches()
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
                embedContainers(item.itemView).forEach(::resetEmbedContainer)
            },
        )
        patcher.patch(
            onConfigure,
            Hook { frame ->
                val item = frame.thisObject as? WidgetChatListAdapterItemEmbed ?: return@Hook
                val entry = frame.args.getOrNull(1) as? EmbedEntry ?: return@Hook
                val adapter = getAdapter.invoke(null, item) as WidgetChatListAdapter
                val handler = adapter.eventHandler
                if (handler.javaClass.name == "com.discord.widgets.search.results.WidgetSearchResults\$SearchResultAdapterEventHandler") {
                    // Search previews navigate to the message; they must not
                    // install playback mappings or intercept media clicks.
                    setClickListeners(item.itemView, View.OnClickListener {
                        handler.onMessageClicked(entry.message, entry.isThreadStarterMessage)
                    })
                    return@Hook
                }
                val link = embedLink(entry.embed) ?: return@Hook
                attachMediaClickHandlers(item.itemView, link)
            },
        )
    }

    /**
     * Embed titles and media previews are normally routed through UriHandler.
     * Intercepting only URLs that look like media keeps ordinary web links unchanged.
     */
    private fun patchMediaLinks() {
        val handleOrUntrusted = UriHandler::class.java.getDeclaredMethod(
            "handleOrUntrusted",
            Context::class.java,
            String::class.java,
            String::class.java,
        )

        patcher.patch(
            handleOrUntrusted,
            PreHook { frame ->
                val context = frame.args.getOrNull(0) as? Context ?: return@PreHook
                val url = frame.args.getOrNull(1) as? String ?: return@PreHook
                val media = mediaLink(url)
                if (media != null) {
                    openInPlayer(context, media)
                    frame.setResult(null)
                }
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
                if (genericVideoUrl(embed) != null) {
                    val container = findHostedContainer(embed)
                    if (container != null) {
                        playInlineVideoEmbed(container, embed)
                        frame.setResult(null)
                        return@PreHook
                    }
                }
                val hosted = hostedEmbed(embed) ?: return@PreHook
                val container = findHostedContainer(embed) ?: return@PreHook
                openHostedEmbed(container, hosted)
                frame.setResult(null)
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
                val embed = container?.let { hostedEmbeds[it] }
                if (container != null && embed != null && genericVideoUrl(embed) != null) {
                    playInlineVideoEmbed(container, embed)
                    frame.setResult(null)
                    return@PreHook
                }
                val url = mediaSourceUrl(frame.thisObject) ?: return@PreHook
                if (mediaLink(url) != null) return@PreHook
                val hosted = hostedEmbed(url) ?: return@PreHook
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
                if (genericVideoUrl(embed) != null) {
                    val source = frame.args.getOrNull(0) as? View
                        ?: viewGroupField(frame.thisObject)
                        ?: return@PreHook
                    val container = findHostedContainer(source) ?: return@PreHook
                    playInlineVideoEmbed(container, embed)
                    frame.setResult(null)
                    return@PreHook
                }
                val hosted = hostedEmbed(embed) ?: return@PreHook
                val source = viewGroupField(frame.thisObject)
                    ?: frame.args.getOrNull(0) as? View
                    ?: return@PreHook
                if (!openHostedEmbed(source, hosted)) return@PreHook
                frame.setResult(null)
            },
        )
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
            setClickListeners(container, listener)
            if (link is EmbedLink.Hosted && link.embed.provider == HostedProvider.SPOTIFY) {
                addSpotifyPlayButton(container, listener)
            }
        }
    }

    private fun resetEmbedContainer(container: ViewGroup) {
        removeHostedPlayer(container)
        removeInlineVideoPlayer(container)
        removeSpotifyPlayButton(container)
        embedLinks.remove(container)
        hostedEmbeds.remove(container)
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
            is EmbedLink.Direct -> openInPlayer(view.context, link.media)
            is EmbedLink.Hosted -> openHostedEmbed(view, link.embed)
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

    private fun setClickListeners(view: View, listener: View.OnClickListener) {
        if (view is WebView) return
        if (view.id == Utils.getResId("chat_list_item_embed_spoiler", "id")) return
        view.setOnClickListener(listener)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                setClickListeners(view.getChildAt(index), listener)
            }
        }
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

    private fun openInPlayer(context: Context, media: MediaLink) {
        try {
            val source = MediaSource(
                Uri.parse(media.url),
                FEATURE_TAG,
                // Discord's player has a progressive media backend; VIDEO is
                // also the audio-capable mode because MediaType has no AUDIO value.
                MediaType.VIDEO,
            )
            val intent = Intent()
                .putExtra(INTENT_TITLE, media.title ?: media.url)
                .putExtra(INTENT_URL, media.url)
                .putExtra(INTENT_IMAGE_URL, media.previewUrl ?: media.url)
                .putExtra(INTENT_WIDTH, media.width ?: DEFAULT_MEDIA_SIZE)
                .putExtra(INTENT_HEIGHT, media.height ?: DEFAULT_MEDIA_SIZE)
                .putExtra(INTENT_MEDIA_SOURCE, source)

            // This is Discord's normal media screen, so playback stays in the
            // app and uses the same controls and ExoPlayer configuration as
            // native media attachments.
            AppScreen.d(context, WidgetMedia::class.java, intent)
        } catch (_: Throwable) {
            // A client update may move the internal media screen. Preserve the
            // feature with Android's default player instead of failing silently.
            openWithDefaultPlayer(context, media)
        }
    }

    private fun openWithDefaultPlayer(context: Context, media: MediaLink) {
        val typedIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(media.url), media.mimeType)
        }
        try {
            context.startActivity(typedIntent)
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(media.url)))
            } catch (_: ActivityNotFoundException) {
                Utils.showToast("No media player found", true)
            }
        }
    }

    private fun openHostedEmbed(view: View, embed: HostedEmbed): Boolean {
        val container = findHostedContainer(view)
            ?: embed.sourceEmbed?.let(::findHostedContainer)
            ?: return false
        openHostedEmbed(container, embed)
        return true
    }

    /**
     * YouTube and SoundCloud URLs are webpages, not progressive media files.
     * Add their provider player to the embed itself instead of opening a
     * dialog, external app, or browser.
     */
    private fun openHostedEmbed(container: ViewGroup, embed: HostedEmbed) {
        removeSpotifyPlayButton(container)
        val state = hostedPlayers[container]
        val existing = state?.webView
        if (existing != null) {
            if (existing.parent == state?.parent) {
                existing.visibility = View.VISIBLE
                existing.bringToFront()
                loadHostedPlayer(existing, embed)
                return
            }
            removeHostedPlayer(container)
        }

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
        } ?: return
        val playerParent = preview.parent as? ViewGroup ?: return
        val width = preview.width.takeIf { it > 0 }
            ?: preview.layoutParams.width.takeIf { it > 0 }
            ?: return
        val height = preview.height.takeIf { it > 0 }
            ?: preview.layoutParams.height.takeIf { it > 0 }
            ?: return
        val playerParams = when (playerParent) {
            is ConstraintLayout -> ConstraintLayout.LayoutParams(preview.layoutParams as ConstraintLayout.LayoutParams)
            is FrameLayout -> FrameLayout.LayoutParams(preview.layoutParams)
            else -> ViewGroup.LayoutParams(preview.layoutParams)
        }.apply {
            this.width = width
            this.height = height
        }

        val webView = object : WebView(container.context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (embed.provider != HostedProvider.YOUTUBE) {
                    return super.dispatchTouchEvent(event)
                }

                // Keep seeking inside the iframe from becoming a swipe of
                // Discord's panels (or a scroll of the surrounding chat).
                val finished = event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                if (!finished) parent?.requestDisallowInterceptTouchEvent(true)
                return try {
                    super.dispatchTouchEvent(event)
                } finally {
                    // WebView can change interception while handling an event.
                    // Hold it for this gesture, then release it when finished.
                    parent?.requestDisallowInterceptTouchEvent(!finished)
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
                return !isHttpUrl(url)
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
        // Keep the original preview in the layout to preserve Discord's image
        // dimensions and constraints. Overlay only its media area, not the card.
        val previousVisibility = ArrayList<Pair<View, Int>>()
        if (playerParent is ConstraintLayout) {
            previousVisibility.add(preview to preview.visibility)
            preview.visibility = View.INVISIBLE
        } else {
            for (index in 0 until playerParent.childCount) {
                val child = playerParent.getChildAt(index)
                previousVisibility.add(child to child.visibility)
                if (child.visibility == View.VISIBLE) child.visibility = View.INVISIBLE
            }
        }
        hostedPlayers[container] = HostedPlayerState(webView, playerParent, previousVisibility)
        playerParent.addView(webView, playerParams)
        webView.bringToFront()
        container.requestLayout()
        loadHostedPlayer(webView, embed)
    }

    private fun loadHostedPlayer(webView: WebView, embed: HostedEmbed) {
        val appOrigin = "https://${webView.context.packageName.lowercase(Locale.ROOT)}"
        val url = hostedPlayerUrl(embed, appOrigin)
        if (embed.provider == HostedProvider.YOUTUBE) {
            // An iframe keeps the embedding origin/referrer available throughout
            // player initialization, including on redirects and frame reloads.
            val html = """
                <!doctype html>
                <html>
                  <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <meta name="referrer" content="strict-origin-when-cross-origin">
                    <style>
                      html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:black}
                      iframe{display:block;width:100%;height:100%;border:0}
                    </style>
                  </head>
                  <body>
                    <iframe src="${escapeHtmlAttribute(url)}" title="YouTube video player"
                      allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
                      referrerpolicy="strict-origin-when-cross-origin" allowfullscreen></iframe>
                  </body>
                </html>
            """
            webView.loadDataWithBaseURL("$appOrigin/", html, "text/html", "UTF-8", null)
        } else {
            webView.loadUrl(url)
        }
    }

    private fun isYoutubePlayerRequest(request: WebResourceRequest): Boolean {
        return request.isForMainFrame ||
            (isHost(request.url.host, "youtube.com") && request.url.path?.startsWith("/embed/") == true)
    }

    private fun playInlineVideoEmbed(container: ViewGroup, embed: MessageEmbed) {
        if (!openInlineVideoEmbed(container, embed)) {
            Utils.showToast("Unable to display the inline video player", true)
        }
    }

    /** Replace the preview on tap with an HTML5 player using Discord's video URL. */
    private fun openInlineVideoEmbed(container: ViewGroup, embed: MessageEmbed): Boolean {
        val videoUrl = genericVideoUrl(embed) ?: return false
        inlineVideoPlayers[container]?.let { state ->
            if (state.embed === embed && state.webView.parent === state.parent) {
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
                return !isHttpUrl(url)
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

        val posterUrl = thumbnail?.b()?.takeIf(::isHttpUrl)
            ?: thumbnail?.c()?.takeIf(::isHttpUrl)
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
        inlineVideoPlayers[container] = InlineVideoState(webView, parent, previousVisibility, embed)
        hostedEmbeds[container] = embed
        // loadData uses a data URL: '#' truncates unencoded HTML and '%' can
        // decode signed/redirect URLs. An HTTP(S) base loads the HTML verbatim.
        val baseUrl = embed.l()?.takeIf(::isHttpUrl) ?: videoUrl
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
        hideFullscreen(state.webView)
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
        val webView = state.webView
        hideFullscreen(webView)
        webView.stopLoading()
        webView.loadUrl("about:blank")
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        state.previousVisibility.forEach { (view, visibility) ->
            view.visibility = visibility
        }
        container.requestLayout()
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
        fullscreenPlayers[webView] = FullscreenState(
            activity,
            overlay,
            callback,
            previousSystemUiVisibility,
            wasFullscreen,
        )
    }

    @Suppress("DEPRECATION")
    private fun hideFullscreen(webView: WebView) {
        val state = fullscreenPlayers.remove(webView) ?: return
        state.overlay.removeAllViews()
        (state.overlay.parent as? ViewGroup)?.removeView(state.overlay)
        webView.visibility = View.VISIBLE
        state.activity.window.decorView.systemUiVisibility = state.previousSystemUiVisibility
        if (!state.wasFullscreen) {
            state.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        state.callback.onCustomViewHidden()
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
        spotifyPlayButtons[container] = button
    }

    private fun removeSpotifyPlayButton(container: ViewGroup) {
        val button = spotifyPlayButtons.remove(container) ?: return
        (button.parent as? ViewGroup)?.removeView(button)
    }

    private fun hostedPlayerUrl(embed: HostedEmbed, appOrigin: String): String {
        return when (embed.provider) {
            HostedProvider.YOUTUBE -> {
                youtubePlayerUrl(embed.url, appOrigin)
            }

            HostedProvider.SOUNDCLOUD -> {
                "https://w.soundcloud.com/player/?url=${Uri.encode(embed.url)}" +
                    "&auto_play=true&hide_related=false&show_comments=true" +
                    "&show_user=true&show_reposts=false&visual=true&show_teaser=false"
            }

            HostedProvider.SPOTIFY -> {
                spotifyPlayerUrl(embed.url)
            }

            HostedProvider.GENERIC -> {
                embed.url
            }
        }
    }

    private fun spotifyPlayerUrl(url: String): String {
        val pathSegments = Uri.parse(url).pathSegments
        val segments = pathSegments
            .filter { it.isNotEmpty() && !it.startsWith("intl-", ignoreCase = true) }
        val contentIndex = segments.indexOfFirst { it.equals("embed", ignoreCase = true) }
        val typeIndex = if (contentIndex >= 0) contentIndex + 1 else 0
        val idIndex = typeIndex + 1
        val type = segments.getOrNull(typeIndex)?.lowercase(Locale.ROOT)
        val id = segments.getOrNull(idIndex)
        if (type !in SPOTIFY_CONTENT_TYPES || id.isNullOrEmpty()) return url
        return "https://open.spotify.com/embed/$type/${Uri.encode(id)}?utm_source=generator"
    }

    private fun youtubePlayerUrl(url: String, appOrigin: String): String {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase(Locale.ROOT)
        val path = uri.path.orEmpty().trim('/')
        val videoId = when {
            host == "youtu.be" -> path.substringBefore('/')
            path.startsWith("embed/") -> path.removePrefix("embed/").substringBefore('/')
            path.startsWith("shorts/") -> path.removePrefix("shorts/").substringBefore('/')
            path.startsWith("live/") -> path.removePrefix("live/").substringBefore('/')
            else -> uri.getQueryParameter("v")
        }?.takeIf { it.length > 0 }

        return if (videoId == null) {
            url
        } else {
            "https://www.youtube.com/embed/${Uri.encode(videoId)}" +
                "?playsinline=1&autoplay=1&origin=${Uri.encode(appOrigin)}"
        }
    }

    private fun embedLink(embed: MessageEmbed): EmbedLink? {
        if (isGifEmbed(embed)) return null
        if (genericVideoUrl(embed) != null) return EmbedLink.InlineVideo(embed)
        return mediaLink(embed)?.let { EmbedLink.Direct(it) }
            ?: hostedEmbed(embed)?.let { EmbedLink.Hosted(it) }
    }

    private fun genericVideoUrl(embed: MessageEmbed): String? {
        if (isGifEmbed(embed)) return null
        val video = embed.m() ?: return null
        val providerName = embed.g()?.a()?.lowercase(Locale.ROOT)
        if (providerName in HOSTED_VIDEO_PROVIDERS) return null

        val urls = listOfNotNull(embed.l(), video.c(), video.b()).filter(::isHttpUrl)
        if (urls.isEmpty() || urls.any(::requiresHostedPlayer)) return null
        return listOfNotNull(video.c(), video.b()).firstOrNull(::isHttpUrl)
    }

    private fun hostedEmbed(embed: MessageEmbed): HostedEmbed? {
        if (isGifEmbed(embed)) return null
        if (genericVideoUrl(embed) != null) return null
        if (mediaLink(embed) != null) return null
        val candidates = listOfNotNull(embed.l(), embed.m()?.c(), embed.m()?.b(), embed.g()?.b())
        for (url in candidates) {
            if (!isHttpUrl(url)) continue
            val provider = hostedProvider(Uri.parse(url).host) ?: continue
            if (provider == HostedProvider.GENERIC && !isGenericHostedEmbed(embed, url)) continue
            return HostedEmbed(url, provider, embed.j(), embed)
        }
        return null
    }

    private fun hostedEmbed(url: String): HostedEmbed? {
        if (!isHttpUrl(url)) return null
        if (isGifUrl(url)) return null
        if (isDiscordNativeVideoUrl(url)) return null
        if (mediaLink(url) != null) return null
        val provider = hostedProvider(Uri.parse(url).host) ?: return null
        return HostedEmbed(url = url, provider = provider)
    }

    private fun isGenericHostedEmbed(embed: MessageEmbed, url: String): Boolean {
        val host = Uri.parse(url).host
        return hostedProvider(host) == HostedProvider.SPOTIFY ||
            embed.k() == EmbedType.VIDEO ||
            embed.k() == EmbedType.GIFV ||
            embed.m() != null
    }

    private fun isGifEmbed(embed: MessageEmbed): Boolean {
        if (embed.k() == EmbedType.GIFV) return true
        val providerName = embed.g()?.a()?.lowercase(Locale.ROOT)
        if (providerName in GIF_PROVIDER_NAMES) return true
        return listOfNotNull(
            embed.l(),
            embed.m()?.c(),
            embed.m()?.b(),
            embed.g()?.b(),
        ).any(::isGifUrl)
    }

    private fun isGifUrl(url: String): Boolean {
        if (!isHttpUrl(url)) return false
        val uri = Uri.parse(url)
        val extension = uri.path
            .orEmpty()
            .substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
        return extension in GIF_EXTENSIONS ||
            GIF_HOSTS.any { domain ->
                isHost(uri.host, domain)
            }
    }

    private fun hostedProvider(host: String?): HostedProvider? {
        return when {
            isHost(host, "youtube.com") || isHost(host, "youtu.be") -> HostedProvider.YOUTUBE
            isHost(host, "soundcloud.com") -> HostedProvider.SOUNDCLOUD
            isHost(host, "spotify.com") || isHost(host, "spotify.link") -> HostedProvider.SPOTIFY
            host?.isNotEmpty() == true -> HostedProvider.GENERIC
            else -> null
        }
    }

    private fun requiresHostedPlayer(url: String): Boolean {
        return when (hostedProvider(Uri.parse(url).host)) {
            HostedProvider.YOUTUBE,
            HostedProvider.SOUNDCLOUD,
            HostedProvider.SPOTIFY -> true
            else -> false
        }
    }

    private fun isDiscordNativeVideoUrl(url: String): Boolean {
        return isHttpUrl(url) && !isGifUrl(url) && !requiresHostedPlayer(url)
    }

    private fun isHost(host: String?, domain: String): Boolean {
        val normalizedHost = host?.lowercase(Locale.ROOT) ?: return false
        return normalizedHost == domain || normalizedHost.endsWith(".$domain")
    }

    private fun mediaLink(embed: MessageEmbed): MediaLink? {
        if (isGifEmbed(embed)) return null
        val video = embed.m()
        val embedUrl = embed.l()?.takeIf(::isHttpUrl)
        val thumbnail = embed.h()
        val previewUrl = thumbnail?.b()?.takeIf(::isHttpUrl)
            ?: thumbnail?.c()?.takeIf(::isHttpUrl)
            ?: video?.b()?.takeIf(::isHttpUrl)
            ?: video?.c()?.takeIf(::isHttpUrl)

        val candidates = listOfNotNull(video?.b(), video?.c(), embedUrl).filter(::isHttpUrl)
        for (url in candidates) {
            val kind = mediaKind(url, hasEmbedVideo = video != null) ?: continue
            return MediaLink(
                url = url,
                mimeType = kind.mimeType,
                title = embed.j(),
                previewUrl = previewUrl,
                width = video?.d()?.takeIf { it > 0 },
                height = video?.a()?.takeIf { it > 0 },
            )
        }
        return null
    }

    private fun mediaLink(url: String): MediaLink? {
        if (!isHttpUrl(url)) return null
        if (isGifUrl(url)) return null
        val kind = mediaKind(url, hasEmbedVideo = isTwitterVideoHost(Uri.parse(url).host)) ?: return null
        return MediaLink(url = url, mimeType = kind.mimeType)
    }

    private fun isTwitterVideoHost(host: String?): Boolean {
        return isHost(host, "video.twimg.com")
    }

    /**
     * An EmbedVideo can contain a webpage (YouTube/SoundCloud) rather than a
     * progressive media URL. Passing those pages to ExoPlayer leaves the
     * built-in media screen loading forever, so only allow extensionless URLs
     * from hosts known to serve the actual media bytes.
     */
    private fun mediaKind(url: String, hasEmbedVideo: Boolean): MediaKind? {
        val uri = Uri.parse(url)
        val path = uri.path ?: return null
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)

        return when {
            extension in VIDEO_EXTENSIONS -> MediaKind.VIDEO
            extension in AUDIO_EXTENSIONS -> MediaKind.AUDIO
            hasEmbedVideo && isDirectMediaHost(uri.host) -> MediaKind.VIDEO
            else -> null
        }
    }

    private fun isDirectMediaHost(host: String?): Boolean {
        return DIRECT_MEDIA_HOSTS.any { domain ->
            isHost(host, domain)
        }
    }

    private fun isHttpUrl(url: String): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase(Locale.ROOT)
        return scheme == "http" || scheme == "https"
    }

    override fun stop(context: Context) {
        ArrayList(fullscreenPlayers.keys).forEach(::hideFullscreen)
        ArrayList(hostedPlayers.keys).forEach(::removeHostedPlayer)
        ArrayList(inlineVideoPlayers.keys).forEach(::removeInlineVideoPlayer)
        ArrayList(spotifyPlayButtons.keys).forEach(::removeSpotifyPlayButton)
        embedLinks.clear()
        hostedEmbeds.clear()
        patcher.unpatchAll()
    }

    private data class MediaLink(
        val url: String,
        val mimeType: String,
        val title: String? = null,
        val previewUrl: String? = null,
        val width: Int? = null,
        val height: Int? = null,
    )

    private data class FullscreenState(
        val activity: Activity,
        val overlay: FrameLayout,
        val callback: WebChromeClient.CustomViewCallback,
        val previousSystemUiVisibility: Int,
        val wasFullscreen: Boolean,
    )

    private data class HostedPlayerState(
        val webView: WebView,
        val parent: ViewGroup,
        val previousVisibility: List<Pair<View, Int>>,
    )

    private data class InlineVideoState(
        val webView: WebView,
        val parent: ViewGroup,
        val previousVisibility: List<Pair<View, Int>>,
        val embed: MessageEmbed,
    )

    private sealed class EmbedLink {
        data class InlineVideo(val embed: MessageEmbed) : EmbedLink()

        data class Direct(val media: MediaLink) : EmbedLink()

        data class Hosted(val embed: HostedEmbed) : EmbedLink()
    }

    private data class HostedEmbed(
        val url: String,
        val provider: HostedProvider,
        val title: String? = null,
        val sourceEmbed: MessageEmbed? = null,
    )

    private enum class HostedProvider(val label: String) {
        YOUTUBE("YouTube"),
        SOUNDCLOUD("SoundCloud"),
        SPOTIFY("Spotify"),
        GENERIC("Hosted media"),
    }

    private enum class MediaKind(val mimeType: String) {
        VIDEO("video/*"),
        AUDIO("audio/*"),
    }

    private companion object {
        const val FEATURE_TAG = "PlayEmbeds"
        const val DEFAULT_MEDIA_SIZE = 1
        const val INTENT_TITLE = "INTENT_TITLE"
        const val INTENT_URL = "INTENT_MEDIA_URL"
        const val INTENT_IMAGE_URL = "INTENT_IMAGE_URL"
        const val INTENT_WIDTH = "INTENT_MEDIA_WIDTH"
        const val INTENT_HEIGHT = "INTENT_MEDIA_HEIGHT"
        const val INTENT_MEDIA_SOURCE = "INTENT_MEDIA_SOURCE"

        val VIDEO_EXTENSIONS = setOf(
            "3gp",
            "avi",
            "flv",
            "m3u8",
            "m4v",
            "mkv",
            "mov",
            "mp4",
            "mpd",
            "mpeg",
            "mpg",
            "ogv",
            "ts",
            "webm",
        )
        val AUDIO_EXTENSIONS = setOf(
            "aac",
            "aiff",
            "amr",
            "flac",
            "m4a",
            "mid",
            "midi",
            "mp3",
            "oga",
            "ogg",
            "opus",
            "wav",
            "weba",
        )
        val DIRECT_MEDIA_HOSTS = setOf(
            "cdn.discordapp.com",
            "cdn.discordapp.net",
            "media.discordapp.com",
            "media.discordapp.net",
            "googlevideo.com",
            "cf-media.sndcdn.com",
            "video.twimg.com",
        )
        val GIF_EXTENSIONS = setOf("gif", "gifv")
        val GIF_HOSTS = setOf("giphy.com", "klipy.com", "tenor.com")
        val GIF_PROVIDER_NAMES = setOf("giphy", "klipy", "tenor")
        val HOSTED_VIDEO_PROVIDERS = setOf("youtube", "soundcloud", "spotify")
        val SPOTIFY_CONTENT_TYPES = setOf(
            "album",
            "artist",
            "episode",
            "playlist",
            "show",
            "track",
        )
    }
}
