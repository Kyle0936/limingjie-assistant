package com.landosol.toolbox.clanbattle.recognition

import com.landosol.toolbox.automation.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClanBattleObservationTest {
    @Test
    fun `processor emits structured battle observation without allowing actions`() {
        val clock = clock(minute = 1, tens = 2, ones = 3)
        val profile = ClanBattleVisionProfile(
            referenceSize = PixelSize(clock.width, clock.height),
            clock = ReferenceRegion(0, 0, clock.width, clock.height),
            energyHud = null,
            autoButton = null,
            globalSetButton = null,
            roleSetBadges = emptyMap(),
        )

        val observation = ClanBattleFrameProcessor(
            templates = DigitTemplates((0..9).associateWith { digit(it.toString()) }),
            profile = profile,
        ).process(clock, timestampMillis = 1_000)

        assertEquals(83, observation.filteredClock?.timeSeconds)
        assertEquals(ClanBattleScreenKind.BATTLE, observation.screenKind)
        assertFalse(observation.actionSafe)
        assertTrue(observation.viewport!!.scale > 0f)
    }

    @Test
    fun `portrait frame is unsupported and requests pause`() {
        val processor = ClanBattleFrameProcessor(
            templates = DigitTemplates((0..9).associateWith { digit(it.toString()) }),
        )

        val observation = processor.process(PixelImage(5, 10, IntArray(50)), timestampMillis = 1_000)

        assertEquals(ClanBattleScreenKind.UNSUPPORTED_ORIENTATION, observation.screenKind)
        assertTrue(observation.shouldPause)
        assertFalse(observation.actionSafe)
    }

    @Test
    fun `clock with missing menu anchor is classified as obscured battle`() {
        val clock = clock(minute = 1, tens = 2, ones = 3)
        val profile = ClanBattleVisionProfile(
            referenceSize = PixelSize(clock.width, clock.height),
            clock = ReferenceRegion(0, 0, clock.width, clock.height),
            menuButton = ReferenceRegion(0, 0, clock.width, clock.height),
            energyHud = null,
            autoButton = null,
            globalSetButton = null,
            roleSetBadges = emptyMap(),
        )
        val menuTemplate = PixelImage(clock.width, clock.height, clock.pixels.map { it.inv() }.toIntArray())

        val observation = ClanBattleFrameProcessor(
            templates = DigitTemplates((0..9).associateWith { digit(it.toString()) }),
            profile = profile,
            menuRecognizer = BattleMenuRecognizer(menuTemplate),
        ).process(clock, timestampMillis = 1_000)

        assertEquals(ClanBattleScreenKind.BATTLE_MENU_OR_OVERLAY, observation.screenKind)
        assertTrue(observation.shouldPause)
        assertFalse(observation.actionSafe)
        assertFalse(observation.menuButton!!.trustworthy)
    }

    private fun digit(value: String): PixelImage {
        val patterns = mapOf(
            '0' to listOf("111", "101", "101", "101", "111"),
            '1' to listOf("010", "110", "010", "010", "111"),
            '2' to listOf("111", "001", "111", "100", "111"),
            '3' to listOf("111", "001", "111", "001", "111"),
            '4' to listOf("101", "101", "111", "001", "001"),
            '5' to listOf("111", "100", "111", "001", "111"),
            '6' to listOf("111", "100", "111", "101", "111"),
            '7' to listOf("111", "001", "001", "001", "001"),
            '8' to listOf("111", "101", "111", "101", "111"),
            '9' to listOf("111", "101", "111", "001", "111"),
        )
        val rows = patterns.getValue(value.single())
        val pixels = IntArray(15) { 0xffffffff.toInt() }
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, cell -> if (cell == '1') pixels[y * 3 + x] = 0xff000000.toInt() }
        }
        return PixelImage(3, 5, pixels)
    }

    private fun clock(minute: Int, tens: Int, ones: Int): PixelImage {
        val groups = listOf(digit(minute.toString()), colon(), digit(tens.toString()), digit(ones.toString()))
        val gap = 2
        val width = groups.sumOf(PixelImage::width) + gap * (groups.size - 1)
        val pixels = IntArray(width * 7) { 0xffffffff.toInt() }
        var offset = 0
        groups.forEachIndexed { index, group ->
            repeat(group.height) { y ->
                repeat(group.width) { x -> pixels[(y + 1) * width + offset + x] = group[x, y] }
            }
            offset += group.width
            if (index != groups.lastIndex) offset += gap
        }
        return PixelImage(width, 7, pixels)
    }

    private fun colon() = PixelImage(1, 5, IntArray(5) { index ->
        if (index == 1 || index == 3) 0xff000000.toInt() else 0xffffffff.toInt()
    })
}
