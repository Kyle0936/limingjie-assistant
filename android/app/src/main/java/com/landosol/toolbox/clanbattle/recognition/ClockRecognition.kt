package com.landosol.toolbox.clanbattle.recognition

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class ClockCandidate(val timeSeconds: Int, val rawText: String, val score: Double, val isPrimary: Boolean)

data class RecognitionResult(
    val ok: Boolean,
    val timeSeconds: Int? = null,
    val rawText: String? = null,
    val confidence: Double = 0.0,
    val reason: String? = null,
    val candidates: List<ClockCandidate> = emptyList(),
)

class ClockRecognizer(private val templates: DigitTemplates) {
    private val digitMatcher = DigitMatcher(templates)
    private data class ColumnGroup(val x: Int, val width: Int)
    private data class DigitScore(val digit: Int, val score: Double)

    fun recognize(image: PixelImage, minConfidence: Double = 0.8): RecognitionResult {
        val groups = foregroundColumnGroups(image)
        if (groups.size < 4) return RecognitionResult(false, reason = "clock-split-failed")
        val minute = digitMatcher.match(cropGroup(image, groups[0], 2), 0..1)
        val tens = digitMatcher.match(cropGroup(image, groups[2], 1), 0..if (minute.digit == 1) 3 else 5)
        val ones = digitMatcher.match(cropGroup(image, groups[3], 1), 0..9)
        val totalSeconds = minute.digit * 60 + tens.digit * 10 + ones.digit
        val digitConfidence = min(minute.confidence, min(tens.confidence, ones.confidence))
        val shapeConfidence = ((min(minute.score, min(tens.score, ones.score)) - 0.30) / 0.40).coerceIn(0.0, 1.0)
        val confidence = max(digitConfidence, shapeConfidence)
        val inRange = totalSeconds in 1..90
        return RecognitionResult(
            ok = inRange && confidence >= minConfidence,
            timeSeconds = totalSeconds,
            rawText = formatClock(totalSeconds),
            confidence = confidence,
            reason = when {
                !inRange -> "out-of-range"
                confidence < minConfidence -> "low-confidence"
                else -> null
            },
            candidates = buildCandidates(minute, tens, ones),
        )
    }

    private fun buildCandidates(minute: DigitMatchResult, tens: DigitMatchResult, ones: DigitMatchResult): List<ClockCandidate> =
        buildList {
            for (tensOption in distinctOptions(tens)) {
                for (onesOption in distinctOptions(ones)) {
                    val total = minute.digit * 60 + tensOption.digit * 10 + onesOption.digit
                    if (total in 1..90) add(
                        ClockCandidate(
                            total,
                            formatClock(total),
                            min(minute.score, min(tensOption.score, onesOption.score)),
                            tensOption.digit == tens.digit && onesOption.digit == ones.digit,
                        ),
                    )
                }
            }
        }.sortedByDescending(ClockCandidate::score)

    private fun distinctOptions(match: DigitMatchResult): List<DigitScore> = buildList {
        add(DigitScore(match.digit, match.score))
        if (match.secondDigit != match.digit) add(DigitScore(match.secondDigit, match.secondScore))
        if (match.thirdDigit != match.digit && match.thirdDigit != match.secondDigit) {
            add(DigitScore(match.thirdDigit, match.thirdScore))
        }
    }

    private fun foregroundColumnGroups(image: PixelImage): List<ColumnGroup> {
        val average = image.pixels.sumOf(::luminance).toDouble() / image.pixels.size
        val threshold = average - 80.0
        val minimumDarkPixels = max(2, floor(image.height * 0.06).toInt())
        val darkCounts = IntArray(image.width) { x ->
            (0 until image.height).count { y -> luminance(image[x, y]) < threshold }
        }
        val groups = mutableListOf<ColumnGroup>()
        var start = -1
        darkCounts.forEachIndexed { x, count ->
            if (count >= minimumDarkPixels && start < 0) start = x
            if (start >= 0 && (count < minimumDarkPixels || x == darkCounts.lastIndex)) {
                val end = if (count < minimumDarkPixels) x - 1 else x
                groups += ColumnGroup(start, end - start + 1)
                start = -1
            }
        }
        return groups
    }

    private fun cropGroup(image: PixelImage, group: ColumnGroup, padding: Int): PixelImage {
        val left = max(0, group.x - padding)
        val right = min(image.width, group.x + group.width + padding)
        return image.crop(left, 0, right - left, image.height)
    }

    private fun luminance(color: Int): Int =
        ((color shr 16) and 0xff) + ((color shr 8) and 0xff) + (color and 0xff)

    private fun formatClock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

enum class ReadingSource { PRIMARY, ALTERNATIVE }

data class FilterResult(
    val accepted: Boolean,
    val reason: String? = null,
    val timeSeconds: Int? = null,
    val rawText: String? = null,
    val source: ReadingSource? = null,
    val shouldPause: Boolean = false,
)

class RecognitionFilter(
    private val minConfidence: Double = 0.8,
    private val maxFailedReads: Int = 8,
    private val temporalSlackSeconds: Double = 1.5,
) {
    private var lastAccepted: Int? = null
    private var lastTimestampMs: Long? = null
    private var failedReads = 0

    fun reset() {
        lastAccepted = null
        lastTimestampMs = null
        failedReads = 0
    }

    fun update(result: RecognitionResult, nowMs: Long): FilterResult {
        val seconds = result.timeSeconds
        if (!result.ok || result.confidence < minConfidence || seconds == null || seconds !in 1..90) {
            return reject(result.reason ?: "failed")
        }
        val previous = lastAccepted
        if (previous != null) {
            if (seconds > previous) return reject("time-increased")
            if (seconds == previous) {
                lastTimestampMs = nowMs
                failedReads = 0
                return FilterResult(false, "same-time", seconds, result.rawText, ReadingSource.PRIMARY)
            }
            val elapsed = lastTimestampMs?.let { ((nowMs - it).coerceAtLeast(0L)) / 1000.0 } ?: Double.MAX_VALUE
            if (previous - seconds > elapsed + temporalSlackSeconds) return reject("temporal-reject")
        }
        lastAccepted = seconds
        lastTimestampMs = nowMs
        failedReads = 0
        return FilterResult(true, timeSeconds = seconds, rawText = result.rawText, source = ReadingSource.PRIMARY)
    }

    private fun reject(reason: String): FilterResult {
        failedReads++
        return FilterResult(false, reason = reason, shouldPause = failedReads >= maxFailedReads)
    }
}

