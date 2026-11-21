package com.khumam.gbaduo.companion.ui

/** How long ago the pack list was fetched, in the unit a status line wants: just now, minutes, hours or days. */
sealed interface AgeLabel {
    data object JustNow : AgeLabel
    data class Minutes(val count: Int) : AgeLabel
    data class Hours(val count: Int) : AgeLabel
    data class Days(val count: Int) : AgeLabel

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR

        fun of(ageMillis: Long): AgeLabel = when {
            ageMillis < MINUTE -> JustNow
            ageMillis < HOUR -> Minutes((ageMillis / MINUTE).toInt())
            ageMillis < DAY -> Hours((ageMillis / HOUR).toInt())
            else -> Days((ageMillis / DAY).toInt())
        }
    }
}
