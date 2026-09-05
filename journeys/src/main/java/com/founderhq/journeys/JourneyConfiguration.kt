package com.founderhq.journeys

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class JourneyIdentity(
    val email: String? = null,
    val phone: String? = null,
    val externalId: String? = null,
) {
    internal fun toJson() = JSONObject().apply {
        put("email", email ?: JSONObject.NULL)
        put("phone", phone ?: JSONObject.NULL)
        put("externalId", externalId ?: JSONObject.NULL)
    }
}

data class JourneyCaptureOptions(
    val context: JSONObject = JSONObject(),
    val captureContext: Boolean = true,
    val redactUrlParameters: List<String>? = null,
    val batchSize: Int? = null,
    val flushIntervalMilliseconds: Int? = null,
    val maxRetries: Int? = null,
    val contextProvider: (() -> JSONObject)? = null,
    val transport: ((JourneyCaptureTransportRequest) -> Boolean)? = null,
) {
    internal fun toJson() = JSONObject().apply {
        put("context", context)
        put("captureContext", captureContext)
        redactUrlParameters?.let { put("redactUrlParams", JSONArray(it)) }
        batchSize?.let { put("batchSize", it) }
        flushIntervalMilliseconds?.let { put("flushIntervalMs", it) }
        maxRetries?.let { put("maxRetries", it) }
    }
}

data class JourneyConfiguration(
    val apiKey: String,
    val journeyId: String,
    val baseUrl: String = PRODUCTION_BASE_URL,
    val rendererUrl: String? = null,
    /** Optional local render config. Access is still validated before rendering. */
    val config: JSONObject? = null,
    /** Set to null to disable FounderHQ capture. */
    val capture: JourneyCaptureOptions? = JourneyCaptureOptions(),
    val identity: JourneyIdentity? = null,
    val initialAnswers: JSONObject = JSONObject(),
    val initialOptions: JSONObject = JSONObject(),
    val storageKey: String? = null,
    val theme: String? = null,
) {
    companion object {
        const val PRODUCTION_BASE_URL = "https://app.getfounderhq.com"
    }

    internal fun resolvedBaseUrl(): String = JourneyUrls.secureOrigin(baseUrl)

    internal fun resolvedRendererUrl(): String {
        rendererUrl?.let {
            JourneyUrls.secureOrigin(it)
            val uri = URI(it).normalize()
            return URI(
                uri.scheme,
                null,
                uri.host,
                uri.port,
                uri.path,
                null,
                null,
            ).toString()
        }
        return "${resolvedBaseUrl()}/embed/journeys/native"
    }

    internal fun endpoint(): String =
        "${resolvedBaseUrl()}/api/v1/journeys/${JourneyUrls.encodePathSegment(journeyId)}"
}

internal object JourneyUrls {
    private val localHosts = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

    private fun isLocalDevelopmentHost(host: String): Boolean {
        if (host in localHosts) return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return false
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
        }
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    fun secureOrigin(value: String): String {
        val uri = try {
            URI(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("Journey URL is invalid", error)
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        require(
            host != null &&
                (scheme == "https" || (scheme == "http" && isLocalDevelopmentHost(host)))
        ) {
            "Journey URLs must use HTTPS (HTTP is supported only for local development)"
        }
        return URI(scheme, null, host, uri.port, null, null, null).toString()
    }

    fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
