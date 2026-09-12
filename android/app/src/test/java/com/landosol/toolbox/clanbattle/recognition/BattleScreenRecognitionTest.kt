package com.landosol.toolbox.clanbattle.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleScreenRecognitionTest {
    @Test
    fun `screen recognizer distinguishes start and loading templates`() {
        val start = pattern(0xff000000.toInt())
        val loading = pattern(0xffffffff.toInt())
        val recognizer = BattleScreenRecognizer(BattleScreenTemplates(start, loading), minScore = 0.5)

        val result = recognizer.recognize(BattleScreenCrops(start, start))

        assertEquals(BattleScreenStage.BATTLE_START, result.stage)
        assertTrue(result.confidence > 0.5)
        assertTrue(result.startScore > result.loadingScore)
    }

    @Test
    fun `ambiguous screen evidence remains unknown`() {
        val template = pattern(0xff000000.toInt())
        val recognizer = BattleScreenRecognizer(BattleScreenTemplates(template, template), minScore = 0.5)

        val result = recognizer.recognize(BattleScreenCrops(template, template))

        assertEquals(BattleScreenStage.UNKNOWN, result.stage)
    }

    private fun pattern(color: Int) = PixelImage(4, 4, IntArray(16) { index ->
        if (index % 3 == 0) color else 0xff808080.toInt()
    })
}
