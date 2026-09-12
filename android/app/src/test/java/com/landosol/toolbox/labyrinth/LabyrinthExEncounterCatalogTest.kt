package com.landosol.toolbox.labyrinth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthExEncounterCatalogTest {
    @Test
    fun `queen bee formal name and guide alias resolve to same special encounter`() {
        val formal = requireNotNull(LabyrinthExEncounterCatalog.matchObservedName("黄蜂女王"))
        val alias = requireNotNull(LabyrinthExEncounterCatalog.matchObservedName("蜂后"))

        assertEquals("queen_bee", formal.id)
        assertEquals(formal.id, alias.id)
        assertEquals("黄蜂女王", formal.identityName)
        assertEquals(2, formal.targetCount)
    }

    @Test
    fun `misora is modeled as special two target encounter`() {
        val strategy = requireNotNull(LabyrinthExEncounterCatalog.matchObservedName("美空"))
        assertEquals("misora", strategy.id)
        assertEquals(2, strategy.targetCount)
    }

    @Test
    fun `destruction relic resolves sixth multi monster encounter`() {
        val formal = requireNotNull(LabyrinthExEncounterCatalog.matchObservedName("破坏・遗物"))
        val ocrVariant = requireNotNull(LabyrinthExEncounterCatalog.matchObservedName("破坏·遗物"))

        assertEquals("multi_destruction_relic", formal.id)
        assertEquals(formal.id, ocrVariant.id)
        assertEquals("破坏・遗物", formal.identityName)
        assertEquals(5, formal.targetCount)
    }

    @Test
    fun `single target boss name resolves directly from challenge page OCR`() {
        val strategy = requireNotNull(LabyrinthExEncounterCatalog.matchObservedName("幽灵领主"))

        assertEquals("ghost_lord", strategy.id)
        assertEquals("幽灵领主", strategy.identityName)
        assertEquals(1, strategy.targetCount)
    }

    @Test
    fun `antimatter beast resolves as a known single target EX`() {
        val strategy = requireNotNull(LabyrinthExEncounterCatalog.matchObservedName("反物质兽"))

        assertEquals("antimatter_beast", strategy.id)
        assertEquals("反物质兽", strategy.identityName)
        assertEquals(1, strategy.targetCount)
    }

    @Test
    fun `new year karyl shadow models full heal robot and broad aoe answer`() {
        val strategy = requireNotNull(LabyrinthExEncounterCatalog.matchObservedName("凯露（新年）的暗影"))

        assertEquals("multi_ny_karyl_shadow", strategy.id)
        assertEquals(5, strategy.targetCount)
        assertTrue(LabyrinthEncounterCapability.WIDE_AOE in strategy.preferredCapabilities)
        assertTrue(LabyrinthEncounterCapability.BACKLINE_AOE in strategy.preferredCapabilities)
        assertEquals(2, strategy.preferredCapabilityMemberTargets[LabyrinthEncounterCapability.WIDE_AOE])
        assertEquals(1, strategy.preferredCapabilityMemberTargets[LabyrinthEncounterCapability.BACKLINE_AOE])
        val fallback = strategy.fallbackPreferences.single()
        assertEquals(LabyrinthEncounterCapability.WIDE_AOE, fallback.primaryCapability)
        assertEquals(3, fallback.primaryTargetCount)
        assertTrue(LabyrinthEncounterCapability.PUSH in fallback.anyOf)
        assertTrue(LabyrinthEncounterCapability.PULL in fallback.anyOf)
        assertTrue(strategy.notes.any { it.contains("100%生命值") })
        assertTrue(strategy.notes.any { it.contains("第5位") })
        assertTrue(strategy.notes.any { it.contains("压缩") })
    }

    @Test
    fun `frost wolf boss uses multi target model and prefers aoe plus dot`() {
        val strategy = requireNotNull(LabyrinthBossEncounterCatalog.forUnitId(319604))

        assertEquals("boss_frost_wolf", strategy.id)
        assertEquals("冰霜魔狼", strategy.identityName)
        assertEquals(3, strategy.targetCount)
        assertTrue(LabyrinthEncounterCapability.AOE in strategy.preferredCapabilities)
        assertTrue(LabyrinthEncounterCapability.DOT in strategy.preferredCapabilities)
    }
}
