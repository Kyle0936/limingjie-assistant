package com.landosol.toolbox.clanbattle.recognition

import com.landosol.toolbox.clanbattle.axis.BattleSlot
import kotlin.math.abs

data class EnergyRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    init {
        require(x >= 0 && y >= 0)
        require(width > 0 && height > 0)
    }
}

data class SlotEnergyObservation(
    val fillRatio: Float,
    val isFull: Boolean,
    val delta: Float?,
    val dropConfirmed: Boolean,
    val confirmationFrames: Int,
)

data class EnergyObservation(
    val slots: Map<BattleSlot, SlotEnergyObservation>,
    val averageDelta: Float?,
    val confirmedDrops: Set<BattleSlot>,
)

/**
 * Reads the horizontal TP fill extent and confirms a full-to-low transition over multiple frames.
 * A single blue-background artefact is diagnostic only and never becomes a UB event.
 */
class EnergyRecognizer(
    private val regions: Map<BattleSlot, EnergyRegion>,
    private val fullThreshold: Float = 0.97f,
    private val dropThreshold: Float = 0.30f,
    private val requiredDropFrames: Int = 2,
) {
    private var previousRatios: Map<BattleSlot, Float>? = null
    private val armedFromFull = BattleSlot.entries.associateWith { false }.toMutableMap()
    private val lowFrameCounts = BattleSlot.entries.associateWith { 0 }.toMutableMap()

    init {
        require(regions.keys == BattleSlot.entries.toSet())
        require(fullThreshold in 0f..1f)
        require(dropThreshold in 0f..1f)
        require(dropThreshold < fullThreshold)
        require(requiredDropFrames >= 1)
    }

    fun detect(image: PixelImage): EnergyObservation {
        val ratios = regions.mapValues { (_, region) -> fillExtentRatio(image, region) }
        val previous = previousRatios
        val observations = ratios.mapValues { (slot, ratio) ->
            if (ratio >= fullThreshold) {
                armedFromFull[slot] = true
                lowFrameCounts[slot] = 0
            } else if (armedFromFull.getValue(slot) && ratio < dropThreshold) {
                lowFrameCounts[slot] = lowFrameCounts.getValue(slot) + 1
            } else {
                lowFrameCounts[slot] = 0
            }

            val confirmed = armedFromFull.getValue(slot) && lowFrameCounts.getValue(slot) >= requiredDropFrames
            if (confirmed) {
                armedFromFull[slot] = false
                lowFrameCounts[slot] = 0
            }
            SlotEnergyObservation(
                fillRatio = ratio,
                isFull = ratio >= fullThreshold,
                delta = previous?.get(slot)?.let { abs(ratio - it) },
                dropConfirmed = confirmed,
                confirmationFrames = if (confirmed) requiredDropFrames else lowFrameCounts.getValue(slot),
            )
        }
        previousRatios = ratios
        return EnergyObservation(
            slots = observations,
            averageDelta = previous?.let {
                observations.values.sumOf { observation -> observation.delta!!.toDouble() }.toFloat() / observations.size
            },
            confirmedDrops = observations.filterValues(SlotEnergyObservation::dropConfirmed).keys,
        )
    }

    fun reset() {
        previousRatios = null
        BattleSlot.entries.forEach { slot ->
            armedFromFull[slot] = false
            lowFrameCounts[slot] = 0
        }
    }

    private fun fillExtentRatio(image: PixelImage, region: EnergyRegion): Float {
        require(region.x + region.width <= image.width)
        require(region.y + region.height <= image.height)
        val blueColumns = BooleanArray(region.width) { column ->
            val bluePixels = (region.y until region.y + region.height).count { y ->
                isBluePixel(image[region.x + column, y])
            }
            bluePixels.toFloat() / region.height >= MIN_BLUE_COLUMN_RATIO
        }
        val smoothed = BooleanArray(region.width) { column ->
            val start = maxOf(0, column - SMOOTHING_RADIUS)
            val end = minOf(region.width - 1, column + SMOOTHING_RADIUS)
            val count = (start..end).count { blueColumns[it] }
            count * 2 > end - start + 1
        }
        val lastFilled = smoothed.indexOfLast { it }
        return if (lastFilled < 0) 0f else (lastFilled + 1).toFloat() / region.width
    }

    companion object {
        private const val MIN_BLUE_COLUMN_RATIO = 0.20f
        private const val SMOOTHING_RADIUS = 2

        fun isBluePixel(color: Int): Boolean {
            val red = color ushr 16 and 0xff
            val green = color ushr 8 and 0xff
            val blue = color and 0xff
            return blue > red + 40 && blue > green + 30 && blue > 80
        }
    }
}
