# FounderHQ Journeys for Android

## Installation

Add `mavenCentral()` to your dependency repositories, then:

```kotlin
implementation("com.getfounderhq:journeys:0.7.0")
// Optional Jetpack Compose integration:
implementation("com.getfounderhq:journeys-compose:0.7.0")
```

Requires Android API 24 or later. Kotlin imports continue to use `com.founderhq`.

## Usage

The Android SDK includes a core View artifact and an optional Jetpack Compose
adapter. It supports Android API 24 and later.

```kotlin
val journey = JourneyView(context)
journey.load(
    JourneyConfiguration(
        apiKey = "fhq_pk_...",
        journeyId = "journey_id",
        identity = JourneyIdentity(externalId = "customer_id"),
    ),
    object : JourneyListener {
        override fun onEvent(event: JourneyEvent) {
            if (event.type == JourneyEventType.COMPLETE) {
                // Continue into the native app.
            }
        }
    },
)
```

Compose apps can use `com.founderhq.journeys.compose.JourneyView`. Both APIs expose
a `JourneyController` with `goNext`, `goBack`, `goToStep`, `setAnswer`,
`flushCapture`, and `reload`.

For instant presentation, retain one host for each place that can show a
Journey and prepare it while the preceding screen is visible:

```kotlin
val state = rememberJourneyState()

Box {
    Button(onClick = state::present) { Text("Start") }
    JourneyHost(
        configuration = JourneyConfiguration(
            apiKey = "fhq_pk_...",
            journeyId = "journey_id",
            identity = JourneyIdentity(externalId = "customer_id"),
        ),
        state = state,
        modifier = Modifier.fillMaxSize(),
    )
}
```

Keep `JourneyHost` composed while the surrounding screen changes. It loads the
published configuration and renderer in parallel, then stays hidden without
presentation analytics until `present()` makes it visible. Call `dismiss()`
instead of removing the host; it resets the same renderer to a fresh hidden
session so the next presentation never reuses completed answers or navigation.
Call `prepare()` to retry or refresh, and `dispose()` when the owning flow is
finished. `JourneyState.readiness` reports preparation progress.

View consumers use the same lifecycle directly:

```kotlin
journeyView.prepare(configuration, listener)
journeyView.present()
journeyView.dismiss()
journeyView.dispose()
```

View hosts can observe preparation progress with
`journeyView.readinessListener` and read the current `journeyView.readiness`.

Existing `load(...)` calls remain supported and prepare plus present in one
step. Renderers without the preparation capability use the original visible
initialization path and never initialize while hidden.

The SDK includes first-paint loading, lifecycle capture flushing, automatic
hardware Back, native haptics, typed events and discounts, external/deep-link
handling, local test configs and custom capture transports. Capture request
bodies are forwarded unchanged so renderer queue metadata reaches the server.
The core View exposes `loadingViewFactory`, `errorViewFactory`,
`hapticHandler`, `handleBackPressed()`, and current navigation state. Compose
consumers can configure the underlying View with `configureView`.
