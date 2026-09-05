package com.founderhq.journeys

import android.net.Uri

data class JourneyError(
    val code: String,
    override val message: String,
    val recoverable: Boolean,
    override val cause: Throwable? = null,
) : Exception(message, cause) {
    var httpStatus: Int? = null
        internal set
    var retryAfterMillis: Long? = null
        internal set
}

interface JourneyListener {
    fun onEvent(event: JourneyEvent) = Unit
    fun onError(error: JourneyError) = Unit
    /** Return true when the host handled the URL; false uses the system browser/deep-link handler. */
    fun onOpenUrl(url: Uri): Boolean = false

    /** Call completion with a JSON result or an exception. */
    fun onDiscountCodeApply(
        request: JourneyDiscountCodeRequest,
        completion: (Result<JourneyDiscountCodeResult>) -> Unit,
    ) = completion(Result.failure(IllegalStateException("No discount code handler was provided")))
}
