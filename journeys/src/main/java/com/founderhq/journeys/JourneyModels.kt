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

data class JourneyPreparation(
    val config: JSONObject?,
    val revisionId: String,
    val version: Int,
    val captureIdentityMode: String,
    val refreshAfterSeconds: Long,
    val unchanged: Boolean,
    internal val supportsPreparation: Boolean = true,
) {
    companion object {
        const val MIN_REFRESH_SECONDS = 600L
        const val MAX_REFRESH_SECONDS = 1_800L
        const val MAXIMUM_START_AGE_MILLIS = 30 * 60 * 1_000L
    }

    internal fun clamped() = copy(
        refreshAfterSeconds = refreshAfterSeconds.coerceIn(
            MIN_REFRESH_SECONDS,
            MAX_REFRESH_SECONDS,
        ),
    )
}

enum class JourneyReadiness {
    IDLE,
    PREPARING,
    READY,
    DIRECT_PRESENTATION,
    PRESENTED,
    FAILED,
    BLOCKED,
    DISPOSED,
}

internal data class JourneyFreshness(
    val preparedAtMillis: Long,
    val refreshAfterSeconds: Long,
) {
    fun needsRefresh(nowMillis: Long): Boolean =
        nowMillis - preparedAtMillis >= refreshAfterSeconds.coerceIn(
            JourneyPreparation.MIN_REFRESH_SECONDS,
            JourneyPreparation.MAX_REFRESH_SECONDS,
        ) * 1_000L

    fun canStart(nowMillis: Long): Boolean =
        nowMillis - preparedAtMillis <= JourneyPreparation.MAXIMUM_START_AGE_MILLIS
}

internal class JourneyForegroundDeadline(private val totalMillis: Long) {
    var remainingMillis: Long = totalMillis
        private set
    private var startedAtMillis: Long? = null

    fun reset() {
        remainingMillis = totalMillis
        startedAtMillis = null
    }

    fun start(nowMillis: Long): Long? {
        if (startedAtMillis != null) return null
        startedAtMillis = nowMillis
        return remainingMillis
    }

    fun pause(nowMillis: Long) {
        val startedAt = startedAtMillis ?: return
        remainingMillis = (remainingMillis - (nowMillis - startedAt)).coerceAtLeast(0L)
        startedAtMillis = null
    }

    fun finish() {
        remainingMillis = 0L
        startedAtMillis = null
    }
}

internal data class JourneyPreparationReference(
    val value: JourneyPreparation,
    val preparedAtMillis: Long,
)

internal fun latestPreparationReference(
    active: JourneyPreparation?,
    activePreparedAtMillis: Long,
    pending: JourneyPreparation?,
    pendingPreparedAtMillis: Long,
): JourneyPreparationReference? = when {
    pending != null -> JourneyPreparationReference(pending, pendingPreparedAtMillis)
    active != null -> JourneyPreparationReference(active, activePreparedAtMillis)
    else -> null
}

internal fun mustStopForPreparationFailure(error: JourneyError): Boolean =
    !error.recoverable || error.code == "authorization_denied"

internal class JourneyAuthorizationGate {
    var blocksAutomaticAttempts: Boolean = false
        private set

    fun block() {
        blocksAutomaticAttempts = true
    }

    fun allowExplicitAttempt() {
        blocksAutomaticAttempts = false
    }
}
