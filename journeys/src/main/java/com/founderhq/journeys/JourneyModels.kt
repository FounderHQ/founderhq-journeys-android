package com.founderhq.journeys

import org.json.JSONObject

enum class JourneyEventType(val wireValue: String) {
    SESSION_START("session_start"),
    STEP_VIEW("step_view"),
    STEP_SUBMIT("step_submit"),
    NAVIGATE("navigate"),
    COMPLETE("complete"),
    PURCHASE_INTENT("purchase_intent"),
    UNKNOWN("unknown");

    companion object {
        fun fromWireValue(value: String): JourneyEventType =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

data class JourneyEvent(val raw: JSONObject) {
    val type: JourneyEventType = JourneyEventType.fromWireValue(raw.optString("type"))
    val answers: JSONObject = raw.optJSONObject("answers") ?: JSONObject()
    val computedVariables: JSONObject =
        raw.optJSONObject("computedVariables") ?: JSONObject()
}

data class JourneyDiscountCodeRequest(val raw: JSONObject) {
    val code: String = raw.optString("code")
    val variable: String = raw.optString("variable")
    val planVariable: String? = raw.optString("planVariable").takeIf(String::isNotBlank)
    val plan: JSONObject? = raw.optJSONObject("plan")
    val answers: JSONObject = raw.optJSONObject("answers") ?: JSONObject()
}

data class JourneyDiscountCodeResult(val raw: JSONObject) {
    constructor(valid: Boolean, values: JSONObject = JSONObject()) : this(
        JSONObject(values.toString()).put("valid", valid),
    )
}

enum class JourneyHapticType(val wireValue: String) {
    SELECTION("selection"),
    LIGHT("light"),
    MEDIUM("medium"),
    HEAVY("heavy"),
    SUCCESS("success"),
    WARNING("warning"),
    ERROR("error");

    companion object {
        fun fromWireValue(value: String): JourneyHapticType? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class JourneyCaptureTransportRequest(
    val url: String,
    val method: String,
    val body: JSONObject,
)
