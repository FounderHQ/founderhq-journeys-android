package com.founderhq.journeys.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.founderhq.journeys.JourneyConfiguration
import com.founderhq.journeys.JourneyController
import com.founderhq.journeys.JourneyDiscountCodeRequest
import com.founderhq.journeys.JourneyDiscountCodeResult
import com.founderhq.journeys.JourneyError
import com.founderhq.journeys.JourneyEvent
import com.founderhq.journeys.JourneyListener
import com.founderhq.journeys.JourneyReadiness
import android.net.Uri
import com.founderhq.journeys.JourneyView as AndroidJourneyView

@Composable
fun JourneyView(
    configuration: JourneyConfiguration,
    modifier: Modifier = Modifier,
    controller: JourneyController? = null,
    listener: JourneyListener? = null,
    configureView: AndroidJourneyView.() -> Unit = {},
) {
    val resolvedController = controller ?: remember { JourneyController() }
    val resolvedListener = listener ?: NoOpJourneyListener
    val currentListener = rememberUpdatedState(resolvedListener)
    val forwardingListener = remember {
        object : JourneyListener {
            override fun onEvent(event: JourneyEvent) = currentListener.value.onEvent(event)
            override fun onError(error: JourneyError) = currentListener.value.onError(error)
            override fun onOpenUrl(url: Uri): Boolean = currentListener.value.onOpenUrl(url)
            override fun onDiscountCodeApply(
                request: JourneyDiscountCodeRequest,
                completion: (Result<JourneyDiscountCodeResult>) -> Unit,
            ) = currentListener.value.onDiscountCodeApply(request, completion)
        }
    }
    val configurationKey = listOf(
        configuration.apiKey,
        configuration.journeyId,
        configuration.baseUrl,
        configuration.rendererUrl,
        configuration.config?.toString(),
        configuration.capture?.toString(),
        configuration.identity?.toString(),
        configuration.initialAnswers.toString(),
        configuration.initialOptions.toString(),
        configuration.storageKey,
        configuration.theme,
    )
    key(configurationKey, resolvedController) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                AndroidJourneyView(context).also { view ->
                    view.configureView()
                    view.load(configuration, forwardingListener, resolvedController)
                }
            },
            onRelease = { view ->
                view.dispose()
            },
        )
    }
}

/** State for a retained [JourneyHost]. Keep the host composed while navigating around it. */
@Stable
class JourneyState internal constructor() {
    private var attachedView: AndroidJourneyView? = null
    private var configuration: JourneyConfiguration? = null
    private var listener: JourneyListener = NoOpJourneyListener
    private var controller: JourneyController? = null
    private var presentationRequested = false

    var readiness by mutableStateOf(JourneyReadiness.IDLE)
        private set

    val isPrepared: Boolean
        get() = readiness == JourneyReadiness.READY || readiness == JourneyReadiness.PRESENTED

    /** Re-run preparation if the retained host is stale or previously failed. */
    fun prepare() {
        val view = attachedView ?: return
        val config = configuration ?: return
        view.prepare(config, listener, controller ?: view.controller)
    }

    fun present() {
        presentationRequested = true
        attachedView?.present()
    }

    fun dismiss() {
        presentationRequested = false
        attachedView?.dismiss()
    }

    fun dispose() {
        presentationRequested = false
        attachedView?.dispose()
        attachedView = null
        readiness = JourneyReadiness.DISPOSED
    }

    internal fun attach(
        view: AndroidJourneyView,
        configuration: JourneyConfiguration,
        listener: JourneyListener,
        controller: JourneyController,
    ) {
        attachedView = view
        this.configuration = configuration
        this.listener = listener
        this.controller = controller
        readiness = view.readiness
        if (presentationRequested) view.present()
    }

    internal fun detach(view: AndroidJourneyView) {
        if (attachedView === view) attachedView = null
    }

    internal fun updateReadiness(value: JourneyReadiness) {
        readiness = value
    }
}

@Composable
fun rememberJourneyState(): JourneyState = remember { JourneyState() }

/**
 * A persistent Compose host that prepares offscreen and retains one Android WebView.
 * Keep this composable in the tree; use [JourneyState.present] and [JourneyState.dismiss].
 */
@Composable
fun JourneyHost(
    configuration: JourneyConfiguration,
    state: JourneyState,
    modifier: Modifier = Modifier,
    controller: JourneyController? = null,
    listener: JourneyListener = NoOpJourneyListener,
    configureView: AndroidJourneyView.() -> Unit = {},
) {
    val resolvedController = controller ?: remember { JourneyController() }
    val currentListener = rememberUpdatedState(listener)
    val forwardingListener = remember(state) {
        object : JourneyListener {
            override fun onEvent(event: JourneyEvent) = currentListener.value.onEvent(event)
            override fun onError(error: JourneyError) = currentListener.value.onError(error)
            override fun onOpenUrl(url: Uri): Boolean = currentListener.value.onOpenUrl(url)
            override fun onDiscountCodeApply(
                request: JourneyDiscountCodeRequest,
                completion: (Result<JourneyDiscountCodeResult>) -> Unit,
            ) = currentListener.value.onDiscountCodeApply(request, completion)
        }
    }
    val configurationKey = listOf(
        configuration.apiKey,
        configuration.journeyId,
        configuration.baseUrl,
        configuration.rendererUrl,
        configuration.config?.toString(),
        configuration.capture?.toString(),
        configuration.identity?.toString(),
        configuration.initialAnswers.toString(),
        configuration.initialOptions.toString(),
        configuration.storageKey,
        configuration.theme,
    )
    key(configurationKey, resolvedController) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                AndroidJourneyView(context).also { view ->
                    view.configureView()
                    view.readinessListener = state::updateReadiness
                    view.prepare(configuration, forwardingListener, resolvedController)
                    state.attach(view, configuration, forwardingListener, resolvedController)
                }
            },
            onRelease = { view ->
                state.detach(view)
                view.dispose()
            },
        )
    }
}

private object NoOpJourneyListener : JourneyListener
