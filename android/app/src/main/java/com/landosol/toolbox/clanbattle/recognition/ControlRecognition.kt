package com.landosol.toolbox.clanbattle.recognition

import com.landosol.toolbox.clanbattle.axis.BattleSlot
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.sqrt

enum class VisualToggleState { ON, OFF, UNKNOWN }

data class ToggleObservation(
    val state: VisualToggleState,
    val onScore: Double,
    val offScore: Double? = null,
    val margin: Double = 0.0,
)

data class BattleControlCrops(
    val auto: PixelImage,
    val globalSet: PixelImage,
    val roleSets: Map<BattleSlot, PixelImage>,
)

data class BattleControlTemplates(
    val autoOn: PixelImage,
    val autoOff: PixelImage,
    val globalSetOn: PixelImage,
    val globalSetOff: PixelImage,
    val roleSetOn: PixelImage,
)

data class MenuButtonObservation(
    val score: Double,
    val trustworthy: Boolean,
)

data class BattleControlObservation(
    val auto: ToggleObservation,
    val globalSet: ToggleObservation,
    val roleSets: Map<BattleSlot, ToggleObservation>,
    val consistent: Boolean,
    val trustworthy: Boolean,
    val reason: String? = null,
)

class BattleControlRecognizer(
    private val templates: BattleControlTemplates,
    private val pairMinScore: Double = 0.65,
    private val pairMinMargin: Double = 0.08,
    private val badgeOnThreshold: Double = 0.75,
    private val badgeOffThreshold: Double = 0.56,
) {
    fun recognize(crops: BattleControlCrops): BattleControlObservation {
        require(crops.roleSets.keys == BattleSlot.entries.toSet())
        val auto = classifyPair(
            TemplateMatcher.score(crops.auto, templates.autoOn),
            TemplateMatcher.score(crops.auto, templates.autoOff),
        )
        val globalSet = classifyPair(
            TemplateMatcher.score(crops.globalSet, templates.globalSetOn),
            TemplateMatcher.score(crops.globalSet, templates.globalSetOff),
        )
        val roles = crops.roleSets.mapValues { (_, crop) ->
            classifyBadge(TemplateMatcher.animatedBadgeScore(crop, templates.roleSetOn))
        }
        val explicitlyOff = roles.filterValues { it.state == VisualToggleState.OFF }.keys
        val consistent = globalSet.state != VisualToggleState.ON || explicitlyOff.isEmpty()
        val complete = auto.state != VisualToggleState.UNKNOWN &&
            globalSet.state != VisualToggleState.UNKNOWN &&
            roles.values.none { it.state == VisualToggleState.UNKNOWN }
        return BattleControlObservation(
            auto = auto,
            globalSet = globalSet,
            roleSets = roles,
            consistent = consistent,
            trustworthy = consistent && complete,
            reason = when {
                !consistent -> "global-set-on-but-role-off:${explicitlyOff.joinToString(",")}" 
                !complete -> "control-state-unknown"
                else -> null
            },
        )
    }

    private fun classifyPair(onScore: Double, offScore: Double): ToggleObservation {
        val margin = abs(onScore - offScore)
        val state = when {
            maxOf(onScore, offScore) < pairMinScore -> VisualToggleState.UNKNOWN
            margin < pairMinMargin -> VisualToggleState.UNKNOWN
            onScore > offScore -> VisualToggleState.ON
            else -> VisualToggleState.OFF
        }
        return ToggleObservation(state, onScore, offScore, margin)
    }

    private fun classifyBadge(onScore: Double): ToggleObservation {
        require(badgeOffThreshold <= badgeOnThreshold)
        val state = when {
            onScore >= badgeOnThreshold -> VisualToggleState.ON
            onScore <= badgeOffThreshold -> VisualToggleState.OFF
            else -> VisualToggleState.UNKNOWN
        }
        return ToggleObservation(state, onScore)
    }
}

/** Confirms that the unobscured battle HUD still exposes its top-right menu button. */
class BattleMenuRecognizer(
    private val template: PixelImage,
    private val minScore: Double = 0.70,
) {
    init {
        require(minScore in -1.0..1.0)
    }

    fun recognize(crop: PixelImage): MenuButtonObservation {
        val score = TemplateMatcher.score(crop, template)
        return MenuButtonObservation(score = score, trustworthy = score >= minScore)
    }
}

object TemplateMatcher {
    private data class TemplateStats(val luminance: DoubleArray, val mean: Double, val denominator: Double)

    private val cache = IdentityHashMap<PixelImage, TemplateStats>()

    fun score(image: PixelImage, template: PixelImage): Double {
        val stats = stats(template)
        val count = template.width * template.height
        var imageSum = 0.0
        repeat(template.height) { y ->
            repeat(template.width) { x ->
                imageSum += luminance(image[map(x, template.width, image.width), map(y, template.height, image.height)])
            }
        }
        val imageMean = imageSum / count
        var numerator = 0.0
        var imageDenominator = 0.0
        repeat(template.height) { y ->
            repeat(template.width) { x ->
                val imageDelta = luminance(
                    image[map(x, template.width, image.width), map(y, template.height, image.height)],
                ) - imageMean
                val templateDelta = stats.luminance[y * template.width + x] - stats.mean
                numerator += imageDelta * templateDelta
                imageDenominator += imageDelta * imageDelta
            }
        }
        if (imageDenominator == 0.0 || stats.denominator == 0.0) return 0.0
        return numerator / sqrt(imageDenominator * stats.denominator)
    }

    fun animatedBadgeScore(image: PixelImage, template: PixelImage): Double {
        val coverage = cyanCoverage(image)
        if (coverage <= 0.45) return 0.0
        val structure = bestScaleScore(image, template)
        if (structure < 0.35) return structure
        return if (coverage >= 0.63) maxOf(structure, minOf(1.0, 0.75 + coverage - 0.63)) else structure
    }

    private fun bestScaleScore(image: PixelImage, template: PixelImage): Double {
        var best = -1.0
        val scales = doubleArrayOf(1.0, 0.94, 1.08, 0.86, 1.16, 0.78, 1.24)
        scales.forEach { scale ->
            val width = (template.width * scale).toInt().coerceIn(2, image.width)
            val height = (template.height * scale).toInt().coerceIn(2, image.height)
            val centerX = (image.width - width) / 2
            val centerY = (image.height - height) / 2
            listOf(0 to 0, -2 to 0, 2 to 0, 0 to -2, 0 to 2).forEach { (dx, dy) ->
                val left = (centerX + dx).coerceIn(0, image.width - width)
                val top = (centerY + dy).coerceIn(0, image.height - height)
                best = maxOf(best, score(image.crop(left, top, width, height), template))
            }
        }
        return best
    }

    private fun cyanCoverage(image: PixelImage): Double = image.pixels.count { color ->
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        green > 110 && blue > 110 && green - red > 35 && blue - red > 35 && abs(green - blue) < 100
    }.toDouble() / image.pixels.size

    private fun map(value: Int, sourceSize: Int, targetSize: Int): Int =
        (value * targetSize / sourceSize).coerceAtMost(targetSize - 1)

    private fun luminance(color: Int): Double {
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        return 0.299 * red + 0.587 * green + 0.114 * blue
    }

    @Synchronized
    private fun stats(template: PixelImage): TemplateStats = cache[template] ?: run {
        val values = DoubleArray(template.pixels.size) { luminance(template.pixels[it]) }
        val mean = values.average()
        val denominator = values.sumOf { value -> (value - mean) * (value - mean) }
        TemplateStats(values, mean, denominator).also { cache[template] = it }
    }
}
