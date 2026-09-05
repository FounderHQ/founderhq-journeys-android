package com.founderhq.journeys.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.founderhq.journeys.JourneyConfiguration
import com.founderhq.journeys.JourneyController
import com.founderhq.journeys.JourneyListener
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
    key(configurationKey, resolvedController, resolvedListener) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                AndroidJourneyView(context).also { view ->
                    view.configureView()
                    view.load(configuration, resolvedListener, resolvedController)
                }
            },
            onRelease = { view ->
                view.dispose()
            },
        )
    }
}

private object NoOpJourneyListener : JourneyListener
