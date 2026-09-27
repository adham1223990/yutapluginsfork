package com.github.yutaplug.profileeffects

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import b.f.g.c.c
import com.discord.utilities.images.MGImages
import com.facebook.drawee.view.SimpleDraweeView
import com.facebook.imagepipeline.image.ImageInfo

internal class EffectOverlay(context: Context, private val log: (String, Throwable) -> Unit) :
    TouchThroughLayout(context) {
    private var effect: Product.Effect? = null
    private var animate = true
    private var webView: WebView? = null
    private var staticImage: SimpleDraweeView? = null
    private var pageReady = false
    private var disposed = false
    private val cleanupHandler = Handler(Looper.getMainLooper())
    private val cleanup = DeferredCleanup({ cleanupHandler.post(it) }, { cleanupHandler.removeCallbacks(it) })
    private val restart = Runnable {
        if (!disposed && isShown && windowToken != null) {
            if (webView == null) rebuild() else play()
        }
    }

    fun bind(effect: Product.Effect?, animate: Boolean) {
        if (this.effect == effect && this.animate == animate) return
        this.effect = effect
        this.animate = animate
        rebuild()
    }

    fun restartAnimation() {
        removeCallbacks(restart)
        if (!disposed) postDelayed(restart, 120)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!disposed) {
            cleanup.cancel()
            restartAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        pause()
        if (!disposed) {
            // View.post() can retain this until a future attach; use the main Handler instead.
            cleanup.schedule { if (windowToken == null) clearRenderer() }
        }
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) restartAnimation() else pause()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        resize()
    }

    private fun rebuild() {
        clearRenderer()
        val effect = effect ?: return
        if (disposed || windowToken == null) return
        if (!animate || effect.layers.isEmpty()) {
            showStatic(effect.source)
            return
        }
        try {
            val view = WebView(context)
            webView = view
            view.setBackgroundColor(Color.TRANSPARENT)
            view.isClickable = false
            view.isFocusable = false
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            view.isVerticalScrollBarEnabled = false
            view.isHorizontalScrollBarEnabled = false
            view.overScrollMode = View.OVER_SCROLL_NEVER
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                loadsImagesAutomatically = true
                setSupportZoom(false)
                setSupportMultipleWindows(false)
            }
            view.webViewClient = object : WebViewClient() {
                @Suppress("OVERRIDE_DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String) = true

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                override fun onPageFinished(view: WebView, url: String) {
                    if (webView !== view || disposed) return
                    pageReady = true
                    if (isShown && windowToken != null) play() else pause()
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && webView === view) fallback()
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onReceivedError(view: WebView, code: Int, description: String, url: String) {
                    if (webView === view && url == "$CDN/") fallback()
                }
            }
            addView(view, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1))
            resize()
            view.loadDataWithBaseURL("$CDN/", EffectDocument.html(effect), "text/html", "UTF-8", null)
        } catch (error: Exception) {
            log("Could not create the profile effect renderer", error)
            fallback()
        }
    }

    private fun fallback() {
        clearRenderer()
        if (!disposed) showStatic(effect?.source)
    }

    private fun showStatic(source: String?) {
        if (source == null) return
        val image = SimpleDraweeView(context)
        staticImage = image
        image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(image, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        MGImages.setImage(
            image,
            listOf(source),
            0,
            0,
            false,
            null,
            MGImages.AlwaysUpdateChangeDetector.INSTANCE,
            object : c<ImageInfo>() {
                override fun onFinalImageSet(id: String?, info: ImageInfo?, animatable: Animatable?) {
                    if (disposed || staticImage !== image) return
                    if (info != null && info.width > 0 && info.height > 0) {
                        image.aspectRatio = info.width.toFloat() / info.height
                    }
                    // Reduced-motion/static assets must not auto-play through Fresco.
                    animatable?.stop()
                }
            },
        )
    }

    private fun resize() {
        val view = webView ?: return
        val effect = effect ?: return
        val params = view.layoutParams
        val surfaceHeight = EffectDocument.surfaceHeight(effect, width, height)
        val surfaceWidth = width.coerceIn(1, MAX_SURFACE_SIZE)
        if (params.height != surfaceHeight || params.width != surfaceWidth) {
            params.height = surfaceHeight
            params.width = surfaceWidth
            view.layoutParams = params
        }
    }

    private fun play() {
        val view = webView ?: return
        view.onResume()
        if (pageReady) view.evaluateJavascript("window.restartEffects&&window.restartEffects();", null)
    }

    private fun pause() {
        removeCallbacks(restart)
        val view = webView ?: return
        if (pageReady) view.evaluateJavascript("window.pauseEffects&&window.pauseEffects();", null)
        // pauseTimers() would pause every WebView in Discord, including unrelated plugins.
        view.onPause()
    }

    private fun clearRenderer() {
        cleanup.cancel()
        removeCallbacks(restart)
        pageReady = false
        staticImage?.controller = null
        staticImage = null
        val view = webView
        webView = null
        if (view != null) {
            removeView(view)
            view.stopLoading()
            view.onPause()
            view.destroy()
        }
        removeAllViews()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        pause()
        // Destroying/removing children inline can invalidate an ancestor's detach traversal.
        cleanup.schedule(::clearRenderer)
    }
}
