package com.founderhq.journeys

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.ComponentCallbacks2
import android.content.res.ColorStateList
import android.content.res.Configuration as AndroidConfiguration
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
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
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
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
import java.util.UUID
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
    private var rendererSupportsPreparation = false
    private var rendererInitialized = false
    private var preparedRendered = false
    private var presentationRequested = false
    private var presented = false
    private var prepareInFlight = false
    private var preparationGeneration = 0L
    private var preparation: JourneyPreparation? = null
    private var pendingPreparation: JourneyPreparation? = null
    private var preparedAtMillis = 0L
    private var pendingPreparedAtMillis = 0L
    private var retryAttempt = 0
    private var retryNotBeforeMillis = 0L
    private val presentationDeadlineBudget =
        JourneyForegroundDeadline(PRESENTATION_DEADLINE_MILLIS)
    private var clientSessionId: String? = null
    private var foreground = true
    private val authorizationGate = JourneyAuthorizationGate()
    private var loadedRendererUrl: String? = null
    private var failureView: View? = null
    private var memoryCallbacksRegistered = true
    var readiness: JourneyReadiness = JourneyReadiness.IDLE
        private set(value) {
            if (field == value) return
            field = value
            readinessListener?.invoke(value)
        }
    var readinessListener: ((JourneyReadiness) -> Unit)? = null
    val isPrepared: Boolean
        get() = readiness == JourneyReadiness.READY || readiness == JourneyReadiness.PRESENTED
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

    private val refreshRunnable = Runnable { refreshIfNeeded() }
    private val presentationDeadline = Runnable {
        presentationDeadlineBudget.finish()
        if (presentationRequested && !presented && !disposed) {
            readiness = JourneyReadiness.FAILED
            showFailure(JourneyError("presentation_timeout", GENERIC_ERROR, true))
        }
    }

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
        override fun onStart(owner: LifecycleOwner) {
            foreground = true
            ensureWebView()
            armPresentationDeadline()
            refreshIfNeeded()
            attemptInitializeRenderer()
        }

        override fun onStop(owner: LifecycleOwner) {
            foreground = false
            mainHandler.removeCallbacks(refreshRunnable)
            pausePresentationDeadline()
            flushCaptureForLifecycle()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            dispose()
        }
    }
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: AndroidConfiguration) {
            setBackgroundColor(themeBackgroundColor())
            if (failureView != null && errorViewFactory == null) {
                showFailure(JourneyError("presentation_failed", GENERIC_ERROR, true))
            }
        }
        override fun onLowMemory() = releasePreparedRendererForMemoryPressure()
        @Suppress("DEPRECATION")
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !presented) {
                releasePreparedRendererForMemoryPressure()
            }
        }
    }

    val controller = JourneyController()

    init {
        setBackgroundColor(themeBackgroundColor())
        context.applicationContext.registerComponentCallbacks(memoryCallbacks)
    }

    @JvmOverloads
    fun load(
        configuration: JourneyConfiguration,
        listener: JourneyListener = object : JourneyListener {},
        controller: JourneyController = this.controller,
    ) {
        prepare(configuration, listener, controller)
        present()
    }

    /** Warm the Journey renderer and configuration without presenting or capturing. */
    @JvmOverloads
    fun prepare(
        configuration: JourneyConfiguration,
        listener: JourneyListener = object : JourneyListener {},
        controller: JourneyController = this.controller,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "JourneyView.prepare must be called on the main thread"
        }
        val sameConfiguration = this.configuration == configuration && !disposed
        if (!memoryCallbacksRegistered) {
            context.applicationContext.registerComponentCallbacks(memoryCallbacks)
            memoryCallbacksRegistered = true
        }
        disposed = false
        authorizationGate.allowExplicitAttempt()
        this.listener = listener
        bindController(controller)
        if (presented) {
            if (!sameConfiguration) {
                listener.onError(
                    JourneyError(
                        "configuration_active",
                        "Dismiss the active Journey before changing its configuration",
                        true,
                    ),
                )
            }
            return
        }
        if (!sameConfiguration) resetForConfiguration(configuration)
        setBackgroundColor(themeBackgroundColor())
        visibility = if (presentationRequested) VISIBLE else INVISIBLE
        readiness = JourneyReadiness.PREPARING
        ensureWebView()
        startPreparation(force = !sameConfiguration)
    }

    /** Present a prepared Journey. A stale preparation refreshes before its 30-minute limit. */
    fun present() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "JourneyView.present must be called on the main thread"
        }
        if (disposed || configuration == null) return
        if (presented) return
        if (authorizationGate.blocksAutomaticAttempts) {
            authorizationGate.allowExplicitAttempt()
            readiness = JourneyReadiness.PREPARING
        }
        val isNewPresentation = !presentationRequested
        presentationRequested = true
        if (isNewPresentation) resetPresentationDeadline()
        visibility = VISIBLE
        clearFailure()
        showLoadingOverlay()
        ensureWebView()
        armPresentationDeadline()
        val canStart = preparation?.let {
            JourneyFreshness(preparedAtMillis, it.refreshAfterSeconds)
                .canStart(SystemClock.elapsedRealtime())
        } == true
        if (preparedRendered && canStart) {
            revealPreparedRenderer()
            if (needsRefresh()) startPreparation(force = true)
            return
        }
        if (!prepareInFlight && (preparation == null || !canStart || needsRefresh())) {
            startPreparation(force = true)
        }
        attemptInitializeRenderer()
    }

    /** Hide the Journey while retaining and resetting the same renderer for the next presentation. */
    fun dismiss() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "JourneyView.dismiss must be called on the main thread"
        }
        resetPresentationDeadline()
        presentationRequested = false
        presented = false
        canGoBack = false
        backCallback.isEnabled = false
        if (authorizationGate.blocksAutomaticAttempts) {
            destroyWebView()
            readiness = JourneyReadiness.BLOCKED
            visibility = INVISIBLE
        } else if (
            rendererSupportsPreparation &&
            rendererReady &&
            preparation?.supportsPreparation == true
        ) {
            sendVisibility(false)
            visibility = INVISIBLE
            pendingPreparation?.let(::activatePreparation)
            pendingPreparation = null
            pendingPreparedAtMillis = 0L
            val canResetFromCache = preparation?.let {
                JourneyFreshness(preparedAtMillis, it.refreshAfterSeconds)
                    .canStart(SystemClock.elapsedRealtime())
            } == true
            if (canResetFromCache) {
                initializeRenderer(prepared = true)
                scheduleRefresh()
            } else {
                rendererInitialized = false
                preparedRendered = false
                readiness = JourneyReadiness.PREPARING
                startPreparation(force = true)
            }
        } else {
            destroyWebView()
            readiness = JourneyReadiness.IDLE
            visibility = INVISIBLE
        }
    }

    fun dispose() {
        flushCaptureForLifecycle()
        disposed = true
        readiness = JourneyReadiness.DISPOSED
        preparationGeneration += 1
        prepareInFlight = false
        mainHandler.removeCallbacks(refreshRunnable)
        finishPresentationDeadline()
        unbindPlatformLifecycle()
        boundController?.sink = null
        boundController = null
        readinessListener = null
        destroyWebView()
        if (memoryCallbacksRegistered) {
            runCatching { context.applicationContext.unregisterComponentCallbacks(memoryCallbacks) }
            memoryCallbacksRegistered = false
        }
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

    private fun resetForConfiguration(configuration: JourneyConfiguration) {
        val rendererUrl = configuration.resolvedRendererUrl()
        val hostChanged = loadedRendererUrl != null && loadedRendererUrl != rendererUrl
        if (hostChanged || this.configuration?.identity != configuration.identity) destroyWebView()
        this.configuration = configuration
        journeyConfig = null
        preparation = null
        pendingPreparation = null
        preparedAtMillis = 0L
        pendingPreparedAtMillis = 0L
        rendererInitialized = false
        preparedRendered = false
        presented = false
        presentationRequested = false
        resetPresentationDeadline()
        retryAttempt = 0
        retryNotBeforeMillis = 0L
        authorizationGate.allowExplicitAttempt()
        clientSessionId = null
        canGoBack = false
        currentStepId = null
        currentStepIndex = 0
        backCallback.isEnabled = false
        queuedCommands.clear()
    }

    private fun bindController(controller: JourneyController) {
        if (boundController === controller) return
        boundController?.sink = null
        boundController = controller
        controller.sink = { command ->
            if (rendererInitialized) send("command", command)
            else queuedCommands.add(command)
        }
    }

    // The first branch below returns unless WEB_MESSAGE_LISTENER is supported;
    // lint does not carry that feature guard through the rest of this method.
    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun ensureWebView() {
        val config = configuration ?: return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            val error = JourneyError(
                "unsupported_webview",
                "Android System WebView must be updated to display this Journey",
                true,
            )
            readiness = JourneyReadiness.FAILED
            listener.onError(error)
            if (presentationRequested) showFailure(error)
            return
        }

        val rendererUrl = config.resolvedRendererUrl()
        if (webView != null && loadedRendererUrl == rendererUrl) return
        destroyWebView()
        rendererReady = false
        rendererSupportsPreparation = false
        rendererInitialized = false
        preparedRendered = false
        canGoBack = false
        backCallback.isEnabled = false
        val rendererUri = Uri.parse(rendererUrl)
        val origin = buildString {
            append(rendererUri.scheme).append("://").append(rendererUri.host)
            if (rendererUri.port != -1) append(":").append(rendererUri.port)
        }
        val current = WebView(context).apply {
            setBackgroundColor(themeBackgroundColor())
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
        loadedRendererUrl = rendererUrl
        removeAllViews()
        addView(current, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        current.visibility = if (presentationRequested) VISIBLE else INVISIBLE
        if (presentationRequested) showLoadingOverlay()
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
                rendererReady = false
                rendererInitialized = false
                loadedRendererUrl = null
                if (presentationRequested) showFailure(journeyError)
                else {
                    readiness = JourneyReadiness.FAILED
                    recordRetryBackoff()
                }
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            removeView(view)
            view.destroy()
            webView = null
            loadedRendererUrl = null
            rendererReady = false
            rendererInitialized = false
            preparedRendered = false
            listener.onError(
                JourneyError(
                    "renderer_process_gone",
                    "The Journey renderer restarted after a WebView process failure",
                    true,
                ),
            )
            if (!disposed) {
                ensureWebView()
                startPreparation(force = true)
            }
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
                    val capabilities = message.payload.optJSONArray("capabilities")
                    rendererSupportsPreparation = capabilities != null &&
                        (0 until capabilities.length()).map(capabilities::optString).containsAll(
                            listOf("prepare", "visibility"),
                        )
                    attemptInitializeRenderer()
                }
                "rendered" -> {
                    preparedRendered = true
                    if (presentationRequested) revealPreparedRenderer()
                    else {
                        readiness = JourneyReadiness.READY
                        visibility = INVISIBLE
                        hideLoadingOverlay()
                    }
                }
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

    private fun startPreparation(force: Boolean) {
        val config = configuration ?: return
        if (
            prepareInFlight ||
            disposed ||
            authorizationGate.blocksAutomaticAttempts ||
            !foreground
        ) return
        val retryDelay = retryNotBeforeMillis - SystemClock.elapsedRealtime()
        if (retryDelay > 0L) return
        if (!force && preparation != null && !needsRefresh()) {
            scheduleRefresh()
            attemptInitializeRenderer()
            return
        }
        prepareInFlight = true
        if (!presented) readiness = JourneyReadiness.PREPARING
        val generation = ++preparationGeneration
        val knownRevisionId = latestPreparation()?.value?.revisionId
        IO_EXECUTOR.execute {
            try {
                val result = JourneyApiClient.prepare(config, knownRevisionId)
                mainHandler.post {
                    if (disposed || generation != preparationGeneration || this.configuration !== config) {
                        return@post
                    }
                    prepareInFlight = false
                    retryAttempt = 0
                    retryNotBeforeMillis = 0L
                    val latestBeforeRefresh = latestPreparation()
                    val resolved = try {
                        JourneyApiClient.resolvePreparation(
                            result = result,
                            requestedRevisionId = knownRevisionId,
                            existing = latestBeforeRefresh,
                        )
                    } catch (error: JourneyError) {
                        handlePreparationFailure(error)
                        return@post
                    }
                    if (resolved.config == null) {
                        handlePreparationFailure(
                            JourneyError(
                                "invalid_config",
                                "FounderHQ returned an invalid Journey configuration",
                                false,
                            ),
                        )
                        return@post
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (presented && (pendingPreparation != null || !result.unchanged)) {
                        pendingPreparation = resolved
                        pendingPreparedAtMillis = now
                    } else {
                        activatePreparation(resolved, now)
                        if (!result.unchanged) {
                            rendererInitialized = false
                            preparedRendered = false
                        }
                    }
                    scheduleRefresh()
                    attemptInitializeRenderer()
                }
            } catch (error: Throwable) {
                mainHandler.post {
                    if (disposed || generation != preparationGeneration || this.configuration !== config) {
                        return@post
                    }
                    prepareInFlight = false
                    handlePreparationFailure(error)
                }
            }
        }
    }

    private fun activatePreparation(
        value: JourneyPreparation,
        preparedAt: Long = pendingPreparedAtMillis.takeIf { it > 0L }
            ?: SystemClock.elapsedRealtime(),
    ) {
        preparation = value
        journeyConfig = value.config?.let { JSONObject(it.toString()) }
        preparedAtMillis = preparedAt
    }

    private fun handlePreparationFailure(error: Throwable) {
        val journeyError = error as? JourneyError ?: JourneyError(
            "prepare_failed",
            error.message ?: "Journey preparation failed",
            true,
            error,
        )
        listener.onError(journeyError)
        journeyError.retryAfterMillis?.let { delay ->
            extendRetryDeadline(delay)
        }
        if (mustStopForPreparationFailure(journeyError)) {
            blockForDefinitiveDenial(journeyError)
            return
        }
        if (presented) {
            readiness = JourneyReadiness.PRESENTED
            recordRetryBackoff()
            return
        }
        val canStart = preparation?.let {
            JourneyFreshness(preparedAtMillis, it.refreshAfterSeconds)
                .canStart(SystemClock.elapsedRealtime())
        } == true
        if (preparedRendered && canStart) {
            readiness = JourneyReadiness.READY
            if (presentationRequested) revealPreparedRenderer()
        } else {
            readiness = JourneyReadiness.FAILED
        }
        recordRetryBackoff()
        if (presentationRequested && failureView != null) showFailure(journeyError)
    }

    private fun recordRetryBackoff() {
        if (!foreground || disposed || readiness == JourneyReadiness.BLOCKED) return
        val backoff = RETRY_DELAYS_MILLIS[minOf(retryAttempt, RETRY_DELAYS_MILLIS.lastIndex)]
        val serverDelay = (retryNotBeforeMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val delay = maxOf(backoff, serverDelay)
        extendRetryDeadline(delay)
        retryAttempt += 1
    }

    private fun extendRetryDeadline(delayMillis: Long) {
        val now = SystemClock.elapsedRealtime()
        val target = if (delayMillis > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delayMillis
        retryNotBeforeMillis = maxOf(retryNotBeforeMillis, target)
    }

    private fun needsRefresh(now: Long = SystemClock.elapsedRealtime()): Boolean {
        val latest = latestPreparation() ?: return true
        return JourneyFreshness(latest.preparedAtMillis, latest.value.refreshAfterSeconds)
            .needsRefresh(now)
    }

    private fun scheduleRefresh() {
        mainHandler.removeCallbacks(refreshRunnable)
        val latest = latestPreparation() ?: return
        if (!foreground || disposed) return
        val elapsed = SystemClock.elapsedRealtime() - latest.preparedAtMillis
        val delay = (latest.value.refreshAfterSeconds * 1_000L - elapsed).coerceAtLeast(0L)
        mainHandler.postDelayed(refreshRunnable, delay)
    }

    private fun latestPreparation(): JourneyPreparationReference? = latestPreparationReference(
        active = preparation,
        activePreparedAtMillis = preparedAtMillis,
        pending = pendingPreparation,
        pendingPreparedAtMillis = pendingPreparedAtMillis,
    )

    private fun refreshIfNeeded() {
        if (!foreground || disposed) return
        if (needsRefresh()) startPreparation(force = true) else scheduleRefresh()
    }

    private fun attemptInitializeRenderer() {
        if (!rendererReady || journeyConfig == null) return
        val canStart = preparation?.let {
            JourneyFreshness(preparedAtMillis, it.refreshAfterSeconds)
                .canStart(SystemClock.elapsedRealtime())
        } == true
        if (!canStart) return
        if (rendererInitialized) {
            if (preparedRendered && presentationRequested) revealPreparedRenderer()
            return
        }
        if (rendererSupportsPreparation && preparation?.supportsPreparation == true) {
            initializeRenderer(prepared = true)
        } else if (presentationRequested) {
            initializeRenderer(prepared = false)
        } else {
            readiness = JourneyReadiness.DIRECT_PRESENTATION
        }
    }

    private fun initializeRenderer(prepared: Boolean) {
        val config = configuration ?: return
        val renderedConfig = journeyConfig ?: return
        clientSessionId = UUID.randomUUID().toString()
        rendererInitialized = true
        preparedRendered = false
        presented = false
        canGoBack = false
        backCallback.isEnabled = false
        val payload = JourneyBridge.initializePayload(
            configuration = config,
            renderedConfig = renderedConfig,
            clientSessionId = clientSessionId!!,
            prepared = prepared,
            revisionId = preparation?.revisionId,
        )
        send("initialize", payload)
        queuedCommands.forEach { send("command", it) }
        queuedCommands.clear()
    }

    private fun revealPreparedRenderer() {
        if (!preparedRendered || !presentationRequested) return
        if (rendererSupportsPreparation) sendVisibility(true)
        webView?.visibility = VISIBLE
        visibility = VISIBLE
        presented = true
        readiness = JourneyReadiness.PRESENTED
        hideLoadingOverlay()
        clearFailure()
        finishPresentationDeadline()
    }

    private fun sendVisibility(visible: Boolean) {
        send("command", JSONObject().put("name", "set_visibility").put("visible", visible))
    }

    private fun handleCapture(payload: JSONObject) {
        val config = configuration ?: return
        val requestId = payload.optString("requestId")
        val body = payload.optJSONObject("body") ?: return
        if (requestId.isBlank()) return
        IO_EXECUTOR.execute {
            val response = JSONObject().put("requestId", requestId)
            var captureError: JourneyError? = null
            try {
                val status = JourneyApiClient.capture(config, body)
                response.put("ok", true).put("status", status)
            } catch (error: Throwable) {
                captureError = error as? JourneyError
                val failure = JourneyBridge.captureErrorResponse(requestId, error)
                response.put("ok", false).put("error", failure.getString("error"))
                if (failure.has("retryAfterMs")) {
                    response.put("retryAfterMs", failure.getLong("retryAfterMs"))
                }
            }
            mainHandler.post {
                if (disposed) return@post
                send("capture_response", response)
                captureError?.let { error ->
                    listener.onError(error)
                    if (error.httpStatus in DEFINITIVE_HTTP_STATUSES) {
                        mainHandler.post { blockForDefinitiveDenial(error) }
                    }
                }
            }
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

    private fun blockForDefinitiveDenial(error: JourneyError) {
        if (disposed) return
        authorizationGate.block()
        presentationRequested = false
        presented = false
        preparationGeneration += 1
        prepareInFlight = false
        preparation = null
        pendingPreparation = null
        journeyConfig = null
        preparedAtMillis = 0L
        pendingPreparedAtMillis = 0L
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.removeCallbacks(presentationDeadline)
        destroyWebView()
        readiness = JourneyReadiness.BLOCKED
        visibility = VISIBLE
        showFailure(error)
    }

    private fun send(type: String, payload: JSONObject) {
        if (!rendererReady && type != "initialize") return
        webView?.evaluateJavascript(JourneyBridge.dispatchScript(type, payload), null)
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

    private fun createLoadingView(): View = loadingViewFactory?.invoke(context) ?: run {
        val ringColor = themeAccentColor()
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) {
            JourneyStaticRingView(context, ringColor)
        } else {
            ProgressBar(context).apply {
                contentDescription = "Loading Journey"
                isFocusable = true
                indeterminateTintList = ColorStateList.valueOf(ringColor)
            }
        }
    }

    private fun loadingLayoutParams(view: View): LayoutParams =
        if (view is ProgressBar || view is JourneyStaticRingView) {
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        } else {
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

    private fun retryCurrentConfiguration() {
        if (
            configuration == null ||
            disposed ||
            SystemClock.elapsedRealtime() < retryNotBeforeMillis
        ) return
        authorizationGate.allowExplicitAttempt()
        clearFailure()
        rendererInitialized = false
        preparedRendered = false
        readiness = JourneyReadiness.PREPARING
        ensureWebView()
        resetPresentationDeadline()
        startPreparation(force = true)
        present()
    }

    private fun resetPresentationDeadline() {
        mainHandler.removeCallbacks(presentationDeadline)
        presentationDeadlineBudget.reset()
    }

    private fun armPresentationDeadline() {
        if (
            !foreground ||
            !presentationRequested ||
            presented ||
            disposed ||
            failureView != null
        ) return
        val remaining = presentationDeadlineBudget.start(SystemClock.elapsedRealtime()) ?: return
        if (remaining <= 0L) {
            presentationDeadline.run()
            return
        }
        mainHandler.postDelayed(presentationDeadline, remaining)
    }

    private fun pausePresentationDeadline() {
        mainHandler.removeCallbacks(presentationDeadline)
        presentationDeadlineBudget.pause(SystemClock.elapsedRealtime())
    }

    private fun finishPresentationDeadline() {
        mainHandler.removeCallbacks(presentationDeadline)
        presentationDeadlineBudget.finish()
    }

    private fun showFailure(error: JourneyError) {
        finishPresentationDeadline()
        hideLoadingOverlay()
        clearFailure()
        visibility = VISIBLE
        val retry = ::retryCurrentConfiguration
        errorViewFactory?.invoke(context, error, retry)?.let { custom ->
            failureView = custom
            addView(custom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            custom.bringToFront()
            return
        }
        val retryDelay = (retryNotBeforeMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val retryButton = Button(context).apply {
            text = "Try again"
            contentDescription = "Try loading the Journey again"
            isEnabled = !prepareInFlight && retryDelay == 0L
            setOnClickListener { retry() }
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(themeBackgroundColor())
            setPadding(dp(32), dp(32), dp(32), dp(32))
            addView(
                ImageView(context).apply {
                    setImageResource(android.R.drawable.ic_dialog_alert)
                    setColorFilter(themeTextColor())
                    contentDescription = null
                },
                LinearLayout.LayoutParams(dp(28), dp(28)).apply { bottomMargin = dp(16) },
            )
            addView(
                TextView(context).apply {
                    text = GENERIC_ERROR
                    gravity = Gravity.CENTER
                    setTextColor(themeTextColor())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(16) },
            )
            addView(
                retryButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            if (retryDelay > 0L) {
                val currentView = this
                mainHandler.postDelayed({
                    if (failureView === currentView && !prepareInFlight) retryButton.isEnabled = true
                }, retryDelay)
            }
        }
        failureView = view
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        view.bringToFront()
    }

    private fun clearFailure() {
        failureView?.let(::removeView)
        failureView = null
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

    private fun destroyWebView() {
        webView?.let { current ->
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                runCatching { WebViewCompat.removeWebMessageListener(current, JourneyBridge.HANDLER) }
            }
            current.stopLoading()
            current.webViewClient = WebViewClient()
            current.destroy()
        }
        webView = null
        loadedRendererUrl = null
        rendererReady = false
        rendererSupportsPreparation = false
        rendererInitialized = false
        preparedRendered = false
        clearFailure()
        hideLoadingOverlay()
    }

    private fun releasePreparedRendererForMemoryPressure() {
        if (disposed || presented || presentationRequested) return
        destroyWebView()
        preparation = null
        journeyConfig = null
        preparedAtMillis = 0L
        readiness = JourneyReadiness.IDLE
    }

    companion object {
        private val IO_EXECUTOR = Executors.newCachedThreadPool()
        private const val GENERIC_ERROR = "Unable to load. Please try again."
        private const val PRESENTATION_DEADLINE_MILLIS = 15_000L
        private val RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 2_000L, 5_000L, 15_000L, 30_000L, 60_000L)
        private val DEFINITIVE_HTTP_STATUSES = setOf(401, 403, 404)
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

    private fun isDarkTheme(): Boolean = when (configuration?.theme?.lowercase()) {
        "dark" -> true
        "light" -> false
        else -> resources.configuration.uiMode and AndroidConfiguration.UI_MODE_NIGHT_MASK ==
            AndroidConfiguration.UI_MODE_NIGHT_YES
    }

    private fun themeBackgroundColor(): Int =
        if (isDarkTheme()) Color.rgb(20, 18, 24) else Color.rgb(250, 249, 252)

    private fun themeTextColor(): Int =
        if (isDarkTheme()) Color.rgb(246, 243, 250) else Color.rgb(31, 27, 36)

    private fun themeAccentColor(): Int =
        if (isDarkTheme()) Color.rgb(190, 177, 255) else Color.rgb(91, 70, 210)

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)
            .toInt()
}

private class JourneyStaticRingView(context: Context, color: Int) : View(context) {
    private val size = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        32f,
        resources.displayMetrics,
    ).toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            3f,
            resources.displayMetrics,
        )
        this.color = color
    }

    init {
        contentDescription = "Loading Journey"
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = paint.strokeWidth / 2f
        canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f - inset, paint)
    }
}
