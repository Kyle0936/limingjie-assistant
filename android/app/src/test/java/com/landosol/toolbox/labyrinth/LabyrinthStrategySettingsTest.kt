package com.landosol.toolbox.labyrinth

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LabyrinthStrategySettingsTest {
    @Test fun `defaults preserve current production scoring and choice rules`() {
        val original = productionDocument()
        assertEquals(original, LabyrinthStrategySettings().applyTo(original))
        assertEquals(LabyrinthBossTeamMode.MULTI_TEAM, LabyrinthStrategySettings().bossTeamMode)
        assertTrue(LabyrinthStrategySettings().preferPureBossDamageSystem)
        assertTrue(LabyrinthStrategySettings().attributePairingBonusEnabled)
        assertTrue(LabyrinthStrategySettings().rerollAfterThreeBattleFailures)
        assertEquals(15, LabyrinthStrategySettings().mixedDamagePenalty)
        assertEquals(8, LabyrinthStrategySettings().effectiveCharacterSystemBonus)
    }

    @Test fun `settings survive persistence round trip`() {
        val settings = LabyrinthStrategySettings(playerWeight = 45, secondTeamWeight = 80,
            bossTeamMode = LabyrinthBossTeamMode.SINGLE_TEAM,
            preferPureBossDamageSystem = false,
            rerollAfterThreeBattleFailures = false,
            attributePairingBonusEnabled = false,
            mixedDamagePenalty = 21, effectiveCharacterSystemBonus = 6,
            baselineLastArea = 2, buyRelics = false, refreshShop = false, extremeWinRate = 30)
        assertEquals(settings, LabyrinthStrategySettingsCodec.decode(LabyrinthStrategySettingsCodec.encode(settings)))
    }

    @Test fun `legacy saved settings without boss mode default to multi team`() {
        val decoded = LabyrinthStrategySettingsCodec.decode("{\"playerWeight\":50}")
        assertEquals(LabyrinthBossTeamMode.MULTI_TEAM, decoded.bossTeamMode)
        assertTrue(decoded.preferPureBossDamageSystem)
        assertTrue(decoded.attributePairingBonusEnabled)
        assertTrue(decoded.rerollAfterThreeBattleFailures)
    }

    @Test fun `missing damaged and invalid saved settings fall back to defaults`() {
        listOf(null, "broken", "{\"playerWeight\":101}", "{\"baselineLastArea\":6}")
            .forEach { assertEquals(LabyrinthStrategySettings(), LabyrinthStrategySettingsCodec.decode(it)) }
    }

    @Test fun `system weights are normalized and safety rules are preserved`() {
        val original = productionDocument()
        val configured = LabyrinthStrategySettings(playerWeight = 20, damageWeight = 100,
            survivalWeight = 100, functionWeight = 0, formationWeight = 0, cohesionWeight = 0,
            secondTeamWeight = 65, thirdTeamWeight = 0).applyTo(original)
        assertEquals(0.2, configured.scoring.playerWeight, 0.00001)
        assertEquals(0.5, configured.scoring.systemDamageWeight, 0.00001)
        assertEquals(0.5, configured.scoring.systemSurvivalWeight, 0.00001)
        assertEquals(15.0, configured.scoring.battleMixedDamagePenalty, 0.00001)
        assertEquals(8.0, configured.scoring.encounterEffectiveCharacterBonus, 0.00001)
        assertEquals(listOf(1.0, 0.65, 0.0), configured.roleChoice.candidateTeamWeights)
        assertEquals(original.roleChoice.requireStrictProfiles, configured.roleChoice.requireStrictProfiles)
        assertSame(original.characters, configured.characters)
        assertEquals(original.scoring.attributeDamageBonus, configured.scoring.attributeDamageBonus)
        assertEquals(original.scoring.minimumReliableVanguard, configured.scoring.minimumReliableVanguard, 0.0)
    }

    @Test fun `attribute pairing switch removes only same-attribute damage tiers`() {
        val original = productionDocument()
        val configured = LabyrinthStrategySettings(attributePairingBonusEnabled = false).applyTo(original)
        assertEquals(original.scoring.attributeDamageBonus.keys, configured.scoring.attributeDamageBonus.keys)
        assertTrue(configured.scoring.attributeDamageBonus.values.all { it == 0.0 })
        assertEquals(original.scoring.playerWeight, configured.scoring.playerWeight, 0.0)
        assertEquals(original.scoring.battleMixedDamagePenalty, configured.scoring.battleMixedDamagePenalty, 0.0)
        assertEquals(original.scoring.encounterEffectiveCharacterBonus, configured.scoring.encounterEffectiveCharacterBonus, 0.0)
    }

    @Test fun `zero scoring draft survives recreation but cannot be persisted`() {
        val draft = LabyrinthStrategySettings(damageWeight = 0, survivalWeight = 0,
            functionWeight = 0, formationWeight = 0, cohesionWeight = 0)
        assertNotNull(draft.validationError())
        assertEquals(draft, LabyrinthStrategySettingsCodec.decodeDraft(LabyrinthStrategySettingsCodec.encodeDraft(draft)))
        assertThrows(IllegalArgumentException::class.java) { LabyrinthStrategySettingsCodec.encode(draft) }
    }

    @Test fun `all editable bounds reject invalid values`() {
        listOf(LabyrinthStrategySettings(duplicateRolePenalty = 31),
            LabyrinthStrategySettings(mixedDamagePenalty = 31),
            LabyrinthStrategySettings(effectiveCharacterSystemBonus = 31),
            LabyrinthStrategySettings(secondTeamWeight = -1), LabyrinthStrategySettings(baselineTarget = 0),
            LabyrinthStrategySettings(debuffPivotMinimum = 4), LabyrinthStrategySettings(debuffPivotTolerance = 6),
            LabyrinthStrategySettings(refreshFromArea = 0), LabyrinthStrategySettings(normalWinRate = 101))
            .forEach { assertNotNull(it.validationError()) }
    }

    private fun productionDocument(): LabyrinthRoleDecisionDocument {
        val file = listOf(File("app/src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"),
            File("src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json")).first { it.isFile }
        return (LabyrinthRoleDecisionDataParser.parse(file.readText()) as LabyrinthRoleDecisionDataResult.Ready).runtime.document
    }
}
