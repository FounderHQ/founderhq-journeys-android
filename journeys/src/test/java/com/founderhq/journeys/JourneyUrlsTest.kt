package com.founderhq.journeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONObject

class JourneyUrlsTest {
    @Test
    fun productionAPIAndRendererUseAppHost() {
        val configuration = JourneyConfiguration(apiKey = "fhq_pk_test", journeyId = "journey")
        assertEquals("https://app.getfounderhq.com", configuration.resolvedBaseUrl())
        assertEquals("https://app.getfounderhq.com/embed/journeys/native", configuration.resolvedRendererUrl())
        assertEquals("http://10.0.2.2:3000/embed/journeys/native", configuration.copy(baseUrl = "http://10.0.2.2:3000").resolvedRendererUrl())
        assertEquals("https://renderer.example.com/custom", configuration.copy(rendererUrl = "https://renderer.example.com/custom").resolvedRendererUrl())
    }

    @Test
    fun acceptsSecureAndLocalOrigins() {
        assertEquals("https://example.com", JourneyUrls.secureOrigin("https://example.com/path"))
        assertEquals("http://10.0.2.2:3000", JourneyUrls.secureOrigin("http://10.0.2.2:3000/path"))
        assertEquals("http://10.10.20.11:3002", JourneyUrls.secureOrigin("http://10.10.20.11:3002/path"))
        assertEquals("http://172.31.0.5:3000", JourneyUrls.secureOrigin("http://172.31.0.5:3000"))
        assertEquals("http://192.168.1.5:3000", JourneyUrls.secureOrigin("http://192.168.1.5:3000"))
    }

    @Test
    fun rejectsInsecureRemoteOrigins() {
        assertThrows(IllegalArgumentException::class.java) {
            JourneyUrls.secureOrigin("http://example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            JourneyUrls.secureOrigin("http://172.32.0.5")
        }
    }

    @Test
    fun encodesJourneyIdsAsOnePathSegment() {
        assertEquals("journey%2Fwith%20spaces", JourneyUrls.encodePathSegment("journey/with spaces"))
    }

    @Test
    fun exposesTypedEventsAndDiscounts() {
        val event = JourneyEvent(
            JSONObject()
                .put("type", "purchase_intent")
                .put("answers", JSONObject().put("plan", "pro")),
        )
        assertEquals(JourneyEventType.PURCHASE_INTENT, event.type)
        assertEquals("pro", event.answers.getString("plan"))

        val result = JourneyDiscountCodeResult(false, JSONObject().put("reason", "Expired"))
        assertEquals(false, result.raw.getBoolean("valid"))
    }
}
