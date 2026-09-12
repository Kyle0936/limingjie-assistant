package com.landosol.toolbox.clanbattle.axis

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisParserTest {
    @Test
    fun `parses owned water axis sample and validates it`() {
        val text = javaClass.classLoader!!.getResourceAsStream("clanbattle/water-7-6.axis.txt")!!
            .readBytes().toString(StandardCharsets.UTF_8)

        val document = AxisParser.parse(text)
        val result = AxisValidator.validate(document)

        assertEquals(AxisType.SEQUENCE, document.type)
        assertEquals(7, document.events.size)
        assertEquals(76, document.events.first().timeSeconds)
        assertTrue(document.events.any { it.trigger is BossDelayTrigger })
        assertTrue(result.isValid)
    }

    @Test
    fun `preserves action order and resolves aliases only for clicks`() {
        val document = AxisParser.parse(
            """
            轴类型=顺序
            轴名称=测试
            角色1=ams
            [轴]
            0:10 | 点击=ams | 点击=AUTO | 提示=准备
            """.trimIndent(),
        )

        val actions = document.events.single().actions

        assertEquals(AxisActionType.CLICK_ROLE, actions[0].type)
        assertEquals("ams", actions[0].role)
        assertEquals(AxisActionType.CLICK_AUTO, actions[1].type)
        assertEquals(AxisActionType.NOTIFY, actions[2].type)
        assertTrue(AxisValidator.validate(document).isValid)
    }

    @Test
    fun `rejects invalid roles set values and conflicting triggers`() {
        val document = AxisParser.parse(
            """
            轴类型=顺序
            [轴]
            0:10 | 点击=角色6 | SET=开,关 | UB后=角色1 | 卡帧=角色2
            """.trimIndent(),
        )

        val result = AxisValidator.validate(document)

        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.code == "unknown-role" })
        assertTrue(result.issues.any { it.code == "invalid-set-values" })
        assertTrue(result.issues.any { it.code == "conflicting-triggers" })
    }

    @Test
    fun `parses complete switch axis`() {
        val document = AxisParser.parse(
            """
            轴类型=开关
            [轴开局] | SET=开,关,开,关,开 | AUTO=开
            0:30 | UB后=角色1 | SET=关,关,开,关,开 | AUTO=关
            0:10 | 卡帧=角色2 | SET=开,开,开,关,开 | AUTO=开
            """.trimIndent(),
        )

        assertEquals(AxisType.SWITCH, document.type)
        assertEquals(1, document.switchOpenings.size)
        assertEquals(2, document.switchNodes.size)
        assertTrue(AxisValidator.validate(document).isValid)
    }

    @Test
    fun `rejects duplicate header and malformed time`() {
        try {
            AxisParser.parse("轴类型=顺序\n轴类型=开关")
            throw AssertionError("expected duplicate header failure")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("表头字段重复"))
        }
        try {
            AxisParser.parse("轴类型=顺序\n[轴]\n0:99 | 点击=角色1")
            throw AssertionError("expected malformed time failure")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("时间格式"))
        }
    }
}

