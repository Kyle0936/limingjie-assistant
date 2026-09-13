package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.gamedata.EventChoiceResource
import com.landosol.toolbox.gamedata.EventLayoutTemplateResource
import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.gamedata.NormalizedOcrRect
import kotlin.math.max
import kotlin.math.roundToInt

data class LabyrinthEventTextMatch(
    val event: EventResource?,
    val score: Double,
    val margin: Double,
    val titleScore: Double,
    val choiceScore: Double,
    val trusted: Boolean,
)

data class LabyrinthEventChoiceVisual(
    val choice: EventChoiceResource,
    val buttonRect: EntryPixelRect,
    /** Pixel evidence that the mapped button is currently blue/enabled. */
    val buttonConfidence: Double,
)

data class LabyrinthEventChoiceObservation(
    val event: EventResource,
    val choices: List<LabyrinthEventChoiceVisual>,
    val rawText: String,
    val confidence: Double,
    val rivalMargin: Double,
    val stableFrames: Int,
    val trusted: Boolean,
)

/**
 * Matches one OCR block against the database-backed event catalog. Shared event titles are
 * deliberately not sufficient: labels, effects and numeric rewards all participate so the four
 * arena variants and four guild-recruit variants can be separated.
 */
class LabyrinthEventTextRecognizer(
    private val events: List<EventResource>,
    private val minimumCandidateScore: Double = 0.62,
    private val minimumTrustedScore: Double = 0.72,
    private val minimumTrustedMargin: Double = 0.045,
) {
    init {
        require(events.isNotEmpty())
        require(events.map(EventResource::id).distinct().size == events.size)
    }

    fun recognize(observedText: String): LabyrinthEventTextMatch {
        val observed = normalizeEventText(observedText)
        if (observed.length < MINIMUM_OBSERVED_TEXT_LENGTH) return emptyMatch()
        val ranked = events.map { event -> score(event, observed) }
            .sortedWith(compareByDescending<ScoredEvent>(ScoredEvent::score).thenBy { it.event.id })
        val best = ranked.first()
        val runnerUp = ranked.getOrNull(1)?.score ?: 0.0
        val margin = (best.score - runnerUp).coerceAtLeast(0.0)
        val candidate = best.score >= minimumCandidateScore &&
            best.titleScore >= MINIMUM_TITLE_SCORE &&
            best.choiceScore >= MINIMUM_CHOICE_SCORE
        return LabyrinthEventTextMatch(
            event = best.event.takeIf { candidate },
            score = best.score,
            margin = margin,
            titleScore = best.titleScore,
            choiceScore = best.choiceScore,
            trusted = candidate && best.score >= minimumTrustedScore && margin >= minimumTrustedMargin,
        )
    }

    private fun score(event: EventResource, observed: String): ScoredEvent {
        val titleScore = fuzzyContainedScore(normalizeEventText(event.name), observed)
        val choiceScores = event.choices.map { choice ->
            val label = normalizeEventText(choice.name)
            val description = normalizeEventText(choice.description)
            val labelScore = fuzzyContainedScore(label, observed)
            val descriptionScore = fuzzyContainedScore(description, observed)
            val digitScore = numericEvidenceScore(label + description, observed)
            labelScore * 0.48 + descriptionScore * 0.37 + digitScore * 0.15
        }
        val choiceScore = choiceScores.averageOrZero()
        return ScoredEvent(
            event = event,
            score = titleScore * 0.35 + choiceScore * 0.65,
            titleScore = titleScore,
            choiceScore = choiceScore,
        )
    }

    private fun fuzzyContainedScore(target: String, observed: String): Double {
        if (target.isBlank()) return 0.0
        if (observed.contains(target)) return 1.0
        val lcs = longestCommonSubsequenceLength(target, observed).toDouble() / target.length
        val dice = bigramContainmentDice(target, observed)
        return (lcs * 0.55 + dice * 0.45).coerceIn(0.0, 1.0)
    }

    /** Best Dice score against target-sized windows, rather than diluting it with the full page. */
    private fun bigramContainmentDice(target: String, observed: String): Double {
        if (target.length < 2 || observed.length < 2) return 0.0
        val targetPairs = bigramCounts(target)
        val windowSize = (target.length + 4).coerceAtMost(observed.length)
        var best = 0.0
        val step = max(1, target.length / 8)
        var start = 0
        while (start <= observed.length - windowSize) {
            val windowPairs = bigramCounts(observed.substring(start, start + windowSize))
            val overlap = targetPairs.entries.sumOf { (pair, count) ->
                minOf(count, windowPairs[pair] ?: 0)
            }
            val denominator = targetPairs.values.sum() + windowPairs.values.sum()
            if (denominator > 0) best = max(best, 2.0 * overlap / denominator)
            start += step
        }
        return best
    }

    private fun numericEvidenceScore(target: String, observed: String): Double {
        val expected = NUMBER.findAll(target).map(MatchResult::value).toSet()
        if (expected.isEmpty()) return 1.0
        val actual = NUMBER.findAll(observed).map(MatchResult::value).toSet()
        return expected.count(actual::contains).toDouble() / expected.size
    }

    private fun longestCommonSubsequenceLength(first: String, second: String): Int {
        // Event labels are short; retaining only one row keeps this cheap even for a full-page OCR block.
        var previous = IntArray(second.length + 1)
        first.forEach { left ->
            val current = IntArray(second.length + 1)
            second.forEachIndexed { index, right ->
                current[index + 1] = if (left == right) previous[index] + 1
                else max(current[index], previous[index + 1])
            }
            previous = current
        }
        return previous.last()
    }

    private fun bigramCounts(value: String): Map<String, Int> =
        (0 until value.length - 1)
            .map { index -> value.substring(index, index + 2) }
            .groupingBy(String::toString)
            .eachCount()

    private fun emptyMatch() = LabyrinthEventTextMatch(null, 0.0, 0.0, 0.0, 0.0, false)

    private data class ScoredEvent(
        val event: EventResource,
        val score: Double,
        val titleScore: Double,
        val choiceScore: Double,
    )

    private companion object {
        const val MINIMUM_OBSERVED_TEXT_LENGTH = 12
        const val MINIMUM_TITLE_SCORE = 0.62
        const val MINIMUM_CHOICE_SCORE = 0.48
        val NUMBER = Regex("[0-9]+")
    }
}

