package com.founderhq.journeys

import org.json.JSONObject

internal object JourneyBridge {
    const val VERSION = 1
    const val HANDLER = "founderhqJourneysNative"
    const val RECEIVER = "__founderhqJourneysReceive"
    const val SDK_VERSION = "0.2.0"

    data class Message(val type: String, val payload: JSONObject)

    fun parse(raw: String?): Message? {
        return try {
            if (raw == null) return null
            val root = JSONObject(raw)
            if (root.optInt("version") != VERSION) return null
            val type = root.optString("type")
            val payload = root.optJSONObject("payload") ?: return null
            if (type.isBlank()) null else Message(type, payload)
        } catch (_: Exception) {
            null
        }
    }

    fun message(type: String, payload: JSONObject): String = JSONObject()
        .put("version", VERSION)
        .put("type", type)
        .put("payload", payload)
        .toString()

    fun dispatchScript(type: String, payload: JSONObject): String =
        "window.$RECEIVER?.(${JSONObject.quote(message(type, payload))});true;"

    fun initializePayload(
        configuration: JourneyConfiguration,
        renderedConfig: JSONObject,
        clientSessionId: String,
        prepared: Boolean,
        revisionId: String?,
    ): JSONObject = JSONObject()
        .put("journeyId", configuration.journeyId)
        .put("config", renderedConfig)
        .put("capture", configuration.capture?.toJson() ?: false)
        .put("initialAnswers", configuration.initialAnswers)
        .put("initialOptions", configuration.initialOptions)
        .put("platform", "android")
        .put("sdkVersion", SDK_VERSION)
        .put("clientSessionId", clientSessionId)
        .apply {
            if (prepared) {
                put("presentationMode", "prepared")
                revisionId?.let { put("revisionId", it) }
            }
            configuration.identity?.let { put("identity", it.toJson()) }
            configuration.storageKey?.let { put("storageKey", it) }
            configuration.theme?.let { put("theme", it) }
        }

    fun captureErrorResponse(requestId: String, error: Throwable): JSONObject = JSONObject()
        .put("requestId", requestId)
        .put("ok", false)
        .put("error", error.message ?: "Capture failed")
        .apply {
            val journeyError = error as? JourneyError
            if (journeyError?.httpStatus == 429) {
                journeyError.retryAfterMillis?.let { put("retryAfterMs", it) }
            }
        }
}
