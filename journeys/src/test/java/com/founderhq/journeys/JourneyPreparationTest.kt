package com.founderhq.journeys

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyPreparationTest {
    @Test
    fun parsesPreparationAndClampsRefreshWindow() {
        val preparation = JourneyApiClient.parsePreparation(
            JSONObject()
                .put("config", JSONObject().put("title", "Welcome"))
                .put("revisionId", "revision-2")
                .put("version", 2)
                .put("captureIdentityMode", "RESPONSES_ONLY")
                .put("refreshAfterSeconds", 50)
                .put("unchanged", false)
                .toString(),
        )

        assertEquals("revision-2", preparation.revisionId)
        assertEquals(2, preparation.version)
        assertEquals(600L, preparation.refreshAfterSeconds)
        assertEquals("Welcome", preparation.config?.getString("title"))
    }

    @Test
    fun unchangedPreparationCanReuseLocalConfig() {
        val local = JSONObject().put("title", "Cached")
        val preparation = JourneyApiClient.parsePreparation(
            JSONObject()
                .put("revisionId", "revision-2")
                .put("version", 2)
                .put("captureIdentityMode", "RESPONSES_ONLY")
                .put("refreshAfterSeconds", 3_600)
                .put("unchanged", true)
                .toString(),
            local,
        )

        assertEquals("Cached", preparation.config?.getString("title"))
        assertEquals(1_800L, preparation.refreshAfterSeconds)
    }

    @Test
    fun unchangedPreparationMustMatchRequestedCachedRevision() {
        val cached = preparation("revision-2", 600)
        val unchanged = cached.copy(config = null, unchanged = true)
        val reference = JourneyPreparationReference(cached, 10_000L)

        val resolved = JourneyApiClient.resolvePreparation(
            result = unchanged,
            requestedRevisionId = "revision-2",
            existing = reference,
        )

        assertEquals("revision-2", resolved.revisionId)
        assertEquals("revision-2", resolved.config?.getString("revision"))
        assertThrows(JourneyError::class.java) {
            JourneyApiClient.resolvePreparation(
                result = unchanged.copy(revisionId = "revision-3"),
                requestedRevisionId = "revision-2",
                existing = reference,
            )
        }
        assertThrows(JourneyError::class.java) {
            JourneyApiClient.resolvePreparation(
                result = unchanged,
                requestedRevisionId = "revision-2",
                existing = JourneyPreparationReference(cached.copy(config = null), 10_000L),
            )
        }
    }

    @Test
    fun changedPreparationRequiresConfig() {
        assertThrows(JourneyError::class.java) {
            JourneyApiClient.parsePreparation(
                JSONObject()
                    .put("revisionId", "revision-2")
                    .put("version", 2)
                    .put("captureIdentityMode", "RESPONSES_ONLY")
                    .put("refreshAfterSeconds", 600)
                    .put("unchanged", false)
                    .toString(),
            )
        }
    }

    @Test
    fun freshnessRefreshesAtServerWindowAndExpiresAtThirtyMinutes() {
        val freshness = JourneyFreshness(preparedAtMillis = 1_000, refreshAfterSeconds = 600)
        assertFalse(freshness.needsRefresh(600_999))
        assertTrue(freshness.needsRefresh(601_000))
        assertTrue(freshness.canStart(1_801_000))
        assertFalse(freshness.canStart(1_801_001))
    }

    @Test
    fun cachedJourneyBetweenRefreshAndExpiryCanOpenBeforeRefreshing() {
        val freshness = JourneyFreshness(preparedAtMillis = 0, refreshAfterSeconds = 600)

        assertTrue(freshness.canStart(15 * 60 * 1_000L))
        assertTrue(freshness.needsRefresh(15 * 60 * 1_000L))
    }

    @Test
    fun presentationDeadlineCountsForegroundTimeOnly() {
        val deadline = JourneyForegroundDeadline(15_000L)

        assertEquals(15_000L, deadline.start(1_000L))
        deadline.pause(6_000L)
        assertEquals(10_000L, deadline.remainingMillis)
        assertEquals(10_000L, deadline.start(60_000L))
        deadline.pause(70_000L)
        assertEquals(0L, deadline.remainingMillis)
    }

    @Test
    fun pendingRevisionDrivesTheNextActiveRefresh() {
        val active = preparation("revision-1", 600)
        val pending = preparation("revision-2", 1_200)

        val latest = latestPreparationReference(
            active = active,
            activePreparedAtMillis = 1_000L,
            pending = pending,
            pendingPreparedAtMillis = 500_000L,
        )!!

        assertEquals("revision-2", latest.value.revisionId)
        assertEquals(500_000L, latest.preparedAtMillis)
        assertFalse(
            JourneyFreshness(latest.preparedAtMillis, latest.value.refreshAfterSeconds)
                .needsRefresh(600_000L),
        )
    }

    @Test
    fun definitivePreparationFailuresStopAnActiveJourney() {
        assertTrue(
            mustStopForPreparationFailure(
                JourneyApiClient.httpError(401, "{}", "Unavailable"),
            ),
        )
        assertTrue(
            mustStopForPreparationFailure(
                JourneyApiClient.httpError(403, "{}", "Unavailable"),
            ),
        )
        assertFalse(
            mustStopForPreparationFailure(
                JourneyApiClient.httpError(503, "{}", "Unavailable"),
            ),
        )
    }

    @Test
    fun explicitPresentPrepareOrRetryCanRecoverAfterAuthorizationDenial() {
        val gate = JourneyAuthorizationGate()

        gate.block()
        assertTrue(gate.blocksAutomaticAttempts)

        gate.allowExplicitAttempt()
        assertFalse(gate.blocksAutomaticAttempts)
    }

    @Test
    fun captureTransportReceivesRendererBodyWithoutModification() {
        val body = JSONObject()
            .put("events", org.json.JSONArray().put(JSONObject().put("sequence", 4)))
            .put("queueMetadata", JSONObject().put("attempt", 2))
        var received: JSONObject? = null
        val configuration = JourneyConfiguration(
            apiKey = "fhq_pk_test",
            journeyId = "journey",
            capture = JourneyCaptureOptions(
                contextProvider = { JSONObject().put("mustNotBeMerged", true) },
                transport = {
                    received = it.body
                    true
                },
            ),
        )

        assertEquals(200, JourneyApiClient.capture(configuration, body))
        assertSame(body, received)
        assertFalse(received!!.has("context"))
    }

    @Test
    fun preparedInitializationCarriesAdditiveBridgeFields() {
        val payload = JourneyBridge.initializePayload(
            configuration = JourneyConfiguration("fhq_pk_test", "journey"),
            renderedConfig = JSONObject().put("title", "Welcome"),
            clientSessionId = "session-1",
            prepared = true,
            revisionId = "revision-2",
        )

        assertEquals("prepared", payload.getString("presentationMode"))
        assertEquals("revision-2", payload.getString("revisionId"))
        assertEquals("session-1", payload.getString("clientSessionId"))
        assertEquals("0.2.0", payload.getString("sdkVersion"))
    }

    @Test
    fun legacyInitializationOmitsPreparationModeAndSentinelRevision() {
        val payload = JourneyBridge.initializePayload(
            configuration = JourneyConfiguration("fhq_pk_test", "journey"),
            renderedConfig = JSONObject(),
            clientSessionId = "session-direct",
            prepared = false,
            revisionId = "legacy",
        )

        assertFalse(payload.has("presentationMode"))
        assertFalse(payload.has("revisionId"))
        assertEquals("session-direct", payload.getString("clientSessionId"))
    }

    @Test
    fun authorizationDenialsAreDefinitive() {
        val denied = JourneyApiClient.httpError(403, "{}", "Unavailable")
        val missing = JourneyApiClient.httpError(404, "{}", "Unavailable")
        val transient = JourneyApiClient.httpError(503, "{}", "Unavailable")

        assertEquals("authorization_denied", denied.code)
        assertEquals(403, denied.httpStatus)
        assertFalse(denied.recoverable)
        assertEquals("not_found", missing.code)
        assertEquals(404, missing.httpStatus)
        assertFalse(missing.recoverable)
        assertEquals("http_error", transient.code)
        assertEquals(503, transient.httpStatus)
        assertTrue(transient.recoverable)
    }

    @Test
    fun rateLimitCarriesRetryAfterSeconds() {
        val error = JourneyApiClient.httpError(
            status = 429,
            response = "{}",
            fallback = "Unavailable",
            retryAfter = "75",
            nowEpochMillis = 0,
        )

        assertEquals("rate_limited", error.code)
        assertEquals(429, error.httpStatus)
        assertEquals(75_000L, error.retryAfterMillis)
        assertTrue(error.recoverable)
    }

    @Test
    fun rateLimitCarriesRetryAfterHttpDate() {
        val error = JourneyApiClient.httpError(
            status = 429,
            response = "{}",
            fallback = "Unavailable",
            retryAfter = "Thu, 01 Jan 1970 00:02:00 GMT",
            nowEpochMillis = 60_000L,
        )

        assertEquals(60_000L, error.retryAfterMillis)
    }

    @Test
    fun captureRateLimitResponseIncludesRendererRetryDelay() {
        val rateLimited = JourneyApiClient.httpError(
            status = 429,
            response = "{}",
            fallback = "Capture failed",
            retryAfter = "45",
            nowEpochMillis = 0,
        )
        val transient = JourneyApiClient.httpError(503, "{}", "Capture failed")

        val rateLimitedResponse = JourneyBridge.captureErrorResponse("request-1", rateLimited)
        val transientResponse = JourneyBridge.captureErrorResponse("request-2", transient)

        assertFalse(rateLimitedResponse.getBoolean("ok"))
        assertEquals(45_000L, rateLimitedResponse.getLong("retryAfterMs"))
        assertFalse(transientResponse.has("retryAfterMs"))
    }

    private fun preparation(revisionId: String, refreshAfterSeconds: Long) = JourneyPreparation(
        config = JSONObject().put("revision", revisionId),
        revisionId = revisionId,
        version = 1,
        captureIdentityMode = "RESPONSES_ONLY",
        refreshAfterSeconds = refreshAfterSeconds,
        unchanged = false,
    )
}