class LabyrinthEventLayoutMapper(
    layouts: List<EventLayoutTemplateResource>,
) {
    private val layoutsByCount = layouts.associateBy(EventLayoutTemplateResource::optionCount)

    fun buttonRects(optionCount: Int, frameWidth: Int, frameHeight: Int): List<EntryPixelRect> {
        val layout = layoutsByCount[optionCount] ?: return emptyList()
        if (layout.calibrationRequired || layout.selectButtonRois.size != optionCount) return emptyList()
        return layout.selectButtonRois.mapNotNull { it.toPixelRect(frameWidth, frameHeight) }
    }

    /**
     * Tight OCR bounds for one calibrated layout.  The event resource already stores a title ROI
     * and one text ROI per option; using their union keeps unrelated map controls and most of the
     * background out of Chinese OCR while still allowing one asynchronous read per frame.
     */
    fun ocrBounds(optionCount: Int, frameWidth: Int, frameHeight: Int): EntryPixelRect? {
        val layout = layoutsByCount[optionCount] ?: return null
        if (layout.calibrationRequired || layout.optionTextRois.size != optionCount) return null
        val normalized = listOfNotNull(layout.titleRoi) + layout.optionTextRois
        if (normalized.isEmpty()) return null
        val left = normalized.minOf { it.left }
        val top = normalized.minOf { it.top }
        val right = normalized.maxOf { it.right }
        val bottom = normalized.maxOf { it.bottom }
        return NormalizedOcrRect(left, top, right, bottom).toPixelRect(frameWidth, frameHeight)
    }

    private fun NormalizedOcrRect.toPixelRect(frameWidth: Int, frameHeight: Int): EntryPixelRect? {
        if (frameWidth <= 0 || frameHeight <= 0 || left !in 0.0..1.0 || top !in 0.0..1.0 ||
            right !in 0.0..1.0 || bottom !in 0.0..1.0 || left >= right || top >= bottom
        ) return null
        val pixelLeft = (left * frameWidth).toInt().coerceIn(0, frameWidth - 1)
        val pixelTop = (top * frameHeight).toInt().coerceIn(0, frameHeight - 1)
        val pixelRight = (right * frameWidth).toInt().coerceIn(pixelLeft + 1, frameWidth)
        val pixelBottom = (bottom * frameHeight).toInt().coerceIn(pixelTop + 1, frameHeight)
        return EntryPixelRect(pixelLeft, pixelTop, pixelRight - pixelLeft, pixelBottom - pixelTop)
    }
}

internal fun normalizeEventText(value: String): String = buildString(value.length) {
    value.lowercase().forEach { character ->
        if (character.isLetterOrDigit()) append(character)
    }
}

private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

/** Shared by the bitmap resolver and screenshot regressions. Text and borders are sampled too. */
internal fun labyrinthEventBlueButtonConfidence(
    rect: EntryPixelRect,
    pixelAt: (Int, Int) -> Int,
): Double {
    var blue = 0
    val rows = 8
    val columns = 16
    for (row in 1..rows) {
        val y = rect.top + (row.toDouble() * (rect.height - 1) / (rows + 1)).roundToInt()
        for (column in 1..columns) {
            val x = rect.left + (column.toDouble() * (rect.width - 1) / (columns + 1)).roundToInt()
            val color = pixelAt(x, y)
            val red = color ushr 16 and 0xff
            val green = color ushr 8 and 0xff
            val channelBlue = color and 0xff
            if (channelBlue >= 105 && channelBlue >= red + 18 && channelBlue >= green - 25) blue++
        }
    }
    return blue.toDouble() / (rows * columns)
}

/**
 * Blue fill is an actionability signal, not a reliable option-count signal: condition-gated
 * event choices may be grey.  When several *complete* layouts are plausible, prefer the one with
 * more options so a three-choice page cannot collapse to the identical centered one-choice ROI.
 * A null result means the caller must fall back to generic OCR and let the matched event catalog
 * provide the authoritative choice count.
 */
internal fun labyrinthCompleteEventLayoutCount(
    confidencesByOptionCount: Map<Int, List<Double>>,
    minimumButtonConfidence: Double,
): Int? = confidencesByOptionCount
    .asSequence()
    .filter { (count, confidences) ->
        count > 0 && confidences.size == count && confidences.all { it >= minimumButtonConfidence }
    }
    .sortedWith(
        compareByDescending<Map.Entry<Int, List<Double>>> { it.key }
            .thenByDescending { it.value.averageOrZero() },
    )
    .firstOrNull()
    ?.key
