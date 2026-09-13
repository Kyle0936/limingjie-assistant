package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage

data class LabyrinthBattleFailureObservation(
    val confidence: Double,
    val endButtonRect: EntryPixelRect,
    val retryButtonRect: EntryPixelRect,
)

/**
 * Detects the current-client battle-failure screen without depending on changing character art.
 *
 * The stable structure is unusually distinctive: a pale "结束" button on the lower left, a blue
 * "重新挑战" button on the lower right, the pale damage-report control in the upper right, and
 * the large blue failure heading. Keeping all four gates prevents a generic two-button popup from
 * being mistaken for a failed battle.
 */
class LabyrinthBattleFailureDetector(
    private val minEndLightCoverage: Double = 0.60,
    private val minRetryBlueCoverage: Double = 0.60,
    private val minReportLightCoverage: Double = 0.48,
    private val minReportBlueCoverage: Double = 0.55,
    private val minTitleBlueCoverage: Double = 0.18,
) {
    fun detect(frame: PixelImage): LabyrinthBattleFailureObservation? {
        if (frame.width <= frame.height) return null
        val endSample = map(frame, END_BUTTON_SAMPLE) ?: return null
        val retrySample = map(frame, RETRY_BUTTON_SAMPLE) ?: return null
        val legacyReportSample = map(frame, LEGACY_DAMAGE_REPORT_SAMPLE) ?: return null
        val currentReportSample = map(frame, CURRENT_DAMAGE_REPORT_SAMPLE) ?: return null
        val titleSample = map(frame, FAILURE_TITLE_SAMPLE) ?: return null
        val endButton = map(frame, END_BUTTON_RECT) ?: return null
        val retryButton = map(frame, RETRY_BUTTON_RECT) ?: return null

        val endLight = coverage(frame, endSample, ::isLight)
        val retryBlue = coverage(frame, retrySample, ::isBlue)
        val legacyReportLight = coverage(frame, legacyReportSample, ::isLight)
        val currentReportBlue = coverage(frame, currentReportSample, ::isBlue)
        val reportConfidence = maxOf(legacyReportLight, currentReportBlue)
        val reportMatched =
            legacyReportLight >= minReportLightCoverage || currentReportBlue >= minReportBlueCoverage
        val titleBlue = coverage(frame, titleSample, ::isBlue)
        if (
            endLight < minEndLightCoverage ||
            retryBlue < minRetryBlueCoverage ||
            !reportMatched ||
            titleBlue < minTitleBlueCoverage
        ) return null

        return LabyrinthBattleFailureObservation(
            confidence = (endLight + retryBlue + reportConfidence + titleBlue) / 4.0,
            endButtonRect = endButton,
            retryButtonRect = retryButton,
        )
    }

    private fun map(frame: PixelImage, rect: EntryReferenceRect): EntryPixelRect? =
        ReferenceFitMapper.map(
            frameWidth = frame.width,
            frameHeight = frame.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = rect,
        )

    private fun coverage(
        frame: PixelImage,
        rect: EntryPixelRect,
        predicate: (red: Int, green: Int, blue: Int) -> Boolean,
    ): Double {
        var matches = 0
        var samples = 0
        var y = rect.top
        while (y < rect.top + rect.height) {
            var x = rect.left
            while (x < rect.left + rect.width) {
                val color = frame[x, y]
                val red = color shr 16 and 0xff
                val green = color shr 8 and 0xff
                val blue = color and 0xff
                if (predicate(red, green, blue)) matches++
                samples++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        return if (samples == 0) 0.0 else matches.toDouble() / samples
    }

    private companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        val FAILURE_TITLE_SAMPLE = EntryReferenceRect(740, 40, 450, 130)
        // Older captures placed the report control near the top-right. Current Boss failure
        // screens render the blue report button inside the white result panel lower down.
        // Accept either stable layout so client/UI revisions do not silently disable retry.
        val LEGACY_DAMAGE_REPORT_SAMPLE = EntryReferenceRect(1600, 55, 220, 45)
        val CURRENT_DAMAGE_REPORT_SAMPLE = EntryReferenceRect(1380, 250, 380, 55)
        val END_BUTTON_SAMPLE = EntryReferenceRect(1010, 965, 260, 55)
        val RETRY_BUTTON_SAMPLE = EntryReferenceRect(1480, 965, 260, 55)
        val END_BUTTON_RECT = EntryReferenceRect(945, 935, 430, 110)
        val RETRY_BUTTON_RECT = EntryReferenceRect(1405, 935, 430, 110)
        const val SAMPLE_STEP = 4

        fun isBlue(red: Int, green: Int, blue: Int): Boolean =
            blue >= 150 && blue - red >= 35 && blue - green >= 10

        fun isLight(red: Int, green: Int, blue: Int): Boolean =
            red >= 205 && green >= 205 && blue >= 205
    }
}
