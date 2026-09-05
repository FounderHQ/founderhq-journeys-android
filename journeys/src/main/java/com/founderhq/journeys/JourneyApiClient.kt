package com.founderhq.journeys

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object JourneyApiClient {
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
        val requestBody = applyDynamicContext(configuration.capture, body)
        configuration.capture?.transport?.let { transport ->
            val delivered = transport(
                JourneyCaptureTransportRequest(
                    url = "${configuration.endpoint()}/capture",
                    method = "POST",
                    body = requestBody,
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
            body = requestBody,
            fallback = "Capture failed",
        )
        return status
    }

    private fun applyDynamicContext(
        options: JourneyCaptureOptions?,
        body: JSONObject,
    ): JSONObject {
        val supplied = options?.contextProvider?.invoke() ?: return body
        val result = JSONObject(body.toString())
        val context = result.optJSONObject("context") ?: JSONObject()
        supplied.keys().forEach { key -> context.put(key, supplied.opt(key)) }
        result.put("context", context)
        return result
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
                val serverMessage = try {
                    JSONObject(response).optString("error").takeIf(String::isNotBlank)
                } catch (_: Exception) {
                    null
                }
                throw JourneyError(
                    "http_error",
                    serverMessage ?: "$fallback (HTTP $status)",
                    true,
                )
            }
            response to status
        } finally {
            connection.disconnect()
        }
    }
}
