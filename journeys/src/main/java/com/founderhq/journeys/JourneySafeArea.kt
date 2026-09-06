package com.founderhq.journeys

/** Only the portion of a system bar that overlaps the WebView belongs to its safe area. */
internal data class JourneySafeArea(
    val top: Float,
    val right: Float,
    val bottom: Float,
    val left: Float,
) {
    fun script(): String = "(function(){var s=document.documentElement.style;" +
        listOf("top" to top, "right" to right, "bottom" to bottom, "left" to left)
            .joinToString("") { (edge, value) ->
                "s.setProperty('--jy-native-safe-area-$edge','${value}px');"
            } + "})();true;"

    companion object {
        fun overlapping(
            top: Int, right: Int, bottom: Int, left: Int,
            x: Int, y: Int, width: Int, height: Int,
            windowWidth: Int, windowHeight: Int, density: Float,
        ): JourneySafeArea = JourneySafeArea(
            (top - y).coerceIn(0, height).toFloat() / density,
            (x + width - windowWidth + right).coerceIn(0, width).toFloat() / density,
            (y + height - windowHeight + bottom).coerceIn(0, height).toFloat() / density,
            (left - x).coerceIn(0, width).toFloat() / density,
        )
    }
}
