package com.landosol.toolbox.clanbattle.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleMenuRecognitionTest {
    @Test
    fun `matching menu anchor is trustworthy`() {
        val template = pattern(0xff202020.toInt())

        val observation = BattleMenuRecognizer(template).recognize(template)

        assertTrue(observation.trustworthy)
        assertTrue(observation.score > 0.99)
    }

    @Test
    fun `different menu anchor is unsafe`() {
        val template = pattern(0xff202020.toInt())
        val different = pattern(0xffe0e0e0.toInt())

        val observation = BattleMenuRecognizer(template).recognize(different)

        assertFalse(observation.trustworthy)
        assertTrue(observation.score < 0.70)
    }

    private fun pattern(color: Int) = PixelImage(8, 6, IntArray(48) { index ->
        if (index % 5 == 0) color else 0xff808080.toInt()
    })
}
