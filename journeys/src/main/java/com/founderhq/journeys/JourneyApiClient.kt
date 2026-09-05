package com.founderhq.journeys

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal object JourneyApiClient {
    fun prepare(
        configuration: JourneyConfiguration,
        knownRevisionId: String? = null,
    ): JourneyPreparation {
        val body = JSONObject().put("capture", configuration.capture != null)
        knownRevisionId?.let { body.put("knownRevisionId", it) }
        return try {
            val response = request(
                url = "${configuration.endpoint()}/prepare",
                method = "POST",
                apiKey = configuration.apiKey,
                body = body,
                fallback = "Journey is unavailable",
            )
            parsePreparation(response, configuration.config)
        } catch (error: JourneyError) {
            if (error.httpStatus != 404) throw error
            // A 404 may mean an older FounderHQ host has no preparation route.
            // The legacy journey-specific validation distinguishes that case
            // from a missing or revoked Journey before direct presentation.
            JourneyPreparation(
                config = fetchConfiguration(configuration),
                revisionId = "legacy",
                version = 0,
                captureIdentityMode = "legacy",
                refreshAfterSeconds = JourneyPreparation.MIN_REFRESH_SECONDS,
                unchanged = false,
                supportsPreparation = false,
            )
        }
    }

    internal fun parsePreparation(
        response: String,
        localConfig: JSONObject? = null,
    ): JourneyPreparation {
        val root = try {
            JSONObject(response)
        } catch (error: Exception) {
            throw JourneyError("invalid_preparation", "FounderHQ returned an invalid preparation", false, error)
        }
        val revisionId = root.optString("revisionId").takeIf(String::isNotBlank)
            ?: throw JourneyError("invalid_preparation", "FounderHQ returned an invalid preparation", false)
        val captureIdentityMode = root.optString("captureIdentityMode").takeIf(String::isNotBlank)
            ?: throw JourneyError("invalid_preparation", "FounderHQ returned an invalid preparation", false)
        val version = root.opt("version") as? Number
        val refreshAfterSeconds = root.opt("refreshAfterSeconds") as? Number
        val unchangedValue = root.opt("unchanged") as? Boolean
        if (version == null || refreshAfterSeconds == null || unchangedValue == null) {
            throw JourneyError("invalid_preparation", "FounderHQ returned an invalid preparation", false)
        }
        val unchanged = unchangedValue
        val returnedConfig = localConfig?.let { JSONObject(it.toString()) }
            ?: root.optJSONObject("config")?.let { JSONObject(it.toString()) }
        if (!unchanged && returnedConfig == null) {
            throw JourneyError("invalid_config", "FounderHQ returned an invalid Journey configuration", false)
        }
        return JourneyPreparation(
            config = returnedConfig,
            revisionId = revisionId,
            version = version.toInt(),
            captureIdentityMode = captureIdentityMode,
            refreshAfterSeconds = refreshAfterSeconds.toLong(),
            unchanged = unchanged,
        ).clamped()
    }

    internal fun resolvePreparation(
        result: JourneyPreparation,
        requestedRevisionId: String?,
        existing: JourneyPreparationReference?,
    ): JourneyPreparation {
        if (!result.unchanged) return result
        val existingValue = existing?.value
        if (
            requestedRevisionId == null ||
            result.revisionId != requestedRevisionId ||
            existingValue?.revisionId != requestedRevisionId ||
            existingValue.config == null
        ) {
            throw JourneyError(
                "invalid_preparation",
                "FounderHQ returned an invalid preparation",
                false,
            )
        }
        return result.copy(config = JSONObject(existingValue.config.toString()))
    }

    fun fetchConfiguration(configuration: JourneyConfiguration): JSONObject {
        val endpoint = configuration.endpoint()
        request(
            url = "$endpoint/validate",
            method = "POST",
            apiKey = configuration.apiKey,
            body = JSONObject().put("capture", configuration.capture != null),
            fallback = "Journey is unavailable",
        )
        configuration.config?.let { return JSONObject(it.toString()) }
        val response = request(
            url = endpoint,
            method = "GET",
            apiKey = configuration.apiKey,
            fallback = "Failed to fetch Journey",
        )
        return JSONObject(response).optJSONObject("config")
            ?: throw JourneyError(
                "invalid_config",
                "FounderHQ returned an invalid Journey configuration",
                false,
            )
    }

    fun capture(configuration: JourneyConfiguration, body: JSONObject): Int {
        configuration.capture?.transport?.let { transport ->
            val delivered = transport(
                JourneyCaptureTransportRequest(
                    url = "${configuration.endpoint()}/capture",
                    method = "POST",
                    body = body,
                ),
            )
            if (!delivered) {
                throw JourneyError("capture_transport_failed", "Capture transport rejected the batch", true)
            }
            return 200
        }
        val (_, status) = requestWithStatus(
            url = "${configuration.endpoint()}/capture",
            method = "POST",
            apiKey = configuration.apiKey,
            body = body,
            fallback = "Capture failed",
        )
        return status
    }

    private fun request(
        url: String,
        method: String,
        apiKey: String,
        body: JSONObject? = null,
        fallback: String,
    ): String = requestWithStatus(url, method, apiKey, body, fallback).first

    private fun requestWithStatus(
        url: String,
        method: String,
        apiKey: String,
        body: JSONObject? = null,
        fallback: String,
    ): Pair<String, Int> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (body != null) doOutput = true
        }
        return try {
            if (body != null) {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use {
                    it.write(body.toString())
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw httpError(
                    status = status,
                    response = response,
                    fallback = fallback,
                    retryAfter = connection.getHeaderField("Retry-After"),
                )
            }
            response to status
        } finally {
            connection.disconnect()
        }
    }

    internal fun httpError(
        status: Int,
        response: String,
        fallback: String,
        retryAfter: String? = null,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): JourneyError {
        if (status == 401 || status == 403 || status == 404) {
            val code = if (status == 404) "not_found" else "authorization_denied"
            return JourneyError(
                code = code,
                message = "This Journey is unavailable",
                recoverable = false,
            ).withHttpMetadata(status)
        }
        val serverMessage = try {
            JSONObject(response).optString("error").takeIf(String::isNotBlank)
        } catch (_: Exception) {
            null
        }
        return JourneyError(
            code = if (status == 429) "rate_limited" else "http_error",
            message = serverMessage ?: "$fallback (HTTP $status)",
            recoverable = true,
        ).withHttpMetadata(
            status = status,
            retryAfterMillis = if (status == 429) parseRetryAfter(retryAfter, nowEpochMillis) else null,
        )
    }

    internal fun parseRetryAfter(value: String?, nowEpochMillis: Long): Long? {
        val normalized = value?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        normalized.toLongOrNull()?.let { seconds ->
            val nonNegative = seconds.coerceAtLeast(0L)
            return if (nonNegative > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE
            else nonNegative * 1_000L
        }
        val parser = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val retryAt = runCatching { parser.parse(normalized)?.time }.getOrNull() ?: return null
        return (retryAt - nowEpochMillis).coerceAtLeast(0L)
    }

    private fun JourneyError.withHttpMetadata(
        status: Int,
        retryAfterMillis: Long? = null,
    ) = apply {
        httpStatus = status
        this.retryAfterMillis = retryAfterMillis
    }
}
