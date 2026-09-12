package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.labyrinth.LabyrinthRelicMark

/** Only visible, individually confirmed series; absent/clipped rows never imply zero. */
data class LabyrinthRelicDetailObservation(
    val titleConfirmed: Boolean,
    val entries: Map<LabyrinthRelicMark, Int> = emptyMap(),
    val evidenceId: Long? = null,
)

internal object LabyrinthRelicDetailText {
    fun isSeriesTitle(text: String): Boolean = text.filterNot(Char::isWhitespace).contains("系列效果一览")

    fun parseVisibleSeries(text: String): Map<LabyrinthRelicMark, Int> {
        val headings = Regex("守备|加速|强化|会心|暴击|弱体|削弱|崩弱").findAll(text).toList()
        val entries = headings.mapIndexedNotNull { index, heading ->
            val mark = LabyrinthRelicMark.fromLabel(heading.value) ?: return@mapIndexedNotNull null
            val segment = text.substring(heading.range.last + 1, headings.getOrNull(index + 1)?.range?.first ?: text.length)
            // The ROI contains only series headings/icons, not descriptions. Reject conflicting
            // digits, bonus expressions and clipped rows instead of scavenging any number.
            if (segment.contains('+') || segment.contains('%') || segment.contains('-')) return@mapIndexedNotNull null
            val value = Regex("[0-9]+").findAll(segment).map { it.value.toIntOrNull() }.toList().singleOrNull()
                ?.takeIf { it in 0..30 } ?: return@mapIndexedNotNull null
            mark to value
        }
        return entries.groupBy({ it.first }, { it.second }).mapNotNull { (mark, values) ->
            values.distinct().singleOrNull()?.let { mark to it }
        }.toMap()
    }
}
