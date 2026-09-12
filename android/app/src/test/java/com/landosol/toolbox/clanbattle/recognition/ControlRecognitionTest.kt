package com.landosol.toolbox.clanbattle.recognition

import com.landosol.toolbox.clanbattle.axis.BattleSlot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRecognitionTest {
    @Test
    fun `inconsistent global set and role badges are never trustworthy`() {
        val on = cyanTemplate()
        val off = whiteTemplate()
        val recognizer = BattleControlRecognizer(
            BattleControlTemplates(on, off, on, off, on),
        )
        val observation = recognizer.recognize(
            BattleControlCrops(
                auto = on,
                globalSet = on,
                roleSets = BattleSlot.entries.associateWith { slot -> if (slot == BattleSlot.SLOT_1) on else off },
            ),
        )

        assertFalse(observation.consistent)
        assertFalse(observation.trustworthy)
        assertTrue(observation.reason!!.startsWith("global-set-on-but-role-off"))
    }

    private fun cyanTemplate(): PixelImage = PixelImage(4, 4, IntArray(16) { index ->
        if (index % 4 == 0 || index / 4 == 0) 0xff00e0ff.toInt() else 0xff202020.toInt()
    })

    private fun whiteTemplate() = PixelImage(4, 4, IntArray(16) { 0xffffffff.toInt() })
}
