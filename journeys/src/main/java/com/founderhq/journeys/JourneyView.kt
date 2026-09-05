package com.founderhq.journeys

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.Executors

class JourneyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var configuration: JourneyConfiguration? = null
    private var listener: JourneyListener = object : JourneyListener {}
    private var journeyConfig: JSONObject? = null
    private var rendererReady = false
    var canGoBack: Boolean = false
        private set
    var currentStepId: String? = null
        private set
    var currentStepIndex: Int = 0
        private set
    private var disposed = false
    private var boundController: JourneyController? = null
    private val queuedCommands = mutableListOf<JSONObject>()
    private var loadingView: View? = null
    private var lifecycleOwner: LifecycleOwner? = null

    /** Optional native loading and error UI factories. */
    var loadingViewFactory: ((Context) -> View)? = null
    var errorViewFactory: ((Context, JourneyError, () -> Unit) -> View)? = null
    var hapticHandler: ((JourneyHapticType) -> Unit)? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            handleBackPressed()
        }
    }
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            flushCaptureForLifecycle()
        }
    }

    val controller = JourneyController()

    init {
        setBackgroundColor(BACKGROUND_COLOR)
    }

    @JvmOverloads
    fun load(
        configuration: JourneyConfiguration,
        listener: JourneyListener = object : JourneyListener {},
        controller: JourneyController = this.controller,
    ) {
        disposed = false
        this.configuration = configuration
        this.listener = listener
        this.journeyConfig = null
        this.rendererReady = false
        this.canGoBack = false
        this.currentStepId = null
        this.currentStepIndex = 0
        backCallback.isEnabled = false
        queuedCommands.clear()
        boundController?.sink = null
        boundController = controller
        showLoading()
        IO_EXECUTOR.execute {
            try {
                configuration.resolvedRendererUrl()
                val config = JourneyApiClient.fetchConfiguration(configuration)
                mainHandler.post {
                    if (disposed || this.configuration !== configuration) return@post
                    journeyConfig = config
                    createAndLoadWebView()
                }
            } catch (error: Throwable) {
                postFailure("load_failed", error, true)
            }
        }
    }

    fun dispose() {
        flushCaptureForLifecycle()
        disposed = true
        unbindPlatformLifecycle()
        boundController?.sink = null
        boundController = null
        webView?.let { current ->
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                runCatching { WebViewCompat.removeWebMessageListener(current, JourneyBridge.HANDLER) }
            }
            current.stopLoading()
            current.webViewClient = WebViewClient()
            current.destroy()
        }
        webView = null
        removeAllViews()
    }

    override fun onDetachedFromWindow() {
        flushCaptureForLifecycle()
        unbindPlatformLifecycle()
        super.onDetachedFromWindow()
        webView?.onPause()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bindPlatformLifecycle()
        webView?.onResume()
    }

    /** Consumes Android Back when the Journey can navigate backward. */
    fun handleBackPressed(): Boolean {
        if (!canGoBack) return false
        (boundController ?: controller).goBack()
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createAndLoadWebView() {
        val config = configuration ?: return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            showFailure(
                JourneyError(
                    "unsupported_webview",
                    "Android System WebView must be updated to display this Journey",
                    true,
                ),
            )
            return
        }

        webView?.destroy()
        rendererReady = false
        canGoBack = false
        backCallback.isEnabled = false
        val rendererUrl = config.resolvedRendererUrl()
        val rendererUri = Uri.parse(rendererUrl)
        val origin = buildString {
            append(rendererUri.scheme).append("://").append(rendererUri.host)
            if (rendererUri.port != -1) append(":").append(rendererUri.port)
        }
        val current = WebView(context).apply {
            setBackgroundColor(BACKGROUND_COLOR)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = journeyWebViewClient(rendererUrl)
        }
        WebViewCompat.addWebMessageListener(
            current,
            JourneyBridge.HANDLER,
            setOf(origin),
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy,
                ) {
                    if (!isMainFrame || !sameOrigin(sourceOrigin, rendererUri)) return
                    handleBridgeMessage(message.data)
                }
            },
        )
        webView = current
        removeAllViews()
        addView(current, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        showLoadingOverlay()
        current.loadUrl(rendererUrl)
    }

    private fun journeyWebViewClient(rendererUrl: String) = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url.toString()
            if (target == rendererUrl || target == "about:blank") return false
            if (request.isForMainFrame) listener.onOpenUrl(request.url)
            return true
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) {
                val journeyError = JourneyError(
                    "renderer_load_failed",
                    error.description?.toString() ?: "Journey renderer failed to load",
                    true,
                )
                listener.onError(journeyError)
                showFailure(journeyError)
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            removeView(view)
            view.destroy()
            webView = null
            listener.onError(
                JourneyError(
                    "renderer_process_gone",
                    "The Journey renderer restarted after a WebView process failure",
                    true,
                ),
            )
            if (!disposed) createAndLoadWebView()
            return true
        }
    }

    private fun handleBridgeMessage(raw: String?) {
        val message = JourneyBridge.parse(raw)
        if (message == null) {
            listener.onError(
                JourneyError(
                    "invalid_bridge_message",
                    "The Journey renderer sent an invalid message",
                    true,
                ),
            )
            return
        }
        try {
            when (message.type) {
                "ready" -> {
                    rendererReady = true
                    initializeRenderer()
                }
                "rendered" -> hideLoadingOverlay()
                "navigation_state" -> {
                    currentStepId = message.payload.optString("stepId")
                    currentStepIndex = message.payload.optInt("stepIndex")
                    canGoBack = message.payload.optBoolean("canGoBack")
                    backCallback.isEnabled = canGoBack
                }
                "haptic" -> JourneyHapticType
                    .fromWireValue(message.payload.optString("type"))
                    ?.let(::performJourneyHaptic)
                "event" -> message.payload.optJSONObject("event")
                    ?.let(::JourneyEvent)
                    ?.let(listener::onEvent)
                "capture_request" -> handleCapture(message.payload)
                "discount_code_request" -> handleDiscount(message.payload)
                "open_url" -> message.payload.optString("url")
                    .takeIf(String::isNotBlank)
                    ?.let { openUrl(Uri.parse(it)) }
                "error" -> {
                    val error = JourneyError(
                        message.payload.optString("code", "renderer_error"),
                        message.payload.optString("message", "Journey renderer error"),
                        message.payload.optBoolean("recoverable", true),
                    )
                    listener.onError(error)
                    if (!error.recoverable) showFailure(error)
                }
            }
        } catch (error: Throwable) {
            listener.onError(
                JourneyError("bridge_handler_failed", error.message ?: "Bridge handler failed", true, error),
            )
        }
    }

    private fun initializeRenderer() {
        val config = configuration ?: return
        val renderedConfig = journeyConfig ?: return
        val payload = JSONObject()
            .put("journeyId", config.journeyId)
            .put("config", renderedConfig)
            .put("capture", config.capture?.toJson() ?: false)
            .put("initialAnswers", config.initialAnswers)
            .put("initialOptions", config.initialOptions)
            .put("platform", "android")
            .put("sdkVersion", JourneyBridge.SDK_VERSION)
        config.identity?.let { payload.put("identity", it.toJson()) }
        config.storageKey?.let { payload.put("storageKey", it) }
        config.theme?.let { payload.put("theme", it) }
        send("initialize", payload)
        boundController?.sink = { command ->
            if (rendererReady) send("command", command)
            else queuedCommands.add(command)
        }
        queuedCommands.forEach { send("command", it) }
        queuedCommands.clear()
    }

    private fun handleCapture(payload: JSONObject) {
        val config = configuration ?: return
        val requestId = payload.optString("requestId")
        val body = payload.optJSONObject("body") ?: return
        if (requestId.isBlank()) return
        IO_EXECUTOR.execute {
            val response = JSONObject().put("requestId", requestId)
            try {
                val status = JourneyApiClient.capture(config, body)
                response.put("ok", true).put("status", status)
            } catch (error: Throwable) {
                response
                    .put("ok", false)
                    .put("error", error.message ?: "Capture failed")
            }
            mainHandler.post { if (!disposed) send("capture_response", response) }
        }
    }

    private fun handleDiscount(payload: JSONObject) {
        val requestId = payload.optString("requestId")
        val request = payload.optJSONObject("request") ?: return
        if (requestId.isBlank()) return
        listener.onDiscountCodeApply(JourneyDiscountCodeRequest(request)) { result ->
            mainHandler.post {
                if (disposed) return@post
                val response = JSONObject().put("requestId", requestId)
                result.fold(
                    onSuccess = { response.put("result", it.raw) },
                    onFailure = { response.put("error", it.message ?: "Discount code failed") },
                )
                send("discount_code_response", response)
            }
        }
    }

    private fun send(type: String, payload: JSONObject) {
        if (!rendererReady && type != "initialize") return
        webView?.evaluateJavascript(JourneyBridge.dispatchScript(type, payload), null)
    }

    private fun showLoading() {
        removeAllViews()
        val view = createLoadingView()
        loadingView = view
        addView(view, loadingLayoutParams(view))
    }

    private fun showLoadingOverlay() {
        hideLoadingOverlay()
        val view = createLoadingView()
        loadingView = view
        addView(view, loadingLayoutParams(view))
        view.bringToFront()
    }

    private fun hideLoadingOverlay() {
        loadingView?.let(::removeView)
        loadingView = null
    }

    private fun createLoadingView(): View = loadingViewFactory?.invoke(context) ?: ProgressBar(context)

    private fun loadingLayoutParams(view: View): LayoutParams =
        if (view is ProgressBar) {
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        } else {
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

    private fun retryCurrentConfiguration() {
        val config = configuration ?: return
        load(config, listener, boundController ?: controller)
    }

    private fun showFailure(error: JourneyError) {
        webView?.let { current ->
            removeView(current)
            current.stopLoading()
            current.destroy()
        }
        webView = null
        removeAllViews()
        loadingView = null
        val retry = ::retryCurrentConfiguration
        errorViewFactory?.invoke(context, error, retry)?.let { custom ->
            addView(custom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            return
        }
        addView(
            TextView(context).apply {
                text = "This Journey is unavailable\n\n${error.message}"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(48, 48, 48, 48)
                setOnClickListener { retry() }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    private fun postFailure(code: String, error: Throwable, recoverable: Boolean) {
        mainHandler.post {
            if (disposed) return@post
            showFailure(
                JourneyError(code, error.message ?: "Journey failed to load", recoverable, error),
            )
            listener.onError(
                JourneyError(code, error.message ?: "Journey failed to load", recoverable, error),
            )
        }
    }

    private fun openUrl(uri: Uri) {
        if (listener.onOpenUrl(uri)) return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: Throwable) {
            listener.onError(
                JourneyError("open_url_failed", error.message ?: "Could not open URL", true, error),
            )
        }
    }

    private fun performJourneyHaptic(type: JourneyHapticType) {
        hapticHandler?.let {
            it(type)
            return
        }
        val constant = when (type) {
            JourneyHapticType.SELECTION -> HapticFeedbackConstants.CLOCK_TICK
            JourneyHapticType.LIGHT -> HapticFeedbackConstants.KEYBOARD_TAP
            JourneyHapticType.MEDIUM -> HapticFeedbackConstants.CONTEXT_CLICK
            JourneyHapticType.HEAVY -> HapticFeedbackConstants.LONG_PRESS
            JourneyHapticType.SUCCESS -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
            JourneyHapticType.WARNING, JourneyHapticType.ERROR -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        }
        (webView ?: this).performHapticFeedback(constant)
    }

    private fun flushCaptureForLifecycle() {
        if (disposed) return
        (boundController ?: controller).flushCapture()
    }

    private fun bindPlatformLifecycle() {
        val owner = findViewTreeLifecycleOwner()
        if (owner !== lifecycleOwner) {
            lifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
            lifecycleOwner = owner
            owner?.lifecycle?.addObserver(lifecycleObserver)
        }
        backCallback.remove()
        findViewTreeOnBackPressedDispatcherOwner()
            ?.onBackPressedDispatcher
            ?.addCallback(backCallback)
        backCallback.isEnabled = canGoBack
    }

    private fun unbindPlatformLifecycle() {
        lifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
        lifecycleOwner = null
        backCallback.remove()
    }

    companion object {
        private val IO_EXECUTOR = Executors.newCachedThreadPool()
        private val BACKGROUND_COLOR = Color.rgb(20, 18, 16)
    }

    private fun sameOrigin(first: Uri, second: Uri): Boolean =
        first.scheme.equals(second.scheme, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) &&
            normalizedPort(first) == normalizedPort(second)

    private fun normalizedPort(uri: Uri): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }
}
