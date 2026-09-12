package com.landosol.toolbox.automation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationOverlayLabelLayoutTest {
    private val measureCharacters: (String) -> Float = { text -> text.codePointCount(0, text.length).toFloat() }

    @Test
    fun `long Chinese node label wraps without losing text`() {
        val label = "候选 2/1 视觉=遗物 0.56 可点 → 拓扑=连结"

        val lines = wrapAutomationOverlayLabel(
            label = label,
            maxLineWidth = 12f,
            maxLines = 8,
            measureText = measureCharacters,
        )

        assertTrue(lines.size > 1)
        assertTrue(lines.all { measureCharacters(it) <= 12f })
        assertEquals(label, lines.joinToString(""))
    }

    @Test
    fun `label exceeding visible rows is ellipsized on final row`() {
        val lines = wrapAutomationOverlayLabel(
            label = "TYPE_CONFLICT 10503 视觉=遗物 路线=连结 置信度=0.990",
            maxLineWidth = 10f,
            maxLines = 2,
            measureText = measureCharacters,
        )

        assertEquals(2, lines.size)
        assertTrue(lines.last().endsWith("…"))
        assertTrue(lines.all { measureCharacters(it) <= 10f })
    }

    @Test
    fun `explicit line break is preserved`() {
        assertEquals(
            listOf("目标 连结#10503", "可点 → 拓扑"),
            wrapAutomationOverlayLabel(
                label = "目标 连结#10503\n可点 → 拓扑",
                maxLineWidth = 20f,
                maxLines = 4,
                measureText = measureCharacters,
            ),
        )
    }
}
