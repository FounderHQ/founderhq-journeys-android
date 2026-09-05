# FounderHQ Journeys for Android

## Installation

Add `mavenCentral()` to your dependency repositories, then:

```kotlin
implementation("com.getfounderhq:journeys:0.1.0")
// Optional Jetpack Compose integration:
implementation("com.getfounderhq:journeys-compose:0.1.0")
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

The SDK includes first-paint loading, lifecycle capture flushing, automatic
hardware Back, native haptics, typed events and discounts, external/deep-link
handling, local test configs, dynamic capture context, and custom capture
transports. The core View exposes `loadingViewFactory`, `errorViewFactory`,
`hapticHandler`, `handleBackPressed()`, and current navigation state. Compose
consumers can configure the underlying View with `configureView`.
