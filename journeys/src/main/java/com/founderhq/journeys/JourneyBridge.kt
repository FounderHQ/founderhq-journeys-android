package com.founderhq.journeys

import org.json.JSONObject

internal object JourneyBridge {
    const val VERSION = 1
    const val HANDLER = "founderhqJourneysNative"
    const val RECEIVER = "__founderhqJourneysReceive"
    const val SDK_VERSION = "0.1.0"

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
}
