package com.founderhq.journeys

import org.json.JSONObject

class JourneyController {
    internal var sink: ((JSONObject) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) {
                queuedCommands.forEach(value)
                queuedCommands.clear()
            }
        }
    private val queuedCommands = mutableListOf<JSONObject>()

    private fun send(command: JSONObject) {
        sink?.invoke(command) ?: queuedCommands.add(command)
    }

    @JvmOverloads
    fun goNext(answer: Any? = null) = send(JSONObject().apply {
        put("name", "go_next")
        if (answer != null) put("answer", answer)
    })

    fun goBack() = send(JSONObject().put("name", "go_back"))

    fun goToStep(stepId: String) = send(
        JSONObject().put("name", "go_to_step").put("stepId", stepId),
    )

    fun setAnswer(variable: String, answer: Any?) = send(
        JSONObject()
            .put("name", "set_answer")
            .put("variable", variable)
            .put("answer", answer ?: JSONObject.NULL),
    )

    fun flushCapture() = send(JSONObject().put("name", "flush_capture"))
    fun reload() = send(JSONObject().put("name", "reload"))
}
