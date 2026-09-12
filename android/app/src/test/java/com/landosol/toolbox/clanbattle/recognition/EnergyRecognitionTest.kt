package com.landosol.toolbox.clanbattle.recognition

import com.landosol.toolbox.clanbattle.axis.BattleSlot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyRecognitionTest {
    @Test
    fun `full to low requires two consecutive frames before confirming drop`() {
        val regions = BattleSlot.entries.associateWith { slot -> EnergyRegion(slot.ordinal * 12, 0, 10, 3) }
        val detector = EnergyRecognizer(regions, requiredDropFrames = 2)

        detector.detect(energyFrame(regions, BattleSlot.entries.associateWith { 10 }))
        val firstLow = detector.detect(energyFrame(regions, BattleSlot.entries.associateWith { slot ->
            if (slot == BattleSlot.SLOT_1) 0 else 10
        }))
        val secondLow = detector.detect(energyFrame(regions, BattleSlot.entries.associateWith { slot ->
            if (slot == BattleSlot.SLOT_1) 0 else 10
        }))

        assertFalse(firstLow.confirmedDrops.contains(BattleSlot.SLOT_1))
        assertTrue(secondLow.confirmedDrops.contains(BattleSlot.SLOT_1))
    }

    private fun energyFrame(regions: Map<BattleSlot, EnergyRegion>, fills: Map<BattleSlot, Int>): PixelImage {
        val width = 12 * BattleSlot.entries.size
        val pixels = IntArray(width * 3) { 0xffffffff.toInt() }
        regions.forEach { (slot, region) ->
            val fill = fills.getValue(slot).coerceIn(0, region.width)
            repeat(3) { y ->
                repeat(fill) { x -> pixels[(region.y + y) * width + region.x + x] = 0xff0080ff.toInt() }
            }
        }
        return PixelImage(width, 3, pixels)
    }
}
